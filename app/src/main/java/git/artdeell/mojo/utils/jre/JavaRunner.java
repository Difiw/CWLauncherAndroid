package git.artdeell.mojo.utils.jre;

import android.content.Context;

/** JNI contract used by the current MJLauncher v3_openjdk JVM loader. */
public final class JavaRunner {
    static {
        System.loadLibrary("pojavexec");
    }

    private JavaRunner() {}

    public static native long nativeTransferArguments(String[] args);

    public static native boolean nativeLoadJVM(
            String vmPath,
            long javaArgs,
            String mainClass,
            long appArgs,
            boolean hasJavaAgents
    ) throws VMLoadException;

    public static native void nativeSetupExit(Context context);
}
