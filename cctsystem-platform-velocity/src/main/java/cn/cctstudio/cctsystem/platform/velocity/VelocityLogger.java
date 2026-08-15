package cn.cctstudio.cctsystem.platform.velocity;

import cn.cctstudio.cctsystem.core.logging.CctLogger;
import java.util.Objects;
import org.slf4j.Logger;

final class VelocityLogger implements CctLogger {
    private final Logger logger;

    VelocityLogger(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warn(String message) {
        logger.warn(message);
    }

    @Override
    public void warn(String message, Throwable throwable) {
        logger.warn(message, throwable);
    }

    @Override
    public void error(String message, Throwable throwable) {
        logger.error(message, throwable);
    }
}
