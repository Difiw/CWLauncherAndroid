package ru.cw.launcher.ui.controls

/** GLFW key codes by name, and the text form of the actions assigned to on-screen buttons. */
object KeyTable {
    class Entry(val name: String, val code: Int)

    val keys: List<Entry> = buildList {
        for (c in 'A'..'Z') add(Entry(c.toString(), c.code))
        for (c in '0'..'9') add(Entry(c.toString(), c.code))
        for (i in 1..12) add(Entry("F$i", 289 + i))
        add(Entry("Esc", 256))
        add(Entry("Enter", 257))
        add(Entry("Tab", 258))
        add(Entry("Backspace", 259))
        add(Entry("Space", 32))
        add(Entry("Insert", 260))
        add(Entry("Delete", 261))
        add(Entry("Стрелка вправо", 262))
        add(Entry("Стрелка влево", 263))
        add(Entry("Стрелка вниз", 264))
        add(Entry("Стрелка вверх", 265))
        add(Entry("Page Up", 266))
        add(Entry("Page Down", 267))
        add(Entry("Home", 268))
        add(Entry("End", 269))
        add(Entry("Caps Lock", 280))
        add(Entry("Левый Shift", 340))
        add(Entry("Левый Ctrl", 341))
        add(Entry("Левый Alt", 342))
        add(Entry("Правый Shift", 344))
        add(Entry("Правый Ctrl", 345))
        add(Entry("Правый Alt", 346))
        add(Entry("`", 96))
        add(Entry("-", 45))
        add(Entry("=", 61))
        add(Entry("[", 91))
        add(Entry("]", 93))
        add(Entry("\\", 92))
        add(Entry(";", 59))
        add(Entry("'", 39))
        add(Entry(",", 44))
        add(Entry(".", 46))
        add(Entry("/", 47))
        for (i in 0..9) add(Entry("Num $i", 320 + i))
        add(Entry("Num .", 330))
        add(Entry("Num /", 331))
        add(Entry("Num *", 332))
        add(Entry("Num -", 333))
        add(Entry("Num +", 334))
        add(Entry("Num Enter", 335))
    }

    val modifiers: List<Entry> = listOf(Entry("Ctrl", 341), Entry("Alt", 342), Entry("Shift", 340))

    private val byCode = keys.associateBy { it.code }

    fun name(code: Int): String = byCode[code]?.name ?: "код $code"
}

/** Action strings: "keys:341+87", "mouse:0", "scroll:1", or a built-in name. */
object Act {
    const val SNEAK = "sneak"
    const val SPRINT = "sprint"
    const val CHAT = "chat"
    const val KEYBOARD = "keyboard"
    const val STICK_H = "hotbar-h"
    const val STICK_V = "hotbar-v"
    const val BAR = "hotbar-bar"
    const val PROFILE = "profile"

    fun keys(vararg codes: Int): String = "keys:" + codes.joinToString("+")

    fun parseKeys(action: String): List<Int> =
        action.removePrefix("keys:").split('+').mapNotNull { it.trim().toIntOrNull() }

    fun describe(action: String): String = when {
        action == SNEAK -> "Приседание"
        action == SPRINT -> "Спринт"
        action == CHAT -> "Чат (клавиатура Android)"
        action == KEYBOARD -> "ПК-клавиатура"
        action == STICK_H -> "Джойстик хотбара ↔"
        action == STICK_V -> "Джойстик хотбара ↕"
        action == BAR -> "Полоса прокрутки хотбара"
        action == PROFILE -> "Сменить вид управления"
        action.startsWith("keys:") -> parseKeys(action).joinToString(" + ") { KeyTable.name(it) }.ifBlank { "не назначено" }
        action == "mouse:0" -> "Левая кнопка мыши"
        action == "mouse:1" -> "Правая кнопка мыши"
        action == "mouse:2" -> "Средняя кнопка мыши"
        action == "scroll:1" -> "Колесо вверх"
        action == "scroll:-1" -> "Колесо вниз"
        else -> "не назначено"
    }
}
