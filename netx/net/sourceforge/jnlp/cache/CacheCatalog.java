/*
 Copyright (C) 2026 IcedTea-Web contributors

 This file is part of IcedTea-Web.
*/
package net.sourceforge.jnlp.cache;

import java.util.List;
import java.util.Map.Entry;

/**
 * Backend for {@link CacheLRUWrapper}: either legacy {@code recently_used}
 * properties or SQLite under {@code {cachedir}/db/}.
 */
interface CacheCatalog {

    void lock();

    void unlock();

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

    /** Release resources (e.g. JDBC connection). No-op for properties backend. */
    void close();
}
