package git.artdeell.mojo.awt;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Surface;

import ru.cw.launcher.CwApplication;
import ru.cw.launcher.engine.CwLog;

/** Android side of the AWT bridge expected by libpojavexec_awt. */
public final class AWTBridge {
    private static volatile Runnable enableCallback;
    private static volatile boolean windowCreated;

    private AWTBridge() {}

    public static void queryClipboardString() {
        Context context = CwApplication.Companion.getInstance();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence text = clipboard != null && clipboard.hasPrimaryClip()
                ? clipboard.getPrimaryClip().getItemAt(0).coerceToText(context) : "";
        nativeClipboardReceived(text == null ? "" : text.toString(), "plain");
    }

    public static void putClipboardString(String data) {
        Context context = CwApplication.Companion.getInstance();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Minecraft", data));
    }

    public static void openLink(String data) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(data));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            CwApplication.Companion.getInstance().startActivity(intent);
        } catch (Throwable error) {
            CwLog.INSTANCE.warn("AWT не смог открыть ссылку: " + error.getMessage());
        }
    }

    public static void notifyWindowOpened() {
        if (windowCreated) return;
        windowCreated = true;
        Runnable callback = enableCallback;
        if (callback != null) callback.run();
        CwLog.INSTANCE.info("AWT: игровое окно создано");
    }

    public static void setEnableCallback(Runnable callback) {
        enableCallback = callback;
        if (windowCreated && callback != null) callback.run();
    }

    public static native void nativeClipboardReceived(String data, String mimeTypeSub);
    public static native void nativeMoveWindow(int xoff, int yoff);
    public static native void nativeBeginRendering(Surface surface, int bridgeWidth, int bridgeHeight);
    public static native void nativeEndRendering();
    public static native void nativeSendCursorPos(int x, int y);
    public static native boolean nativeSendKeyEvent(int keycode, int state, int mods, int codepoint);
    public static native void nativeSendMouseEvent(int button, int state, int mods);
    public static native void nativeResize(int bridgeWidth, int bridgeHeight);
    public static native void nativeTypeChars(String chars);
}
