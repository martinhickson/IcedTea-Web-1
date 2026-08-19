/* CacheLRUWrapper -- Handle LRU for cache files.
   Copyright (C) 2011 Red Hat, Inc.
   Copyright (C) 2026 IcedTea-Web contributors

This file is part of IcedTea.

IcedTea is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 2.

IcedTea is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with IcedTea; see the file COPYING.  If not, write to
the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
02110-1301 USA.

Linking this library statically or dynamically with other modules is
making a combined work based on this library.  Thus, the terms and
conditions of the GNU General Public License cover the whole
combination.

As a special exception, the copyright holders of this library give you
permission to link this library with independent modules to produce an
executable, regardless of the license terms of these independent
modules, and to copy and distribute the resulting executable under
terms of your choice, provided that you also meet, for each linked
independent module, the terms and conditions of the license of that
module.  An independent module is a module which is not derived from
or based on this library.  If you modify this library, you may extend
this exception to your version of the library, but you are not
obligated to do so.  If you do not wish to do so, delete this
exception statement from your version.
 */
package net.sourceforge.jnlp.cache;

import static net.sourceforge.jnlp.runtime.Translator.R;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.InfrastructureFileDescriptor;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * This class helps maintain the ordering of most recently use items across
 * multiple jvm instances.
 */
public class CacheLRUWrapper {

    /** Under the configured cache root: {@code cache/db/}. */
    public static final String SQLITE_NEST_DIR_NAME = "cache";
    public static final String DB_CACHE_DIR_NAME = "db";

    private final InfrastructureFileDescriptor cacheDir;
    private final InfrastructureFileDescriptor recentlyUsedPropertiesFile;
    private final CacheCatalog catalog;
    private final boolean sqliteMode;
    private final File windowsShortcutList;

    public CacheLRUWrapper() {
        this(isSqliteCatalogEnabled(), null, null);
    }

    /**
     * testing constructor — legacy properties catalog.
     *
     * @param recentlyUsed file to be used as recently_used file
     * @param cacheDir dir with cache
     */
    CacheLRUWrapper(final InfrastructureFileDescriptor recentlyUsed, final InfrastructureFileDescriptor cacheDir) {
        this(false, recentlyUsed, cacheDir);
    }

    /**
     * testing constructor with explicit backend selection.
     */
    CacheLRUWrapper(boolean useSqlite, final InfrastructureFileDescriptor recentlyUsed,
            final InfrastructureFileDescriptor cacheDir) {
        if (useSqlite) {
            InfrastructureFileDescriptor parent = cacheDir != null ? cacheDir : PathsAndFiles.CACHE_DIR;
            this.cacheDir = dbDirDescriptor(parent);
            ensureDir(this.cacheDir.getFile());
            File marker = new File(this.cacheDir.getFile(), SqliteCacheCatalog.FAILED_MARKER);
            if (marker.isFile()) {
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                        "sqlite catalog sticky-fail marker present; using properties under cache/db/");
                this.sqliteMode = false;
                this.recentlyUsedPropertiesFile = recentlyUsedUnder(this.cacheDir);
                this.catalog = new PropertiesCacheCatalog(this.recentlyUsedPropertiesFile);
            } else {
                this.sqliteMode = true;
                this.recentlyUsedPropertiesFile = recentlyUsed; // unused in sqlite mode
                this.catalog = new SqliteCacheCatalog(this.cacheDir.getFile());
            }
        } else {
            this.sqliteMode = false;
            this.cacheDir = cacheDir != null ? cacheDir : PathsAndFiles.CACHE_DIR;
            this.recentlyUsedPropertiesFile = recentlyUsed != null ? recentlyUsed : PathsAndFiles.getRecentlyUsedFile();
            this.catalog = new PropertiesCacheCatalog(this.recentlyUsedPropertiesFile);
        }
        // Keep Windows shortcuts at the configured user cache root (parent of cache/db/ when sqlite).
        InfrastructureFileDescriptor shortcutRoot = useSqlite
                ? (cacheDir != null ? cacheDir : PathsAndFiles.CACHE_DIR)
                : this.cacheDir;
        windowsShortcutList = new File(shortcutRoot.getFile(), "shortcutList.txt");
    }

    /**
     * Integration-test factory: sqlite catalog roots at {@code parentCache/cache/db}
     * (or {@code parentCache/db} when {@code parentCache} is named {@code cache});
     * legacy uses {@code parentCache/recently_used} under the same parent.
     */
    public static CacheLRUWrapper createForTests(boolean useSqlite, File parentCache) {
        InfrastructureFileDescriptor parent = new InfrastructureFileDescriptor() {
            @Override
            public File getFile() {
                return parentCache;
            }

            @Override
            public String getFullPath() {
                return parentCache.getAbsolutePath();
            }
        };
        if (useSqlite) {
            return new CacheLRUWrapper(true, null, parent);
        }
        InfrastructureFileDescriptor recentlyUsed = new InfrastructureFileDescriptor() {
            @Override
            public File getFile() {
                return new File(parentCache, PathsAndFiles.CACHE_INDEX_FILE_NAME);
            }

            @Override
            public String getFullPath() {
                return getFile().getAbsolutePath();
            }
        };
        return new CacheLRUWrapper(false, recentlyUsed, parent);
    }

    /**
     * Absolute path to {@code cache_catalog.sqlite} when in sqlite mode; otherwise null.
     */
    public File getSqliteCatalogFile() {
        if (!sqliteMode || !(catalog instanceof SqliteCacheCatalog)) {
            return null;
        }
        return ((SqliteCacheCatalog) catalog).getDbFile();
    }

    int sqlitePragmaSynchronous() throws java.sql.SQLException {
        if (!(catalog instanceof SqliteCacheCatalog)) {
            throw new IllegalStateException("not sqlite catalog");
        }
        return ((SqliteCacheCatalog) catalog).pragmaSynchronous();
    }

    String sqliteExplainFindEntriesPlan() throws java.sql.SQLException {
        if (!(catalog instanceof SqliteCacheCatalog)) {
            throw new IllegalStateException("not sqlite catalog");
        }
        return ((SqliteCacheCatalog) catalog).explainFindEntriesPlan();
    }

    static boolean isSqliteCatalogEnabled() {
        try {
            String v = JNLPRuntime.getConfiguration().getProperty(DeploymentConfiguration.KEY_CACHE_CATALOG_SQLITE);
            if (v == null || v.trim().isEmpty()) {
                return true;
            }
            return Boolean.parseBoolean(v.trim());
        } catch (Exception e) {
            return true;
        }
    }

    private static void ensureDir(File dir) {
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                    "Unable to create cache directory: " + dir);
        }
    }

    /**
     * {@code {cachedir}/cache/db}, or {@code {cachedir}/db} when {@code cachedir}
     * already ends with the path segment {@code cache}.
     */
    static File sqliteCacheRoot(File parentCache) {
        if (parentCache == null) {
            return new File(SQLITE_NEST_DIR_NAME, DB_CACHE_DIR_NAME);
        }
        File nest = SQLITE_NEST_DIR_NAME.equals(parentCache.getName())
                ? parentCache
                : new File(parentCache, SQLITE_NEST_DIR_NAME);
        return new File(nest, DB_CACHE_DIR_NAME);
    }

    static InfrastructureFileDescriptor dbDirDescriptor(final InfrastructureFileDescriptor parentCache) {
        return new InfrastructureFileDescriptor() {
            @Override
            public File getFile() {
                return sqliteCacheRoot(parentCache.getFile());
            }

            @Override
            public String getFullPath() {
                return getFile().getAbsolutePath();
            }
        };
    }

    static InfrastructureFileDescriptor recentlyUsedUnder(final InfrastructureFileDescriptor dir) {
        return new InfrastructureFileDescriptor() {
            @Override
            public File getFile() {
                return new File(dir.getFile(), PathsAndFiles.CACHE_INDEX_FILE_NAME);
            }

            @Override
            public String getFullPath() {
                return getFile().getAbsolutePath();
            }
        };
    }

    /**
     * Returns an instance of the policy.
     *
     * @return an instance of the policy
     */
    public static CacheLRUWrapper getInstance() {
        return CacheLRUWrapperHolder.INSTANCE;
    }

    private static class CacheLRUWrapperHolder {
        private static final CacheLRUWrapper INSTANCE = new CacheLRUWrapper();
    }

    /**
     * @return the cacheDir (legacy root or {@code cachedir/cache/db})
     */
    public InfrastructureFileDescriptor getCacheDir() {
        return cacheDir;
    }

    public boolean isSqliteMode() {
        return sqliteMode;
    }

    public File getWindowsShortcutList() {
        return windowsShortcutList;
    }

    /**
     * @return the recentlyUsedFile (legacy only; may be null-ish unused in sqlite mode)
     */
    public InfrastructureFileDescriptor getRecentlyUsedFile() {
        return recentlyUsedPropertiesFile;
    }

    public synchronized void load() {
        boolean repaired = catalog.load();
        if (repaired) {
            OutputController.getLogger().log(new LruCacheException());
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, R("CFakeCache"));
            store();
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, R("CFakedCache"));
        }
        if (!sqliteMode) {
            // Properties backend does not know cache root; drop paths outside it.
            List<Entry<String, String>> snapshot = new ArrayList<>(catalog.getLRUSortedEntries());
            boolean modified = false;
            for (Entry<String, String> e : snapshot) {
                if (!isPathUnderCacheDir(e.getValue())) {
                    catalog.removeEntry(e.getKey());
                    modified = true;
                }
            }
            if (modified) {
                OutputController.getLogger().log(new LruCacheException());
                OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, R("CFakeCache"));
                store();
                OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, R("CFakedCache"));
            }
        }
    }

    /**
     * True if {@code path} is the cache directory or a file beneath it.
     */
    static boolean isPathUnderCacheDir(String path, String cacheDirPath) {
        if (path == null || path.isEmpty() || cacheDirPath == null || cacheDirPath.isEmpty()) {
            return false;
        }
        final String normalizedPathInput = path.replace('\\', '/');
        final String normalizedRootInput = cacheDirPath.replace('\\', '/');
        try {
            Path cache = Paths.get(normalizedRootInput).toAbsolutePath().normalize();
            Path candidate = Paths.get(normalizedPathInput).toAbsolutePath().normalize();
            return candidate.startsWith(cache);
        } catch (Exception ex) {
            String normalizedPath = normalizedPathInput.replace('/', File.separatorChar);
            String normalizedRoot = normalizedRootInput.replace('/', File.separatorChar);
            while (normalizedPath.contains(File.separator + File.separator)) {
                normalizedPath = normalizedPath.replace(File.separator + File.separator, File.separator);
            }
            while (normalizedRoot.contains(File.separator + File.separator)) {
                normalizedRoot = normalizedRoot.replace(File.separator + File.separator, File.separator);
            }
            return normalizedPath.equalsIgnoreCase(normalizedRoot)
                    || normalizedPath.regionMatches(true, 0, normalizedRoot + File.separator, 0,
                            normalizedRoot.length() + 1);
        }
    }

    private boolean isPathUnderCacheDir(String path) {
        return isPathUnderCacheDir(path, getCacheDir().getFullPath());
    }

    public synchronized boolean store() {
        return catalog.store();
    }

    public synchronized boolean addEntry(String key, String path) {
        return catalog.addEntry(key, path);
    }

    public synchronized boolean removeEntry(String key) {
        return catalog.removeEntry(key);
    }

    public synchronized boolean removeByPath(String path) {
        return catalog.removeByPath(path);
    }

    public synchronized boolean updateEntry(String oldKey) {
        return catalog.updateEntry(oldKey, getCacheDir().getFullPath());
    }

    public synchronized List<Entry<String, String>> getLRUSortedEntries() {
        return catalog.getLRUSortedEntries();
    }

    /**
     * Newest-first entries matching the URL-shaped path (after folder id).
     */
    public synchronized List<Entry<String, String>> findEntriesByUrlPath(String urlPath) {
        return catalog.findEntriesByUrlPath(urlPath, getCacheDir().getFullPath());
    }

    /**
     * Acquire the catalog lock. Not {@code synchronized}: a synchronized
     * {@code lock()} that then holds a {@link java.util.concurrent.locks.ReentrantLock}
     * after returning deadlocks with another thread in {@code lock()} (monitor vs
     * ReentrantLock inversion). Callers that need both should take this lock first.
     */
    public void lock() {
        catalog.lock();
    }

    public void unlock() {
        catalog.unlock();
    }

    /** Package-private for unit tests. */
    boolean tryLock() {
        return catalog.tryLock();
    }

    /** Package-private for unit tests. */
    boolean isCatalogHeldByCurrentThread() {
        return catalog.isHeldByCurrentThread();
    }

    public synchronized String getValue(String key) {
        return catalog.getValue(key);
    }

    public synchronized boolean containsKey(String key) {
        return catalog.containsKey(key);
    }

    public synchronized boolean containsValue(String value) {
        return catalog.containsValue(value);
    }

    public String generateKey(String path) {
        return catalog.generateKey(path, getCacheDir().getFullPath());
    }

    /**
     * Next unused numbered folder under the cache root (sqlite: {@code MAX(folder_id)+1}
     * plus filesystem confirm).
     */
    public int nextFolderId() {
        return catalog.nextFolderId(getCacheDir().getFile());
    }

    void clearLRUSortedEntries() {
        catalog.clear();
    }

    /** Close catalog resources (SQLite connection). Safe to call more than once. */
    public void close() {
        catalog.close();
    }

    public CacheEntryMeta getMetaByPath(String path) {
        return catalog.getMetaByPath(path);
    }

    public void putMeta(CacheEntryMeta meta) {
        catalog.putMeta(meta);
    }

    public List<CacheEntryMeta> listAllMeta() {
        return catalog.listAllMeta();
    }

    List<CacheCleanupRow> listMarkedForDelete() {
        return catalog.listMarkedForDelete();
    }

    List<CacheCleanupRow> listUnmarkedLruNewestFirst() {
        return catalog.listUnmarkedLruNewestFirst();
    }

    public void registerRunningApp(int pid, String jnlpPath, String processStart) {
        catalog.registerRunningApp(pid, jnlpPath, processStart);
    }

    public void unregisterRunningApp(int pid) {
        catalog.unregisterRunningApp(pid);
    }

    public List<CacheRunningApp> listRunningApps() {
        return catalog.listRunningApps();
    }

    /** {@code {cache/db}/native} — sqlite-jdbc extract and app natives. */
    public File nativeStoreDir() {
        return new File(getCacheDir().getFile(), "native");
    }

    /** Per-jar extract dir under {@code native/jars/}. */
    public File jarNativeExtractDir(File jar) {
        String key;
        try {
            key = CacheUtil.hex(jar.getAbsolutePath(), jar.getName());
        } catch (Exception e) {
            key = Integer.toHexString(jar.getAbsolutePath().hashCode());
        }
        return new File(new File(nativeStoreDir(), "jars"), key);
    }

    public void putNativeLib(String libName, String jarPath, String extractPath) {
        catalog.putNativeLib(libName, jarPath, extractPath);
    }

    public String findNativeLib(String libName) {
        return catalog.findNativeLib(libName);
    }

    public void removeNativeLibsByJarPath(String jarPath) {
        catalog.removeNativeLibsByJarPath(jarPath);
    }
}
