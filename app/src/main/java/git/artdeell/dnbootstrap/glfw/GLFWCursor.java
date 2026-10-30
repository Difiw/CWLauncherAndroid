package git.artdeell.dnbootstrap.glfw;

import android.graphics.Bitmap;

public final class GLFWCursor {
    private final Bitmap bitmap;
    private final int xhot;
    private final int yhot;

    public GLFWCursor(Bitmap bitmap, int xhot, int yhot) {
        this.bitmap = bitmap;
        this.xhot = xhot;
        this.yhot = yhot;
    }

    public Bitmap getBitmap() { return bitmap; }
    public int getXhot() { return xhot; }
    public int getYhot() { return yhot; }
}
