package ru.cw.boot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Small diagnostic main wrapper used by the native JNI launcher.
 *
 * Do not initialize GLFW here.  CW's patched GLFW class already loads
 * libpojavexec, binds its SharedLibrary and calls
 * nativeInitializeGLFWNativeBridge() from its own static initializer.  Doing
 * any of that a second time corrupts the native bridge before Minecraft opens
 * its window.
 */
public final class CwMain {
    private CwMain() {}

    public static void main(String[] args) throws Throwable {
        PrintStream log = openLog();
        log.println("CW: загрузочный класс Java запущен");
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            log.println("CW: необработанная ошибка в потоке " + thread.getName());
            error.printStackTrace(log);
            log.flush();
        });

        String target = System.getProperty("cw.main", "").trim();
        if (target.isEmpty()) {
            fail(log, new IllegalStateException("Не указан главный класс Minecraft"));
            return;
        }

        log.println("CW: запускаю настоящий main: " + target);
        log.flush();
        try {
            Class<?> targetClass = Class.forName(target, true, CwMain.class.getClassLoader());
            Method main = targetClass.getMethod("main", String[].class);
            main.invoke(null, (Object) args);
            log.println("CW: main Minecraft штатно завершился");
            log.flush();
        } catch (InvocationTargetException e) {
            fail(log, e.getCause() == null ? e : e.getCause());
        } catch (Throwable error) {
            fail(log, error);
        }
    }

    private static PrintStream openLog() throws Exception {
        File file = new File(System.getProperty("cw.jvm.log", "jvm.log"));
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        return new PrintStream(new FileOutputStream(file, true), true, "UTF-8");
    }

    private static void fail(PrintStream log, Throwable error) throws Throwable {
        log.println("CW: Minecraft завершился с ошибкой");
        error.printStackTrace(log);
        log.flush();
        throw error;
    }
}
