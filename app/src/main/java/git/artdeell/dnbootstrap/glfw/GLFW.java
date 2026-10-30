package git.artdeell.dnbootstrap.glfw;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Java side of MojoLauncher's Android GLFW backend. */
public final class GLFW {
    public interface PosCallback { void receive(double x, double y); }
    public interface CursorCallback { void onCursorUse(GLFWCursor cursor); }
    public interface GrabListener { void onGrabState(boolean grabbing); }

    private static GrabListener grabListener = value -> {};
    private static Runnable initCallback = () -> {};
    private static PosCallback positionCallback = (x, y) -> {};
    private static CursorCallback cursorCallback = cursor -> {};
    private static boolean grabbing;
    public static ByteBuffer gamepadButtonBuffer;
    public static FloatBuffer gamepadAxisBuffer;

    static {
        System.loadLibrary("pojavexec");
        System.loadLibrary("glfw");
        initialize();
    }

    private GLFW() {}

    public static void setGrabListener(GrabListener listener) { grabListener = listener; }
    public static void setPositionCallback(PosCallback callback) { positionCallback = callback; }
    public static void setCursorCallback(CursorCallback callback) { cursorCallback = callback; }
    public static void setInitCallback(Runnable callback) { initCallback = callback; }
    public static boolean isGrabbing() { return grabbing; }

    @SuppressWarnings("unused")
    private static void receiveGrabState(boolean value) {
        grabbing = value;
        grabListener.onGrabState(value);
    }

    @SuppressWarnings("unused")
    private static void receiveCursorPos(double x, double y) { positionCallback.receive(x, y); }

    @SuppressWarnings("unused")
    private static GLFWCursor loadCursor(ByteBuffer bytes, int width, int height, int xhot, int yhot) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(bytes);
            return new GLFWCursor(bitmap, xhot, yhot);
        } catch (Throwable error) {
            Log.w("GLFW", "Failed to load cursor", error);
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static void useCursor(GLFWCursor cursor) { cursorCallback.onCursorUse(cursor); }

    @SuppressWarnings("unused")
    private static String getClipboardString() { return ""; }

    @SuppressWarnings("unused")
    private static void setClipboardString(String value) {}

    @SuppressWarnings("unused")
    private static void enableDirectGamepad(ByteBuffer buttons, ByteBuffer axes) {
        gamepadButtonBuffer = buttons.order(ByteOrder.nativeOrder());
        gamepadAxisBuffer = axes.order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    @SuppressWarnings("unused")
    public static void receiveInit() { initCallback.run(); }

    public static native void initialize();
    public static native void sendMousePosition0(double x, double y);
    public static native void sendKeyEvent(int glfwCode, int state, int mods);
    public static native boolean sendRawKeyEvent(int androidCode, int state, int mods, char codepoint);
    public static native void sendMouseEvent(int glfwMouseKey, int state, int mods);
    public static native void sendBulkUnicodeEvent(String input, int mods);
    public static native void sendScrollEvent(double xoffset, double yoffset);
    public static native void nativeSurfaceCreated(Surface surface);
    public static native void nativeSurfaceDestroyed();
    public static native void nativeSurfaceUpdated();
}
