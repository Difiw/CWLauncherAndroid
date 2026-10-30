package ru.cw.launcher.ui

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import ru.cw.launcher.R
import android.content.IntentFilter
import android.os.SystemClock
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import git.artdeell.dnbootstrap.glfw.GLFW
import ru.cw.launcher.engine.CwLog
import ru.cw.launcher.engine.PhoneVm
import ru.cw.launcher.ui.controls.ControlsConfig
import ru.cw.launcher.ui.controls.ControlsOverlay
import ru.cw.launcher.ui.controls.Glfw
import ru.cw.launcher.ui.controls.GlfwInput
import ru.cw.launcher.ui.controls.TextSinkView
import ru.cw.launcher.work.GameService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class GameActivity : Activity(), ControlsOverlay.Host, TextSinkView.Out {
    private val started = AtomicBoolean(false)
    private var status: View? = null
    private var bootBar: ProgressBar? = null
    private var bootText: TextView? = null
    private var bootEta: TextView? = null
    private var bootLog: TextView? = null
    private var bootHolder: FrameLayout? = null
    private var detailed = false
    private val openedAt = System.currentTimeMillis()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var surface: SurfaceView
    private lateinit var overlay: ControlsOverlay
    private lateinit var chatView: TextSinkView
    private var chatActive = false
    private var imeHeight = 0
    private var renderWidth = 0
    private var highPriority = false

    // Leaves the black game screen as soon as the game process reports that it ended,
    // and hides the start-up text once Minecraft has reached GLFW window creation.
    private val watcher = object : Runnable {
        override fun run() {
            if (started.get() && PhoneVm.stage() == PhoneVm.Stage.EXITED) {
                PhoneVm.returnToLauncher(applicationContext)
                android.os.Process.killProcess(android.os.Process.myPid())
                return
            }
            if (PhoneVm.windowShowing()) {
                status?.visibility = View.GONE
                if (::overlay.isInitialized && overlay.loading) overlay.loading = false
            } else {
                updateBootPanel()
            }
            ui.postDelayed(this, 500)
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(watcher)
        unregisterScreenReceiver()
        // The Java machine lives in this process and cannot be restarted in it, so a
        // finished game screen must end the process; the next launch starts a clean one.
        if (isFinishing && PhoneVm.vmStarted) {
            PhoneVm.mark(PhoneVm.Stage.EXITED, 0, "stop")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android re-delivers the old intent when it restarts a killed game process; a stale
        // start must not launch Minecraft again.
        if (!PhoneVm.claimLaunch(intent?.getStringExtra(EXTRA_LAUNCH).orEmpty())) {
            finishAndRemoveTask()
            return
        }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                val message = "Необработанное исключение ${thread.name}: ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
                CwLog.error(message)
                PhoneVm.mark(PhoneVm.Stage.EXITED, PhoneVm.UNKNOWN_EXIT_CODE, message)
            } finally {
                previousHandler?.uncaughtException(thread, failure)
                    ?: android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Keeps this process alive when the player switches apps or locks the screen.
        GameService.start(this)
        // This callback is the same synchronization point used by MojoLauncher:
        // the Surface must not reach native GLFW before Minecraft calls glfwInit.
        GLFW.setInitCallback { PhoneVm.onGlfwInitialized() }
        GLFW.setGrabListener { ui.post { if (::overlay.isInitialized) overlay.onGrabChanged() } }

        val root = FrameLayout(this)
        surface = SurfaceView(this)
        // Reduced render resolution: the game draws into a smaller buffer that the GPU scales up.
        // This is the main way to speed up heavy shader packs on a phone.
        val scaleCfg = ru.cw.launcher.engine.CwSettings.load()
        highPriority = scaleCfg.highPriority
        if (highPriority && Build.VERSION.SDK_INT >= 24) {
            // Keeps CPU and GPU clocks steady instead of letting them jump with heat.
            val power = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (power.isSustainedPerformanceModeSupported) window.setSustainedPerformanceMode(true)
        }
        val percent = ru.cw.launcher.engine.Shaders.renderScale(scaleCfg, ru.cw.launcher.engine.AppPaths.game(scaleCfg.minecraftVersion))
        if (percent < 100) {
            val m = resources.displayMetrics
            val longSide = maxOf(m.widthPixels, m.heightPixels)
            val shortSide = minOf(m.widthPixels, m.heightPixels)
            val fixedW = ((longSide * percent / 100) and 1.inv()).coerceAtLeast(320)
            val fixedH = ((shortSide * percent / 100) and 1.inv()).coerceAtLeast(180)
            surface.holder.setFixedSize(fixedW, fixedH)
            renderWidth = fixedW
            CwLog.info("Разрешение рендера $percent%: ${fixedW}x$fixedH")
        }
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width < 16 || height < 16) return
                if (PhoneVm.vmStarted || !started.compareAndSet(false, true)) {
                    started.set(true)
                    PhoneVm.surfaceChanged(holder.surface)
                    return
                }
                val windowSurface = holder.surface
                thread(name = "cw-jvm", isDaemon = false) {
                    // The Minecraft render thread is this thread: give it display priority.
                    if (highPriority) {
                        try {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                        } catch (_: Exception) {
                        }
                    }
                    var code = -1
                    var message: String? = null
                    var windowReady = false
                    try {
                        code = PhoneVm.launch(this@GameActivity, windowSurface, width, height)
                        windowReady = true
                        CwLog.info("Игра завершилась с кодом ${PhoneVm.formatCode(code)}")
                    } catch (t: Throwable) {
                        code = -1
                        message = t.message ?: "Игра не запустилась"
                        t.printStackTrace()
                        CwLog.error("Игра не запустилась: ${t.message}")
                    } finally {
                        if (windowReady) {
                            try {
                                PhoneVm.destroySurface()
                            } catch (_: Throwable) {
                            }
                        }
                        val stored = PhoneVm.storedError()
                        if (message.isNullOrBlank() && stored.isNotBlank()) {
                            PhoneVm.mark(PhoneVm.Stage.EXITED, PhoneVm.storedCode(), stored)
                        } else {
                            PhoneVm.mark(PhoneVm.Stage.EXITED, code, message)
                        }
                        PhoneVm.returnToLauncher(applicationContext)
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (started.get()) PhoneVm.destroySurface()
            }
        })
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // The start-up screen sits under the controls, so the gear and its settings stay above it.
        val cfgNow = ru.cw.launcher.engine.CwSettings.load()
        Pal.set(cfgNow.uiPalette, cfgNow.uiSurface, cfgNow.uiCustomHue, cfgNow.uiDynHues)
        detailed = cfgNow.developerMode || cfgNow.verboseLogging
        showLogs = detailed && !cfgNow.hideBootLogs
        val panel = buildBootPanel()
        status = panel
        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        overlay = ControlsOverlay(this, GlfwInput, ControlsConfig.load(), this)
        overlay.guiScaleProvider = { PhoneVm.optionInt("guiScale", 0) }
        overlay.mcWidth = renderWidth
        overlay.loading = !PhoneVm.windowShowing()
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        chatView = TextSinkView(this, this)
        root.addView(chatView, FrameLayout.LayoutParams(1, 1))

        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            overlay.safeLeft = bars.left
            overlay.safeTop = bars.top
            val sideGap = maxOf(bars.left, bars.right) + dp(24)
            val edgeGap = maxOf(bars.top, bars.bottom) + dp(16)
            bootHolder?.setPadding(sideGap, edgeGap, sideGap, edgeGap)
            overlay.requestLayout()
            imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            applyShift()
            insets
        }
        hideSystemBars()
        ui.post(watcher)
        registerScreenReceiver()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Start-up screen. Everyone sees a progress bar with a percentage, the current stage and a
     * rough time left. With developer mode or verbose logs on, the bar moves to the top and the
     * live game log is shown below it.
     */
    private var bootPercent: TextView? = null
    private var bootProgress: ((Int) -> Unit)? = null
    private var bootLogScroll: android.widget.ScrollView? = null
    private var showLogs = false

    private fun label(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = android.graphics.Typeface.DEFAULT_BOLD
        return t
    }

    private fun cardBackground(accent: Int): GradientDrawable {
        val card = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Tc(0xF2162C50).toArgb(), Tc(0xF20B1424).toArgb()))
        card.cornerRadius = dp(18).toFloat()
        card.setStroke(dp(1), (accent and 0x00FFFFFF) or 0x88000000.toInt())
        return card
    }

    /** Scrollable live game log: it can be read upward while it keeps following the end. */
    private fun buildLogView(accent: Int): android.widget.ScrollView {
        val scroll = android.widget.ScrollView(this)
        val box = GradientDrawable()
        box.setColor(0x99000000.toInt())
        box.cornerRadius = dp(12).toFloat()
        box.setStroke(dp(1), (accent and 0x00FFFFFF) or 0x55000000)
        scroll.background = box
        scroll.isVerticalScrollBarEnabled = true
        val log = TextView(this)
        log.setTextColor(0xFFCFE0FF.toInt())
        log.textSize = 10f
        log.typeface = android.graphics.Typeface.MONOSPACE
        log.setPadding(dp(10), dp(8), dp(10), dp(8))
        log.gravity = Gravity.START or Gravity.TOP
        log.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        log.includeFontPadding = false
        scroll.addView(log, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        bootLog = log
        bootLogScroll = scroll
        return scroll
    }

    private inner class SegmentBar(private val fill: Int) : View(this@GameActivity) {
        var percent = 0
            set(value) {
                field = value
                invalidate()
            }
        private val paint = Paint()

        override fun onDraw(canvas: Canvas) {
            val count = 20
            val gap = dp(3).toFloat()
            val w = (width - gap * (count - 1)) / count
            val lit = percent * count / 100
            for (i in 0 until count) {
                paint.color = if (i < lit) fill else 0x33FFFFFF
                val x = i * (w + gap)
                canvas.drawRect(x, 0f, x + w, height.toFloat(), paint)
            }
        }
    }

    private inner class RingView(private val fill: Int) : View(this@GameActivity) {
        var percent = 0
            set(value) {
                field = value
                invalidate()
            }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            track.style = Paint.Style.STROKE
            track.strokeWidth = dp(10).toFloat()
            track.color = 0x33FFFFFF
            arc.style = Paint.Style.STROKE
            arc.strokeWidth = dp(10).toFloat()
            arc.strokeCap = Paint.Cap.ROUND
            arc.color = fill
            text.color = Color.WHITE
            text.textAlign = Paint.Align.CENTER
            text.textSize = dp(30).toFloat()
            text.typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        override fun onDraw(canvas: Canvas) {
            val pad = dp(10).toFloat()
            val rect = RectF(pad, pad, width - pad, height - pad)
            canvas.drawArc(rect, 0f, 360f, false, track)
            canvas.drawArc(rect, -90f, 360f * percent / 100f, false, arc)
            canvas.drawText("$percent%", width / 2f, height / 2f + text.textSize / 3f, text)
        }
    }

    /**
     * Start-up screen in one of five designs (card, minimal, cinema, pixels, ring), all in the colours
     * of the chosen theme over the chosen background. The live game log is optional (developer mode)
     * and scrolls.
     */
    private fun buildBootPanel(): View {
        val accent = Tc(0xFF3B82F6).toArgb()
        val settings = ru.cw.launcher.engine.CwSettings.load()
        val style = settings.bootStyle
        val page = FrameLayout(this)
        page.setBackgroundColor(0xFF070C16.toInt())
        val picture = ImageView(this)
        Backgrounds.apply(picture, settings)
        picture.scaleType = ImageView.ScaleType.CENTER_CROP
        page.addView(picture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val wash = View(this)
        wash.background = if (style == "cinema") GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF2070C16.toInt(), 0x66070C16)
        ) else GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf((accent and 0x00FFFFFF) or 0x40000000, 0xD9070C16.toInt())
        )
        page.addView(wash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val muted = 0xFFB8C7E0.toInt()
        val screenW = resources.displayMetrics.widthPixels
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        val wide = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        var lp = FrameLayout.LayoutParams(minOf(dp(420), (screenW * 0.8f).toInt()), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        val logHeight = (resources.displayMetrics.heightPixels * 0.34f).toInt()

        fun logs(topGap: Int = 10) {
            if (!showLogs) return
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, logHeight)
            params.topMargin = dp(topGap)
            column.addView(buildLogView(accent), params)
        }

        fun thinBar(heightDp: Int, track: Int): ProgressBar {
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            bar.max = 100
            bar.progressTintList = ColorStateList.valueOf(accent)
            bar.progressBackgroundTintList = ColorStateList.valueOf(track)
            bootBar = bar
            return bar
        }

        when (style) {
            "minimal" -> {
                lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                column.setPadding(dp(8), 0, dp(8), dp(8))
                if (showLogs) {
                    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, logHeight)
                    params.bottomMargin = dp(12)
                    column.addView(buildLogView(accent), params)
                }
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                val stage = label("", 15f)
                bootText = stage
                row.addView(stage, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val percent = label("0%", 18f, accent, true)
                bootPercent = percent
                row.addView(percent)
                column.addView(row, wide)
                column.addView(thinBar(4, 0x33FFFFFF), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(6) })
                val eta = label("", 11f, muted)
                bootEta = eta
                column.addView(eta, wide)
            }
            "cinema" -> {
                lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
                column.gravity = Gravity.CENTER_VERTICAL
                column.setPadding(dp(16), 0, dp(16), 0)
                column.addView(label("CW LAUNCHER", 12f, accent, true).also { it.letterSpacing = 0.4f }, wide)
                column.addView(label("MINECRAFT", 44f, Color.WHITE, true).also { it.letterSpacing = 0.08f }, wide)
                val line = LinearLayout(this)
                line.orientation = LinearLayout.HORIZONTAL
                line.gravity = Gravity.BOTTOM
                val percent = label("0%", 40f, accent, true)
                bootPercent = percent
                line.addView(thinBar(6, 0x22FFFFFF), LinearLayout.LayoutParams(0, dp(6), 1f).apply { bottomMargin = dp(14) })
                line.addView(percent, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(16) })
                column.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
                val stage = label("", 15f)
                bootText = stage
                column.addView(stage, wide)
                val eta = label("", 12f, muted)
                bootEta = eta
                column.addView(eta, wide)
                logs(14)
            }
            "pixels" -> {
                column.gravity = Gravity.CENTER_HORIZONTAL
                column.setPadding(dp(22), dp(18), dp(22), dp(18))
                column.background = cardBackground(accent)
                column.addView(label("ЗАГРУЗКА МИРА", 18f, Color.WHITE, true).also { it.gravity = Gravity.CENTER }, wide)
                val seg = SegmentBar(accent)
                bootProgress = { seg.percent = it }
                column.addView(seg, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(22)).apply { topMargin = dp(16) })
                val stage = label("", 14f)
                stage.gravity = Gravity.CENTER
                bootText = stage
                column.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
                val eta = label("", 12f, muted)
                eta.gravity = Gravity.CENTER
                bootEta = eta
                column.addView(eta, wide)
                logs()
                if (showLogs) lp = FrameLayout.LayoutParams(minOf(dp(560), (screenW * 0.9f).toInt()), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            }
            "ring" -> {
                column.orientation = LinearLayout.HORIZONTAL
                column.gravity = Gravity.CENTER_VERTICAL
                column.setPadding(dp(22), dp(18), dp(22), dp(18))
                column.background = cardBackground(accent)
                val ring = RingView(accent)
                bootProgress = { ring.percent = it }
                column.addView(ring, LinearLayout.LayoutParams(dp(130), dp(130)))
                val side = LinearLayout(this)
                side.orientation = LinearLayout.VERTICAL
                side.addView(label("Загрузка Minecraft", 18f, Color.WHITE, true), wide)
                val stage = label("", 14f)
                bootText = stage
                side.addView(stage, wide)
                val eta = label("", 12f, muted)
                bootEta = eta
                side.addView(eta, wide)
                if (showLogs) {
                    side.addView(buildLogView(accent), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, logHeight).apply { topMargin = dp(10) })
                }
                column.addView(side, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(18) })
                lp = FrameLayout.LayoutParams(minOf(dp(620), (screenW * 0.9f).toInt()), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            }
            else -> {
                column.setPadding(dp(22), dp(18), dp(22), dp(18))
                column.background = cardBackground(accent)
                val gravity = if (showLogs) Gravity.START else Gravity.CENTER
                column.addView(label("CW Launcher", 12f, accent).also { it.letterSpacing = 0.2f; it.gravity = gravity }, wide)
                column.addView(label("Загрузка Minecraft", 20f, Color.WHITE, true).also { it.gravity = gravity }, wide)
                column.addView(thinBar(10, 0x33FFFFFF), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(14) })
                val stage = label("", 14f)
                stage.gravity = gravity
                stage.setPadding(0, dp(8), 0, 0)
                bootText = stage
                column.addView(stage, wide)
                val eta = label("", 12f, muted)
                eta.gravity = gravity
                bootEta = eta
                column.addView(eta, wide)
                column.addView(label("Шестерёнка — настройки управления", 11f, 0x88FFFFFF.toInt()).also { it.gravity = gravity; it.setPadding(0, dp(8), 0, 0) }, wide)
                logs()
                if (showLogs) lp = FrameLayout.LayoutParams(minOf(dp(640), (screenW * 0.9f).toInt()), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            }
        }
        // The holder follows the screen cut-outs equally on both sides, so the content is always centred.
        val holder = FrameLayout(this)
        holder.setPadding(dp(24), dp(16), dp(24), dp(16))
        bootHolder = holder
        holder.addView(column, lp)
        page.addView(holder, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        updateBootPanel()
        return page
    }

    private fun updateBootPanel() {
        val boot = PhoneVm.boot(System.currentTimeMillis())
        bootBar?.progress = boot.percent
        bootProgress?.invoke(boot.percent)
        bootPercent?.text = "${boot.percent}%"
        bootText?.text = if (bootPercent != null || bootProgress != null) boot.stage else "${boot.percent}%  ·  ${boot.stage}"
        bootEta?.text = when {
            boot.etaSeconds < 0 -> "Моды загружаются, это может занять несколько минут"
            boot.etaSeconds < 60 -> "Осталось меньше минуты"
            else -> "Осталось примерно ${(boot.etaSeconds + 30) / 60} мин"
        }
        val log = bootLog
        val scroll = bootLogScroll
        if (showLogs && log != null && scroll != null) {
            val text = PhoneVm.lastGameLines(400, 80_000).joinToString("\n") { if (it.length > 160) it.take(157) + "…" else it }
            if (text != log.text.toString()) {
                val atEnd = scroll.getChildAt(0).bottom - (scroll.scrollY + scroll.height) < dp(24)
                log.text = text
                if (atEnd) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else if (::overlay.isInitialized) overlay.releaseAll()
    }

    override fun onPause() {
        if (::overlay.isInitialized) {
            overlay.releaseAll()
            // Leaving the game screen with the mouse grabbed: open the pause menu so the world
            // does not keep running unattended while the launcher is shown.
            if (started.get() && PhoneVm.glfwStarted() && GlfwInput.grabbing()) GlfwInput.tapKey(Glfw.KEY_ESCAPE)
        }
        super.onPause()
    }

    // ---------------------------------------------------------------- back / chat

    @Deprecated("Кнопка назад закрывает меню игры")
    override fun onBackPressed() {
        if (overlay.consumeBack()) return
        if (chatActive) {
            cancelChat()
            return
        }
        GlfwInput.tapKey(Glfw.KEY_ESCAPE)
    }

    override fun onChat() {
        if (!chatActive) {
            // In the world the chat screen is opened with the T key; inside a menu or an
            // already open chat the keyboard just attaches to the current screen.
            if (GlfwInput.grabbing()) GlfwInput.tapKey(Glfw.KEY_T)
            chatActive = true
        }
        showKeyboard()
    }

    override fun onEditFinished() {}

    override fun onToLauncher() {
        PhoneVm.showLauncher(applicationContext)
    }

    override fun text(s: String) {
        GlfwInput.text(s)
    }

    override fun backspace(count: Int) {
        repeat(count) { GlfwInput.tapKey(Glfw.KEY_BACKSPACE) }
    }

    override fun enter() {
        GlfwInput.tapKey(Glfw.KEY_ENTER)
        endChat()
    }

    private fun cancelChat() {
        GlfwInput.tapKey(Glfw.KEY_ESCAPE)
        endChat()
    }

    private fun endChat() {
        chatActive = false
        hideKeyboard()
        applyShift()
    }

    private fun showKeyboard() {
        chatView.requestFocus()
        chatView.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(chatView, 0)
        }
        applyShift()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(chatView.windowToken, 0)
        chatView.clearFocus()
    }

    /** Lifts the game picture above the keyboard so the chat line stays visible. */
    private fun applyShift() {
        var shift = imeHeight.toFloat()
        if (shift == 0f && chatActive && Build.VERSION.SDK_INT < 30) {
            shift = surface.height * 0.4f
        }
        if (!chatActive && imeHeight == 0) shift = 0f
        surface.translationY = -shift
        overlay.shiftY = shift
    }

    // ---------------------------------------------------------------- screen off

    private var quitting = false
    private var screenReceiver: BroadcastReceiver? = null
    private var alarmIntent: PendingIntent? = null

    /**
     * Reacts to the phone screen being switched off (not to leaving the app): quit at once,
     * quit after a timer, or keep playing. The default is "keep playing". The timer uses the
     * system alarm service so it still fires while the CPU sleeps; Android may delay it in
     * deep power saving, so the time is approximate.
     */
    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON -> cancelQuitTimer()
                    ACTION_QUIT -> quitGracefully()
                    ACTION_STOP -> stopNow()
                }
            }
        }
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(ACTION_QUIT)
        filter.addAction(ACTION_STOP)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    private fun unregisterScreenReceiver() {
        cancelQuitTimer()
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        screenReceiver = null
    }

    private fun onScreenOff() {
        val cfg = ru.cw.launcher.engine.CwSettings.load()
        when (cfg.screenOffMode) {
            0 -> quitGracefully()
            1 -> {
                val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
                val pending = PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_QUIT).setPackage(packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                alarmIntent = pending
                val at = SystemClock.elapsedRealtime() + cfg.screenOffMinutes.coerceAtLeast(1) * 60_000L
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
            }
        }
    }

    private fun cancelQuitTimer() {
        alarmIntent?.let {
            (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(it)
        }
        alarmIntent = null
    }

    /**
     * Quits the way a player would: Esc, then the pause menu's "Save and Quit" / "Disconnect"
     * button, so the world is saved and the server connection is closed. Minecraft cannot be
     * asked to close its window from here, so if it is still alive a few seconds later the
     * process is ended as a last resort. Position-based clicking is best effort.
     */
    private fun quitGracefully() {
        if (quitting || !PhoneVm.vmStarted) return
        quitting = true
        if (GlfwInput.grabbing()) GlfwInput.tapKey(Glfw.KEY_ESCAPE)
        ui.postDelayed({ if (!GlfwInput.grabbing()) overlay.clickPauseQuit() }, 900)
        ui.postDelayed({
            PhoneVm.mark(PhoneVm.Stage.EXITED, 0, "stop")
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 5000)
    }

    /** The launcher asked to close the game: leave the task first so Android has nothing to restart. */
    private fun stopNow() {
        PhoneVm.mark(PhoneVm.Stage.EXITED, 0, "stop")
        finishAndRemoveTask()
        ui.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
    }

    companion object {
        private const val ACTION_QUIT = "ru.cw.launcher.SCREEN_OFF_QUIT"
        const val ACTION_STOP = "ru.cw.launcher.GAME_STOP"
        const val EXTRA_LAUNCH = "launchId"
    }
}
