/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 */
package net.sourceforge.jnlp.util.logging;

import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

/**
 * Cached SLF4J factory so Apache HttpClient 5 does not fall back to the NOP
 * binder (which prints to stderr and ITW then records as {@code ERROR_ALL}).
 * The uber jar relocates {@code org.slf4j} so this factory is not visible to
 * JNLP applications.
 */
public final class ItwSlf4jLoggerFactory implements ILoggerFactory {

    public static final ItwSlf4jLoggerFactory INSTANCE = new ItwSlf4jLoggerFactory();

    private final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();

    private ItwSlf4jLoggerFactory() {
    }

    @Override
    public Logger getLogger(String name) {
        String key = name != null ? name : "unknown";
        Logger existing = loggers.get(key);
        if (existing != null) {
            return existing;
        }
        Logger created = new ItwSlf4jLogger(key);
        Logger raced = loggers.putIfAbsent(key, created);
        return raced != null ? raced : created;
    }
}
