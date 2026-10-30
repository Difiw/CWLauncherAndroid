package ru.cw.launcher.ui.controls

import git.artdeell.dnbootstrap.glfw.GLFW

/** Sink for the game actions produced by the on-screen controls. */
interface GameInput {
    fun key(code: Int, down: Boolean)
    fun mouseButton(button: Int, down: Boolean)
    fun cursor(x: Double, y: Double)
    fun scroll(dy: Double)
    fun text(s: String)
    fun grabbing(): Boolean

    fun tapKey(code: Int) {
        key(code, true)
        key(code, false)
    }
}

object Glfw {
    const val KEY_SPACE = 32
    const val KEY_1 = 49
    const val KEY_E = 69
    const val KEY_T = 84
    const val KEY_W = 87
    const val KEY_A = 65
    const val KEY_S = 83
    const val KEY_D = 68
    const val KEY_ESCAPE = 256
    const val KEY_ENTER = 257
    const val KEY_BACKSPACE = 259
    const val KEY_LEFT_SHIFT = 340
    const val KEY_LEFT_CONTROL = 341
    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1
}

/** Real input: events go to Minecraft through the Android GLFW backend. */
object GlfwInput : GameInput {
    override fun key(code: Int, down: Boolean) = GLFW.sendKeyEvent(code, if (down) 1 else 0, 0)
    override fun mouseButton(button: Int, down: Boolean) = GLFW.sendMouseEvent(button, if (down) 1 else 0, 0)
    override fun cursor(x: Double, y: Double) = GLFW.sendMousePosition0(x, y)
    override fun scroll(dy: Double) = GLFW.sendScrollEvent(0.0, dy)
    override fun text(s: String) {
        if (s.isNotEmpty()) GLFW.sendBulkUnicodeEvent(s, 0)
    }

    override fun grabbing(): Boolean = GLFW.isGrabbing()
}

/** Used by the layout editor outside the game: nothing is sent anywhere. */
object NullInput : GameInput {
    override fun key(code: Int, down: Boolean) {}
    override fun mouseButton(button: Int, down: Boolean) {}
    override fun cursor(x: Double, y: Double) {}
    override fun scroll(dy: Double) {}
    override fun text(s: String) {}
    override fun grabbing(): Boolean = false
}
