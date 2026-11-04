package com.glass.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import com.glass.launcher.App
import com.glass.launcher.data.HomeGrid

/** Where a dragged icon came from. page = -1 means the Dock. */
data class DragSource(val page: Int, val index: Int)

/**
 * Drag and drop for app icons across the home page and the Dock (in and out of it).
 * Positions are in root (screen) pixels.
 */
class IconDrag {
    var pkg by mutableStateOf<String?>(null)
    var source: DragSource? = null
    var pos by mutableStateOf(Offset.Zero)
    var currentPage = 0
    var pagesRtl = false
    var screenWidth = 0f
    var edgePx = 60f
    val gridRects = HashMap<Int, Rect>()
    var dockRect: Rect? = null

    /** Cell under the finger right now (for the highlight while dragging). */
    var hover by mutableStateOf<DragSource?>(null)

    fun begin(p: String, src: DragSource, at: Offset) { pkg = p; source = src; pos = at; hover = targetAt(pos) }
    fun cancel() { pkg = null; source = null; hover = null }

    fun move(at: Offset) { pos = at; hover = targetAt(at) }

    private fun targetAt(at: Offset): DragSource? {
        val prefs = App.instance.prefs
        val cols = prefs.columns
        val rows = prefs.rows
        dockRect?.let { dr ->
            if (dr.contains(at)) {
                val n = prefs.dockCount
                val phys = ((at.x - dr.left) / (dr.width / n)).toInt().coerceIn(0, n - 1)
                return DragSource(-1, n - 1 - phys)
            }
        }
        val gr = gridRects[currentPage] ?: return null
        if (!gr.contains(at)) return null
        val phys = ((at.x - gr.left) / (gr.width / cols)).toInt().coerceIn(0, cols - 1)
        val row = ((at.y - gr.top) / (gr.height / rows)).toInt().coerceIn(0, rows - 1)
        return DragSource(currentPage, row * cols + (cols - 1 - phys))
    }

    /** Which page-edge the finger is on: +1 next page, -1 previous page, 0 none. */
    fun edge(): Int {
        val inDock = dockRect?.contains(pos) == true
        if (inDock || screenWidth <= 0f) return 0
        val right = pos.x > screenWidth - edgePx
        val left = pos.x < edgePx
        if (!right && !left) return 0
        val towardsNext = if (pagesRtl) left else right
        return if (towardsNext) 1 else -1
    }

    /** Applies the drop. Swaps with whatever is at the target. */
    fun drop() {
        val p = pkg ?: return cancel()
        val src = source ?: return cancel()
        val prefs = App.instance.prefs
        val cols = prefs.columns
        val rows = prefs.rows
        val pages = prefs.homePages.map { HomeGrid.normalize(it, cols, rows) }.toMutableList()
        val dock = (prefs.dock + List(6) { null }).take(prefs.dockCount).toMutableList()

        fun get(s: DragSource): String? = if (s.page < 0) dock.getOrNull(s.index) else pages.getOrNull(s.page)?.getOrNull(s.index)
        fun set(s: DragSource, v: String?) {
            if (s.page < 0) dock[s.index] = v else pages[s.page][s.index] = v
        }

        // 1. Dock
        val dr = dockRect
        var target: DragSource? = null
        if (dr != null && dr.contains(pos)) {
            val n = dock.size
            val phys = ((pos.x - dr.left) / (dr.width / n)).toInt().coerceIn(0, n - 1)
            target = DragSource(-1, n - 1 - phys) // right-to-left
        }
        // 2. Next / previous page (finger held on the screen edge)
        if (target == null) {
            val e = edge()
            if (e != 0) {
                var tp = currentPage + e
                if (tp >= pages.size) { pages.add(HomeGrid.normalize(emptyList(), cols, rows)); tp = pages.size - 1 }
                if (tp >= 0) {
                    val free = HomeGrid.firstFree(pages[tp], cols, rows, 1, 1)
                    if (free >= 0) target = DragSource(tp, free)
                }
            }
        }
        // 3. A cell of the visible page
        if (target == null) {
            val gr = gridRects[currentPage]
            if (gr != null && gr.contains(pos)) {
                val cw = gr.width / cols
                val ch = gr.height / rows
                val phys = ((pos.x - gr.left) / cw).toInt().coerceIn(0, cols - 1)
                val col = cols - 1 - phys // right-to-left
                val row = ((pos.y - gr.top) / ch).toInt().coerceIn(0, rows - 1)
                val idx = row * cols + col
                val owners = HomeGrid.owners(pages[currentPage], cols, rows)
                if (owners[idx] == idx && !HomeGrid.isWidget(pages[currentPage][idx])) target = DragSource(currentPage, idx)
            }
        }
        if (target == null || target == src) return cancel()

        val there = get(target)
        val isApp = { v: String? -> v != null && !HomeGrid.isWidget(v) && !v.startsWith("f:") && !v.startsWith("s:") }
        if (target.page >= 0 && isApp(p) && there != null && there.startsWith("f:")) {
            // Dropped onto a folder: the app goes inside it.
            val id = there.removePrefix("f:")
            val folders = prefs.homeFolders.toMutableMap()
            folders[id]?.let { (n, a) -> folders[id] = n to (a - p + p) }
            prefs.homeFolders = folders
            set(src, null)
        } else if (target.page >= 0 && src.page >= 0 && isApp(p) && isApp(there)) {
            // Dropped onto another app: make a folder of the two, like iPhone.
            val id = System.currentTimeMillis().toString(36)
            prefs.homeFolders = prefs.homeFolders + (id to ("תיקייה" to listOf(there!!, p)))
            set(target, "f:$id")
            set(src, null)
        } else {
            set(target, p)
            set(src, there) // swap (null if the target was empty)
        }
        prefs.homePages = pages
        prefs.dock = dock
        cancel()
    }
}

val LocalIconDrag = staticCompositionLocalOf { IconDrag() }

/**
 * Gesture for an app icon on the home screen or in the Dock:
 * tap opens, hold shows the menu, hold and move (or just move in edit mode) drags it anywhere.
 */
@Composable
fun Modifier.dragIcon(
    pkg: String,
    source: DragSource,
    editMode: Boolean,
    onTap: () -> Unit,
    onMenu: () -> Unit,
    onEnterEdit: () -> Unit,
): Modifier {
    val drag = LocalIconDrag.current
    val view = LocalView.current
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return this
        .onGloballyPositioned { coords = it }
        .pointerInput(editMode, source) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val slop = viewConfiguration.touchSlop
                val r: Int? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull 1
                        if (!ch.pressed) return@withTimeoutOrNull 1
                        if ((ch.position - down.position).getDistance() > slop) return@withTimeoutOrNull 2
                    }
                    0
                }
                val stage = r ?: 3 // 1 tap, 2 moved before long-press, 3 long-pressed
                if (stage == 1) { if (!editMode) onTap(); return@awaitEachGesture }
                if (stage == 2 && !editMode) return@awaitEachGesture // a page swipe, not ours
                if (stage == 3) view.thud()
                var dragging = false
                fun startDrag(at: Offset) {
                    dragging = true
                    if (!editMode) onEnterEdit()
                    coords?.let { drag.begin(pkg, source, it.localToRoot(at)) }
                }
                if (stage == 2) startDrag(down.position)
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    if (!dragging && (ch.position - down.position).getDistance() > slop) startDrag(ch.position)
                    if (dragging) {
                        coords?.let { drag.move(it.localToRoot(ch.position)) }
                        ch.consume()
                    }
                }
                if (dragging) { drag.drop(); view.tick() }
                else if (stage == 3 && !editMode) onMenu()
            }
        }
}
