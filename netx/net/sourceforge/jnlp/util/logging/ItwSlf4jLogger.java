/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 */
package net.sourceforge.jnlp.util.logging;

import net.sourceforge.jnlp.runtime.JNLPRuntime;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.helpers.MessageFormatter;

/**
 * SLF4J logger that writes to {@link OutputController}. Apache HttpClient 5
 * logs only when ITW debug is on, and never dumps exception stacks — a cipher
 * probe miss is not an error.
 */
public final class ItwSlf4jLogger extends MarkerIgnoringBase {

    private static final long serialVersionUID = 1L;

    private final boolean apacheHc;

    ItwSlf4jLogger(String name) {
        this.name = name;
        this.apacheHc = name != null && name.startsWith("org.apache.hc");
    }

    private static boolean debugOn() {
        try {
            return JNLPRuntime.isDebug();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isTraceEnabled() {
        return false;
    }

    @Override
    public boolean isDebugEnabled() {
        return debugOn();
    }

    @Override
    public boolean isInfoEnabled() {
        return debugOn();
    }

    @Override
    public boolean isWarnEnabled() {
        return debugOn();
    }

    @Override
    public boolean isErrorEnabled() {
        return debugOn();
    }

    @Override
    public void trace(String msg) {
    }

    @Override
    public void trace(String format, Object arg) {
    }

    @Override
    public void trace(String format, Object arg1, Object arg2) {
    }

    @Override
    public void trace(String format, Object... arguments) {
    }

    @Override
    public void trace(String msg, Throwable t) {
    }

    @Override
    public void debug(String msg) {
        emit(OutputController.Level.MESSAGE_DEBUG, msg, null);
    }

    @Override
    public void debug(String format, Object arg) {
        emit(OutputController.Level.MESSAGE_DEBUG, format, new Object[] { arg }, null);
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
        emit(OutputController.Level.MESSAGE_DEBUG, format, new Object[] { arg1, arg2 }, null);
    }

    @Override
    public void debug(String format, Object... arguments) {
        emit(OutputController.Level.MESSAGE_DEBUG, format, arguments, null);
    }

    @Override
    public void debug(String msg, Throwable t) {
        emit(OutputController.Level.MESSAGE_DEBUG, msg, t);
    }

    @Override
    public void info(String msg) {
        emit(OutputController.Level.MESSAGE_DEBUG, msg, null);
    }

    @Override
    public void info(String format, Object arg) {
        emit(OutputController.Level.MESSAGE_DEBUG, format, new Object[] { arg }, null);
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
        emit(OutputController.Level.MESSAGE_DEBUG, format, new Object[] { arg1, arg2 }, null);
    }

    @Override
    public void info(String format, Object... arguments) {
        emit(OutputController.Level.MESSAGE_DEBUG, format, arguments, null);
    }

    @Override
    public void info(String msg, Throwable t) {
        emit(OutputController.Level.MESSAGE_DEBUG, msg, t);
    }

    @Override
    public void warn(String msg) {
        emit(OutputController.Level.WARNING_DEBUG, msg, null);
    }

    @Override
    public void warn(String format, Object arg) {
        emit(OutputController.Level.WARNING_DEBUG, format, new Object[] { arg }, null);
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        emit(OutputController.Level.WARNING_DEBUG, format, new Object[] { arg1, arg2 }, null);
    }

    @Override
    public void warn(String format, Object... arguments) {
        emit(OutputController.Level.WARNING_DEBUG, format, arguments, null);
    }

    @Override
    public void warn(String msg, Throwable t) {
        emit(OutputController.Level.WARNING_DEBUG, msg, t);
    }

    @Override
    public void error(String msg) {
        emit(apacheHc ? OutputController.Level.MESSAGE_DEBUG : OutputController.Level.ERROR_DEBUG, msg, null);
    }

    @Override
    public void error(String format, Object arg) {
        emit(apacheHc ? OutputController.Level.MESSAGE_DEBUG : OutputController.Level.ERROR_DEBUG,
                format, new Object[] { arg }, null);
    }

    @Override
    public void error(String format, Object arg1, Object arg2) {
        emit(apacheHc ? OutputController.Level.MESSAGE_DEBUG : OutputController.Level.ERROR_DEBUG,
                format, new Object[] { arg1, arg2 }, null);
    }

    @Override
    public void error(String format, Object... arguments) {
        emit(apacheHc ? OutputController.Level.MESSAGE_DEBUG : OutputController.Level.ERROR_DEBUG,
                format, arguments, null);
    }

    @Override
    public void error(String msg, Throwable t) {
        emit(apacheHc ? OutputController.Level.MESSAGE_DEBUG : OutputController.Level.ERROR_DEBUG, msg, t);
    }

    private void emit(OutputController.Level level, String format, Object[] args, Throwable unused) {
        if (!debugOn()) {
            return;
        }
        org.slf4j.helpers.FormattingTuple tuple = MessageFormatter.arrayFormat(format, args);
        emit(level, tuple.getMessage(), tuple.getThrowable());
    }

    private void emit(OutputController.Level level, String msg, Throwable t) {
        if (!debugOn()) {
            return;
        }
        String text = msg != null ? msg : "";
        if (t != null) {
            String reason = t.getMessage();
            text = text + " (" + t.getClass().getSimpleName()
                    + (reason != null && !reason.isEmpty() ? ": " + reason : "") + ")";
        }
        try {
            OutputController.getLogger().log(level, name + ": " + text);
        } catch (Throwable ignored) {
        }
    }
}
