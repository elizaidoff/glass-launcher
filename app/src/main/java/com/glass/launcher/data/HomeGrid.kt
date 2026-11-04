package com.glass.launcher.data

/**
 * Home-screen grid helpers. A page is a list of slots (row by row). A slot holds:
 *  - a package name (an app), or
 *  - "w:<widget>|<S|M|L>" at the widget's top-start cell (the widget covers more cells), or
 *  - null (empty square).
 */
object HomeGrid {
    enum class Size(val label: String) { S("קטן"), M("בינוני"), L("גדול") }

    fun isWidget(v: String?) = v != null && v.startsWith("w:")

    private val custom = Regex("""^(\d+)x(\d+)$""")

    /** Free size in cells, when the user resized the widget by hand ("key|3x2"). */
    fun customSpan(entry: String): Pair<Int, Int>? {
        val t = entry.substringAfterLast('|', "")
        val m = custom.find(t) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** "builtin:clock|M" -> ("builtin:clock", M). A free size maps to the closest style. */
    fun parse(entry: String): Pair<String, Size> {
        val key = entry.removePrefix("w:")
        val i = key.lastIndexOf('|')
        if (i < 0) return key to Size.M
        val token = key.substring(i + 1)
        val cs = customSpan(key)
        val size = when {
            cs != null -> when { cs.second >= 3 -> Size.L; cs.first >= 3 -> Size.M; else -> Size.S }
            else -> runCatching { Size.valueOf(token) }.getOrDefault(Size.M)
        }
        return key.substring(0, i) to size
    }

    fun entry(key: String, size: Size) = "$key|${size.name}"

    fun entry(key: String, w: Int, h: Int) = "$key|${w}x$h"

    /** Width x height in cells for any entry (preset or free size). */
    fun spanOf(entry: String, cols: Int, rows: Int): Pair<Int, Int> {
        customSpan(entry.removePrefix("w:"))?.let { return it.first.coerceIn(1, cols) to it.second.coerceIn(1, rows) }
        return span(parse(entry).second, cols, rows)
    }

    /** Width x height in cells. */
    fun span(size: Size, cols: Int, rows: Int): Pair<Int, Int> {
        val wide = minOf(4, cols)
        return when (size) {
            Size.S -> minOf(2, cols) to minOf(2, rows)
            Size.M -> wide to minOf(2, rows)
            Size.L -> wide to minOf(4, rows)
        }
    }

    fun span(slot: String, cols: Int, rows: Int) = spanOf(slot, cols, rows)

    /** For every cell: index of the slot that owns it (itself for apps/empty, the widget origin for covered cells). */
    fun owners(page: List<String?>, cols: Int, rows: Int): IntArray {
        val owner = IntArray(cols * rows) { it }
        page.forEachIndexed { i, v ->
            if (i >= cols * rows || !isWidget(v)) return@forEachIndexed
            val (w, h) = span(v!!, cols, rows)
            val r0 = i / cols
            val c0 = i % cols
            for (r in r0 until minOf(rows, r0 + h)) for (c in c0 until minOf(cols, c0 + w)) owner[r * cols + c] = i
        }
        return owner
    }

    fun normalize(page: List<String?>, cols: Int, rows: Int): MutableList<String?> =
        (page + List(cols * rows) { null }).take(cols * rows).toMutableList()

    /** True if a w x h block starting at [origin] is free (ignoring the slot [ignore], e.g. the item being moved). */
    fun fits(page: List<String?>, cols: Int, rows: Int, origin: Int, w: Int, h: Int, ignore: Int = -1): Boolean {
        val r0 = origin / cols
        val c0 = origin % cols
        if (r0 + h > rows || c0 + w > cols) return false
        val owner = owners(page, cols, rows)
        for (r in r0 until r0 + h) for (c in c0 until c0 + w) {
            val i = r * cols + c
            val o = owner[i]
            if (o == ignore) continue
            if (o != i) return false              // covered by another widget
            if (page.getOrNull(i) != null) return false
        }
        return true
    }

    fun firstFree(page: List<String?>, cols: Int, rows: Int, w: Int, h: Int): Int =
        (0 until cols * rows).firstOrNull { fits(page, cols, rows, it, w, h) } ?: -1

    /** Places an item (app or widget) on the first page with room, adding a page if needed. */
    fun place(pages: List<List<String?>>, cols: Int, rows: Int, value: String, startPage: Int = 0): List<List<String?>> {
        val w: Int
        val h: Int
        if (isWidget(value)) { val s = span(value, cols, rows); w = s.first; h = s.second } else { w = 1; h = 1 }
        val out = pages.map { normalize(it, cols, rows) }.toMutableList()
        val order = (startPage until out.size) + (0 until minOf(startPage, out.size))
        for (p in order) {
            val i = firstFree(out[p], cols, rows, w, h)
            if (i >= 0) { out[p][i] = value; return out }
        }
        val fresh = normalize(emptyList(), cols, rows)
        fresh[0] = value
        out.add(fresh)
        return out
    }
}
