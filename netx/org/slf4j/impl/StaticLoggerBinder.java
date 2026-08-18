/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 */
package org.slf4j.impl;

import net.sourceforge.jnlp.util.logging.ItwSlf4jLoggerFactory;
import org.slf4j.ILoggerFactory;
import org.slf4j.spi.LoggerFactoryBinder;

/**
 * SLF4J 1.7 binder. Must live in {@code org.slf4j.impl} so LoggerFactory finds
 * it instead of printing the NOP / StaticLoggerBinder warning to stderr.
 * The uber jar relocates {@code org.slf4j} so JNLP apps do not see this binder.
 */
public final class StaticLoggerBinder implements LoggerFactoryBinder {

    public static String REQUESTED_API_VERSION = "1.6.99";

    private static final StaticLoggerBinder SINGLETON = new StaticLoggerBinder();

    public static StaticLoggerBinder getSingleton() {
        return SINGLETON;
    }

    private StaticLoggerBinder() {
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return ItwSlf4jLoggerFactory.INSTANCE;
    }

    @Override
    public String getLoggerFactoryClassStr() {
        return ItwSlf4jLoggerFactory.class.getName();
    }
}
