package ru.cw.launcher.ui.controls

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

/**
 * Full PC keyboard drawn on screen. Every key sends the physical GLFW key code, so keybinds
 * of Minecraft and mods see real keys. Printable keys also send the typed character (EN or RU
 * layout), which is what text fields use; characters are skipped while Ctrl or Alt is held.
 * Shift, Ctrl and Alt are latched: tap to arm, they apply to the next key and release.
 */
class VirtualKeyboard(context: Context, private val input: GameInput, private val onClose: () -> Unit) : LinearLayout(context) {

    private class Key(
        val label: String,
        val code: Int,
        val en: String? = null,
        val enShift: String? = null,
        val ru: String? = null,
        val ruShift: String? = null,
        val weight: Float = 1f
    )

    private var russian = false
    private var shift = false
    private var ctrl = false
    private var alt = false
    private var caps = false
    private val letterButtons = ArrayList<Pair<Key, Button>>()
    private val toggles = HashMap<Int, Button>()
    private lateinit var layoutButton: Button

    init {
        orientation = VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xE60B1424.toInt())
        setBackgroundColor(0xE60B1424.toInt())
        setPadding(dp(4), dp(4), dp(4), dp(4))
        isClickable = true
        build()
        refreshLabels()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun k(label: String, code: Int, w: Float = 1f) = Key(label, code, weight = w)

    private fun letter(code: Int, en: String, ru: String): Key = Key(en.uppercase(), code, en, en.uppercase(), ru, ru.uppercase())

    private fun build() {
        val rows = ArrayList<List<Key>>()
        rows.add(listOf(k("Esc", 256)) + (1..12).map { k("F$it", 289 + it) } + listOf(k("Del", 261)))
        rows.add(
            listOf(Key("`", 96, "`", "~", "ё", "Ё")) +
                listOf(
                    Key("1", 49, "1", "!", "1", "!"), Key("2", 50, "2", "@", "2", "\""), Key("3", 51, "3", "#", "3", "№"),
                    Key("4", 52, "4", "$", "4", ";"), Key("5", 53, "5", "%", "5", "%"), Key("6", 54, "6", "^", "6", ":"),
                    Key("7", 55, "7", "&", "7", "?"), Key("8", 56, "8", "*", "8", "*"), Key("9", 57, "9", "(", "9", "("),
                    Key("0", 48, "0", ")", "0", ")"), Key("-", 45, "-", "_", "-", "_"), Key("=", 61, "=", "+", "=", "+"),
                    k("⌫", 259, 1.6f)
                )
        )
        rows.add(
            listOf(k("Tab", 258, 1.4f)) + listOf(
                letter(81, "q", "й"), letter(87, "w", "ц"), letter(69, "e", "у"), letter(82, "r", "к"), letter(84, "t", "е"),
                letter(89, "y", "н"), letter(85, "u", "г"), letter(73, "i", "ш"), letter(79, "o", "щ"), letter(80, "p", "з"),
                Key("[", 91, "[", "{", "х", "Х"), Key("]", 93, "]", "}", "ъ", "Ъ"), Key("\\", 92, "\\", "|", "\\", "/")
            )
        )
        rows.add(
            listOf(k("Caps", 280, 1.6f)) + listOf(
                letter(65, "a", "ф"), letter(83, "s", "ы"), letter(68, "d", "в"), letter(70, "f", "а"), letter(71, "g", "п"),
                letter(72, "h", "р"), letter(74, "j", "о"), letter(75, "k", "л"), letter(76, "l", "д"),
                Key(";", 59, ";", ":", "ж", "Ж"), Key("'", 39, "'", "\"", "э", "Э"), k("Enter", 257, 1.8f)
            )
        )
        rows.add(
            listOf(k("Shift", 340, 2f)) + listOf(
                letter(90, "z", "я"), letter(88, "x", "ч"), letter(67, "c", "с"), letter(86, "v", "м"), letter(66, "b", "и"),
                letter(78, "n", "т"), letter(77, "m", "ь"),
                Key(",", 44, ",", "<", "б", "Б"), Key(".", 46, ".", ">", "ю", "Ю"), Key("/", 47, "/", "?", ".", ",")
            ) + listOf(k("▲", 265))
        )
        rows.add(
            listOf(k("Ctrl", 341, 1.4f), k("Alt", 342, 1.4f), Key("Пробел", 32, " ", " ", " ", " ", 5f),
                k("◀", 263), k("▼", 264), k("▶", 262), k("Home", 268), k("End", 269), k("PgUp", 266), k("PgDn", 267))
        )
        for (row in rows) {
            val line = LinearLayout(context)
            line.orientation = HORIZONTAL
            for (key in row) line.addView(button(key), LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight))
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val last = LinearLayout(context)
        last.orientation = HORIZONTAL
        layoutButton = plain("RU/EN") { russian = !russian; refreshLabels() }
        last.addView(layoutButton, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        last.addView(plain("Ввод") { send(Key("Enter", 257)) }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        last.addView(plain("Скрыть ✕") { onClose() }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(last, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun style(b: Button) {
        b.setTextColor(Color.WHITE)
        b.isAllCaps = false
        b.textSize = 11f
        b.setPadding(0, 0, 0, 0)
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.gravity = Gravity.CENTER
        val d = GradientDrawable()
        d.setColor(0xFF1F3354.toInt())
        d.cornerRadius = dp(5).toFloat()
        d.setStroke(dp(1), 0xFF0B1424.toInt())
        b.background = d
    }

    private fun plain(label: String, click: () -> Unit): Button {
        val b = Button(context)
        b.text = label
        style(b)
        b.setOnClickListener { click() }
        return b
    }

    private fun button(key: Key): Button {
        val b = Button(context)
        b.text = key.label
        style(b)
        when (key.code) {
            340, 341, 342, 280 -> {
                toggles[key.code] = b
                b.setOnClickListener {
                    when (key.code) {
                        340 -> shift = !shift
                        341 -> ctrl = !ctrl
                        342 -> alt = !alt
                        280 -> caps = !caps
                    }
                    refreshLabels()
                }
            }
            else -> {
                if (key.en != null) letterButtons.add(key to b)
                b.setOnClickListener { send(key) }
            }
        }
        return b
    }

    private fun send(key: Key) {
        val mods = ArrayList<Int>()
        if (ctrl) mods.add(341)
        if (alt) mods.add(342)
        if (shift) mods.add(340)
        for (m in mods) input.key(m, true)
        input.key(key.code, true)
        if (!ctrl && !alt) {
            val ch = charFor(key)
            if (ch != null) input.text(ch)
        }
        input.key(key.code, false)
        for (m in mods.reversed()) input.key(m, false)
        if (ctrl || alt || shift) {
            ctrl = false
            alt = false
            shift = false
            refreshLabels()
        }
    }

    private fun charFor(key: Key): String? {
        val base = if (russian) key.ru else key.en
        if (base == null) return null
        val upper = if (russian) key.ruShift else key.enShift
        val isLetter = base.length == 1 && base[0].isLetter()
        val useShift = if (isLetter) (shift != caps) else shift
        return if (useShift) upper else base
    }

    private fun refreshLabels() {
        layoutButton.text = if (russian) "RU → EN" else "EN → RU"
        for ((key, b) in letterButtons) {
            if (key.en == " ") continue
            val ch = charFor(key)
            if (ch != null && ch.isNotBlank()) b.text = ch
        }
        fun mark(code: Int, on: Boolean) {
            val b = toggles[code] ?: return
            val d = GradientDrawable()
            d.setColor(if (on) 0xFF4E8CFF.toInt() else 0xFF1F3354.toInt())
            d.cornerRadius = dp(5).toFloat()
            d.setStroke(dp(1), 0xFF0B1424.toInt())
            b.background = d
        }
        mark(340, shift)
        mark(341, ctrl)
        mark(342, alt)
        mark(280, caps)
    }

    fun toggle() {
        visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }
}
