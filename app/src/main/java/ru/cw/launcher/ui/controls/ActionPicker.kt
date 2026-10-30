package ru.cw.launcher.ui.controls

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Lays children out left to right and wraps them onto new lines. */
class FlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + gap
                lineHeight = 0
            }
            x += c.measuredWidth + gap
            lineHeight = maxOf(lineHeight, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + lineHeight + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + gap
                lineHeight = 0
            }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            lineHeight = maxOf(lineHeight, c.measuredHeight)
        }
    }
}

/**
 * One compact dialog for assigning a button action: the type chips on top, and for "key or
 * combination" a PC keyboard to tap the key, plus Ctrl/Alt/Shift toggles. The chosen action is
 * shown in a line at the top.
 */
object ActionPicker {

    private class Chip(val label: String, val value: String)

    private val chips = listOf(
        Chip("Клавиша / сочетание", "#keys"),
        Chip("ЛКМ", "mouse:0"),
        Chip("ПКМ", "mouse:1"),
        Chip("СКМ", "mouse:2"),
        Chip("Колесо ↑", "scroll:1"),
        Chip("Колесо ↓", "scroll:-1"),
        Chip("Приседание", Act.SNEAK),
        Chip("Спринт", Act.SPRINT),
        Chip("Чат", Act.CHAT),
        Chip("ПК-клавиатура", Act.KEYBOARD),
        Chip("Хотбар ↔", Act.STICK_H),
        Chip("Хотбар ↕", Act.STICK_V),
        Chip("Полоса хотбара", Act.BAR),
        Chip("Сменить вид управления", Act.PROFILE)
    )

    private class KeyDef(val label: String, val code: Int, val weight: Float = 1f)

    private fun k(label: String, code: Int, w: Float = 1f) = KeyDef(label, code, w)

    private val rows: List<List<KeyDef>> = listOf(
        listOf(k("Esc", 256)) + (1..12).map { k("F$it", 289 + it) },
        listOf(k("`", 96)) + "1234567890".map { k(it.toString(), it.code) } + listOf(k("-", 45), k("=", 61), k("Bksp", 259, 1.6f)),
        listOf(k("Tab", 258, 1.4f)) + "QWERTYUIOP".map { k(it.toString(), it.code) } + listOf(k("[", 91), k("]", 93), k("\\", 92)),
        listOf(k("Caps", 280, 1.6f)) + "ASDFGHJKL".map { k(it.toString(), it.code) } + listOf(k(";", 59), k("'", 39), k("Enter", 257, 1.8f)),
        listOf(k("Z", 90)) + "XCVBNM".map { k(it.toString(), it.code) } + listOf(k(",", 44), k(".", 46), k("/", 47)),
        listOf(k("Space", 32, 4f), k("←", 263), k("↑", 265), k("↓", 264), k("→", 262), k("Ins", 260), k("Del", 261),
            k("Home", 268), k("End", 269), k("PgUp", 266), k("PgDn", 267)),
        (0..9).map { k("N$it", 320 + it) } + listOf(k("N.", 330), k("N+", 334), k("NEnt", 335))
    )

    fun pick(context: Context, current: String, onPicked: (String) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Working state of the dialog.
        var action = current
        val mods = HashSet<Int>()
        var mainKey = -1
        val existing = if (current.startsWith("keys:")) Act.parseKeys(current) else emptyList()
        for (c in existing) {
            if (KeyTable.modifiers.any { it.code == c }) mods.add(c) else mainKey = c
        }

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xFF102038.toInt())
        bg.cornerRadius = dp(14).toFloat()
        root.background = bg
        root.setPadding(dp(12), dp(10), dp(12), dp(10))

        val summary = TextView(context)
        summary.setTextColor(Color.WHITE)
        summary.textSize = 15f
        root.addView(summary)

        val chipBox = FlowLayout(context, dp(6))
        chipBox.setPadding(0, dp(8), 0, dp(4))
        val chipButtons = ArrayList<Pair<Chip, Button>>()
        val keyPanel = LinearLayout(context)
        keyPanel.orientation = LinearLayout.VERTICAL
        val keyButtons = ArrayList<Pair<KeyDef, Button>>()
        val modButtons = ArrayList<Pair<Int, Button>>()

        fun comboAction(): String {
            val codes = ArrayList<Int>()
            for (m in KeyTable.modifiers) if (mods.contains(m.code)) codes.add(m.code)
            if (mainKey >= 0) codes.add(mainKey)
            return if (codes.isEmpty()) "" else Act.keys(*codes.toIntArray())
        }

        fun paint(b: Button, on: Boolean) {
            val d = GradientDrawable()
            d.setColor(if (on) 0xFF4E8CFF.toInt() else 0xFF1F3354.toInt())
            d.cornerRadius = dp(6).toFloat()
            d.setStroke(dp(1), 0xFF0B1424.toInt())
            b.background = d
        }

        fun refresh() {
            val keysMode = action.startsWith("keys:") || action == "#keys"
            summary.text = "Действие: " + if (action == "#keys") "выберите клавишу ниже" else Act.describe(action)
            for ((chip, b) in chipButtons) {
                paint(b, if (chip.value == "#keys") keysMode else chip.value == action)
            }
            keyPanel.visibility = if (keysMode) View.VISIBLE else View.GONE
            for ((key, b) in keyButtons) paint(b, key.code == mainKey)
            for ((code, b) in modButtons) paint(b, mods.contains(code))
        }

        fun small(label: String): Button {
            val b = Button(context)
            b.text = label
            b.setTextColor(Color.WHITE)
            b.isAllCaps = false
            b.textSize = 12f
            b.minWidth = 0
            b.minimumWidth = 0
            b.minHeight = 0
            b.minimumHeight = 0
            b.setPadding(dp(8), dp(4), dp(8), dp(4))
            return b
        }

        for (chip in chips) {
            val b = small(chip.label)
            b.setOnClickListener {
                if (chip.value == "#keys") {
                    action = comboAction().ifEmpty { "#keys" }
                } else {
                    action = chip.value
                }
                refresh()
            }
            chipButtons.add(chip to b)
            chipBox.addView(b)
        }
        root.addView(chipBox)

        // Modifier toggles.
        val modRow = LinearLayout(context)
        modRow.orientation = LinearLayout.HORIZONTAL
        for (m in KeyTable.modifiers) {
            val b = small(m.name)
            b.setOnClickListener {
                if (!mods.add(m.code)) mods.remove(m.code)
                action = comboAction().ifEmpty { "#keys" }
                refresh()
            }
            modButtons.add(m.code to b)
            val lp = LinearLayout.LayoutParams(0, dp(36), 1f)
            lp.marginEnd = dp(4)
            modRow.addView(b, lp)
        }
        val clear = small("Сбросить")
        clear.setOnClickListener {
            mods.clear()
            mainKey = -1
            action = "#keys"
            refresh()
        }
        paint(clear, false)
        modRow.addView(clear, LinearLayout.LayoutParams(0, dp(36), 1f))
        keyPanel.addView(modRow)

        // Key grid: rows of equal-height keys, so every key is reachable at once.
        for (row in rows) {
            val line = LinearLayout(context)
            line.orientation = LinearLayout.HORIZONTAL
            for (key in row) {
                val b = small(key.label)
                b.textSize = 11f
                b.setPadding(0, 0, 0, 0)
                b.setOnClickListener {
                    mainKey = if (mainKey == key.code) -1 else key.code
                    action = comboAction().ifEmpty { "#keys" }
                    refresh()
                }
                keyButtons.add(key to b)
                val lp = LinearLayout.LayoutParams(0, dp(34), key.weight)
                lp.setMargins(dp(1), dp(1), dp(1), dp(1))
                line.addView(b, lp)
            }
            keyPanel.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val scroll = ScrollView(context)
        scroll.addView(keyPanel)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(context)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.END
        val cancel = small("Отмена")
        cancel.setOnClickListener { dialog.dismiss() }
        paint(cancel, false)
        val ok = small("Назначить")
        ok.setOnClickListener {
            val result = if (action == "#keys") "" else action
            if (result.isNotEmpty()) onPicked(result)
            dialog.dismiss()
        }
        paint(ok, true)
        val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
        lp.topMargin = dp(8)
        lp.marginEnd = dp(6)
        buttons.addView(cancel, lp)
        val lp2 = LinearLayout.LayoutParams(0, dp(40), 1f)
        lp2.topMargin = dp(8)
        buttons.addView(ok, lp2)
        root.addView(buttons)

        refresh()
        dialog.setContentView(root)
        dialog.show()
        val metrics = context.resources.displayMetrics
        dialog.window?.setLayout((metrics.widthPixels * 0.94f).toInt(), (metrics.heightPixels * 0.94f).toInt())
    }
}
