package net.kdt.pojavlaunch.utils;

import android.content.Context;
import java.util.Locale;

/** Native bridge used to hand the phone screen to the game Java machine. */
public final class JREUtils {
    private JREUtils() {}

    public static native void setupBridgeWindow(Object surface);

    public static native void releaseBridgeWindow();

    public static native void setLdLibraryPath(String path);

    public static native boolean dlopen(String path);

    public static native int chdir(String path);

    public static native void setupExitMethod(Context context);

    public static native void initializeHooks();

    /**
     * Pojav, Amethyst and Glow-Worm do this before JLI_Launch.
     * setupExitMethod stores the Android Java machine for nominal_exit.
     * Without it a caught signal dereferences a null machine and dies as SIGSEGV.
     * initializeHooks remembers the real exit code and reports it from atexit.
     */
    public static void prepareExit(Context context) {
        // The machine pointer must be stored before launchJVM. Its signal waiter
        // calls nominal_exit, and that function crashes if the pointer is empty.
        // Amethyst and Pojav call setupExitMethod, then initializeHooks.
        setupExitMethod(context.getApplicationContext());
        try {
            System.loadLibrary("exithook");
        } catch (UnsatisfiedLinkError error) {
            String text = error.getMessage() == null ? "" : error.getMessage();
            if (!text.toLowerCase(Locale.ROOT).contains("already")) throw error;
        }
        // Pojav's JREUtils loads its AWT bridge with the execution bridge.
        // It is packaged in CWLauncher too, but was previously never loaded.
        try {
            System.loadLibrary("pojavexec_awt");
        } catch (UnsatisfiedLinkError error) {
            String text = error.getMessage() == null ? "" : error.getMessage();
            if (!text.toLowerCase(Locale.ROOT).contains("already")) throw error;
        }
        initializeHooks();
        // A zero exit code skips showExitMessage, and nominal_exit then spins.
        // Halt still ends the process, including that path.
        if (!haltArmed) {
            haltArmed = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (ru.cw.launcher.engine.PhoneVm.INSTANCE.stage()
                            == ru.cw.launcher.engine.PhoneVm.Stage.RUNNING) {
                        ru.cw.launcher.engine.PhoneVm.INSTANCE.mark(
                                ru.cw.launcher.engine.PhoneVm.Stage.EXITED, 0, "");
                    }
                } catch (Throwable ignored) {
                }
                android.os.Process.killProcess(android.os.Process.myPid());
            }, "cw-exit"));
        }
    }

    private static boolean haltArmed;
}
