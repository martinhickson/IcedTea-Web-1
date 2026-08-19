/*
 Copyright (C) 2026 IcedTea-Web contributors

 This file is part of IcedTea-Web.
*/
package net.sourceforge.jnlp.cache;

/**
 * One catalog row for cache cleanup. Sqlite returns these from a single
 * {@code SELECT}; the properties backend builds the same list in process.
 */
final class CacheCleanupRow {

    final String lruKey;
    final String path;
    final Long contentLength;

    CacheCleanupRow(String lruKey, String path) {
        this(lruKey, path, null);
    }

    CacheCleanupRow(String lruKey, String path, Long contentLength) {
        this.lruKey = lruKey;
        this.path = path;
        this.contentLength = contentLength;
    }
}
