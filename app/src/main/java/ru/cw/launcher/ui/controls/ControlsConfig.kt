package ru.cw.launcher.ui.controls

import org.json.JSONObject
import ru.cw.launcher.engine.AppPaths
import java.io.File

/** Identifiers of the default buttons; custom buttons get generated ids. */
object BtnId {
    const val JUMP = "jump"
    const val SNEAK = "sneak"
    const val SPRINT = "sprint"
    const val ATTACK = "attack"
    const val USE = "use"
    const val CHAT = "chat"
    const val INV = "inv"
    const val MENU = "menu"
    const val PREV = "prev"
    const val NEXT = "next"
    const val KEYBOARD = "kbd"
    const val SWITCH = "switch"
    const val STICK = "hstick"
    const val BAR = "hbar"
    const val ZONE = "zone"
    const val EYE = "eye"
    const val GEAR = "gear"
}

/** Position is the button centre as a fraction of the screen; size is in dp. */
class ButtonCfg(
    var enabled: Boolean,
    var cx: Float,
    var cy: Float,
    var sizeDp: Int,
    var alpha: Float,
    var label: String,
    var action: String,
    /** Height in dp for rectangular items (the hotbar bar); 0 means a square button. */
    var heightDp: Int = 0,
    /** ARGB fill colour of the idle button; 0 keeps the style colour. */
    var color: Int = 0,
    /** The button is not drawn in the game but still reacts to touches. */
    var hidden: Boolean = false
)

class ControlsConfig {
    /** Which of the [PROFILES] control layouts this object holds (0-based). */
    var profile = 0
    val buttons = LinkedHashMap<String, ButtonCfg>()
    var guiVisible = true

    /** true: the joystick appears under the first touch; false: it stays at the zone centre. */
    var joyFloating = true
    var zoneLeft = 0f
    var zoneTop = 0.28f
    var zoneRight = 0.45f
    var zoneBottom = 1f
    var joyRadiusDp = 64
    var joyAlpha = 0.55f

    /** Fraction of the radius the finger must travel before movement starts. */
    var joyDeadZone = 0.22f
    var lookSensitivity = 1.5f
    var sneakToggle = false

    /** 0 off, 1 slide left, 2 slide right. */
    var sneakGesture = 2
    var sprintToggle = false

    /** Hotbar slot switching: 0 arrow buttons, 1 hotbar joystick, 2 both. */
    var hotbarMode = 0

    /** A tap on a hotbar slot selects that slot. */
    var hotbarTap = true

    /** One-finger vertical swipe in a game menu becomes mouse wheel scrolling. */
    var guiSwipeScroll = true

    /** The eye (show/hide controls) and the gear (settings) can be moved and resized too. */
    val eye = ButtonCfg(true, 0.045f, 0.09f, 44, 0.9f, "", "")
    val gear = ButtonCfg(true, 0.105f, 0.09f, 44, 0.9f, "", "")

    init {
        resetDefaults()
    }

    fun resetDefaults() {
        buttons.clear()
        fun put(id: String, label: String, action: String, cx: Float, cy: Float, size: Int, enabled: Boolean = true) {
            buttons[id] = ButtonCfg(enabled, cx, cy, size, 0.6f, label, action)
        }
        put(BtnId.SPRINT, "Бег", Act.SPRINT, 0.93f, 0.40f, 64)
        put(BtnId.JUMP, "Прыжок", Act.keys(Glfw.KEY_SPACE), 0.93f, 0.62f, 72)
        put(BtnId.SNEAK, "Присед", Act.SNEAK, 0.93f, 0.83f, 64)
        put(BtnId.ATTACK, "Удар", "mouse:0", 0.80f, 0.72f, 60)
        put(BtnId.USE, "Исп.", "mouse:1", 0.80f, 0.50f, 56)
        put(BtnId.MENU, "Меню", Act.keys(Glfw.KEY_ESCAPE), 0.68f, 0.08f, 44)
        put(BtnId.CHAT, "Чат", Act.CHAT, 0.78f, 0.08f, 44)
        put(BtnId.INV, "Инв.", Act.keys(Glfw.KEY_E), 0.88f, 0.08f, 44)
        put(BtnId.PREV, "<", "scroll:1", 0.33f, 0.90f, 44)
        put(BtnId.NEXT, ">", "scroll:-1", 0.67f, 0.90f, 44)
        put(BtnId.KEYBOARD, "Клав.", Act.KEYBOARD, 0.58f, 0.08f, 44, enabled = false)
        put(BtnId.SWITCH, "Вид", Act.PROFILE, 0.58f, 0.20f, 44, enabled = false)
        put(BtnId.STICK, "Слот", Act.STICK_H, 0.50f, 0.78f, 56)
        put(BtnId.BAR, "Слот", Act.BAR, 0.50f, 0.74f, 220, enabled = false)
        buttons[BtnId.BAR]?.heightDp = 30
        guiVisible = true
        joyFloating = true
        zoneLeft = 0f
        zoneTop = 0.28f
        zoneRight = 0.45f
        zoneBottom = 1f
        joyRadiusDp = 64
        joyAlpha = 0.55f
        joyDeadZone = 0.22f
        lookSensitivity = 1.5f
        sneakToggle = false
        sneakGesture = 2
        sprintToggle = false
        hotbarMode = 0
        hotbarTap = true
        guiSwipeScroll = true
        eye.cx = 0.045f; eye.cy = 0.09f; eye.sizeDp = 44; eye.alpha = 0.9f
        gear.cx = 0.105f; gear.cy = 0.09f; gear.sizeDp = 44; gear.alpha = 0.9f
    }

    fun newCustomId(): String = "c" + System.currentTimeMillis().toString(36)

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("version", 2)
        val b = JSONObject()
        for ((id, c) in buttons) {
            b.put(
                id, JSONObject()
                    .put("enabled", c.enabled).put("cx", c.cx.toDouble()).put("cy", c.cy.toDouble())
                    .put("size", c.sizeDp).put("alpha", c.alpha.toDouble())
                    .put("label", c.label).put("action", c.action).put("h", c.heightDp)
                    .put("color", c.color).put("hidden", c.hidden)
            )
        }
        o.put("buttons", b)
        o.put("guiVisible", guiVisible)
        o.put("joyFloating", joyFloating)
        o.put("zoneLeft", zoneLeft.toDouble()).put("zoneTop", zoneTop.toDouble())
        o.put("zoneRight", zoneRight.toDouble()).put("zoneBottom", zoneBottom.toDouble())
        o.put("joyRadiusDp", joyRadiusDp).put("joyAlpha", joyAlpha.toDouble())
        o.put("joyDeadZone", joyDeadZone.toDouble()).put("lookSensitivity", lookSensitivity.toDouble())
        o.put("sneakToggle", sneakToggle).put("sneakGesture", sneakGesture).put("sprintToggle", sprintToggle)
        o.put("hotbarMode", hotbarMode).put("hotbarTap", hotbarTap).put("guiSwipeScroll", guiSwipeScroll)
        o.put("eye", iconJson(eye)).put("gear", iconJson(gear))
        return o
    }

    /**
     * Reads a saved layout. Files written before version 2 only held position, size and
     * transparency of the default buttons; those values are kept and the new fields take
     * their defaults, so an existing layout migrates without any change.
     */
    fun fromJson(o: JSONObject) {
        val version = o.optInt("version", 1)
        val b = o.optJSONObject("buttons")
        if (b != null) {
            if (version >= 2) buttons.clear()
            val ids = b.keys().asSequence().toList()
            for (id in ids) {
                val j = b.optJSONObject(id) ?: continue
                val existing = buttons[id]
                if (existing == null && version < 2) continue
                val c = existing ?: ButtonCfg(true, 0.5f, 0.5f, 56, 0.6f, "Кн.", "")
                c.enabled = j.optBoolean("enabled", c.enabled)
                c.cx = j.optDouble("cx", c.cx.toDouble()).toFloat().coerceIn(0f, 1f)
                c.cy = j.optDouble("cy", c.cy.toDouble()).toFloat().coerceIn(0f, 1f)
                c.sizeDp = j.optInt("size", c.sizeDp).coerceIn(14, 600)
                c.alpha = j.optDouble("alpha", c.alpha.toDouble()).toFloat().coerceIn(0.1f, 1f)
                c.label = j.optString("label", c.label)
                c.action = j.optString("action", c.action)
                c.heightDp = j.optInt("h", c.heightDp).coerceIn(0, 600)
                c.color = j.optInt("color", c.color)
                c.hidden = j.optBoolean("hidden", c.hidden)
                buttons[id] = c
            }
        }
        if (!buttons.containsKey(BtnId.SWITCH)) buttons[BtnId.SWITCH] = ButtonCfg(false, 0.58f, 0.20f, 44, 0.6f, "Вид", Act.PROFILE)
        guiVisible = o.optBoolean("guiVisible", guiVisible)
        joyFloating = o.optBoolean("joyFloating", joyFloating)
        zoneLeft = o.optDouble("zoneLeft", zoneLeft.toDouble()).toFloat().coerceIn(0f, 0.9f)
        zoneTop = o.optDouble("zoneTop", zoneTop.toDouble()).toFloat().coerceIn(0f, 0.9f)
        zoneRight = o.optDouble("zoneRight", zoneRight.toDouble()).toFloat().coerceIn(zoneLeft + 0.1f, 1f)
        zoneBottom = o.optDouble("zoneBottom", zoneBottom.toDouble()).toFloat().coerceIn(zoneTop + 0.1f, 1f)
        joyRadiusDp = o.optInt("joyRadiusDp", joyRadiusDp).coerceIn(30, 140)
        joyAlpha = o.optDouble("joyAlpha", joyAlpha.toDouble()).toFloat().coerceIn(0.1f, 1f)
        joyDeadZone = o.optDouble("joyDeadZone", joyDeadZone.toDouble()).toFloat().coerceIn(0.05f, 0.7f)
        lookSensitivity = o.optDouble("lookSensitivity", lookSensitivity.toDouble()).toFloat().coerceIn(0.3f, 4f)
        sneakToggle = o.optBoolean("sneakToggle", sneakToggle)
        sneakGesture = o.optInt("sneakGesture", sneakGesture).coerceIn(0, 2)
        sprintToggle = o.optBoolean("sprintToggle", sprintToggle)
        hotbarMode = o.optInt("hotbarMode", hotbarMode).coerceIn(0, 2)
        hotbarTap = o.optBoolean("hotbarTap", hotbarTap)
        guiSwipeScroll = o.optBoolean("guiSwipeScroll", guiSwipeScroll)
        o.optJSONObject("eye")?.let { readIcon(it, eye) }
        o.optJSONObject("gear")?.let { readIcon(it, gear) }
    }

    private fun iconJson(c: ButtonCfg): JSONObject =
        JSONObject().put("cx", c.cx.toDouble()).put("cy", c.cy.toDouble()).put("size", c.sizeDp).put("alpha", c.alpha.toDouble())

    private fun readIcon(j: JSONObject, c: ButtonCfg) {
        c.cx = j.optDouble("cx", c.cx.toDouble()).toFloat().coerceIn(0f, 1f)
        c.cy = j.optDouble("cy", c.cy.toDouble()).toFloat().coerceIn(0f, 1f)
        c.sizeDp = j.optInt("size", c.sizeDp).coerceIn(28, 100)
        c.alpha = j.optDouble("alpha", c.alpha.toDouble()).toFloat().coerceIn(0.1f, 1f)
    }

    fun save() {
        try {
            val target = file(profile)
            val part = File(target.parentFile, target.name + ".part")
            part.writeText(toJson().toString())
            if (!part.renameTo(target)) {
                target.writeText(toJson().toString())
                part.delete()
            }
        } catch (_: Exception) {
        }
    }

    /** Reads layout [number] into this object, replacing the current one. */
    fun loadProfile(number: Int) {
        profile = number.coerceIn(0, PROFILES - 1)
        resetDefaults()
        try {
            val f = file(profile)
            if (f.isFile) fromJson(JSONObject(f.readText()))
        } catch (_: Exception) {
        }
    }

    companion object {
        const val PROFILES = 3

        /** Layout 1 keeps the old file name, so an existing layout stays as it was. */
        fun file(profile: Int) = File(AppPaths.root, if (profile <= 0) "controls.json" else "controls${profile + 1}.json")

        private fun activeFile() = File(AppPaths.root, "controls-active.txt")

        fun activeProfile(): Int = try {
            activeFile().readText().trim().toInt().coerceIn(0, PROFILES - 1)
        } catch (_: Exception) {
            0
        }

        fun setActive(profile: Int) {
            try {
                activeFile().writeText(profile.coerceIn(0, PROFILES - 1).toString())
            } catch (_: Exception) {
            }
        }

        fun load(profile: Int = activeProfile()): ControlsConfig {
            val cfg = ControlsConfig()
            cfg.loadProfile(profile)
            ControlStyle.load()
            return cfg
        }

        /** Is the "switch layout" button on in the active layout? */
        fun switchEnabled(): Boolean = load().buttons[BtnId.SWITCH]?.enabled == true

        /** Turns the "switch layout" button on or off in all layouts at once. */
        fun setSwitchEnabled(on: Boolean) {
            for (p in 0 until PROFILES) {
                val cfg = ControlsConfig()
                cfg.loadProfile(p)
                cfg.buttons[BtnId.SWITCH]?.enabled = on
                cfg.save()
            }
        }
    }
}

/**
 * Look of the on-screen buttons, shared by all layouts: shape, and the colours of a button at rest
 * and while it is pressed. With [useTheme] the colours follow the launcher's colour theme.
 */
object ControlStyle {
    /** 0 circle, 1 rounded square, 2 hexagon. */
    @Volatile var shape = 0
    @Volatile var useTheme = true
    @Volatile var idle = 0xFF14233D.toInt()
    @Volatile var pressed = 0xFF4E8CFF.toInt()
    @Volatile var stroke = 0xFFFFFFFF.toInt()
    @Volatile var text = 0xFFFFFFFF.toInt()
    @Volatile var strokeDp = 1.5f

    private fun file() = File(AppPaths.root, "controls-style.json")

    fun load() {
        try {
            val f = file()
            if (!f.isFile) return
            val o = JSONObject(f.readText())
            shape = o.optInt("shape", 0).coerceIn(0, 2)
            useTheme = o.optBoolean("theme", true)
            idle = o.optInt("idle", idle)
            pressed = o.optInt("pressed", pressed)
            stroke = o.optInt("stroke", stroke)
            text = o.optInt("text", text)
            strokeDp = o.optDouble("strokeDp", 1.5).toFloat().coerceIn(0f, 5f)
        } catch (_: Exception) {
        }
    }

    fun save() {
        try {
            val o = JSONObject().put("shape", shape).put("theme", useTheme).put("idle", idle).put("pressed", pressed)
                .put("stroke", stroke).put("text", text).put("strokeDp", strokeDp.toDouble())
            val part = File(file().parentFile, "controls-style.json.part")
            part.writeText(o.toString())
            if (!part.renameTo(file())) {
                file().writeText(o.toString())
                part.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun reset() {
        shape = 0
        useTheme = true
        idle = 0xFF14233D.toInt()
        pressed = 0xFF4E8CFF.toInt()
        stroke = 0xFFFFFFFF.toInt()
        text = 0xFFFFFFFF.toInt()
        strokeDp = 1.5f
    }
}
