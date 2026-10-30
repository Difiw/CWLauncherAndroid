package ru.cw.launcher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Launcher palette. The interface colours are written once as blue values; [Tc] turns a blue
 * into the selected palette by rotating its hue, so a theme changes the whole interface at once
 * while greys, greens, reds and the background picture stay as they are.
 */
object Pal {
    class Theme(val id: String, val title: String, val hue: Float)

    val themes = listOf(
        Theme("blue", "Синяя", -1f),
        Theme("emerald", "Изумруд", 152f),
        Theme("ruby", "Рубин", 350f),
        Theme("amethyst", "Аметист", 276f),
        Theme("amber", "Янтарь", 34f),
        Theme("custom", "Свой", -2f)
    )

    @Volatile var hue = -1f
        private set
    @Volatile var surface = 1f
        private set

    /** Changes on every theme or opacity change; the root reads it to rebuild the interface. */
    var version by mutableIntStateOf(0)
        private set

    private val cache = HashMap<Long, Color>()

    /** Hue of the player-made style; set together with the theme. */
    @Volatile var customHue = 280f
        private set

    /** Hue of a theme as shown on screen (the blue theme is 218). */
    fun hueOf(id: String): Float {
        if (id == "custom") return customHue
        val h = themes.firstOrNull { it.id == id }?.hue ?: -1f
        return if (h < 0f) 218f else h
    }

    /** Card roundness and outline width (dp), set from the appearance settings. */
    @Volatile var corner = 18
        private set
    @Volatile var border = 1
        private set

    fun look(cornerDp: Int, borderDp: Int) {
        corner = cornerDp.coerceIn(4, 32)
        border = borderDp.coerceIn(0, 4)
        version += 1
    }

    /** Hues (left, middle, right) of a dynamic style, or null for a solid one. */
    @Volatile var dyn: IntArray? = null
        private set

    /** Colour the dynamic style has at a place of the screen (0..1 each way), blended between neighbouring cells. */
    fun dynAt(x: Float, y: Float): Color {
        val g = dyn ?: return Color.Unspecified
        val cols = ru.cw.launcher.engine.Styles.COLS
        val rows = ru.cw.launcher.engine.Styles.ROWS
        val fx = (x.coerceIn(0f, 1f) * cols - 0.5f).coerceIn(0f, cols - 1f)
        val fy = (y.coerceIn(0f, 1f) * rows - 0.5f).coerceIn(0f, rows - 1f)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = (x0 + 1).coerceAtMost(cols - 1)
        val y1 = (y0 + 1).coerceAtMost(rows - 1)
        val tx = fx - x0
        val ty = fy - y0
        fun ch(shift: Int): Float {
            fun c(cx: Int, cy: Int) = ((g[cy * cols + cx] shr shift) and 0xFF) / 255f
            val top = c(x0, y0) * (1 - tx) + c(x1, y0) * tx
            val bottom = c(x0, y1) * (1 - tx) + c(x1, y1) * tx
            return top * (1 - ty) + bottom * ty
        }
        return Color(ch(16), ch(8), ch(0), 1f)
    }

    /** [dynHues] "a,b,c" sets a dynamic style, "" clears it, null keeps the current one (a non-custom theme always clears it). */
    fun set(id: String, surfacePercent: Int, custom: Int = 280, dynHues: String? = null) {
        synchronized(cache) {
            if (dynHues != null) {
                val parts = dynHues.split(",").mapNotNull { it.trim().toIntOrNull() }
                dyn = if (parts.size == ru.cw.launcher.engine.Styles.COLS * ru.cw.launcher.engine.Styles.ROWS) parts.toIntArray() else null
            }
            if (id != "custom") dyn = null
            customHue = custom.coerceIn(0, 359).toFloat()
            hue = if (id == "custom") customHue else (themes.firstOrNull { it.id == id }?.hue ?: -1f)
            surface = surfacePercent.coerceIn(50, 140) / 100f
            cache.clear()
        }
        version += 1
    }

    fun convert(argb: Long): Color {
        synchronized(cache) {
            cache[argb]?.let { return it }
            val a = ((argb shr 24) and 0xFF).toInt()
            val r = ((argb shr 16) and 0xFF).toInt()
            val g = ((argb shr 8) and 0xFF).toInt()
            val b = (argb and 0xFF).toInt()
            val hsv = FloatArray(3)
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            var h = hsv[0]
            if (hue >= 0f && hsv[1] > 0.15f && h in 195f..255f) {
                h = ((hue + (h - 218f)) % 360f + 360f) % 360f
            }
            var alpha = a
            // Dark translucent panels follow the "panel opacity" setting.
            if (surface != 1f && a in 0x40..0xF0 && hsv[2] < 0.45f) {
                alpha = (a * surface).toInt().coerceIn(0x30, 0xFF)
            }
            val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, hsv[1], hsv[2]))
            val out = Color(
                red = android.graphics.Color.red(rgb) / 255f,
                green = android.graphics.Color.green(rgb) / 255f,
                blue = android.graphics.Color.blue(rgb) / 255f,
                alpha = alpha / 255f
            )
            cache[argb] = out
            return out
        }
    }
}

/** A colour of the current palette, given as its blue-theme value. */
fun Tc(argb: Long): Color = Pal.convert(argb)

fun Tc(argb: Int): Color = Pal.convert(argb.toLong() and 0xFFFFFFFFL)

/** The five main backgrounds, one per colour theme, and the rule that decides which one is shown. */
object Backgrounds {
    class Item(val id: String, val title: String, val res: Int)

    val items = listOf(
        Item("blue", "Основной синий", ru.cw.launcher.R.drawable.bg_blue),
        Item("ruby", "Основной рубин", ru.cw.launcher.R.drawable.bg_ruby),
        Item("emerald", "Основной изумруд", ru.cw.launcher.R.drawable.bg_emerald),
        Item("amber", "Основной янтарь", ru.cw.launcher.R.drawable.bg_amber),
        Item("amethyst", "Основной аметист", ru.cw.launcher.R.drawable.bg_amethyst)
    )

    const val BUILTIN = "builtin:"

    /** The main background that fits a theme; a custom style takes the one with the closest hue. */
    fun forTheme(id: String): Item {
        items.firstOrNull { it.id == id }?.let { return it }
        val hue = Pal.hueOf(id)
        fun gap(item: Item): Float {
            val d = Math.abs(Pal.hueOf(item.id) - hue) % 360f
            return if (d > 180f) 360f - d else d
        }
        return items.minByOrNull { gap(it) } ?: items[0]
    }

    /** A picture file chosen by the player, only while "Pick background" is off. */
    fun file(cfg: ru.cw.launcher.engine.CwSettings): java.io.File? {
        if (cfg.bgAuto) return null
        val path = cfg.backgroundPath
        if (path.isBlank() || path.startsWith(BUILTIN)) return null
        return java.io.File(path).takeIf { it.isFile }
    }

    /** The built-in background to show when no picture file is chosen. */
    fun item(cfg: ru.cw.launcher.engine.CwSettings): Item {
        if (cfg.bgAuto) return forTheme(cfg.uiPalette)
        val path = cfg.backgroundPath
        if (path.startsWith(BUILTIN)) items.firstOrNull { it.id == path.removePrefix(BUILTIN) }?.let { return it }
        return items[0]
    }

    /** Shows the effective background in a plain view (game start-up screen, game settings). */
    fun apply(view: android.widget.ImageView, cfg: ru.cw.launcher.engine.CwSettings) {
        val file = file(cfg)
        val bitmap = file?.let {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(it.absolutePath, bounds)
            var sample = 1
            while (sample < 16 && (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1080)) sample *= 2
            android.graphics.BitmapFactory.decodeFile(it.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (bitmap != null) view.setImageBitmap(bitmap) else view.setImageResource(item(cfg).res)
    }
}

/** The designs of the "Minecraft is loading" screen the player can choose from. */
object BootStyles {
    class Item(val id: String, val title: String, val glyph: String)

    val items = listOf(
        Item("card", "Карточка", "▭"),
        Item("minimal", "Минимум", "▁"),
        Item("cinema", "Кино", "▌"),
        Item("pixels", "Пиксели", "▦"),
        Item("ring", "Кольцо", "◔")
    )

    fun of(id: String): Item = items.firstOrNull { it.id == id } ?: items[0]
}
