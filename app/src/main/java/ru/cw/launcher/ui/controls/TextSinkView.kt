package ru.cw.launcher.ui.controls

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * Invisible text target for the Android keyboard. The keyboard never edits a real text field:
 * every committed or composed change is translated into Minecraft character, Backspace and
 * Enter events, so what is typed is exactly what the game's own chat receives. Typed text is
 * never stored or logged here.
 */
class TextSinkView(context: Context, private val out: Out) : View(context) {
    interface Out {
        fun text(s: String)
        fun backspace(count: Int)
        fun enter()
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(attrs: EditorInfo): InputConnection {
        attrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        attrs.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return Connection(this, out)
    }

    private class Connection(view: View, private val out: Out) : BaseInputConnection(view, false) {
        private var composing = ""

        private fun apply(next: String) {
            val limit = minOf(composing.length, next.length)
            var common = 0
            while (common < limit && composing[common] == next[common]) common++
            val back = composing.length - common
            if (back > 0) out.backspace(back)
            val added = next.substring(common)
            composing = next
            if (added.isEmpty()) return
            if (added.indexOf('\n') < 0) {
                out.text(added)
                return
            }
            val parts = added.split('\n')
            for (i in parts.indices) {
                if (parts[i].isNotEmpty()) out.text(parts[i])
                if (i < parts.size - 1) out.enter()
            }
            composing = ""
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            apply(text?.toString().orEmpty())
            composing = ""
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            apply(text?.toString().orEmpty())
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (beforeLength > 0) out.backspace(beforeLength)
            composing = ""
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            deleteSurroundingText(beforeLength, afterLength)

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> out.backspace(1)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    composing = ""
                    out.enter()
                }
                else -> {
                    val ch = event.unicodeChar
                    if (ch != 0) out.text(String(Character.toChars(ch)))
                }
            }
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            composing = ""
            out.enter()
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun setSelection(start: Int, end: Int): Boolean = true
    }
}
