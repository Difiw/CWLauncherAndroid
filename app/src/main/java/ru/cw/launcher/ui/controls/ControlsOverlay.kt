package ru.cw.launcher.ui.controls

import android.content.Context
import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.compose.ui.graphics.toArgb
import ru.cw.launcher.R
import ru.cw.launcher.ui.Backgrounds
import ru.cw.launcher.ui.Tc
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * On-screen controls: free joystick, configurable action buttons, look area, hotbar controls,
 * eye/gear, virtual keyboard, settings panel and the layout editor. Touch handling lives here;
 * the resulting actions go to [GameInput].
 */
class ControlsOverlay(
    context: Context,
    private val input: GameInput,
    val cfg: ControlsConfig,
    private val host: Host
) : FrameLayout(context) {

    interface Host {
        fun onChat()
        fun onEditFinished()
        fun onToLauncher()
    }

    /** GUI scale from options.txt (0 = automatic); used to find hotbar slots on screen. */
    var guiScaleProvider: () -> Int = { 0 }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()
    private fun dpf(v: Float): Float = v * density

    var editing = false
        private set
    /** While Minecraft is still starting only the gear is shown; everything else waits for the game. */
    var loading: Boolean = false
        set(value) {
            field = value
            refresh()
        }
    var shiftY = 0f
    var safeLeft = 0
    var safeTop = 0

    private val held = HashSet<Int>()
    private val heldMouse = HashSet<Int>()
    private var sneakLatched = false
    private var sprintOn = false
    private var selected: String? = null
    private var snapshot: String? = null
    private var lookX = 0f
    private var lookY = 0f

    private val layer = TouchLayer(context)
    private val buttons = LinkedHashMap<String, ControlButton>()
    private val eye = IconView(context, true)
    private val gear = IconView(context, false)
    private var panel: View? = null
    private var editBar: View? = null
    private var keyboard: VirtualKeyboard? = null

    init {
        addView(layer)
        eye.setOnClickListener {
            cfg.guiVisible = !cfg.guiVisible
            cfg.save()
            releaseAll()
            refresh()
        }
        gear.setOnClickListener { if (!editing) showSettings() }
        addView(eye)
        addView(gear)
        refresh()
    }

    // ---------------------------------------------------------------- state helpers

    private fun setKey(code: Int, down: Boolean) {
        if (down) {
            if (!held.add(code)) return
        } else if (!held.remove(code)) return
        input.key(code, down)
    }

    private fun setMouse(button: Int, down: Boolean) {
        if (down) {
            if (!heldMouse.add(button)) return
        } else if (!heldMouse.remove(button)) return
        input.mouseButton(button, down)
    }

    /** Stops every held key and mouse button and resets latches. Safe to call at any time. */
    fun releaseAll() {
        layer.cancelAll()
        for (k in held.toList()) setKey(k, false)
        for (m in heldMouse.toList()) setMouse(m, false)
        sneakLatched = false
        sprintOn = false
        for (b in buttons.values) {
            b.down = false
            b.stickAccum = 0f
            b.invalidate()
        }
    }

    /** Back closes the settings panel or cancels the layout editor before anything else. */
    fun consumeBack(): Boolean {
        if (editing) {
            finishEdit(false)
            return true
        }
        if (panel != null) {
            removePanel()
            return true
        }
        val kb = keyboard
        if (kb != null && kb.visibility == View.VISIBLE) {
            kb.visibility = View.GONE
            return true
        }
        return false
    }

    /** The game grabbed or released the cursor (menu opened or closed). */
    fun onGrabChanged() {
        post { layer.cancelAll() }
    }

    /** GUI scale used by the game for the current screen size. */
    /** Game window pixels per overlay pixel (below 1 when the game renders at reduced resolution). */
    var mcWidth = 0

    private fun mcFactor(): Float = if (mcWidth > 0 && width > 0) mcWidth.toFloat() / width else 1f

    private fun guiScaleValue(): Float {
        val w = width * mcFactor()
        val h = height * mcFactor()
        val auto = maxOf(1, minOf((w / 320f).toInt(), (h / 240f).toInt()))
        val option = guiScaleProvider()
        return (if (option in 1..auto) option else auto).toFloat()
    }

    /**
     * Clicks the "Save and Quit" / "Disconnect" button of the vanilla pause menu, which sits at
     * a fixed place relative to the screen centre. Best effort: modded menus may differ.
     */
    fun clickPauseQuit() {
        if (width <= 0 || height <= 0) return
        val s = guiScaleValue()
        val y = ((height * mcFactor() / s) / 4f + 120f - 16f + 10f) * s
        input.cursor(width * mcFactor() / 2.0, y.toDouble())
        postDelayed({
            input.mouseButton(Glfw.MOUSE_LEFT, true)
            input.mouseButton(Glfw.MOUSE_LEFT, false)
        }, 150)
    }

    /** Camera movement from any pointer that is currently holding an on-screen control. */
    private fun lookBy(dx: Float, dy: Float) {
        if (!input.grabbing()) return
        lookX += dx * cfg.lookSensitivity
        lookY += dy * cfg.lookSensitivity
        input.cursor(lookX.toDouble(), lookY.toDouble())
    }

    private fun syncButtons() {
        for (id in buttons.keys.toList()) {
            if (!cfg.buttons.containsKey(id)) {
                buttons.remove(id)?.let { removeView(it) }
            }
        }
        for (id in cfg.buttons.keys) {
            if (!buttons.containsKey(id)) {
                val b = ControlButton(context, id)
                buttons[id] = b
                addView(b, indexOfChild(eye))
            }
        }
    }

    private fun lighten(color: Int): Int {
        val r = Color.red(color) + (255 - Color.red(color)) * 45 / 100
        val g = Color.green(color) + (255 - Color.green(color)) * 45 / 100
        val b = Color.blue(color) + (255 - Color.blue(color)) * 45 / 100
        return Color.rgb(r, g, b)
    }

    private fun visibleByMode(id: String, c: ButtonCfg): Boolean {
        val stick = c.action == Act.STICK_H || c.action == Act.STICK_V || c.action == Act.BAR
        val arrow = id == BtnId.PREV || id == BtnId.NEXT
        return when {
            stick -> cfg.hotbarMode == 1 || cfg.hotbarMode == 2
            arrow -> cfg.hotbarMode == 0 || cfg.hotbarMode == 2
            else -> true
        }
    }

    fun refresh() {
        syncButtons()
        val waiting = loading && !editing
        for ((id, b) in buttons) {
            val c = cfg.buttons[id] ?: continue
            b.visibility = if (editing || (!waiting && cfg.guiVisible && c.enabled && visibleByMode(id, c))) View.VISIBLE else View.GONE
            b.alpha = if (editing && !c.enabled) c.alpha * 0.35f else c.alpha
            b.invalidate()
        }
        eye.crossed = !cfg.guiVisible
        eye.alpha = cfg.eye.alpha
        gear.alpha = if (editing && !cfg.guiVisible) cfg.gear.alpha * 0.5f else cfg.gear.alpha
        // Hiding the controls also hides the gear; only the eye stays (the editor shows both).
        gear.visibility = if (editing || waiting || cfg.guiVisible) View.VISIBLE else View.GONE
        eye.visibility = if (waiting) View.GONE else View.VISIBLE
        layer.visibility = if (waiting) View.GONE else View.VISIBLE
        eye.invalidate()
        gear.invalidate()
        layer.invalidate()
        requestLayout()
    }

    /** Switches to the next of the three control layouts; the choice is remembered. */
    private fun switchProfile() {
        if (editing) return
        cfg.save()
        val next = (cfg.profile + 1) % ControlsConfig.PROFILES
        cfg.loadProfile(next)
        ControlsConfig.setActive(next)
        releaseAll()
        refresh()
        android.widget.Toast.makeText(context, "Вид управления ${next + 1}", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun toggleKeyboard() {
        var kb = keyboard
        if (kb == null) {
            kb = VirtualKeyboard(context, input) { keyboard?.visibility = View.GONE }
            keyboard = kb
            kb.visibility = View.GONE
            addView(kb, indexOfChild(eye))
        }
        kb.toggle()
        requestLayout()
    }

    // ---------------------------------------------------------------- layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val cw: Int
            val ch: Int
            when {
                c is ControlButton -> {
                    val bc = cfg.buttons[c.btnId]
                    cw = dp(bc?.sizeDp ?: 56)
                    ch = if (bc != null && bc.heightDp > 0) dp(bc.heightDp) else cw
                }
                c is IconView -> {
                    cw = dp((if (c === eye) cfg.eye else cfg.gear).sizeDp)
                    ch = cw
                }
                c is VirtualKeyboard -> {
                    cw = w
                    ch = (h * 0.5f).toInt()
                }
                else -> {
                    cw = w
                    ch = h
                }
            }
            c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            when {
                c is ControlButton -> {
                    val cc = cfg.buttons[c.btnId] ?: continue
                    val sw = c.measuredWidth
                    val sh = c.measuredHeight
                    // Any position up to the very corner of the screen is allowed.
                    val x = (cc.cx * w - sw / 2f).toInt().coerceIn(0, (w - sw).coerceAtLeast(0))
                    val y = (cc.cy * h - sh / 2f).toInt().coerceIn(0, (h - sh).coerceAtLeast(0))
                    c.layout(x, y, x + sw, y + sh)
                }
                c === eye || c === gear -> {
                    val ic = if (c === eye) cfg.eye else cfg.gear
                    val s = c.measuredWidth
                    val x = (ic.cx * w - s / 2f).toInt().coerceIn(safeLeft, (w - s).coerceAtLeast(safeLeft))
                    val y = (ic.cy * h - s / 2f).toInt().coerceIn(safeTop, (h - s).coerceAtLeast(safeTop))
                    c.layout(x, y, x + s, y + s)
                }
                c is VirtualKeyboard -> c.layout(0, h - c.measuredHeight, w, h)
                else -> c.layout(0, 0, w, h)
            }
        }
    }

    // ---------------------------------------------------------------- button actions

    private fun kindOf(action: String): Int = when {
        action == Act.SNEAK -> K_SNEAK
        action == Act.SPRINT -> K_SPRINT
        action == Act.CHAT -> K_CHAT
        action == Act.KEYBOARD -> K_KEYBOARD
        action == Act.PROFILE -> K_PROFILE
        action == Act.STICK_H || action == Act.STICK_V -> K_STICK
        action == Act.BAR -> K_BAR
        action.startsWith("keys:") -> K_KEYS
        action.startsWith("mouse:") -> K_MOUSE
        action.startsWith("scroll:") -> K_SCROLL
        else -> K_NONE
    }

    private fun pressButton(b: ControlButton, e: MotionEvent) {
        val action = cfg.buttons[b.btnId]?.action ?: return
        b.down = true
        b.lastRawX = e.rawX
        b.lastRawY = e.rawY
        b.startRawX = e.rawX
        when (kindOf(action)) {
            K_KEYS -> for (code in Act.parseKeys(action)) setKey(code, true)
            K_MOUSE -> setMouse(action.removePrefix("mouse:").toIntOrNull() ?: 0, true)
            K_SCROLL -> input.scroll((action.removePrefix("scroll:").toDoubleOrNull() ?: 1.0))
            K_SNEAK -> {
                sneakIgnore = false
                if (sneakLatched) {
                    sneakLatched = false
                    setKey(Glfw.KEY_LEFT_SHIFT, false)
                    sneakIgnore = true
                } else if (cfg.sneakToggle) {
                    sneakLatched = true
                    setKey(Glfw.KEY_LEFT_SHIFT, true)
                    sneakIgnore = true
                } else {
                    setKey(Glfw.KEY_LEFT_SHIFT, true)
                }
            }
            K_SPRINT -> {
                if (cfg.sprintToggle) {
                    sprintOn = !sprintOn
                    setKey(Glfw.KEY_LEFT_CONTROL, sprintOn)
                } else {
                    setKey(Glfw.KEY_LEFT_CONTROL, true)
                }
            }
            K_STICK -> b.stickAccum = 0f
            K_BAR -> barTouch(b, e, true)
        }
        b.invalidate()
    }

    private var sneakIgnore = false

    /** Maps the finger position on the bar to one of nine slots and steps the hotbar to it. */
    private fun barTouch(b: ControlButton, e: MotionEvent, first: Boolean) {
        val horizontal = b.width >= b.height
        val pad = dpf(3f)
        val len = (if (horizontal) b.width.toFloat() else b.height.toFloat()) - 2 * pad
        if (len <= 0f) return
        val pos = ((if (horizontal) e.x else e.y) - pad) / len
        val p = pos.coerceIn(0f, 1f)
        val index = (p * 9f).toInt().coerceAtMost(8)
        if (b.barIndex < 0 || (first && b.barIndex < 0)) {
            b.barIndex = index
        } else {
            val delta = index - b.barIndex
            repeat(kotlin.math.abs(delta)) { input.scroll(if (delta > 0) -1.0 else 1.0) }
            b.barIndex = index
        }
        b.barPos = p
        b.invalidate()
    }

    private fun moveButton(b: ControlButton, e: MotionEvent) {
        val action = cfg.buttons[b.btnId]?.action ?: return
        val kind = kindOf(action)
        val dx = e.rawX - b.lastRawX
        val dy = e.rawY - b.lastRawY
        b.lastRawX = e.rawX
        b.lastRawY = e.rawY
        if (kind == K_BAR) {
            barTouch(b, e, false)
            return
        }
        if (kind == K_STICK) {
            val horizontal = action == Act.STICK_H
            b.stickAccum += if (horizontal) dx else dy
            val step = dpf(34f)
            while (b.stickAccum >= step) {
                input.scroll(-1.0)
                b.stickAccum -= step
            }
            while (b.stickAccum <= -step) {
                input.scroll(1.0)
                b.stickAccum += step
            }
            return
        }
        if (kind == K_SNEAK && !sneakIgnore && !sneakLatched && !cfg.sneakToggle && cfg.sneakGesture != 0) {
            val total = e.rawX - b.startRawX
            val threshold = dpf(44f)
            if ((cfg.sneakGesture == 1 && total < -threshold) || (cfg.sneakGesture == 2 && total > threshold)) {
                sneakLatched = true
                b.invalidate()
            }
            // The slide is a gesture here, so it must not also turn the camera.
            return
        }
        // Any held control keeps working while the same finger turns the camera.
        lookBy(dx, dy)
    }

    private fun releaseButton(b: ControlButton, fire: Boolean) {
        val action = cfg.buttons[b.btnId]?.action
        b.down = false
        if (action != null) {
            when (kindOf(action)) {
                K_KEYS -> for (code in Act.parseKeys(action).reversed()) setKey(code, false)
                K_MOUSE -> setMouse(action.removePrefix("mouse:").toIntOrNull() ?: 0, false)
                K_SNEAK -> if (!sneakLatched && !cfg.sneakToggle) setKey(Glfw.KEY_LEFT_SHIFT, false)
                K_SPRINT -> if (!cfg.sprintToggle) setKey(Glfw.KEY_LEFT_CONTROL, false)
                K_CHAT -> if (fire) host.onChat()
                K_KEYBOARD -> if (fire) toggleKeyboard()
                K_PROFILE -> if (fire) switchProfile()
            }
        }
        b.invalidate()
    }

    private fun buttonActive(b: ControlButton): Boolean {
        val action = cfg.buttons[b.btnId]?.action ?: return false
        return when (kindOf(action)) {
            K_SNEAK -> sneakLatched || b.down
            K_SPRINT -> sprintOn || b.down
            else -> b.down
        }
    }

    // ---------------------------------------------------------------- views

    private inner class ControlButton(context: Context, val btnId: String) : View(context) {
        var down = false
        var lastRawX = 0f
        var lastRawY = 0f
        var startRawX = 0f
        var stickAccum = 0f
        var barPos = 0f
        var barIndex = -1
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val rectF = RectF()
        private var dragDx = 0f
        private var dragDy = 0f

        override fun onDraw(canvas: Canvas) {
            val own = cfg.buttons[btnId]
            // A hidden button is not drawn in the game, but the view still takes touches.
            if (own?.hidden == true && !editing) return
            if (own?.action == Act.BAR) {
                drawBar(canvas)
                return
            }
            val s = minOf(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            val radius = s / 2f - dpf(2f)
            val st = ControlStyle
            val custom = own?.color ?: 0
            val idle = if (custom != 0) custom else if (st.useTheme) Tc(0xFF14233D).toArgb() else st.idle
            val pressed = if (custom != 0) lighten(custom) else if (st.useTheme) Tc(0xFF4E8CFF).toArgb() else st.pressed
            fill.color = if (buttonActive(this)) pressed else idle
            drawShape(canvas, cx, cy, radius, fill)
            val picked = editing && selected == btnId
            stroke.strokeWidth = dpf(if (picked) 3f else st.strokeDp)
            stroke.color = if (picked) 0xFFFFD54F.toInt() else if (st.useTheme) Tc(0xFF9CC0FF).toArgb() else st.stroke
            if (picked || st.strokeDp > 0f) drawShape(canvas, cx, cy, radius, stroke)
            text.color = if (st.useTheme) Color.WHITE else st.text
            val label = cfg.buttons[btnId]?.label.orEmpty()
            text.textSize = s * (if (label.length > 5) 0.2f else 0.26f)
            canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2f, text)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (editing) return editTouch(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressButton(this, e)
                MotionEvent.ACTION_MOVE -> moveButton(this, e)
                MotionEvent.ACTION_UP -> releaseButton(this, true)
                MotionEvent.ACTION_CANCEL -> releaseButton(this, false)
            }
            return true
        }

        private val shapePath = android.graphics.Path()

        private fun drawShape(canvas: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
            when (ControlStyle.shape) {
                1 -> {
                    rectF.set(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawRoundRect(rectF, r * 0.3f, r * 0.3f, paint)
                }
                2 -> {
                    shapePath.reset()
                    for (i in 0 until 6) {
                        val a = Math.toRadians((60.0 * i) - 90.0)
                        val x = cx + (r * Math.cos(a)).toFloat()
                        val y = cy + (r * Math.sin(a)).toFloat()
                        if (i == 0) shapePath.moveTo(x, y) else shapePath.lineTo(x, y)
                    }
                    shapePath.close()
                    canvas.drawPath(shapePath, paint)
                }
                else -> canvas.drawCircle(cx, cy, r, paint)
            }
        }

        /** A scroll-bar style track with a thumb for stepping through the hotbar slots. */
        private fun drawBar(canvas: Canvas) {
            val horizontal = width >= height
            val pad = dpf(3f)
            val custom = cfg.buttons[btnId]?.color ?: 0
            fill.color = if (custom != 0) custom else if (ControlStyle.useTheme) Tc(0xFF14233D).toArgb() else ControlStyle.idle
            rectF.set(pad, pad, width - pad, height - pad)
            val r = minOf(width, height) / 2f - pad
            canvas.drawRoundRect(rectF, r, r, fill)
            stroke.strokeWidth = dpf(if (editing && selected == btnId) 3f else 1.5f)
            stroke.color = if (editing && selected == btnId) 0xFFFFD54F.toInt() else Color.WHITE
            canvas.drawRoundRect(rectF, r, r, stroke)
            val along = if (horizontal) width - 2 * pad else height - 2 * pad
            val thick = minOf(width, height) - 2 * pad
            val thumbLen = maxOf(thick, along / 9f)
            val travel = (along - thumbLen).coerceAtLeast(0f)
            val start = pad + travel * barPos
            fill.color = if (custom != 0) lighten(custom) else if (ControlStyle.useTheme) (if (down) Tc(0xFF4E8CFF).toArgb() else Tc(0xFF6F9BE0).toArgb()) else ControlStyle.pressed
            if (horizontal) rectF.set(start, pad, start + thumbLen, height - pad) else rectF.set(pad, start, width - pad, start + thumbLen)
            canvas.drawRoundRect(rectF, r, r, fill)
        }

        private fun editTouch(e: MotionEvent): Boolean {
            val c = cfg.buttons[btnId] ?: return true
            val host = this@ControlsOverlay
            val loc = IntArray(2)
            host.getLocationOnScreen(loc)
            val px = e.rawX - loc[0]
            val py = e.rawY - loc[1]
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragDx = c.cx * host.width - px
                    dragDy = c.cy * host.height - py
                    if (selected != btnId) {
                        selected = btnId
                        rebuildEditBar()
                        for (v in buttons.values) v.invalidate()
                        layer.invalidate()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (host.width > 0 && host.height > 0) {
                        c.cx = ((px + dragDx) / host.width).coerceIn(0f, 1f)
                        c.cy = ((py + dragDy) / host.height).coerceIn(0f, 1f)
                        host.requestLayout()
                    }
                }
            }
            return true
        }
    }

    private inner class IconView(context: Context, private val isEye: Boolean) : View(context) {
        var crossed = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
        }
        private val f = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        private var dragDx = 0f
        private var dragDy = 0f

        init {
            isClickable = true
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!editing) return super.onTouchEvent(e)
            val which = if (isEye) BtnId.EYE else BtnId.GEAR
            val c = if (isEye) cfg.eye else cfg.gear
            val host = this@ControlsOverlay
            val loc = IntArray(2)
            host.getLocationOnScreen(loc)
            val px = e.rawX - loc[0]
            val py = e.rawY - loc[1]
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragDx = c.cx * host.width - px
                    dragDy = c.cy * host.height - py
                    if (selected != which) {
                        selected = which
                        rebuildEditBar()
                        for (v in buttons.values) v.invalidate()
                        eye.invalidate()
                        gear.invalidate()
                        layer.invalidate()
                    }
                }
                MotionEvent.ACTION_MOVE -> if (host.width > 0 && host.height > 0) {
                    c.cx = ((px + dragDx) / host.width).coerceIn(0f, 1f)
                    c.cy = ((py + dragDy) / host.height).coerceIn(0f, 1f)
                    host.requestLayout()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            f.color = 0x99000000.toInt()
            canvas.drawCircle(w / 2f, h / 2f, w / 2f - dpf(1f), f)
            if (editing && selected == (if (isEye) BtnId.EYE else BtnId.GEAR)) {
                p.strokeWidth = dpf(3f)
                p.color = 0xFFFFD54F.toInt()
                canvas.drawCircle(w / 2f, h / 2f, w / 2f - dpf(2f), p)
                p.color = Color.WHITE
            }
            p.strokeWidth = dpf(2f)
            val cx = w / 2f
            val cy = h / 2f
            if (isEye) {
                val rx = w * 0.30f
                val ry = h * 0.17f
                rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
                canvas.drawOval(rect, p)
                f.color = Color.WHITE
                canvas.drawCircle(cx, cy, h * 0.08f, f)
                if (crossed) {
                    p.strokeWidth = dpf(2.5f)
                    canvas.drawLine(cx - rx, cy + ry + dpf(3f), cx + rx, cy - ry - dpf(3f), p)
                }
            } else {
                val r = w * 0.15f
                canvas.drawCircle(cx, cy, r, p)
                for (i in 0 until 8) {
                    val a = Math.PI * 2 * i / 8
                    val sx = cx + Math.cos(a).toFloat() * (r + dpf(2f))
                    val sy = cy + Math.sin(a).toFloat() * (r + dpf(2f))
                    val ex = cx + Math.cos(a).toFloat() * (r + dpf(6f))
                    val ey = cy + Math.sin(a).toFloat() * (r + dpf(6f))
                    canvas.drawLine(sx, sy, ex, ey, p)
                }
            }
        }
    }

    /** Full-screen layer behind the buttons: joystick, camera look, menu taps and swipes. */
    private inner class TouchLayer(context: Context) : View(context) {
        private var joyId = -1
        private var joyOx = 0f
        private var joyOy = 0f
        private var joyX = 0f
        private var joyY = 0f

        private var lookId = -1
        private var lastX = 0f
        private var lastY = 0f
        private var startX = 0f
        private var startY = 0f
        private var moved = false
        private var holding = false
        private var guiMode = false
        private var guiPressed = false
        private var guiScrolling = false
        private var scrollAccum = 0f

        private var dragging = false
        private var dragX = 0f
        private var dragY = 0f

        private val base = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()

        private val hold = Runnable {
            if (lookId != -1 && !moved && !guiMode) {
                holding = true
                setMouse(Glfw.MOUSE_LEFT, true)
            }
        }

        // In a menu a finger held still for a moment starts a real press, so item dragging works;
        // a quick tap is delivered as one click when the finger lifts.
        private val guiPress = Runnable {
            if (lookId != -1 && guiMode && !guiScrolling && !guiPressed) {
                guiPressed = true
                setMouse(Glfw.MOUSE_LEFT, true)
            }
        }

        private fun zoneLeftPx() = cfg.zoneLeft * width
        private fun zoneTopPx() = cfg.zoneTop * height
        private fun zoneRightPx() = cfg.zoneRight * width
        private fun zoneBottomPx() = cfg.zoneBottom * height
        private fun inZone(x: Float, y: Float) =
            x >= zoneLeftPx() && x <= zoneRightPx() && y >= zoneTopPx() && y <= zoneBottomPx()

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (editing) return editTouch(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex
                    start(e.getPointerId(i), e.getX(i), e.getY(i))
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val i = e.actionIndex
                    end(e.getPointerId(i), true, e.getX(i), e.getY(i))
                }
                MotionEvent.ACTION_CANCEL -> cancelAll()
            }
            return true
        }

        private fun start(id: Int, x: Float, y: Float) {
            val grabbed = input.grabbing()
            if (grabbed && cfg.guiVisible && joyId == -1 && inZone(x, y)) {
                joyId = id
                if (cfg.joyFloating) {
                    joyOx = x
                    joyOy = y
                } else {
                    joyOx = (zoneLeftPx() + zoneRightPx()) / 2f
                    joyOy = (zoneTopPx() + zoneBottomPx()) / 2f
                }
                joyX = x
                joyY = y
                updateJoy()
                invalidate()
                return
            }
            if (lookId != -1) return
            lookId = id
            lastX = x
            lastY = y
            startX = x
            startY = y
            moved = false
            holding = false
            guiMode = !grabbed
            guiPressed = false
            guiScrolling = false
            scrollAccum = 0f
            if (guiMode) {
                input.cursor(x * mcFactor().toDouble(), (y + shiftY) * mcFactor().toDouble())
                postDelayed(guiPress, 180)
            } else {
                postDelayed(hold, 300)
            }
        }

        private fun move(id: Int, x: Float, y: Float) {
            if (id == joyId) {
                joyX = x
                joyY = y
                updateJoy()
                invalidate()
            } else if (id == lookId) {
                if (guiMode) {
                    moveInMenu(x, y)
                    return
                }
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                if (!moved && hypot(x - startX, y - startY) > dpf(8f)) {
                    moved = true
                    removeCallbacks(hold)
                }
                if (moved || holding) lookBy(dx, dy)
            }
        }

        private fun moveInMenu(x: Float, y: Float) {
            val travelled = hypot(x - startX, y - startY)
            if (guiPressed) {
                // A real press is held: the finger drags (items, sliders).
                input.cursor(x * mcFactor().toDouble(), (y + shiftY) * mcFactor().toDouble())
                return
            }
            if (!guiScrolling && travelled > dpf(12f)) {
                removeCallbacks(guiPress)
                if (cfg.guiSwipeScroll) {
                    guiScrolling = true
                    lastY = y
                } else {
                    guiPressed = true
                    input.cursor(startX * mcFactor().toDouble(), (startY + shiftY) * mcFactor().toDouble())
                    setMouse(Glfw.MOUSE_LEFT, true)
                    input.cursor(x * mcFactor().toDouble(), (y + shiftY) * mcFactor().toDouble())
                }
                return
            }
            if (guiScrolling) {
                scrollAccum += y - lastY
                lastY = y
                val step = dpf(30f)
                while (scrollAccum >= step) {
                    input.scroll(1.0)
                    scrollAccum -= step
                }
                while (scrollAccum <= -step) {
                    input.scroll(-1.0)
                    scrollAccum += step
                }
            }
        }

        private fun end(id: Int, ok: Boolean, x: Float = 0f, y: Float = 0f) {
            if (id == joyId) {
                releaseJoy()
                invalidate()
            } else if (id == lookId) {
                removeCallbacks(hold)
                removeCallbacks(guiPress)
                if (guiMode) {
                    if (guiPressed) {
                        setMouse(Glfw.MOUSE_LEFT, false)
                    } else if (!guiScrolling && ok) {
                        // One tap = one ordinary click at the touched point.
                        input.cursor(startX * mcFactor().toDouble(), (startY + shiftY) * mcFactor().toDouble())
                        setMouse(Glfw.MOUSE_LEFT, true)
                        setMouse(Glfw.MOUSE_LEFT, false)
                    }
                } else if (holding) {
                    setMouse(Glfw.MOUSE_LEFT, false)
                } else if (!moved && ok) {
                    val slot = if (cfg.hotbarTap) hotbarSlotAt(startX, startY) else -1
                    if (slot >= 0) {
                        input.tapKey(Glfw.KEY_1 + slot)
                    } else {
                        setMouse(Glfw.MOUSE_LEFT, true)
                        setMouse(Glfw.MOUSE_LEFT, false)
                    }
                }
                lookId = -1
                holding = false
                guiPressed = false
                guiScrolling = false
            }
        }

        fun cancelAll() {
            removeCallbacks(hold)
            removeCallbacks(guiPress)
            if (lookId != -1) end(lookId, false)
            if (joyId != -1) releaseJoy()
            dragging = false
            invalidate()
        }

        private fun releaseJoy() {
            joyId = -1
            setKey(Glfw.KEY_W, false)
            setKey(Glfw.KEY_A, false)
            setKey(Glfw.KEY_S, false)
            setKey(Glfw.KEY_D, false)
        }

        private fun updateJoy() {
            val r = dpf(cfg.joyRadiusDp.toFloat())
            val dx = joyX - joyOx
            val dy = joyY - joyOy
            val dist = hypot(dx, dy)
            var w = false
            var a = false
            var s = false
            var d = false
            if (dist > 0f && dist >= cfg.joyDeadZone * r) {
                val nx = dx / dist
                val ny = dy / dist
                w = ny < -0.38f
                s = ny > 0.38f
                a = nx < -0.38f
                d = nx > 0.38f
            }
            setKey(Glfw.KEY_W, w)
            setKey(Glfw.KEY_S, s)
            setKey(Glfw.KEY_A, a)
            setKey(Glfw.KEY_D, d)
        }

        // ---- hotbar tap: screen point -> slot index (-1 when outside the hotbar)

        private fun hotbarSlotAt(px: Float, py: Float): Int {
            val f = mcFactor()
            val x = px * f
            val y = py * f
            val w = width * f
            val h = height * f
            if (w <= 0f || h <= 0f) return -1
            val auto = maxOf(1, minOf((w / 320f).toInt(), (h / 240f).toInt()))
            val option = guiScaleProvider()
            val scale = (if (option in 1..auto) option else auto).toFloat()
            val left = w / 2f - 91f * scale
            val top = h - 22f * scale
            if (y < top || y > h || x < left || x > left + 182f * scale) return -1
            return (((x - left - scale) / (20f * scale)).toInt()).coerceIn(0, 8)
        }

        // ---- editor: drag the joystick zone

        private fun editTouch(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (inZone(e.x, e.y)) {
                        selected = BtnId.ZONE
                        dragging = true
                        dragX = e.x
                        dragY = e.y
                    } else {
                        selected = null
                    }
                    rebuildEditBar()
                    for (b in buttons.values) b.invalidate()
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (dragging && width > 0 && height > 0) {
                    var dx = (e.x - dragX) / width
                    var dy = (e.y - dragY) / height
                    dragX = e.x
                    dragY = e.y
                    dx = dx.coerceIn(-cfg.zoneLeft, 1f - cfg.zoneRight)
                    dy = dy.coerceIn(-cfg.zoneTop, 1f - cfg.zoneBottom)
                    cfg.zoneLeft += dx
                    cfg.zoneRight += dx
                    cfg.zoneTop += dy
                    cfg.zoneBottom += dy
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            if (editing) {
                rect.set(zoneLeftPx(), zoneTopPx(), zoneRightPx(), zoneBottomPx())
                base.color = if (selected == BtnId.ZONE) 0x443F8CFF else 0x222F6BCC
                canvas.drawRoundRect(rect, dpf(12f), dpf(12f), base)
                line.strokeWidth = dpf(if (selected == BtnId.ZONE) 3f else 1.5f)
                line.color = if (selected == BtnId.ZONE) 0xFFFFD54F.toInt() else 0x99FFFFFF.toInt()
                canvas.drawRoundRect(rect, dpf(12f), dpf(12f), line)
            }
            if (!cfg.guiVisible) return
            val r = dpf(cfg.joyRadiusDp.toFloat())
            val alpha = (cfg.joyAlpha * 255).toInt().coerceIn(20, 255)
            when {
                joyId != -1 -> drawStick(canvas, joyOx, joyOy, joyX - joyOx, joyY - joyOy, r, alpha)
                !cfg.joyFloating && (editing || input.grabbing()) ->
                    drawStick(canvas, (zoneLeftPx() + zoneRightPx()) / 2f, (zoneTopPx() + zoneBottomPx()) / 2f, 0f, 0f, r, alpha / 2)
                editing ->
                    drawStick(canvas, (zoneLeftPx() + zoneRightPx()) / 2f, (zoneTopPx() + zoneBottomPx()) / 2f, 0f, 0f, r, alpha / 2)
            }
        }

        private fun drawStick(canvas: Canvas, ox: Float, oy: Float, dx: Float, dy: Float, r: Float, alpha: Int) {
            base.color = Color.argb(alpha / 3, 255, 255, 255)
            canvas.drawCircle(ox, oy, r, base)
            line.color = Color.argb(alpha, 255, 255, 255)
            line.strokeWidth = dpf(2f)
            canvas.drawCircle(ox, oy, r, line)
            val dist = hypot(dx, dy)
            var kx = dx
            var ky = dy
            if (dist > r) {
                kx = dx / dist * r
                ky = dy / dist * r
            }
            base.color = Color.argb(alpha, 255, 255, 255)
            canvas.drawCircle(ox + kx, oy + ky, r * 0.38f, base)
        }
    }

    // ---------------------------------------------------------------- panel building blocks

    private fun accent(): Int = Tc(0xFF3B82F6).toArgb()

    private fun styled(tv: TextView, size: Float = 14f): TextView {
        tv.setTextColor(Color.WHITE)
        tv.textSize = size
        return tv
    }

    private fun card(): LinearLayout {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Tc(0xF2162C50).toArgb(), Tc(0xF20B1424).toArgb())
        )
        bg.cornerRadius = dpf(18f)
        bg.setStroke(dp(1), (accent() and 0x00FFFFFF) or 0x88000000.toInt())
        card.background = bg
        card.setPadding(dp(16), dp(12), dp(16), dp(12))
        return card
    }

    private fun switchRow(parent: LinearLayout, title: String, value: Boolean, change: (Boolean) -> Unit) {
        val s = Switch(context)
        s.text = title
        s.setTextColor(Color.WHITE)
        s.isChecked = value
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        s.thumbTintList = ColorStateList(states, intArrayOf(accent(), 0xFF9AA7BD.toInt()))
        s.trackTintList = ColorStateList(states, intArrayOf((accent() and 0x00FFFFFF) or 0x66000000, 0x44FFFFFF))
        s.setOnCheckedChangeListener { _, checked -> change(checked) }
        parent.addView(s, rowLp())
    }

    private fun seekRow(parent: LinearLayout, title: String, min: Int, max: Int, value: Int, change: (Int) -> Unit) {
        val label = styled(TextView(context), 13f)
        label.text = "$title: $value"
        val bar = SeekBar(context)
        bar.max = max - min
        bar.progressTintList = ColorStateList.valueOf(accent())
        bar.thumbTintList = ColorStateList.valueOf(accent())
        bar.progress = (value - min).coerceIn(0, max - min)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress + min
                label.text = "$title: $v"
                if (fromUser) change(v)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        parent.addView(label)
        parent.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
    }

    private fun textButton(title: String, click: () -> Unit): Button {
        val b = Button(context)
        b.text = title
        b.setTextColor(Color.WHITE)
        b.isAllCaps = false
        b.textSize = 13f
        val bg = GradientDrawable()
        bg.setColor(Tc(0xFF1F3A66).toArgb())
        bg.cornerRadius = dpf(12f)
        bg.setStroke(dp(1), (accent() and 0x00FFFFFF) or 0x77000000)
        b.background = bg
        b.setOnClickListener { click() }
        return b
    }

    /** Full-width button with a gap above it, so stacked buttons never touch. */
    private fun rowLp(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        p.topMargin = dp(10)
        return p
    }

    private fun halfLp(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, dp(48), 1f)
        p.topMargin = dp(10)
        p.marginStart = dp(4)
        p.marginEnd = dp(4)
        return p
    }

    private fun gestureName(): String = when (cfg.sneakGesture) {
        1 -> "влево"
        2 -> "вправо"
        else -> "выкл"
    }

    private fun hotbarModeName(): String = when (cfg.hotbarMode) {
        1 -> "джойстик"
        2 -> "стрелки и джойстик"
        else -> "стрелки"
    }

    private fun removePanel() {
        panel?.let { removeView(it) }
        panel = null
    }

    // ---------------------------------------------------------------- settings panel

    fun showSettings() {
        removePanel()
        releaseAll()
        val root = FrameLayout(context)
        root.setBackgroundColor(0x99070C16.toInt())
        root.isClickable = true
        // Same world picture as the launcher, darkened and washed with the theme colour.
        val picture = ImageView(context)
        Backgrounds.apply(picture, ru.cw.launcher.engine.CwSettings.load())
        picture.scaleType = ImageView.ScaleType.CENTER_CROP
        picture.alpha = 0.3f
        root.addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val wash = View(context)
        wash.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf((accent() and 0x00FFFFFF) or 0x55000000, 0xCC070C16.toInt())
        )
        root.addView(wash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val card = card()
        val title = styled(TextView(context), 20f)
        title.text = "Настройки управления"
        title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        card.addView(title)
        val underline = View(context)
        underline.setBackgroundColor(accent())
        card.addView(underline, LinearLayout.LayoutParams(dp(56), dp(3)).apply { topMargin = dp(4); bottomMargin = dp(4) })

        for ((id, c) in cfg.buttons.entries.toList()) {
            switchRow(card, "${c.label.ifBlank { id }} — ${Act.describe(c.action)}", c.enabled) {
                c.enabled = it
                cfg.save()
                refresh()
            }
        }
        switchRow(card, "Плавающий джойстик", cfg.joyFloating) {
            cfg.joyFloating = it
            cfg.save()
            refresh()
        }
        switchRow(card, "Приседание: переключатель (иначе удержание)", cfg.sneakToggle) {
            cfg.sneakToggle = it
            cfg.save()
        }
        val gesture = textButton("Жест фиксации приседания: ${gestureName()}") {}
        gesture.setOnClickListener {
            cfg.sneakGesture = (cfg.sneakGesture + 1) % 3
            cfg.save()
            gesture.text = "Жест фиксации приседания: ${gestureName()}"
        }
        card.addView(gesture, rowLp())
        switchRow(card, "Спринт: переключатель (иначе удержание)", cfg.sprintToggle) {
            cfg.sprintToggle = it
            cfg.save()
        }
        val hotbar = textButton("Переключение слотов: ${hotbarModeName()}") {}
        hotbar.setOnClickListener {
            cfg.hotbarMode = (cfg.hotbarMode + 1) % 3
            cfg.save()
            hotbar.text = "Переключение слотов: ${hotbarModeName()}"
            refresh()
        }
        card.addView(hotbar, rowLp())
        switchRow(card, "Тап по слоту хотбара выбирает слот", cfg.hotbarTap) {
            cfg.hotbarTap = it
            cfg.save()
        }
        switchRow(card, "Свайп в меню игры = колесо мыши", cfg.guiSwipeScroll) {
            cfg.guiSwipeScroll = it
            cfg.save()
        }
        seekRow(card, "Радиус джойстика (dp)", 30, 140, cfg.joyRadiusDp) {
            cfg.joyRadiusDp = it
            cfg.save()
            layer.invalidate()
        }
        seekRow(card, "Мёртвая зона джойстика (%)", 5, 70, (cfg.joyDeadZone * 100).toInt()) {
            cfg.joyDeadZone = it / 100f
            cfg.save()
        }
        seekRow(card, "Прозрачность джойстика (%)", 10, 100, (cfg.joyAlpha * 100).toInt()) {
            cfg.joyAlpha = it / 100f
            cfg.save()
            layer.invalidate()
        }
        seekRow(card, "Чувствительность обзора (%)", 30, 400, (cfg.lookSensitivity * 100).toInt()) {
            cfg.lookSensitivity = it / 100f
            cfg.save()
        }
        card.addView(textButton("Редактировать раскладку") { startEdit() }, rowLp())
        card.addView(textButton("Стандартная раскладка") {
            cfg.resetDefaults()
            cfg.save()
            refresh()
            showSettings()
        }, rowLp())
        card.addView(textButton("В лаунчер (игра продолжит работать)") {
            removePanel()
            host.onToLauncher()
        }, rowLp())
        card.addView(textButton("Закрыть") { removePanel() }, rowLp())

        val scroll = ScrollView(context)
        scroll.addView(card)
        val width = minOf(dp(480), (resources.displayMetrics.widthPixels * 0.92f).toInt())
        val height = (resources.displayMetrics.heightPixels * 0.92f).toInt()
        root.addView(scroll, FrameLayout.LayoutParams(width, height, Gravity.CENTER))
        panel = root
        addView(root)
    }

    // ---------------------------------------------------------------- layout editor

    fun startEdit() {
        removePanel()
        releaseAll()
        snapshot = cfg.toJson().toString()
        editing = true
        selected = null
        rebuildEditBar()
        refresh()
    }

    private fun finishEdit(save: Boolean) {
        if (save) {
            cfg.save()
        } else {
            snapshot?.let {
                val saved = JSONObject(it)
                cfg.fromJson(saved)
            }
        }
        editing = false
        selected = null
        editBar?.let { removeView(it) }
        editBar = null
        refresh()
        host.onEditFinished()
    }

    private var panelX = -1
    private var panelY = -1
    private var panelCollapsed = false

    /** A ScrollView that never grows taller than [maxHeight]. */
    private inner class MaxHeightScroll(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val capped = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, capped)
        }
    }

    private fun rebuildEditBar() {
        if (!editing) return
        editBar?.let { removeView(it) }
        val root = FrameLayout(context)
        val bar = LinearLayout(context)
        bar.orientation = LinearLayout.VERTICAL
        val sel = selected
        val selectedCfg = if (sel != null) cfg.buttons[sel] else null
        val icon = when (sel) {
            BtnId.EYE -> cfg.eye
            BtnId.GEAR -> cfg.gear
            else -> null
        }
        val title = styled(TextView(context), 15f)
        title.text = when {
            sel == BtnId.ZONE -> "Зона джойстика"
            sel == BtnId.EYE -> "Кнопка «Глаз»"
            sel == BtnId.GEAR -> "Кнопка «Настройки»"
            selectedCfg != null -> "Кнопка: ${selectedCfg.label}"
            else -> "Коснитесь кнопки или зоны, чтобы изменить её; перетащите, чтобы переместить"
        }
        bar.addView(title)
        if (sel == BtnId.ZONE) {
            seekRow(bar, "Ширина зоны (%)", 10, 100, ((cfg.zoneRight - cfg.zoneLeft) * 100).toInt()) {
                cfg.zoneRight = (cfg.zoneLeft + it / 100f).coerceAtMost(1f)
                if (cfg.zoneRight - cfg.zoneLeft < it / 100f) cfg.zoneLeft = cfg.zoneRight - it / 100f
                layer.invalidate()
            }
            seekRow(bar, "Высота зоны (%)", 10, 100, ((cfg.zoneBottom - cfg.zoneTop) * 100).toInt()) {
                cfg.zoneBottom = (cfg.zoneTop + it / 100f).coerceAtMost(1f)
                if (cfg.zoneBottom - cfg.zoneTop < it / 100f) cfg.zoneTop = cfg.zoneBottom - it / 100f
                layer.invalidate()
            }
            seekRow(bar, "Радиус джойстика (dp)", 30, 140, cfg.joyRadiusDp) {
                cfg.joyRadiusDp = it
                layer.invalidate()
            }
            seekRow(bar, "Прозрачность (%)", 10, 100, (cfg.joyAlpha * 100).toInt()) {
                cfg.joyAlpha = it / 100f
                layer.invalidate()
            }
            switchRow(bar, "Плавающий джойстик", cfg.joyFloating) {
                cfg.joyFloating = it
                layer.invalidate()
            }
        } else if (icon != null) {
            seekRow(bar, "Размер (dp)", 28, 100, icon.sizeDp) {
                icon.sizeDp = it
                requestLayout()
            }
            seekRow(bar, "Прозрачность (%)", 10, 100, (icon.alpha * 100).toInt()) {
                icon.alpha = it / 100f
                refresh()
            }
        } else if (sel != null && selectedCfg != null) {
            val c = selectedCfg
            val name = EditText(context)
            name.setText(c.label)
            name.setTextColor(Color.WHITE)
            name.setHintTextColor(0x88FFFFFF.toInt())
            name.hint = "Название"
            name.isSingleLine = true
            name.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    c.label = s?.toString().orEmpty().take(12)
                    buttons[sel]?.invalidate()
                }
            })
            bar.addView(name)
            val actionButton = textButton("Действие: ${Act.describe(c.action)}") {}
            actionButton.setOnClickListener {
                ActionPicker.pick(context, c.action) { picked ->
                    val wasBar = c.action == Act.BAR
                    c.action = picked
                    if (picked == Act.BAR && c.heightDp == 0) {
                        c.sizeDp = 220
                        c.heightDp = 30
                    } else if (picked != Act.BAR && wasBar) {
                        c.sizeDp = 56
                        c.heightDp = 0
                    }
                    refresh()
                    rebuildEditBar()
                }
            }
            bar.addView(actionButton, rowLp())
            switchRow(bar, "Показывать кнопку", c.enabled) {
                c.enabled = it
                refresh()
            }
            if (c.action == Act.BAR) {
                // The longer side is the direction of travel, so the bar can lie either way.
                val vertical = c.heightDp > c.sizeDp
                val orientation = textButton(if (vertical) "Положение: вертикально (нажмите — повернуть)" else "Положение: горизонтально (нажмите — повернуть)") {}
                orientation.setOnClickListener {
                    val w = c.sizeDp
                    c.sizeDp = c.heightDp.coerceAtLeast(14)
                    c.heightDp = w
                    requestLayout()
                    rebuildEditBar()
                }
                bar.addView(orientation, rowLp())
                seekRow(bar, "Длина (dp)", 60, 600, if (vertical) c.heightDp else c.sizeDp) {
                    if (vertical) c.heightDp = it else c.sizeDp = it
                    requestLayout()
                }
                seekRow(bar, "Толщина (dp)", 14, 140, (if (vertical) c.sizeDp else c.heightDp).coerceIn(14, 140)) {
                    if (vertical) c.sizeDp = it else c.heightDp = it
                    requestLayout()
                }
                val hint = styled(TextView(context), 11f)
                hint.text = "Ведите пальцем вдоль длинной стороны, чтобы сменить слот."
                bar.addView(hint)
            } else {
                seekRow(bar, "Размер (dp)", 28, 140, c.sizeDp) {
                    c.sizeDp = it
                    requestLayout()
                }
            }
            seekRow(bar, "Прозрачность (%)", 10, 100, (c.alpha * 100).toInt()) {
                c.alpha = it / 100f
                refresh()
            }
            switchRow(bar, "Не рисовать (нажатие работает)", c.hidden) {
                c.hidden = it
                refresh()
            }
            val colorLabel = styled(TextView(context), 12f)
            colorLabel.text = "Цвет кнопки"
            bar.addView(colorLabel)
            val swatches = LinearLayout(context)
            swatches.orientation = LinearLayout.HORIZONTAL
            val palette = intArrayOf(0, 0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF424242.toInt(), 0xFFEEEEEE.toInt())
            for (value in palette) {
                val chip = TextView(context)
                chip.gravity = Gravity.CENTER
                chip.text = if (value == 0) "A" else ""
                chip.setTextColor(Color.WHITE)
                chip.setBackgroundColor(if (value == 0) 0xFF14233D.toInt() else value)
                chip.setOnClickListener {
                    c.color = value
                    refresh()
                }
                val chipLp = LinearLayout.LayoutParams(0, dp(30), 1f)
                chipLp.setMargins(dp(2), dp(2), dp(2), dp(2))
                swatches.addView(chip, chipLp)
            }
            bar.addView(swatches)
            bar.addView(textButton("Удалить кнопку") {
                cfg.buttons.remove(sel)
                selected = null
                refresh()
                rebuildEditBar()
            }, rowLp())
        }
        bar.addView(textButton("+ Новая кнопка") {
            val id = cfg.newCustomId()
            val n = cfg.buttons.size
            val cx = 0.16f + 0.07f * (n % 6)
            val cy = 0.34f + 0.09f * ((n / 6) % 4)
            cfg.buttons[id] = ButtonCfg(true, cx, cy, 56, 0.6f, "Кн.", "")
            selected = id
            refresh()
            rebuildEditBar()
        }, rowLp())
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(textButton("Сохранить") { finishEdit(true) }, halfLp())
        row.addView(textButton("Отмена") { finishEdit(false) }, halfLp())
        row.addView(textButton("Сброс") {
            cfg.resetDefaults()
            selected = null
            refresh()
            rebuildEditBar()
        }, halfLp())
        bar.addView(row)

        // The panel is a small floating window: drag its header anywhere, or fold it away.
        val wrap = card()
        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val grip = styled(TextView(context), 13f)
        grip.text = "≡ Панель — тяните за эту полосу"
        header.addView(grip, LinearLayout.LayoutParams(0, dp(36), 1f))
        val fold = textButton(if (panelCollapsed) "▴" else "▾") {}
        header.addView(fold, LinearLayout.LayoutParams(dp(44), dp(36)))
        wrap.addView(header)
        val scroll = MaxHeightScroll(context, (resources.displayMetrics.heightPixels * 0.5f).toInt())
        scroll.addView(bar)
        scroll.visibility = if (panelCollapsed) View.GONE else View.VISIBLE
        wrap.addView(scroll)
        fold.setOnClickListener {
            panelCollapsed = !panelCollapsed
            scroll.visibility = if (panelCollapsed) View.GONE else View.VISIBLE
            fold.text = if (panelCollapsed) "▴" else "▾"
        }
        val metrics = resources.displayMetrics
        val width = minOf(dp(290), (metrics.widthPixels * 0.42f).toInt())
        if (panelX < 0 || panelY < 0) {
            val itemX = when {
                sel == BtnId.ZONE -> (cfg.zoneLeft + cfg.zoneRight) / 2f
                icon != null -> icon.cx
                selectedCfg != null -> selectedCfg.cx
                else -> 0.3f
            }
            panelX = if (itemX > 0.5f) dp(8) else metrics.widthPixels - width - dp(8)
            panelY = dp(8)
        }
        val lp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        lp.leftMargin = panelX.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        lp.topMargin = panelY.coerceIn(0, (metrics.heightPixels - dp(60)).coerceAtLeast(0))
        var startX = 0f
        var startY = 0f
        var startLeft = 0
        var startTop = 0
        header.setOnTouchListener { _, e ->
            val params = wrap.layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    startLeft = params.leftMargin
                    startTop = params.topMargin
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxLeft = (metrics.widthPixels - wrap.width).coerceAtLeast(0)
                    val maxTop = (metrics.heightPixels - dp(60)).coerceAtLeast(0)
                    params.leftMargin = (startLeft + (e.rawX - startX)).toInt().coerceIn(0, maxLeft)
                    params.topMargin = (startTop + (e.rawY - startY)).toInt().coerceIn(0, maxTop)
                    panelX = params.leftMargin
                    panelY = params.topMargin
                    wrap.layoutParams = params
                }
            }
            true
        }
        root.addView(wrap, lp)
        editBar = root
        addView(root)
    }


    private companion object {
        const val K_NONE = 0
        const val K_KEYS = 1
        const val K_MOUSE = 2
        const val K_SCROLL = 3
        const val K_SNEAK = 4
        const val K_SPRINT = 5
        const val K_CHAT = 6
        const val K_KEYBOARD = 7
        const val K_STICK = 8
        const val K_BAR = 9
        const val K_PROFILE = 10
    }
}
