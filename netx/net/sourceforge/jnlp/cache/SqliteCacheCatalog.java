/*
 Copyright (C) 2026 IcedTea-Web contributors

 This file is part of IcedTea-Web.
*/
package net.sourceforge.jnlp.cache;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.sqlite.SQLiteConfig;

import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * SQLite-backed cache catalog under {@code {cachedir}/cache/db/cache_catalog.sqlite}
 * ({@code {cachedir}/db/} when {@code cachedir} already ends with {@code cache}).
 * Uses WAL and {@code busy_timeout} for multi-process wait/retry.
 * A second JVM must not rename a live catalog; writes retry on busy.
 */
final class SqliteCacheCatalog implements CacheCatalog {

    static final String DB_FILE_NAME = "cache_catalog.sqlite";
    static final String INIT_LOCK_SUFFIX = ".initlock";
    /** Directory mutex: {@code Files.createDirectory} is exclusive when FileLock is not. */
    static final String INIT_LOCK_DIR_SUFFIX = ".initlock.d";
    /** Written when sqlite cannot be opened even after quarantining a corrupt file. */
    static final String FAILED_MARKER = ".sqlite_catalog_failed";
    /** One-fifth of a minute. Busy wait, first-create lock, and close all cap here. */
    private static final int TIME_BOX_MS = 12_000;
    /** Longer than one wait so a peer still holding the mutex is not “stale”. */
    private static final long PEER_GRACE_MS = TIME_BOX_MS * 2L;
    private static final int BUSY_TIMEOUT_MS = TIME_BOX_MS;
    private static final long INIT_LOCK_MS = TIME_BOX_MS;
    private static final long CLOSE_JOIN_MS = TIME_BOX_MS;
    /** Disambiguates same-millisecond {@code lru_key} values (UNIQUE constraint). */
    private static final AtomicLong LRU_KEY_SEQ = new AtomicLong();

    private final File dbFile;
    private final ReentrantLock threadLock = new ReentrantLock();
    private Connection connection;
    private String openPath;
    /** After an init-lock wait fails, do not retry create for one time-box. */
    private volatile long skipInitCreateUntil;

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
        ensureParentAndNativeTmpdir();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            markFailed();
            throw new SQLException("sqlite-jdbc driver missing", e);
        }
        SQLException last = null;
        long deadline = System.currentTimeMillis() + TIME_BOX_MS;
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                return openAndInitGuarded(path);
            } catch (SQLException e) {
                last = e;
                closeQuietly();
                if (shouldQuarantine(e, dbFile) || System.currentTimeMillis() >= deadline) {
                    break;
                }
                sleepQuietly(50L * (1L << Math.min(attempt, 4)));
            }
        }
        if (shouldQuarantine(last, dbFile)) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                    "sqlite catalog open failed, quarantining: " + dbFile);
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, last);
            quarantineSidecars();
            try {
                return openAndInitGuarded(path);
            } catch (SQLException second) {
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL, second);
                markFailed();
                throw second;
            }
        }
        OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                "sqlite catalog open failed; leaving peer catalog in place: " + dbFile);
        OutputController.getLogger().log(OutputController.Level.ERROR_ALL, last);
        throw last;
    }

    private void ensureParentAndNativeTmpdir() {
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        // Prefer extracting sqlitejdbc under the cache tree (VDI often blocks %TEMP%).
        // Refresh if a previous JVM test left a deleted tmpdir path.
        if (parent != null) {
            String existing = System.getProperty("org.sqlite.tmpdir");
            if (existing == null || !new File(existing).isDirectory()) {
                File nativeDir = new File(parent, "native");
                if (nativeDir.isDirectory() || nativeDir.mkdirs()) {
                    System.setProperty("org.sqlite.tmpdir", nativeDir.getAbsolutePath());
                }
            }
        }
    }

    /**
     * First create is serialized across processes so a 0-byte file is not
     * mistaken for garbage while a peer writes the SQLite header.
     * A live catalog (valid header or {@code -wal}) skips the lock. If the
     * lock is held, do not create; only open a catalog the peer already made.
     * {@code mkdir} is the mutex: Java {@code FileLock} is not exclusive on
     * some local/overlay/NFS mounts.
     */
    private Connection openAndInitGuarded(String path) throws SQLException {
        if (peerCatalogLooksLive(dbFile)) {
            return openAndInit(path);
        }
        if (skipInitCreateUntil != 0L && System.currentTimeMillis() < skipInitCreateUntil) {
            throw new SQLException("catalog init mutex held; leaving peer catalog in place: " + dbFile);
        }
        File lockDir = initLockDir(dbFile);
        File parent = lockDir.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        boolean created = false;
        try {
            created = tryAcquireInitLockDir(lockDir);
            if (!created) {
                return waitForLiveCatalogOrThrow(path, INIT_LOCK_MS,
                        "catalog init mutex held");
            }
            return createOrOpenExclusive(path, true);
        } catch (IOException ioe) {
            OutputController.getLogger().log(ioe);
            // mkdir unavailable: exclusive publish is the remaining mutex.
            return createOrOpenExclusive(path, false);
        } finally {
            if (created) {
                try {
                    Files.deleteIfExists(lockDir.toPath());
                } catch (IOException ignored) {
                    // next opener treats a stale lock dir as stealable
                }
            }
        }
    }

    /**
     * @return {@code true} when this process created {@code lockDir}
     */
    private static boolean tryAcquireInitLockDir(File lockDir) throws IOException {
        try {
            Files.createDirectory(lockDir.toPath());
            return true;
        } catch (FileAlreadyExistsException exists) {
            if (!stealStaleInitLockDir(lockDir)) {
                return false;
            }
            try {
                Files.createDirectory(lockDir.toPath());
                return true;
            } catch (FileAlreadyExistsException raced) {
                return false;
            }
        }
    }

    private static boolean stealStaleInitLockDir(File lockDir) {
        if (!lockDir.isDirectory() || isRecentlyModified(lockDir, PEER_GRACE_MS)) {
            return false;
        }
        File[] leftover = lockDir.listFiles();
        if (leftover != null) {
            for (File child : leftover) {
                if (!child.delete()) {
                    return false;
                }
            }
        }
        return lockDir.delete();
    }

    /**
     * Publish a fully initialized catalog (temp file + exclusive link/create)
     * so a peer never sees a 0-byte {@code cache_catalog.sqlite}. Loser waits
     * for a live header instead of opening a second database.
     */
    private Connection createOrOpenExclusive(String path, boolean ownsInitLock)
            throws SQLException {
        if (peerCatalogLooksLive(dbFile)) {
            return openAndInit(path);
        }
        if (publishInitializedCatalog(ownsInitLock)) {
            return openAndInit(path);
        }
        return waitForLiveCatalogOrThrow(path, TIME_BOX_MS,
                "catalog file appeared during create");
    }

    /**
     * @return {@code true} when {@code dbFile} is live and this process may open it
     */
    private boolean publishInitializedCatalog(boolean ownsInitLock) throws SQLException {
        if (peerCatalogLooksLive(dbFile)) {
            return true;
        }
        if (dbFile.isFile()) {
            if (!ownsInitLock && peerMayStillOwnCatalogName()) {
                return false;
            }
            if (!replaceNonLiveCatalogName()) {
                return false;
            }
        }
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (parent == null) {
            return false;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile(parent.toPath(), "cache_catalog.", ".tmp");
            initNewCatalogFile(tmp.toFile());
            try {
                Files.createLink(dbFile.toPath(), tmp);
                return true;
            } catch (FileAlreadyExistsException exists) {
                return peerCatalogLooksLive(dbFile);
            } catch (UnsupportedOperationException | IOException linkFail) {
                if (peerCatalogLooksLive(dbFile)) {
                    return true;
                }
                try {
                    Files.createFile(dbFile.toPath());
                    initNewCatalogFile(dbFile);
                    return true;
                } catch (FileAlreadyExistsException exists) {
                    return peerCatalogLooksLive(dbFile);
                }
            }
        } catch (IOException ioe) {
            OutputController.getLogger().log(ioe);
            return peerCatalogLooksLive(dbFile);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // dest may still hold the hard link
                }
            }
        }
    }

    /**
     * Free the catalog name. Keep non-empty garbage as {@code .corrupt-*} so
     * an operator can inspect it; drop a 0-byte leftover.
     */
    private boolean replaceNonLiveCatalogName() {
        if (peerCatalogLooksLive(dbFile)) {
            return false;
        }
        if (dbFile.length() == 0L) {
            return dbFile.delete();
        }
        File dest = new File(dbFile.getParent(),
                dbFile.getName() + ".corrupt-" + System.currentTimeMillis());
        return dbFile.renameTo(dest) || dbFile.delete();
    }

    /** Recent dest or lock means a peer still owns {@code cache_catalog.sqlite}. */
    private boolean peerMayStillOwnCatalogName() {
        return isRecentlyModified(dbFile, PEER_GRACE_MS)
                || isRecentlyModified(initLockFile(dbFile), PEER_GRACE_MS)
                || isRecentlyModified(initLockDir(dbFile), PEER_GRACE_MS);
    }

    private void initNewCatalogFile(File file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:" + file.getAbsolutePath(), config.toProperties())) {
            // DELETE journal so a later hard-link does not leave a temp-named WAL.
            applySchema(c, false);
        }
        new File(file.getPath() + "-journal").delete();
        new File(file.getPath() + "-wal").delete();
        new File(file.getPath() + "-shm").delete();
    }

    private Connection waitForLiveCatalogOrThrow(String path, long maxWaitMs, String why)
            throws SQLException {
        long deadline = System.currentTimeMillis() + Math.max(0L, maxWaitMs);
        while (true) {
            if (peerCatalogLooksLive(dbFile)) {
                return openAndInit(path);
            }
            if (System.currentTimeMillis() >= deadline) {
                skipInitCreateUntil = System.currentTimeMillis() + TIME_BOX_MS;
                throw new SQLException(why + "; leaving peer catalog in place: " + dbFile);
            }
            sleepQuietly(50);
        }
    }

    static File initLockFile(File dbFile) {
        return new File(dbFile.getPath() + INIT_LOCK_SUFFIX);
    }

    static File initLockDir(File dbFile) {
        return new File(dbFile.getPath() + INIT_LOCK_DIR_SUFFIX);
    }

    private Connection openAndInit(String path) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
        connection = DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
        openPath = path;
        applySchema(connection, true);
        return connection;
    }

    private void applySchema(Connection c, boolean wal) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=" + (wal ? "WAL" : "DELETE"));
            st.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MS);
            st.execute("PRAGMA synchronous=" + (cacheFsyncEnabled() ? "FULL" : "NORMAL"));
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM schema_version")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    st.executeUpdate("INSERT INTO schema_version(version) VALUES (4)");
                }
            }
            st.execute("CREATE TABLE IF NOT EXISTS cache_entry ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "lru_key TEXT NOT NULL UNIQUE,"
                    + "resource_url TEXT NOT NULL,"
                    + "path TEXT NOT NULL UNIQUE,"
                    + "folder_id INTEGER NOT NULL,"
                    + "last_access INTEGER NOT NULL,"
                    + "state TEXT NOT NULL DEFAULT 'ready'"
                    + " CHECK (state IN ('reserved','ready','orphan')),"
                    + "created_at INTEGER NOT NULL"
                    + ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_cache_entry_url_access "
                    + "ON cache_entry (resource_url, last_access DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_cache_entry_access "
                    + "ON cache_entry (last_access ASC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_cache_entry_folder "
                    + "ON cache_entry (folder_id)");
            ensureEntryMetadataColumns(st);
            ensureWireLengthColumn(st);
            ensureNativeLibTable(st);
            st.execute("CREATE TABLE IF NOT EXISTS running_app ("
                    + "pid INTEGER PRIMARY KEY,"
                    + "jnlp_path TEXT,"
                    + "process_start TEXT)");
        }
    }

    /**
     * Schema v2: jnlp-path / HTTP freshness / delete mark on the row.
     * Do not put these in sidecar {@code .info} files.
     */
    private void ensureEntryMetadataColumns(Statement st) throws SQLException {
        addColumnIfMissing(st, "jnlp_path", "TEXT");
        addColumnIfMissing(st, "content_length", "INTEGER");
        addColumnIfMissing(st, "last_modified", "INTEGER");
        addColumnIfMissing(st, "last_updated", "INTEGER");
        addColumnIfMissing(st, "marked_delete", "INTEGER NOT NULL DEFAULT 0");
        st.execute("CREATE INDEX IF NOT EXISTS idx_cache_entry_jnlp ON cache_entry (jnlp_path)");
        try (ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
            if (rs.next() && rs.getInt(1) < 2) {
                st.executeUpdate("UPDATE schema_version SET version = 2");
            }
        }
    }

    /**
     * Schema v4: HTTP Content-Length (pack.gz wire). {@code content_length} stays
     * the unpacked jar on disk so {@code isCached} can compare file length.
     */
    private void ensureWireLengthColumn(Statement st) throws SQLException {
        addColumnIfMissing(st, "wire_length", "INTEGER");
        try (ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
            if (rs.next() && rs.getInt(1) < 4) {
                st.executeUpdate("UPDATE schema_version SET version = 4");
            }
        }
    }

    /**
     * Schema v3: native library index. Files live under {@code native/jars/}
     * beside the sqlite-jdbc extract in {@code native/}.
     */
    private void ensureNativeLibTable(Statement st) throws SQLException {
        st.execute("CREATE TABLE IF NOT EXISTS native_lib ("
                + "lib_name TEXT NOT NULL,"
                + "jar_path TEXT NOT NULL,"
                + "extract_path TEXT NOT NULL,"
                + "PRIMARY KEY (lib_name, jar_path)"
                + ")");
        st.execute("CREATE INDEX IF NOT EXISTS idx_native_lib_name ON native_lib (lib_name)");
        try (ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
            if (rs.next() && rs.getInt(1) < 3) {
                st.executeUpdate("UPDATE schema_version SET version = 3");
            }
        }
    }

    private static void addColumnIfMissing(Statement st, String name, String decl) throws SQLException {
        boolean present = false;
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(cache_entry)")) {
            while (rs.next()) {
                if (name.equalsIgnoreCase(rs.getString("name"))) {
                    present = true;
                    break;
                }
            }
        }
        if (!present) {
            st.execute("ALTER TABLE cache_entry ADD COLUMN " + name + " " + decl);
        }
    }

    /**
     * Quarantine only stable garbage. Never rename a real catalog (valid header),
     * a live {@code -wal}, a busy/locked peer, or a file a peer may still be
     * initializing (empty, tiny, or not-yet-a-header and recently touched).
     */
    static boolean shouldQuarantine(SQLException e, File dbFile) {
        if (e == null || dbFile == null) {
            return false;
        }
        if (peerCatalogLooksLive(dbFile)) {
            return false;
        }
        int code = e.getErrorCode();
        if (code == 5 || code == 6) {
            return false;
        }
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (m.contains("busy") || m.contains("locked") || m.contains("leaving peer catalog")) {
            return false;
        }
        if (isRecentlyModified(initLockFile(dbFile), PEER_GRACE_MS)
                || isRecentlyModified(initLockDir(dbFile), PEER_GRACE_MS)) {
            return false;
        }
        return dbFile.isFile() && !isRecentlyModified(dbFile, PEER_GRACE_MS);
    }

    /** True when WAL or a valid header means a peer already owns this catalog. */
    static boolean peerCatalogLooksLive(File dbFile) {
        if (dbFile == null) {
            return false;
        }
        if (new File(dbFile.getPath() + "-wal").isFile()) {
            return true;
        }
        return looksLikeSqliteHeader(dbFile);
    }

    static boolean isRecentlyModified(File dbFile, long maxAgeMs) {
        if (dbFile == null || !dbFile.exists()) {
            return false;
        }
        long age = System.currentTimeMillis() - dbFile.lastModified();
        return age >= 0 && age < maxAgeMs;
    }

    static boolean looksLikeSqliteHeader(File dbFile) {
        if (dbFile == null || !dbFile.isFile() || dbFile.length() < 16) {
            return false;
        }
        try (FileInputStream in = new FileInputStream(dbFile)) {
            byte[] h = new byte[16];
            if (in.read(h) < 16) {
                return false;
            }
            return "SQLite format 3".equals(new String(h, 0, 15, StandardCharsets.US_ASCII));
        } catch (IOException ioe) {
            return false;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    interface SqlOp<T> {
        T run(Connection c) throws SQLException;
    }

    static boolean isBusy(SQLException e) {
        if (e == null) {
            return false;
        }
        int code = e.getErrorCode();
        if (code == 5 || code == 6) {
            return true;
        }
        if (code == 19) {
            return false;
        }
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (m.contains("leaving peer catalog")) {
            return false;
        }
        return m.contains("busy") || m.contains("locked");
    }

    private <T> T runBusy(SqlOp<T> op) throws SQLException {
        SQLException last = null;
        long deadline = System.currentTimeMillis() + TIME_BOX_MS;
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                return op.run(conn());
            } catch (SQLException e) {
                last = e;
                if (!isBusy(e) || System.currentTimeMillis() >= deadline) {
                    throw e;
                }
                closeQuietly();
                sleepQuietly(25L * (attempt + 1));
            }
        }
        throw last;
    }

    private void quarantineSidecars() {
        File parent = dbFile.getParentFile();
        if (parent == null) {
            return;
        }
        long ts = System.currentTimeMillis();
        String[] names = {
            dbFile.getName(),
            dbFile.getName() + "-wal",
            dbFile.getName() + "-shm"
        };
        for (String name : names) {
            File src = new File(parent, name);
            if (!src.isFile()) {
                continue;
            }
            File dest = new File(parent, name + ".corrupt-" + ts);
            if (!src.renameTo(dest)) {
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                        "unable to quarantine " + src);
            }
        }
    }

    private void markFailed() {
        File parent = dbFile.getParentFile();
        if (parent == null) {
            return;
        }
        File marker = new File(parent, FAILED_MARKER);
        try {
            if (!marker.exists() && !marker.createNewFile()) {
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                        "unable to write sqlite sticky-fail marker: " + marker);
            }
        } catch (IOException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }
    }

    boolean isUsable() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /** Package-private for tests: 1=NORMAL, 2=FULL. */
    int pragmaSynchronous() throws SQLException {
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("PRAGMA synchronous")) {
            if (!rs.next()) {
                throw new SQLException("PRAGMA synchronous returned no row");
            }
            return rs.getInt(1);
        }
    }

    /** Package-private for tests: EXPLAIN QUERY PLAN of indexed URL lookup. */
    String explainFindEntriesPlan() throws SQLException {
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery(
                     "EXPLAIN QUERY PLAN SELECT lru_key, path FROM cache_entry "
                             + "WHERE resource_url = 'probe' AND state IN ('reserved','ready') "
                             + "ORDER BY last_access DESC")) {
            StringBuilder plan = new StringBuilder();
            while (rs.next()) {
                if (plan.length() > 0) {
                    plan.append('\n');
                }
                // SQLite: last column is "detail"
                plan.append(rs.getString("detail"));
            }
            return plan.toString();
        }
    }

    private void closeQuietly() {
        final Connection toClose = connection;
        connection = null;
        openPath = null;
        if (toClose == null) {
            return;
        }
        Thread closer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    try (Statement st = toClose.createStatement()) {
                        st.setQueryTimeout(TIME_BOX_MS / 1000);
                        st.execute("PRAGMA wal_checkpoint(PASSIVE)");
                    } catch (SQLException e) {
                        OutputController.getLogger().log(e);
                    }
                    toClose.close();
                } catch (SQLException e) {
                    OutputController.getLogger().log(e);
                }
            }
        }, "sqlite-catalog-close");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(CLOSE_JOIN_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
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
            runBusy(c -> Boolean.FALSE);
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
            return runBusy(c -> {
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
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return false;
        }
    }

    @Override
    public boolean removeEntry(String key) {
        try {
            return runBusy(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM cache_entry WHERE lru_key = ?")) {
                    ps.setString(1, key);
                    return ps.executeUpdate() > 0;
                }
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return false;
        }
    }

    @Override
    public boolean removeByPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        try {
            return runBusy(c -> {
                try (PreparedStatement natives = c.prepareStatement(
                        "DELETE FROM native_lib WHERE jar_path = ?")) {
                    natives.setString(1, path);
                    natives.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM cache_entry WHERE path = ?")) {
                    ps.setString(1, path);
                    return ps.executeUpdate() > 0;
                }
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return false;
        }
    }

    @Override
    public boolean updateEntry(String oldKey, String cacheDirPath) {
        try {
            return runBusy(c -> {
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
                String newKey = lruKey(now, folderId);
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE cache_entry SET lru_key = ?, last_access = ?, state = 'ready' WHERE lru_key = ?")) {
                    upd.setString(1, newKey);
                    upd.setLong(2, now);
                    upd.setString(3, oldKey);
                    return upd.executeUpdate() > 0;
                }
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return false;
        }
    }

    @Override
    public List<Entry<String, String>> getLRUSortedEntries() {
        try {
            return runBusy(c -> {
                List<Entry<String, String>> entries = new ArrayList<>();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT lru_key, path FROM cache_entry ORDER BY last_access DESC")) {
                    while (rs.next()) {
                        entries.add(new AbstractMap.SimpleImmutableEntry<>(
                                rs.getString(1), rs.getString(2)));
                    }
                }
                return entries;
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<Entry<String, String>> findEntriesByUrlPath(String urlPath, String cacheDirPath) {
        long t0 = System.nanoTime();
        List<Entry<String, String>> entries;
        try {
            entries = runBusy(c -> {
                List<Entry<String, String>> found = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT lru_key, path FROM cache_entry WHERE resource_url = ? "
                                + "AND state IN ('reserved','ready') ORDER BY last_access DESC")) {
                    ps.setString(1, urlPath);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            found.add(new AbstractMap.SimpleImmutableEntry<>(
                                    rs.getString(1), rs.getString(2)));
                        }
                    }
                }
                return found;
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            entries = new ArrayList<>();
        }
        long us = (System.nanoTime() - t0) / 1000L;
        OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                "sqlite catalog findEntriesByUrlPath us=" + us + " matches=" + entries.size());
        return entries;
    }

    @Override
    public String getValue(String key) {
        try {
            return runBusy(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT path FROM cache_entry WHERE lru_key = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return null;
        }
    }

    @Override
    public boolean containsKey(String key) {
        return getValue(key) != null;
    }

    @Override
    public boolean containsValue(String value) {
        try {
            return runBusy(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM cache_entry WHERE path = ?")) {
                    ps.setString(1, value);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next();
                    }
                }
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return false;
        }
    }

    @Override
    public void clear() {
        try {
            runBusy(c -> {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("DELETE FROM cache_entry");
                }
                return Boolean.TRUE;
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }
    }

    @Override
    public String generateKey(String path, String cacheDirPath) {
        int folderId = Integer.parseInt(
                PropertiesCacheCatalog.folderIdFromPath(path, cacheDirPath));
        return lruKey(System.currentTimeMillis(), folderId);
    }

    /** {@code millis,folderId,seq} — seq avoids UNIQUE collisions within the same ms. */
    static String lruKey(long millis, int folderId) {
        return millis + "," + folderId + "," + LRU_KEY_SEQ.incrementAndGet();
    }

    @Override
    public int nextFolderId(File cacheDir) {
        int candidate = 0;
        try {
            candidate = runBusy(c -> {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(folder_id), -1) FROM cache_entry")) {
                    return rs.next() ? rs.getInt(1) + 1 : 0;
                }
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            candidate = 0;
        }
        return CacheCatalog.claimFolderId(cacheDir, candidate);
    }

    @Override
    public CacheEntryMeta getMetaByPath(String path) {
        if (path == null) {
            return null;
        }
        try {
            return runBusy(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT path, resource_url, jnlp_path, content_length, last_modified, "
                                + "last_updated, marked_delete, wire_length FROM cache_entry WHERE path = ?")) {
                    ps.setString(1, path);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rowToMeta(rs) : null;
                    }
                }
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return null;
        }
    }

    @Override
    public void putMeta(CacheEntryMeta meta) {
        if (meta == null || meta.path == null) {
            return;
        }
        try {
            runBusy(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE cache_entry SET jnlp_path = ?, content_length = ?, last_modified = ?, "
                                + "last_updated = ?, marked_delete = ?, wire_length = ? WHERE path = ?")) {
                    ps.setString(1, meta.jnlpPath);
                    setNullableLong(ps, 2, meta.contentLength);
                    setNullableLong(ps, 3, meta.lastModified);
                    setNullableLong(ps, 4, meta.lastUpdated);
                    ps.setInt(5, meta.markedDelete ? 1 : 0);
                    setNullableLong(ps, 6, meta.wireLength);
                    ps.setString(7, meta.path);
                    ps.executeUpdate();
                }
                return Boolean.TRUE;
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }
    }

    private static void ensureRunningAppTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS running_app ("
                    + "pid INTEGER PRIMARY KEY,"
                    + "jnlp_path TEXT,"
                    + "process_start TEXT)");
        }
    }

    @Override
    public void registerRunningApp(int pid, String jnlpPath, String processStart) {
        if (pid <= 0) {
            return;
        }
        try {
            runBusy(c -> {
                ensureRunningAppTable(c);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO running_app(pid, jnlp_path, process_start) VALUES (?,?,?) "
                                + "ON CONFLICT(pid) DO UPDATE SET "
                                + "jnlp_path=excluded.jnlp_path, process_start=excluded.process_start")) {
                    ps.setInt(1, pid);
                    ps.setString(2, jnlpPath);
                    ps.setString(3, processStart);
                    ps.executeUpdate();
                }
                return Boolean.TRUE;
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }
    }

    @Override
    public void unregisterRunningApp(int pid) {
        if (pid <= 0) {
            return;
        }
        try {
            runBusy(c -> {
                ensureRunningAppTable(c);
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM running_app WHERE pid = ?")) {
                    ps.setInt(1, pid);
                    ps.executeUpdate();
                }
                return Boolean.TRUE;
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(e);
        }
    }

    @Override
    public List<CacheRunningApp> listRunningApps() {
        try {
            return runBusy(c -> {
                ensureRunningAppTable(c);
                List<CacheRunningApp> rows = new ArrayList<CacheRunningApp>();
                try (Statement st = c.createStatement();
                        ResultSet rs = st.executeQuery(
                                "SELECT pid, jnlp_path, process_start FROM running_app")) {
                    while (rs.next()) {
                        rows.add(new CacheRunningApp(rs.getInt(1), rs.getString(2), rs.getString(3)));
                    }
                }
                return rows;
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(e);
            return new ArrayList<CacheRunningApp>();
        }
    }

    @Override
    public List<CacheEntryMeta> listAllMeta() {
        try {
            return runBusy(c -> {
                List<CacheEntryMeta> rows = new ArrayList<>();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT path, resource_url, jnlp_path, content_length, last_modified, "
                                     + "last_updated, marked_delete, wire_length FROM cache_entry")) {
                    while (rs.next()) {
                        rows.add(rowToMeta(rs));
                    }
                }
                return rows;
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            return new ArrayList<>();
        }
    }

    private static CacheEntryMeta rowToMeta(ResultSet rs) throws SQLException {
        CacheEntryMeta m = new CacheEntryMeta();
        m.path = rs.getString(1);
        m.resourceUrl = rs.getString(2);
        m.jnlpPath = rs.getString(3);
        m.contentLength = getNullableLong(rs, 4);
        m.lastModified = getNullableLong(rs, 5);
        m.lastUpdated = getNullableLong(rs, 6);
        m.markedDelete = rs.getInt(7) != 0;
        m.wireLength = getNullableLong(rs, 8);
        return m;
    }

    private static Long getNullableLong(ResultSet rs, int idx) throws SQLException {
        long v = rs.getLong(idx);
        return rs.wasNull() ? null : v;
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long value) throws SQLException {
        if (value == null) {
            ps.setObject(idx, null);
        } else {
            ps.setLong(idx, value);
        }
    }

    @Override
    public void close() {
        closeQuietly();
    }

    /**
     * Cache root above the numbered folder. Must not take the first
     * {@code /{folderId}/} in the absolute path — earlier path segments (home dirs,
     * worktree names) or later URL path segments can contain the same digits.
     * Prefer the last match whose next segment looks like a cached URL tree
     * ({@code http/}, {@code https/}, …).
     */
    static String parentCacheDirOf(String path, int folderId) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String normalized = path.replace('\\', '/');
        String marker = "/" + folderId + "/";
        int chosen = -1;
        int from = 0;
        while (from < normalized.length()) {
            int idx = normalized.indexOf(marker, from);
            if (idx < 0) {
                break;
            }
            // "/17/" must not match a search for "/7/"
            if (idx > 0 && Character.isDigit(normalized.charAt(idx - 1))) {
                from = idx + 1;
                continue;
            }
            int after = idx + marker.length();
            if (after <= normalized.length() && looksLikeCachedUrlTree(normalized.substring(after))) {
                chosen = idx;
            }
            from = idx + 1;
        }
        if (chosen >= 0) {
            return normalized.substring(0, chosen).replace('/', File.separatorChar);
        }
        return new File(path).getParent();
    }

    private static boolean looksLikeCachedUrlTree(String rest) {
        String r = rest.toLowerCase(Locale.ROOT);
        return r.startsWith("http/") || r.startsWith("https/") || r.startsWith("ftp/")
                || r.startsWith("file/") || r.startsWith("jar/");
    }

    /** Test hook: when non-null, overrides {@code deployment.enable.cache.fsync}. */
    static Boolean fsyncOverrideForTests;

    private static boolean cacheFsyncEnabled() {
        if (fsyncOverrideForTests != null) {
            return fsyncOverrideForTests.booleanValue();
        }
        try {
            String v = JNLPRuntime.getConfiguration().getProperty(DeploymentConfiguration.KEY_ENABLE_CACHE_FSYNC);
            return Boolean.parseBoolean(v);
        } catch (Throwable e) {
            // Slim dual-JVM workers may not have the full ITW graph (DownloadIndicator, …).
            return false;
        }
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

    @Override
    public void putNativeLib(String libName, String jarPath, String extractPath) {
        if (libName == null || libName.isEmpty() || jarPath == null || extractPath == null) {
            return;
        }
        try {
            runBusy(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR REPLACE INTO native_lib(lib_name, jar_path, extract_path) VALUES (?,?,?)")) {
                    ps.setString(1, libName);
                    ps.setString(2, jarPath);
                    ps.setString(3, extractPath);
                    ps.executeUpdate();
                }
                return Boolean.TRUE;
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.WARNING_DEBUG, e);
        }
    }

    @Override
    public String findNativeLib(String libName) {
        if (libName == null || libName.isEmpty()) {
            return null;
        }
        try {
            return runBusy(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT extract_path FROM native_lib WHERE lib_name = ? ORDER BY rowid DESC LIMIT 1")) {
                    ps.setString(1, libName);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.WARNING_DEBUG, e);
            return null;
        }
    }

    @Override
    public void removeNativeLibsByJarPath(String jarPath) {
        if (jarPath == null || jarPath.isEmpty()) {
            return;
        }
        try {
            runBusy(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM native_lib WHERE jar_path = ?")) {
                    ps.setString(1, jarPath);
                    ps.executeUpdate();
                }
                return Boolean.TRUE;
            });
        } catch (SQLException e) {
            OutputController.getLogger().log(OutputController.Level.WARNING_DEBUG, e);
        }
    }
}
