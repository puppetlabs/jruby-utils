package com.puppetlabs.jruby_utils.jruby;

import org.jruby.util.log.Logger;
import org.slf4j.LoggerFactory;

public class Slf4jLogger implements Logger {

    private final org.slf4j.Logger logger;

    public Slf4jLogger(String loggerName) {
        logger = LoggerFactory.getLogger("jruby." + loggerName);
    }

    @Override
    public String getName() {
        return logger.getName();
    }

    @Override
    public void warn(String message, Object... args) {
        logger.warn(message, args);
    }

    @Override
    public void warn(Throwable throwable) {
        logger.warn("", throwable);
    }

    @Override
    public void warn(String message, Throwable throwable) {
        logger.warn(message, throwable);
    }

    @Override
    public void error(String message, Object... args) {
        logger.error(message, args);
    }

    @Override
    public void error(Throwable throwable) {
        logger.error("", throwable);
    }

    @Override
    public void error(String message, Throwable throwable) {
        logger.error(message, throwable);
    }

    @Override
    public void info(String message, Object... args) {
        logger.info(message, args);
    }

    @Override
    public void info(Throwable throwable) {
        logger.info("", throwable);
    }

    @Override
    public void info(String message, Throwable throwable) {
        logger.info(message, throwable);
    }

    @Override
    public void debug(String message, Object... args) {
        logger.debug(message, args);
    }

    @Override
    public void debug(Throwable throwable) {
        logger.debug("", throwable);
    }

    @Override
    public void debug(String message, Throwable throwable) {
        logger.debug(message, throwable);
    }

    @Override
    public boolean isDebugEnabled() {
        return true;
    }

    @Override
    public void setDebugEnable(boolean b) {
        warn("setDebugEnable not implemented", null, null);
    }
}
