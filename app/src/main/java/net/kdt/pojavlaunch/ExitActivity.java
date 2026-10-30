package net.kdt.pojavlaunch;

import android.content.Context;

import ru.cw.launcher.engine.CwLog;
import ru.cw.launcher.engine.PhoneVm;

/**
 * Called from libpojavexec nominal_exit. The native name and signature are fixed:
 * showExitMessage(Context, int, boolean).
 */
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
            PhoneVm.INSTANCE.mark(PhoneVm.Stage.EXITED, storedCode, text);
        } catch (Throwable ignored) {
        }
        // nominal_exit then loops forever, so the heartbeat would keep running
        // and the game process would stay stuck. Stop it after the reason is saved.
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
