package git.artdeell.mojo.utils;

/** Native runtime utilities with the package and signatures expected by MojoLauncher. */
public final class JREUtils {
    static {
        System.loadLibrary("pojavexec");
        System.loadLibrary("pojavexec_awt");
    }

    private JREUtils() {}

    public static native int chdir(String path);
    public static native void setLdLibraryPath(String path);

    /** Redirects native stdout/stderr and disables libc buffering for crash-safe logs. */
    public static native boolean redirectStdio(String path);
}
