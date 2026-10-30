package git.artdeell.mojo;

import android.content.Context;

import ru.cw.launcher.engine.CwLog;
import ru.cw.launcher.engine.PhoneVm;

/** Exit callback expected by the current MJLauncher native loader. */
public final class ExitActivity {
    private ExitActivity() {}

    public static void showExitMessage(Context context, int code, boolean isSignal) {
        boolean unknownSignal = isSignal && code == 0;
        int storedCode = unknownSignal ? PhoneVm.UNKNOWN_EXIT_CODE : code;
        String text = isSignal
                ? (unknownSignal ? "JVM завершилась из-за фатальной native-ошибки (номер сигнала не передан)" : "Игра остановилась по сигналу " + code)
                : "Игра завершилась с кодом " + code;
        System.out.println("CW: " + text);
        System.out.flush();
        CwLog.INSTANCE.warn(text);
        try {
            // A clean exit after the window was shown is not an error; the launcher
            // still reports "no window" when the game ended before GLFW started.
            boolean clean = !isSignal && code == 0;
            PhoneVm.INSTANCE.mark(PhoneVm.Stage.EXITED, storedCode, clean ? "" : text);
        } catch (Throwable ignored) {
        }
        PhoneVm.INSTANCE.returnToLauncher(context.getApplicationContext());
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
