/*
 * Copyright (C) 2026 IcedTea contributors
 *
 * This file is part of IcedTea-Web.
 *
 * IcedTea-Web is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2, or (at your option)
 * any later version.
 */

package net.sourceforge.jnlp;

import java.io.File;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.sourceforge.jnlp.util.UrlUtils;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Process-lifetime cache of fully parsed on-disk {@link JNLPFile} templates.
 * Keyed by normalized filesystem path only (parser settings are fixed for a
 * {@code javaws} process). Callers always receive a copy; the cached instance
 * stays pristine.
 */
final class JnlpFileParseCache {

    private static final ConcurrentHashMap<String, JNLPFile> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger HITS = new AtomicInteger();
    private static final AtomicInteger MISSES = new AtomicInteger();
    private static final AtomicInteger STORES = new AtomicInteger();

    private JnlpFileParseCache() {
    }

    static String keyFor(URL location) {
        if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
            return null;
        }
        try {
            File file = UrlUtils.decodeUrlAsFile(location);
            if (file.exists()) {
                return file.getCanonicalFile().getAbsolutePath();
            }
            return file.getAbsolutePath();
        } catch (Exception e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, e);
            return location.toExternalForm();
        }
    }

    static JNLPFile get(String key) {
        if (key == null) {
            return null;
        }
        JNLPFile cached = CACHE.get(key);
        if (cached != null) {
            HITS.incrementAndGet();
        } else {
            MISSES.incrementAndGet();
        }
        return cached;
    }

    static void put(String key, JNLPFile pristineTemplate) {
        if (key == null || pristineTemplate == null) {
            return;
        }
        CACHE.put(key, pristineTemplate);
        STORES.incrementAndGet();
    }

    /** Test support. */
    static void clear() {
        CACHE.clear();
        HITS.set(0);
        MISSES.set(0);
        STORES.set(0);
    }

    /** Test support. */
    static int size() {
        return CACHE.size();
    }

    /** Test support. */
    static int hits() {
        return HITS.get();
    }

    /** Test support. */
    static int misses() {
        return MISSES.get();
    }

    /** Test support. */
    static int stores() {
        return STORES.get();
    }
}
