package git.artdeell.mojoexec;

/** JNI bindings for the renderer loader used by MojoLauncher. */
public final class MojoExec {
    static {
        // Keep this binding paired with MojoLauncher's current split native stack.
        System.loadLibrary("mojoexec");
    }

    private MojoExec() {}

    public static native boolean prepareEgl(String eglPath, boolean useBypass, boolean useGles, int glesVersion);
    public static native void setDisplayParams(int width, int height, float hz);
    public static native void setUseTurnip(boolean enable);
    public static native void preloadVulkan();
    public static native void setNativeLibraryDir(String dir);
}
