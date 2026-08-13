/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 */
package org.slf4j.impl;

import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MarkerFactoryBinder;

/** SLF4J 1.7 marker binder — required next to {@link StaticLoggerBinder}. */
public final class StaticMarkerBinder implements MarkerFactoryBinder {

    public static final StaticMarkerBinder SINGLETON = new StaticMarkerBinder();

    private final IMarkerFactory factory = new BasicMarkerFactory();

    public static StaticMarkerBinder getSingleton() {
        return SINGLETON;
    }

    private StaticMarkerBinder() {
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return factory;
    }

    @Override
    public String getMarkerFactoryClassStr() {
        return BasicMarkerFactory.class.getName();
    }
}
