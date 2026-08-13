/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 */
package org.slf4j.impl;

import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;

/** SLF4J 1.7 MDC binder — no-op, required next to {@link StaticLoggerBinder}. */
public final class StaticMDCBinder {

    public static final StaticMDCBinder SINGLETON = new StaticMDCBinder();

    public static StaticMDCBinder getSingleton() {
        return SINGLETON;
    }

    private StaticMDCBinder() {
    }

    public MDCAdapter getMDCA() {
        return new NOPMDCAdapter();
    }

    public String getMDCAdapterClassStr() {
        return NOPMDCAdapter.class.getName();
    }
}
