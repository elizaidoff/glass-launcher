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
        get() = if (sp.contains("ccTiles")) strList("ccTiles") else listOf("rotation", "dnd", "torch", "camera", "calculator", "alarm")
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
