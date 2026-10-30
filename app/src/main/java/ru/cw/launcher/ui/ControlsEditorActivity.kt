package ru.cw.launcher.ui

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ru.cw.launcher.ui.controls.ControlsConfig
import ru.cw.launcher.ui.controls.ControlsOverlay
import ru.cw.launcher.ui.controls.NullInput

/** Opens the same controls editor as the in-game gear, from the launcher settings. */
class ControlsEditorActivity : Activity(), ControlsOverlay.Host {
    private lateinit var overlay: ControlsOverlay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0B1220.toInt())
        overlay = ControlsOverlay(this, NullInput, ControlsConfig.load(), this)
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            overlay.safeLeft = bars.left
            overlay.safeTop = bars.top
            overlay.requestLayout()
            insets
        }
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        overlay.startEdit()
    }

    override fun onChat() {}

    override fun onToLauncher() {
        finish()
    }

    override fun onEditFinished() {
        finish()
    }

    @Deprecated("Back closes panels first")
    override fun onBackPressed() {
        if (!overlay.consumeBack()) finish()
    }
}
