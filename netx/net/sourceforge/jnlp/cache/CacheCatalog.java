/*
 Copyright (C) 2026 IcedTea-Web contributors

 This file is part of IcedTea-Web.
*/
package net.sourceforge.jnlp.cache;

import java.io.File;
import java.util.List;
import java.util.Map.Entry;

/**
 * Backend for {@link CacheLRUWrapper}: either legacy {@code recently_used}
 * properties or SQLite under {@code {cachedir}/cache/db/}
 * ({@code {cachedir}/db/} when {@code cachedir} already ends with {@code cache}).
 */
interface CacheCatalog {

    void lock();

    void unlock();

    /**
     * Non-blocking attempt. Must not take the wrapper monitor in a way that
     * nests with {@link #lock()} (see {@code CacheLRUWrapper} lock-order).
     */
    boolean tryLock();

    boolean isHeldByCurrentThread();

    /**
     * Reload / validate catalog state. Returns true if corrupt entries were repaired.
     */
    boolean load();

    /**
     * Persist pending changes when the backend buffers them.
     *
     * @return true if a store was performed (or not needed because already durable)
     */
    boolean store();

    boolean addEntry(String key, String path);

    boolean removeEntry(String key);

    /** Delete every catalog row whose stored path is {@code path}. */
    boolean removeByPath(String path);

    boolean updateEntry(String oldKey, String cacheDirPath);

    List<Entry<String, String>> getLRUSortedEntries();

    /**
     * Entries whose path (after folder id) matches {@code urlPath}, newest first.
     * Used for indexed lookup; properties backend filters the sorted list.
     */
    List<Entry<String, String>> findEntriesByUrlPath(String urlPath, String cacheDirPath);

    String getValue(String key);

    boolean containsKey(String key);

    boolean containsValue(String value);

    void clear();

    String generateKey(String path, String cacheDirPath);

    /**
     * Next unused numbered cache folder under {@code cacheDir}.
     * SQLite uses {@code MAX(folder_id)+1} then {@code mkdir} to claim the directory
     * (atomic vs a second JVM).
     */
    int nextFolderId(File cacheDir);

    /**
     * Claim the next numbered directory with {@code mkdir} so two processes cannot
     * both observe {@code !exists} and allocate the same id.
     */
    static int claimFolderId(File cacheDir, int startInclusive) {
        if (cacheDir != null && !cacheDir.isDirectory()) {
            cacheDir.mkdirs();
        }
        int candidate = startInclusive < 0 ? 0 : startInclusive;
        while (true) {
            File dir = new File(cacheDir, Integer.toString(candidate));
            if (dir.mkdir()) {
                return candidate;
            }
            if (candidate == Integer.MAX_VALUE) {
                throw new IllegalStateException("cache folder id overflow under " + cacheDir);
            }
            candidate++;
        }
    }

    /** Release resources (e.g. JDBC connection). No-op for properties backend. */
    void close();

    /**
     * Metadata for the catalog row at {@code path}, or {@code null} if unknown.
     * Not a sidecar {@code .info} file.
     */
    CacheEntryMeta getMetaByPath(String path);

    /** Persist metadata on the catalog row for {@code meta.path}. */
    void putMeta(CacheEntryMeta meta);

    /** All catalog rows with metadata (for {@code -Xclearcache} / list-ids). */
    java.util.List<CacheEntryMeta> listAllMeta();

    /** Record a live JNLP JVM so another process can refuse to clear its files. */
    void registerRunningApp(int pid, String jnlpPath, String processStart);

    void unregisterRunningApp(int pid);

    /**
     * Live {@code running_app} leases. Throws if the catalog cannot be read;
     * callers must not treat that as "no apps running".
     */
    java.util.List<CacheRunningApp> listRunningApps();

    /**
     * Index an extracted native library for O(1) lookup by file name
     * ({@code foo.dll} / {@code libfoo.so}).
     */
    void putNativeLib(String libName, String jarPath, String extractPath);

    /** Newest extract path for {@code libName}, or {@code null}. */
    String findNativeLib(String libName);

    /** Drop native index rows for a cache jar path (jar removed). */
    void removeNativeLibsByJarPath(String jarPath);
}
