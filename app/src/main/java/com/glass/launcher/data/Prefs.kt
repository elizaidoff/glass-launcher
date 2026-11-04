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

    /** package -> hidden folder name. */
    var hidden: Map<String, String>
        get() {
            val o = JSONObject(str("hidden") ?: "{}")
            return o.keys().asSequence().associateWith { o.getString(it) }
        }
        set(v) = putStr("hidden", JSONObject(v).toString())

    /** Ordered list of hidden folder names. */
    var hiddenFolders: List<String>
        get() = strList("hiddenFolders").ifEmpty { listOf("נסתר") }
        set(v) = putStr("hiddenFolders", JSONArray(v).toString())

    fun modeOf(pkg: String): AppMode = when {
        pkg in removed -> AppMode.REMOVED
        pkg in hidden -> AppMode.HIDDEN
        pkg in locked -> AppMode.LOCKED
        else -> AppMode.NORMAL
    }

    fun setMode(pkg: String, mode: AppMode, folder: String? = null) {
        removed = if (mode == AppMode.REMOVED) removed + pkg else removed - pkg
        locked = if (mode == AppMode.LOCKED) locked + pkg else locked - pkg
        hidden = if (mode == AppMode.HIDDEN) hidden + (pkg to (folder ?: hiddenFolders.first())) else hidden - pkg
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

    var iconPack: String?
        get() = str("iconPack")
        set(v) = putStr("iconPack", v)

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

    var searchPill: Boolean
        get() = bool("searchPill", true)
        set(v) = putBool("searchPill", v)

    var onboarded: Boolean
        get() = bool("onboarded", false)
        set(v) = putBool("onboarded", v)

    var selfAdded: Boolean
        get() = bool("selfAdded", false)
        set(v) = putBool("selfAdded", v)

    fun iconDp(): Int = when (iconSize) { 0 -> 52; 2 -> 68; else -> 60 }

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
