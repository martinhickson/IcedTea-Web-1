/*
 Copyright (C) 2026 IcedTea-Web contributors

 This file is part of IcedTea-Web.
*/
package net.sourceforge.jnlp.cache;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.locks.ReentrantLock;

import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * SQLite-backed cache catalog under {@code {cachedir}/db/cache_catalog.sqlite}.
 * Uses WAL and {@code busy_timeout} for multi-process wait/retry.
 */
final class SqliteCacheCatalog implements CacheCatalog {

    static final String DB_FILE_NAME = "cache_catalog.sqlite";
    private static final int BUSY_TIMEOUT_MS = 5000;

    private final File dbFile;
    private final ReentrantLock threadLock = new ReentrantLock();
    private Connection connection;
    private String openPath;

    SqliteCacheCatalog(File dbDirectory) {
        if (!dbDirectory.exists() && !dbDirectory.mkdirs()) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                    "Unable to create sqlite cache dir: " + dbDirectory);
        }
        this.dbFile = new File(dbDirectory, DB_FILE_NAME);
    }

    File getDbFile() {
        return dbFile;
    }

    private Connection conn() throws SQLException {
        String path = dbFile.getAbsolutePath();
        if (connection != null && !connection.isClosed() && path.equals(openPath)) {
            return connection;
        }
        closeQuietly();
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc driver missing", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        openPath = path;
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM schema_version")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    st.executeUpdate("INSERT INTO schema_version(version) VALUES (1)");
                }
            }
            st.execute("CREATE TABLE IF NOT EXISTS cache_entry ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "lru_key TEXT NOT NULL UNIQUE,"
                    + "resource_url TEXT NOT NULL,"
                    + "path TEXT NOT NULL UNIQUE,"
                    + "folder_id INTEGER NOT NULL,"
                    + "last_access INTEGER NOT NULL,"
                    + "state TEXT NOT NULL DEFAULT 'ready',"
                    + "created_at INTEGER NOT NULL"
                    + ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_cache_entry_url_access "
                    + "ON cache_entry (resource_url, last_access DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_cache_entry_access "
                    + "ON cache_entry (last_access ASC)");
        }
        return connection;
    }

    private void closeQuietly() {
        if (connection != null) {
            try {
                try (Statement st = connection.createStatement()) {
                    // Release WAL/SHM handles on Windows so tests can delete the temp dir.
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException e) {
                    OutputController.getLogger().log(e);
                }
                connection.close();
            } catch (SQLException e) {
                OutputController.getLogger().log(e);
            }
            connection = null;
            openPath = null;
        }
    }

    @Override
    public void lock() {
        threadLock.lock();
    }

    @Override
    public boolean tryLock() {
        return threadLock.tryLock();
    }

    @Override
    public void unlock() {
        if (threadLock.isHeldByCurrentThread()) {
            threadLock.unlock();
        }
    }

    @Override
    public boolean isHeldByCurrentThread() {
        return threadLock.isHeldByCurrentThread();
    }

    @Override
    public boolean load() {
        try {
            conn();
            return false;
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return false;
        }
    }

    @Override
    public boolean store() {
        // Mutations commit immediately.
        return isHeldByCurrentThread();
    }

    @Override
    public boolean addEntry(String key, String path) {
        try {
            Connection c = conn();
            long now = System.currentTimeMillis();
            long lastAccess = parseLastAccess(key, now);
            int folderId = parseFolderId(key);
            String cacheRoot = parentCacheDirOf(path, folderId);
            String resourceUrl = CacheUtil.pathToURLPath(path, cacheRoot);
            try (PreparedStatement exists = c.prepareStatement(
                    "SELECT 1 FROM cache_entry WHERE lru_key = ?")) {
                exists.setString(1, key);
                try (ResultSet rs = exists.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO cache_entry(lru_key, resource_url, path, folder_id, last_access, state, created_at) "
                            + "VALUES (?,?,?,?,?,'reserved',?)")) {
                ps.setString(1, key);
                ps.setString(2, resourceUrl);
                ps.setString(3, path);
                ps.setInt(4, folderId);
                ps.setLong(5, lastAccess);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return false;
        }
    }

    @Override
    public boolean removeEntry(String key) {
        try {
            Connection c = conn();
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM cache_entry WHERE lru_key = ?")) {
                ps.setString(1, key);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return false;
        }
    }

    @Override
    public boolean updateEntry(String oldKey, String cacheDirPath) {
        try {
            Connection c = conn();
            int folderId;
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT folder_id FROM cache_entry WHERE lru_key = ?")) {
                sel.setString(1, oldKey);
                try (ResultSet rs = sel.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    folderId = rs.getInt(1);
                }
            }
            long now = System.currentTimeMillis();
            String newKey = now + "," + folderId;
            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE cache_entry SET lru_key = ?, last_access = ?, state = 'ready' WHERE lru_key = ?")) {
                upd.setString(1, newKey);
                upd.setLong(2, now);
                upd.setString(3, oldKey);
                return upd.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return false;
        }
    }

    @Override
    public List<Entry<String, String>> getLRUSortedEntries() {
        List<Entry<String, String>> entries = new ArrayList<>();
        try {
            Connection c = conn();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT lru_key, path FROM cache_entry ORDER BY last_access DESC")) {
                while (rs.next()) {
                    entries.add(new AbstractMap.SimpleImmutableEntry<>(
                            rs.getString(1), rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }
        return entries;
    }

    @Override
    public List<Entry<String, String>> findEntriesByUrlPath(String urlPath, String cacheDirPath) {
        List<Entry<String, String>> entries = new ArrayList<>();
        try {
            Connection c = conn();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT lru_key, path FROM cache_entry WHERE resource_url = ? "
                            + "AND state IN ('reserved','ready') ORDER BY last_access DESC")) {
                ps.setString(1, urlPath);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new AbstractMap.SimpleImmutableEntry<>(
                                rs.getString(1), rs.getString(2)));
                    }
                }
            }
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }
        return entries;
    }

    @Override
    public String getValue(String key) {
        try {
            Connection c = conn();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT path FROM cache_entry WHERE lru_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            }
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }
        return null;
    }

    @Override
    public boolean containsKey(String key) {
        return getValue(key) != null;
    }

    @Override
    public boolean containsValue(String value) {
        try {
            Connection c = conn();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM cache_entry WHERE path = ?")) {
                ps.setString(1, value);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return false;
        }
    }

    @Override
    public void clear() {
        try {
            Connection c = conn();
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM cache_entry");
            }
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }
    }

    @Override
    public String generateKey(String path, String cacheDirPath) {
        return System.currentTimeMillis() + ","
                + PropertiesCacheCatalog.folderIdFromPath(path, cacheDirPath);
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private static String parentCacheDirOf(String path, int folderId) {
        String normalized = path.replace('\\', '/');
        String marker = "/" + folderId + "/";
        int idx = normalized.indexOf(marker);
        if (idx < 0) {
            return new File(path).getParent();
        }
        return normalized.substring(0, idx).replace('/', File.separatorChar);
    }

    private static long parseLastAccess(String key, long fallback) {
        try {
            return Long.parseLong(key.split(",")[0]);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int parseFolderId(String key) {
        try {
            return Integer.parseInt(key.split(",")[1]);
        } catch (Exception e) {
            return -1;
        }
    }
}
