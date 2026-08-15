package cn.cctstudio.cctsystem.platform.paper;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

final class PaperLogger implements CctLogger {
    private final Logger logger;

    PaperLogger(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warning(message);
    }

    @Override
    public void warn(String message, Throwable throwable) {
        logger.log(Level.WARNING, message, throwable);
    }

    @Override
    public void error(String message, Throwable throwable) {
        logger.log(Level.SEVERE, message, throwable);
    }
}
