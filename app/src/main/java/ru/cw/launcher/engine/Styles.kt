package ru.cw.launcher.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A colour style made from a picture. [hues] is the main hue (the middle one is used for text and
 * buttons). A dynamic style also keeps [grid]: the real average colour of every cell of the picture
 * ([Styles.COLS] x [Styles.ROWS], row by row), so the interface can take the colour that the
 * picture has at that very place, white and black included.
 */
data class SavedStyle(
    val id: String,
    val name: String,
    val dynamic: Boolean,
    val hues: List<Int>,
    val background: String,
    val grid: List<Int> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("dynamic", dynamic)
        .put("hues", JSONArray(hues)).put("background", background)
        .put("grid", JSONArray(grid))

    /** Colours for a small preview strip. */
    fun swatch(): List<Int> =
        if (grid.size == Styles.COLS * Styles.ROWS) (0 until Styles.COLS).map { grid[Styles.ROWS / 2 * Styles.COLS + it] }
        else hues.map { Color.HSVToColor(floatArrayOf(it.toFloat(), 0.75f, 0.95f)) }

    companion object {
        fun fromJson(o: JSONObject): SavedStyle {
            val h = o.optJSONArray("hues")
            val hues = (0 until 3).map { h?.optInt(it, 218) ?: 218 }
            val g = o.optJSONArray("grid")
            val grid = if (g != null && g.length() == Styles.COLS * Styles.ROWS) (0 until g.length()).map { g.optInt(it) } else emptyList()
            return SavedStyle(o.optString("id"), o.optString("name", "Стиль"), o.optBoolean("dynamic"), hues, o.optString("background"), grid)
        }
    }
}

/** Styles the launcher generated from pictures; kept until the player deletes them. */
object Styles {
    const val COLS = 8
    const val ROWS = 4

    class Analysis(val hues: List<Int>, val grid: List<Int>)

    private val store: File get() = File(AppPaths.root, "styles.json")
    val pictures: File get() = File(AppPaths.root, "styles").apply { mkdirs() }

    @Synchronized
    fun list(): List<SavedStyle> = try {
        val text = if (store.isFile) store.readText() else "[]"
        val array = JSONArray(text)
        (0 until array.length()).map { SavedStyle.fromJson(array.getJSONObject(it)) }.filter { it.id.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    private fun write(items: List<SavedStyle>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        val part = File(store.parentFile, "styles.json.part")
        part.writeText(array.toString())
        if (!part.renameTo(store)) {
            store.writeText(array.toString())
            part.delete()
        }
    }

    @Synchronized
    fun add(style: SavedStyle) = write(list() + style)

    @Synchronized
    fun remove(id: String) {
        val all = list()
        all.firstOrNull { it.id == id }?.let { s ->
            if (!s.background.startsWith("builtin:")) File(s.background).takeIf { it.parentFile == pictures }?.delete()
        }
        write(all.filter { it.id != id })
    }

    /** Most vivid hue (0-359) of the whole picture, or 218 for a grey one. */
    private fun dominantHue(small: Bitmap): Int {
        val bins = DoubleArray(360)
        var total = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            Color.colorToHSV(small.getPixel(x, y), hsv)
            if (hsv[1] < 0.2f || hsv[2] < 0.15f) continue
            val weight = (hsv[1] * hsv[2]).toDouble()
            bins[hsv[0].toInt().coerceIn(0, 359)] += weight
            total += weight
        }
        if (total < small.width * small.height * 0.01) return 218
        var best = 0
        var bestScore = -1.0
        for (h in 0 until 360) {
            var score = 0.0
            for (d in -10..10) score += bins[(h + d + 360) % 360] * (11 - Math.abs(d))
            if (score > bestScore) {
                bestScore = score
                best = h
            }
        }
        return best
    }

    /** Average colour of every cell of a [COLS] x [ROWS] grid laid over the picture. */
    private fun cells(small: Bitmap): List<Int> {
        val out = ArrayList<Int>(COLS * ROWS)
        for (row in 0 until ROWS) for (col in 0 until COLS) {
            val x0 = small.width * col / COLS
            val x1 = (small.width * (col + 1) / COLS).coerceAtLeast(x0 + 1)
            val y0 = small.height * row / ROWS
            val y1 = (small.height * (row + 1) / ROWS).coerceAtLeast(y0 + 1)
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = small.getPixel(x, y)
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                n++
            }
            out.add(Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()))
        }
        return out
    }

    /**
     * Reads the picture. A solid style gets one dominant hue. A dynamic style gets the real colour
     * of every cell of the picture: a white place stays white, a black place stays black.
     * With [contrast] every colour becomes its opposite (white <-> black, orange <-> blue).
     */
    fun analyze(source: Bitmap, dynamic: Boolean, contrast: Boolean): Analysis {
        val small = Bitmap.createScaledBitmap(source, 96, 54, true)
        val hue = dominantHue(small).let { if (contrast) (it + 180) % 360 else it }
        var grid = if (dynamic) cells(small) else emptyList()
        if (contrast) grid = grid.map { Color.rgb(255 - Color.red(it), 255 - Color.green(it), 255 - Color.blue(it)) }
        if (small !== source) small.recycle()
        return Analysis(listOf(hue, hue, hue), grid)
    }
}
