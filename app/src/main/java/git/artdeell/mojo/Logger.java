package git.artdeell.mojo;

/** JNI logging contract shipped with libpojavexec. */
public final class Logger {
    private Logger() {}

    public static native void appendToLog(String text);
    public static native void begin(String logFilePath);
    public static native void setLogListener(eventLogListener listener);

    @SuppressWarnings("checkstyle:TypeName")
    public interface eventLogListener {
        void onEventLogged(String text);
    }
}
