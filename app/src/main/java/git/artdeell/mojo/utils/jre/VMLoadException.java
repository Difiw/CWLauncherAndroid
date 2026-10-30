package git.artdeell.mojo.utils.jre;

public final class VMLoadException extends Exception {
    private final int loadStep;
    private final int errorCode;

    public VMLoadException(String message, int loadStep, int errorCode) {
        super(message);
        this.loadStep = loadStep;
        this.errorCode = errorCode;
    }

    @Override
    public String toString() {
        return "Ошибка запуска JVM MJLauncher (этап " + loadStep + ", код " + errorCode + "): " + getMessage();
    }
}
