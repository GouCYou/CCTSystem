package cn.cctstudio.cctsystem.core.logging;

public interface CctLogger {
    void info(String message);

    void warn(String message);

    void warn(String message, Throwable throwable);

    void error(String message, Throwable throwable);
}
