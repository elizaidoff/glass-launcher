package com.glass.launcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * All launcher settings, stored in SharedPreferences.
 * [version] changes on every write so Compose screens can recompose.
 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun bump() { _version.value = _version.value + 1 }

    /** Ask screens to re-read (e.g. after returning from a system permission screen). */
    fun touch() = bump()

    private fun set(key: String): Set<String> = sp.getStringSet(key, null)?.toSet() ?: emptySet()
    private fun putSet(key: String, v: Set<String>) { sp.edit().putStringSet(key, v).apply(); bump() }
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) { sp.edit().putBoolean(key, v).apply(); bump() }
    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun putInt(key: String, v: Int) { sp.edit().putInt(key, v).apply(); bump() }
    private fun str(key: String): String? = sp.getString(key, null)
    private fun putStr(key: String, v: String?) { sp.edit().putString(key, v).apply(); bump() }

    // ---- Backup ----------------------------------------------------------

    /** Everything as JSON (for a backup file). The passcode is left out on purpose. */
    fun exportJson(): String {
        val o = JSONObject()
        sp.all.forEach { (k, v) ->
            if (k == "pinHash") return@forEach
            val t = JSONObject()
            when (v) {
                is Boolean -> { t.put("t", "b"); t.put("v", v) }
                is Int -> { t.put("t", "i"); t.put("v", v) }
                is Long -> { t.put("t", "l"); t.put("v", v) }
                is Float -> { t.put("t", "f"); t.put("v", v.toDouble()) }
                is String -> { t.put("t", "s"); t.put("v", v) }
                is Set<*> -> { t.put("t", "set"); t.put("v", JSONArray(v.map { it.toString() })) }
                else -> return@forEach
            }
            o.put(k, t)
        }
        return JSONObject().put("glass", 1).put("prefs", o).toString(1)
    }

    /** Restores a backup made by [exportJson]. Returns false if the file isn't one. */
    fun importJson(text: String): Boolean = runCatching {
        val root = JSONObject(text)
        if (!root.has("glass")) return false
        val o = root.getJSONObject("prefs")
        val e = sp.edit()
        o.keys().forEach { k ->
            if (k == "pinHash" || k == "lockMethod") return@forEach
            val t = o.getJSONObject(k)
            when (t.getString("t")) {
                "b" -> e.putBoolean(k, t.getBoolean("v"))
                "i" -> e.putInt(k, t.getInt("v"))
                "l" -> e.putLong(k, t.getLong("v"))
                "f" -> e.putFloat(k, t.getDouble("v").toFloat())
                "s" -> e.putString(k, t.getString("v"))
                "set" -> { val a = t.getJSONArray("v"); e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
        e.apply()
        bump()
        true
    }.getOrDefault(false)

    // ---- App visibility -------------------------------------------------

    /** Apps that never appear anywhere in the launcher. */
    var removed: Set<String>
        get() = set("removed")
        set(v) = putSet("removed", v)

    /** Apps that require fingerprint/face to open (visible lock). */
    var locked: Set<String>
        get() = set("locked")
        set(v) = putSet("locked", v)

    /** package -> folder name (visible or hidden folder in the App Library). Stored under the old "hidden" key. */
    var folderOf: Map<String, String>
        get() {
            val o = JSONObject(str("hidden") ?: "{}")
            return o.keys().asSequence().associateWith { o.getString(it) }
        }
        set(v) = putStr("hidden", JSONObject(v).toString())

    /** The user's own folders, in order. A hidden folder only shows after the secret gesture. */
    var folders: List<FolderDef>
        get() {
            val raw = str("folders")
            if (raw == null) {
                // Older versions only had hidden folders.
                val old = strList("hiddenFolders").ifEmpty { listOf("נסתר") }
                return old.map { FolderDef(it, true) }
            }
            val a = JSONArray(raw)
            return (0 until a.length()).map { val o = a.getJSONObject(it); FolderDef(o.getString("n"), o.optBoolean("h", false)) }
        }
        set(v) = putStr("folders", JSONArray().also { a -> v.forEach { a.put(JSONObject().put("n", it.name).put("h", it.hidden)) } }.toString())

    fun isHiddenFolder(name: String) = folders.any { it.name == name && it.hidden }

    /** Apps that sit in hidden folders: package -> folder. */
    val hidden: Map<String, String>
        get() { val h = folders.filter { it.hidden }.map { it.name }.toSet(); return folderOf.filterValues { it in h } }

    val hiddenFolders: List<String> get() = folders.filter { it.hidden }.map { it.name }

    fun setFolder(pkg: String, folder: String?) {
        folderOf = if (folder == null) folderOf - pkg else folderOf + (pkg to folder)
    }

    fun modeOf(pkg: String): AppMode = when {
        pkg in removed -> AppMode.REMOVED
        pkg in hidden -> AppMode.HIDDEN
        pkg in locked -> AppMode.LOCKED
        else -> AppMode.NORMAL
    }

    fun setMode(pkg: String, mode: AppMode, folder: String? = null) {
        removed = if (mode == AppMode.REMOVED) removed + pkg else removed - pkg
        locked = if (mode == AppMode.LOCKED) locked + pkg else locked - pkg
        when {
            mode == AppMode.HIDDEN -> setFolder(pkg, folder ?: hiddenFolders.firstOrNull() ?: run {
                folders = folders + FolderDef("נסתר", true); "נסתר"
            })
            pkg in hidden -> setFolder(pkg, null)
        }
    }

    /** True when the app may be shown on the home screen, library and search. */
    fun isVisible(pkg: String) = pkg !in removed && pkg !in hidden

    /** Disguised apps: real package -> package whose icon and name it shows (and opens for anyone else). */
    var disguise: Map<String, String>
        get() {
            val o = JSONObject(str("disguise") ?: "{}")
            return o.keys().asSequence().associateWith { o.getString(it) }
        }
        set(v) = putStr("disguise", JSONObject(v).toString())

    var libSuggestions: Boolean
        get() = bool("libSugg", true)
        set(v) = putBool("libSugg", v)

    /** Sort apps that are not in your folders into automatic categories (off = one "other apps" group). */
    var libAutoCategories: Boolean
        get() = bool("libAuto", true)
        set(v) = putBool("libAuto", v)

    var libRecentlyAdded: Boolean
        get() = bool("libRecent", true)
        set(v) = putBool("libRecent", v)

    // ---- Settings protection --------------------------------------------

    var decoyEnabled: Boolean
        get() = bool("decoyEnabled", true)
        set(v) = putBool("decoyEnabled", v)

    var lockEnabled: Boolean
        get() = bool("lockEnabled", true)
        set(v) = putBool("lockEnabled", v)

    // ---- Home screen -----------------------------------------------------

    var columns: Int
        get() = int("columns", 4)
        set(v) = putInt("columns", v)

    var rows: Int
        get() = int("rows", 6)
        set(v) = putInt("rows", v)

    var showLabels: Boolean
        get() = bool("showLabels", true)
        set(v) = putBool("showLabels", v)

    var newAppsToHome: Boolean
        get() = bool("newAppsToHome", false)
        set(v) = putBool("newAppsToHome", v)

    /** Each page is a fixed list of slots; null = empty square. */
    var homePages: List<List<String?>>
        get() {
            val a = JSONArray(str("homePages") ?: "[]")
            return (0 until a.length()).map { p ->
                val page = a.getJSONArray(p)
                (0 until page.length()).map { i -> if (page.isNull(i)) null else page.getString(i) }
            }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { page -> a.put(JSONArray().also { arr -> page.forEach { arr.put(it ?: JSONObject.NULL) } }) }
            putStr("homePages", a.toString())
        }

    var dock: List<String?>
        get() {
            val a = JSONArray(str("dock") ?: "[]")
            return (0 until a.length()).map { if (a.isNull(it)) null else a.getString(it) }
        }
        set(v) = putStr("dock", JSONArray().also { arr -> v.forEach { arr.put(it ?: JSONObject.NULL) } }.toString())

    /** Folders on the home screen: id -> (name, apps). A home slot holds "f:<id>". */
    var homeFolders: Map<String, Pair<String, List<String>>>
        get() {
            val o = JSONObject(str("homeFolders") ?: "{}")
            return o.keys().asSequence().associateWith { k ->
                val f = o.getJSONObject(k)
                val a = f.getJSONArray("a")
                f.optString("n", "תיקייה") to (0 until a.length()).map { a.getString(it) }
            }
        }
        set(v) = putStr("homeFolders", JSONObject().also { o ->
            v.forEach { (k, f) -> o.put(k, JSONObject().put("n", f.first).put("a", JSONArray(f.second))) }
        }.toString())

    var homeInitialized: Boolean
        get() = bool("homeInitialized", false)
        set(v) = putBool("homeInitialized", v)

    var knownPackages: Set<String>
        get() = set("knownPackages")
        set(v) = putSet("knownPackages", v)

    /** Most recently opened packages, newest first. */
    var recents: List<String>
        get() = strList("recents")
        set(v) = putStr("recents", JSONArray(v.take(12)).toString())

    fun noteLaunched(pkg: String) { recents = listOf(pkg) + (recents - pkg) }

    // ---- Features --------------------------------------------------------

    var libraryEnabled: Boolean
        get() = bool("libraryEnabled", true)
        set(v) = putBool("libraryEnabled", v)

    var controlCenterEnabled: Boolean
        get() = bool("ccEnabled", true)
        set(v) = putBool("ccEnabled", v)

    var widgetsEnabled: Boolean
        get() = bool("widgetsEnabled", true)
        set(v) = putBool("widgetsEnabled", v)

    var searchEnabled: Boolean
        get() = bool("searchEnabled", true)
        set(v) = putBool("searchEnabled", v)

    /** Entries: "builtin:clock", "builtin:hebdate", "builtin:battery", "builtin:airpods", "appwidget:<id>" */
    var widgets: List<String>
        get() = if (sp.contains("widgets")) strList("widgets")
        else listOf("builtin:clock", "builtin:hebdate", "builtin:battery")
        set(v) = putStr("widgets", JSONArray(v).toString())

    /** "double_tap" or "none". */
    var assistantGesture: String
        get() = str("assistantGesture") ?: "double_tap"
        set(v) = putStr("assistantGesture", v)

    /** App that opens as the assistant; null = Gemini automatically. */
    var assistantPackage: String?
        get() = str("assistantPkg")
        set(v) = putStr("assistantPkg", v)

    var iconPack: String?
        get() = str("iconPack")
        set(v) = putStr("iconPack", v)

    /** Candle lighting, minutes before sunset. 0 = Hebcal's default for the city. */
    var candleMinutes: Int
        get() = int("candleMin", 0)
        set(v) = putInt("candleMin", v)

    /** End of Shabbat: 0 = stars (8.5°), otherwise minutes after sunset (42 / 50 / 72). */
    var havdalahMode: Int
        get() = int("havdalah", 0)
        set(v) = putInt("havdalah", v)

    var zmanimCache: String?
        get() = str("zmCache")
        set(v) { sp.edit().putString("zmCache", v).apply() }
    var shabbatCache: String?
        get() = str("shCache")
        set(v) { sp.edit().putString("shCache", v).apply() }
    var zmanimKey: String?
        get() = str("zmKey")
        set(v) { sp.edit().putString("zmKey", v).apply() }

    /** Last AirPods reading, kept after the case is closed. */
    var podsLast: String?
        get() = str("podsLast")
        set(v) { sp.edit().putString("podsLast", v).apply() }

    var cityIndex: Int
        get() = int("city", 0)
        set(v) = putInt("city", v)

    var haptics: Boolean
        get() = bool("haptics", true)
        set(v) = putBool("haptics", v)

    // ---- Look --------------------------------------------------------------

    /** false = like an English iPhone (widgets on the left, library on the right). */
    var pagesRtl: Boolean
        get() = bool("pagesRtl", false)
        set(v) = putBool("pagesRtl", v)

    /** 0 small, 1 medium, 2 large */
    var iconSize: Int
        get() = int("iconSize", 1)
        set(v) = putInt("iconSize", v)

    /** "light", "dark", "tinted" */
    var iconStyle: String
        get() = str("iconStyle") ?: "light"
        set(v) = putStr("iconStyle", v)

    var tintColor: Int
        get() = int("tintColor", 0xFF7FB6FF.toInt())
        set(v) = putInt("tintColor", v)

    var dockBackground: Boolean
        get() = bool("dockBg", true)
        set(v) = putBool("dockBg", v)

    var dockCount: Int
        get() = int("dockCount", 4)
        set(v) = putInt("dockCount", v)

    /** 0.4 .. 1.6 */
    var glassStrength: Float
        get() = sp.getFloat("glass", 1f)
        set(v) { sp.edit().putFloat("glass", v).apply(); bump() }

    var dimWallpaper: Boolean
        get() = bool("dimWp", false)
        set(v) = putBool("dimWp", v)

    /** What sits above the Dock: "search", "dots" or "none". */
    var aboveDock: String
        get() = str("aboveDock") ?: if (bool("searchPill", true)) "search" else "dots"
        set(v) = putStr("aboveDock", v)

    /** Show an empty-looking folder card as an extra way to the hidden folders. */
    var decoyFolderCard: Boolean
        get() = bool("decoyCard", false)
        set(v) = putBool("decoyCard", v)

    var onboarded: Boolean
        get() = bool("onboarded", false)
        set(v) = putBool("onboarded", v)

    /** Finished the step-by-step welcome (separate from [onboarded], which only asked for the home role). */
    var welcomeDone: Boolean
        get() = bool("welcomeDone", false)
        set(v) = putBool("welcomeDone", v)

    /** How the visible lock opens: "bio" fingerprint/face, "pin" code only, "both" fingerprint with a code fallback. */
    var lockMethod: String
        get() = str("lockMethod") ?: "bio"
        set(v) = putStr("lockMethod", v)

    var pinHash: String?
        get() = str("pinHash")
        set(v) = putStr("pinHash", v)

    fun hashPin(pin: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(("glass:" + pin).toByteArray()).joinToString("") { "%02x".format(it) }
    }

    var pinLength: Int
        get() = int("pinLength", 4)
        set(v) = putInt("pinLength", v)

    fun checkPin(pin: String) = pinHash != null && hashPin(pin) == pinHash

    /** Smoother look, more motion; off = lighter animations for slow phones. */
    var smoothMode: Boolean
        get() = bool("smoothMode", true)
        set(v) = putBool("smoothMode", v)

    /** Home page swipe effect: "slide", "cube", "fade", "scale". */
    var pageEffect: String
        get() = str("pageEffect") ?: "slide"
        set(v) = putStr("pageEffect", v)

    /** "heebo" (default), "system". */
    var fontName: String
        get() = str("fontName") ?: "heebo"
        set(v) = putStr("fontName", v)

    /** Sent bubbles green (like SMS on iPhone) instead of blue. */
    var messagesGreen: Boolean
        get() = bool("messagesGreen", false)
        set(v) = putBool("messagesGreen", v)

    /** Volume keys show iPhone's pill at the edge. */
    var iosVolume: Boolean
        get() = bool("iosVolume", false)
        set(v) = putBool("iosVolume", v)

    /** With nothing playing, volume keys change the ringer (like iPhone's "Change with buttons"). */
    var volumeKeysRinger: Boolean
        get() = bool("volumeKeysRinger", false)
        set(v) = putBool("volumeKeysRinger", v)

    /** Notifications drop in as iPhone banners. */
    var iosBanners: Boolean
        get() = bool("iosBanners", false)
        set(v) = putBool("iosBanners", v)

    /** The AirPods card that slides up when they connect. */
    var podsCard: Boolean
        get() = bool("podsCard", true)
        set(v) = putBool("podsCard", v)

    var moveGoal: Int
        get() = int("moveGoal", 400)
        set(v) = putInt("moveGoal", v)

    var exerciseGoal: Int
        get() = int("exerciseGoal", 30)
        set(v) = putInt("exerciseGoal", v)

    var stepsGoal: Int
        get() = int("stepsGoal", 8000)
        set(v) = putInt("stepsGoal", v)

    var restingKcalPerDay: Int
        get() = int("restingKcal", 1600)
        set(v) = putInt("restingKcal", v)

    var healthCache: String?
        get() = str("healthCache")
        set(v) { sp.edit().putString("healthCache", v).apply() }

    var focusOn: Boolean
        get() = bool("focusOn", false)
        set(v) = putBool("focusOn", v)

    var focusAllowed: Set<String>
        get() = set("focusAllowed")
        set(v) = putSet("focusAllowed", v)

    /** Rings in the Fitness widget, outer to inner: move, exercise, steps, distance, floors. */
    var ringsOrder: List<String>
        get() = (str("ringsOrder") ?: "move,exercise,steps").split(",").filter { it.isNotBlank() }
        set(v) = putStr("ringsOrder", v.joinToString(","))

    var distanceGoal: Int
        get() = int("distanceGoal", 5)
        set(v) = putInt("distanceGoal", v)

    var floorsGoal: Int
        get() = int("floorsGoal", 10)
        set(v) = putInt("floorsGoal", v)

    /** Clock widget: "analog", "digital" or "both". */
    var clockStyle: String
        get() = str("clockStyle") ?: "analog"
        set(v) = putStr("clockStyle", v)

    /** Other apps' widgets sit on a glass card (off = as the app draws them). */
    var appWidgetGlass: Boolean
        get() = bool("appWidgetGlass", true)
        set(v) = putBool("appWidgetGlass", v)

    /** The phone's own Phone / Messages / Camera apps wear Apple's icons. */
    var appleIcons: Boolean
        get() = bool("appleIcons", true)
        set(v) = putBool("appleIcons", v)

    var assistiveTouch: Boolean
        get() = bool("assistiveTouch", false)
        set(v) = putBool("assistiveTouch", v)

    var assistiveX: Int
        get() = int("assistiveX", -1)
        set(v) = putInt("assistiveX", v)

    var assistiveY: Int
        get() = int("assistiveY", -1)
        set(v) = putInt("assistiveY", v)

    var selfAdded: Boolean
        get() = bool("selfAdded", false)
        set(v) = putBool("selfAdded", v)

    fun iconDp(): Int = when (iconSize) { 0 -> 52; 2 -> 68; else -> 60 }

    // ---- Home-screen swipes ---------------------------------------------------

    /** What a swipe down does, by where it starts: "notifications", "control", "search" or "none". */
    var swipeTopLeft: String
        get() = str("swTL") ?: "notifications"
        set(v) = putStr("swTL", v)
    var swipeTopRight: String
        get() = str("swTR") ?: "control"
        set(v) = putStr("swTR", v)
    var swipeBottom: String
        get() = str("swB") ?: "search"
        set(v) = putStr("swB", v)

    // ---- Control Center ----------------------------------------------------------

    var ccConnect: Boolean
        get() = bool("ccConnect", true)
        set(v) = putBool("ccConnect", v)
    var ccPods: Boolean
        get() = bool("ccPods", true)
        set(v) = putBool("ccPods", v)
    var ccSliders: Boolean
        get() = bool("ccSliders", true)
        set(v) = putBool("ccSliders", v)

    /** Ordered small buttons. Built-in ids or "app:<package>". */
    var ccTiles: List<String>
        get() = (if (sp.contains("ccTiles")) strList("ccTiles") else listOf("rotation", "dnd", "torch", "camera", "calculator", "alarm"))
            .let { if ("shade" in it) it else it + "shade" }
        set(v) = putStr("ccTiles", JSONArray(v).toString())

    // ---- Dynamic Island ------------------------------------------------------

    var islandEnabled: Boolean
        get() = bool("islandOn", false)
        set(v) = putBool("islandOn", v)

    var islandEditing: Boolean
        get() = bool("islandEdit", false)
        set(v) = putBool("islandEdit", v)

    /** Position and size in screen pixels; -1 = not set yet (use the camera cut-out). */
    var islandX: Int
        get() = int("islandX", -1)
        set(v) = putInt("islandX", v)
    var islandY: Int
        get() = int("islandY", -1)
        set(v) = putInt("islandY", v)
    var islandW: Int
        get() = int("islandW", -1)
        set(v) = putInt("islandW", v)
    var islandH: Int
        get() = int("islandH", -1)
        set(v) = putInt("islandH", v)

    var islandLongPressAssistant: Boolean
        get() = bool("islandLong", true)
        set(v) = putBool("islandLong", v)

    var badges: Boolean
        get() = bool("badges", true)
        set(v) = putBool("badges", v)

    // ---- Navigation (iPhone gestures) ---------------------------------------

    /** "off", "dark", "transparent", "appcolor" (cover the buttons) or "android" (Android gesture mode + our pill). */
    var navMode: String
        get() = str("navMode") ?: "off"
        set(v) = putStr("navMode", v)

    /** Plain taps on the covered Android buttons still work. */
    var navButtonsTap: Boolean
        get() = bool("navTap", false)
        set(v) = putBool("navTap", v)

    /** Order of Android's three buttons from left to right: "brh" back-home-recents or "rhb". */
    var navOrder: String
        get() = str("navOrder") ?: "brh"
        set(v) = putStr("navOrder", v)

    /** Back by swiping in from the edge: "right", "left", "both", "none". */
    var backEdge: String
        get() = str("backEdge") ?: "right"
        set(v) = putStr("backEdge", v)

    /** Height adjustment of the cover, in dp (can be negative). */
    var navAdjust: Int
        get() = int("navAdjust", 0)
        set(v) = putInt("navAdjust", v)

    /** Apps that refuse screenshots: package -> cover colour (ARGB). 0 = transparent, 1 = dark. */
    var secureApps: Map<String, Int>
        get() {
            val o = JSONObject(str("secureApps") ?: "{}")
            return o.keys().asSequence().associateWith { o.getInt(it) }
        }
        set(v) = putStr("secureApps", JSONObject(v).toString())

    /** Pull down from the top (status bar) anywhere opens our centers. */
    var topPull: Boolean
        get() = bool("topPull", false)
        set(v) = putBool("topPull", v)

    /** "split" (left notifications / right control), "notifications" or "control" for the whole width. */
    var topPullSplit: String
        get() = str("topSplit") ?: "split"
        set(v) = putStr("topSplit", v)

    var islandLandscape: Boolean
        get() = bool("islandLand", false)
        set(v) = putBool("islandLand", v)

    // ---- Edge gestures ----------------------------------------------------

    var gesturesEnabled: Boolean
        get() = bool("gesturesEnabled", false)
        set(v) = putBool("gesturesEnabled", v)

    /** Cover the system buttons with our own bar. */
    var coverNavButtons: Boolean
        get() = bool("coverNav", false)
        set(v) = putBool("coverNav", v)

    var showHomeBar: Boolean
        get() = bool("showHomeBar", true)
        set(v) = putBool("showHomeBar", v)

    var sideEdges: Boolean
        get() = bool("sideEdges", false)
        set(v) = putBool("sideEdges", v)

    fun gestureAction(zone: GestureZone): GestureAction =
        str("g_" + zone.name)?.let { n -> GestureAction.entries.firstOrNull { it.name == n } } ?: zone.default

    fun setGestureAction(zone: GestureZone, a: GestureAction) = putStr("g_" + zone.name, a.name)

    private fun strList(key: String): List<String> {
        val a = JSONArray(str(key) ?: "[]")
        return (0 until a.length()).map { a.getString(it) }
    }
}

data class FolderDef(val name: String, val hidden: Boolean)

enum class AppMode(val title: String) {
    NORMAL("רגילה"),
    LOCKED("נעולה"),
    HIDDEN("בתיקייה נסתרת"),
    REMOVED("מוסרת מהתצוגה"),
}

enum class GestureZone(val title: String, val default: GestureAction) {
    BOTTOM_RIGHT("החלקה למעלה – שליש ימני", GestureAction.BACK),
    BOTTOM_CENTER("החלקה למעלה – אמצע", GestureAction.HOME),
    BOTTOM_LEFT("החלקה למעלה – שליש שמאלי", GestureAction.RECENTS),
    EDGE_RIGHT("החלקה מהקצה הימני", GestureAction.BACK),
    EDGE_LEFT("החלקה מהקצה השמאלי", GestureAction.BACK),
}

enum class GestureAction(val title: String) {
    NONE("ללא"),
    BACK("חזרה"),
    HOME("מסך הבית"),
    RECENTS("אפליקציות אחרונות"),
    NOTIFICATIONS("התראות"),
    QUICK_SETTINGS("הגדרות מהירות"),
    SCREENSHOT("צילום מסך"),
    LOCK("נעילת מסך"),
    ASSISTANT("Gemini"),
    TORCH("פנס"),
}
