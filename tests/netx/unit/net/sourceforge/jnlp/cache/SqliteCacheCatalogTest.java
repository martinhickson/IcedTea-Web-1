/*
 Copyright (C) 2026 IcedTea-Web contributors
*/
package net.sourceforge.jnlp.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;


public class SqliteCacheCatalogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File parentCache;
    private File dbRoot;
    private CacheLRUWrapper wrapper;

    @Before
    public void setUp() throws IOException {
        parentCache = tmp.newFolder("cache-parent");
        File legacyJar = new File(parentCache, "0/http/evil.example/legacy.jar");
        legacyJar.getParentFile().mkdirs();
        assertTrue(legacyJar.createNewFile());
        new File(parentCache, "recently_used").createNewFile();

        wrapper = CacheLRUWrapper.createForTests(true, parentCache);
        dbRoot = wrapper.getCacheDir().getFile();
    }

    @After
    public void tearDown() {
        if (wrapper != null) {
            wrapper.close();
        }
    }

    @Test
    public void entryMetadataLivesOnCatalogRowNotInfoFile() throws Exception {
        File jar = new File(dbRoot, "4/http/example.com/app.jar");
        jar.getParentFile().mkdirs();
        assertTrue(jar.createNewFile());
        wrapper.lock();
        try {
            wrapper.load();
            String key = wrapper.generateKey(jar.getAbsolutePath());
            assertTrue(wrapper.addEntry(key, jar.getAbsolutePath()));
            CacheEntryMeta meta = new CacheEntryMeta();
            meta.path = jar.getAbsolutePath();
            meta.jnlpPath = "http://example.com/app.jnlp";
            meta.contentLength = 12L;
            meta.wireLength = 3L;
            meta.lastModified = 1_700_000_000_000L;
            meta.markedDelete = false;
            wrapper.putMeta(meta);
            CacheEntryMeta stored = wrapper.getMetaByPath(jar.getAbsolutePath());
            assertEquals("http://example.com/app.jnlp", stored.jnlpPath);
            assertEquals(Long.valueOf(12L), stored.contentLength);
            assertEquals(Long.valueOf(3L), stored.wireLength);
            assertEquals(Long.valueOf(1_700_000_000_000L), stored.lastModified);
            assertFalse(new File(jar.getPath() + CacheDirectory.INFO_SUFFIX).exists());
        } finally {
            wrapper.unlock();
        }
        assertTrue(CacheDirectory.isCacheInfrastructure(
                new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME)));
        assertTrue(CacheDirectory.isCacheInfrastructure(new File(dbRoot, "native")));
        assertFalse(CacheDirectory.isCacheInfrastructure(jar));
    }

    @Test
    public void sqliteCacheRootNestsCacheDbUnlessParentIsAlreadyCache() {
        assertEquals(new File(new File(new File("/tmp/itw-root"), "cache"), "db").getPath(),
                CacheLRUWrapper.sqliteCacheRoot(new File("/tmp/itw-root")).getPath());
        assertEquals(new File(new File("/tmp/itw-root/cache"), "db").getPath(),
                CacheLRUWrapper.sqliteCacheRoot(new File("/tmp/itw-root/cache")).getPath());
        assertEquals(new File(new File(new File("/tmp/mycache"), "cache"), "db").getPath(),
                CacheLRUWrapper.sqliteCacheRoot(new File("/tmp/mycache")).getPath());
    }

    @Test
    public void usesDbSubdirectoryNotLegacyRoot() {
        assertTrue(wrapper.isSqliteMode());
        assertEquals(new File(new File(parentCache, "cache"), "db").getAbsolutePath(),
                dbRoot.getAbsolutePath());
        wrapper.lock();
        try {
            wrapper.load();
            assertTrue(new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME).isFile());
        } finally {
            wrapper.unlock();
        }
        assertTrue(new File(parentCache, "recently_used").isFile());
        assertTrue(new File(parentCache, "0/http/evil.example/legacy.jar").isFile());
    }

    @Test
    public void extractsNativeLibraryUnderDbNativeNotTemp() {
        wrapper.lock();
        try {
            wrapper.load();
        } finally {
            wrapper.unlock();
        }
        String tmpdir = System.getProperty("org.sqlite.tmpdir");
        assertTrue("org.sqlite.tmpdir should be set to a cache db/native dir", tmpdir != null);
        File nativeDir = new File(tmpdir);
        assertEquals("native", nativeDir.getName());
        // sqlite-jdbc extracts once per JVM. A prior test may own the property
        // (and may have already deleted that TemporaryFolder).
        File thisNative = new File(dbRoot, "native");
        assertTrue(thisNative.isDirectory() || nativeDir.isDirectory());
        File[] libs = nativeDir.isDirectory()
                ? nativeDir.listFiles((d, n) -> n.toLowerCase().contains("sqlitejdbc"))
                : null;
        if (libs == null || libs.length == 0) {
            assertTrue("catalog still opens after sqlite-jdbc is already loaded",
                    new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME).isFile());
            return;
        }
        assertTrue("expected sqlitejdbc native under " + nativeDir.getAbsolutePath(),
                libs.length > 0);
    }

    @Test
    public void walModeSchemaVersionAndFolderIndex() throws Exception {
        wrapper.lock();
        try {
            wrapper.load();
        } finally {
            wrapper.unlock();
        }
        File dbFile = new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME);
        Class.forName("org.sqlite.JDBC");
        try (Connection c = DriverManager.getConnection(
                "jdbc:sqlite:" + dbFile.getAbsolutePath().replace('\\', '/'))) {
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
                try (ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                    assertTrue(rs.next());
                    assertEquals("wal", rs.getString(1).toLowerCase());
                }
                try (ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
                    assertTrue(rs.next());
                    assertEquals(4, rs.getInt(1));
                }
                try (ResultSet rs = st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_cache_entry_folder'")) {
                    assertTrue("plan requires idx_cache_entry_folder", rs.next());
                }
            }
        }
    }

    @Test
    public void runningAppLeaseSurvivesSecondCatalogConnection() throws Exception {
        wrapper.registerRunningApp(4242, "http://127.0.0.1:4350/jnlp/c401/app.jnlp", "2026-01-01T00:00:00Z");
        List<CacheRunningApp> first = wrapper.listRunningApps();
        assertEquals(1, first.size());
        assertEquals(4242, first.get(0).pid);
        assertEquals("http://127.0.0.1:4350/jnlp/c401/app.jnlp", first.get(0).jnlpPath);
        wrapper.close();

        CacheLRUWrapper other = CacheLRUWrapper.createForTests(true, parentCache);
        try {
            List<CacheRunningApp> second = other.listRunningApps();
            assertEquals(1, second.size());
            assertEquals(4242, second.get(0).pid);
            other.unregisterRunningApp(4242);
            assertTrue(other.listRunningApps().isEmpty());
        } finally {
            other.close();
        }
        wrapper = CacheLRUWrapper.createForTests(true, parentCache);
    }

    @Test
    public void findEntriesUsesUrlAccessIndex() throws Exception {
        wrapper.lock();
        try {
            wrapper.load();
            String plan = wrapper.sqliteExplainFindEntriesPlan().toLowerCase();
            assertTrue("expected idx_cache_entry_url_access in plan: " + plan,
                    plan.contains("idx_cache_entry_url_access"));
        } finally {
            wrapper.unlock();
        }
    }

    @Test
    public void sqliteOpsDoNotWriteLegacyRecentlyUsed() throws Exception {
        File legacy = new File(parentCache, "recently_used");
        byte[] before = java.nio.file.Files.readAllBytes(legacy.toPath());
        File jar = new File(dbRoot, "5/http/nowrite.example/app.jar");
        assertTrue(jar.getParentFile().mkdirs() || jar.getParentFile().isDirectory());
        assertTrue(jar.createNewFile());
        wrapper.lock();
        try {
            wrapper.load();
            assertTrue(wrapper.addEntry("1000,5", jar.getAbsolutePath()));
            wrapper.store();
            wrapper.findEntriesByUrlPath(
                    CacheUtil.pathToURLPath(jar.getAbsolutePath(), dbRoot.getAbsolutePath()));
        } finally {
            wrapper.unlock();
        }
        byte[] after = java.nio.file.Files.readAllBytes(legacy.toPath());
        assertEquals(new String(before, java.nio.charset.StandardCharsets.UTF_8),
                new String(after, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void corruptCatalogQuarantinesAndRecreatesWithoutTouchingLegacy() throws Exception {
        File legacy = new File(parentCache, "recently_used");
        byte[] before = java.nio.file.Files.readAllBytes(legacy.toPath());
        wrapper.lock();
        try {
            wrapper.load();
        } finally {
            wrapper.unlock();
        }
        wrapper.close();

        File dbFile = new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME);
        java.nio.file.Files.write(dbFile.toPath(),
                "this is not a sqlite database".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME + "-wal").delete();
        new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME + "-shm").delete();
        File leftoverLockDir = SqliteCacheCatalog.initLockDir(dbFile);
        if (leftoverLockDir.isDirectory()) {
            assertTrue(leftoverLockDir.setLastModified(System.currentTimeMillis() - 60_000L));
        }
        // Stable garbage only: a just-written file may be a peer still heading the DB.
        assertTrue(dbFile.setLastModified(System.currentTimeMillis() - 60_000L));

        CacheLRUWrapper recovered = CacheLRUWrapper.createForTests(true, parentCache);
        try {
            assertTrue(recovered.isSqliteMode());
            recovered.lock();
            try {
                recovered.load();
                File jar = new File(dbRoot, "1/http/recovered.example/x.jar");
                assertTrue(jar.getParentFile().mkdirs() || jar.getParentFile().isDirectory());
                assertTrue(jar.createNewFile());
                assertTrue("fresh catalog after quarantine should accept inserts",
                        recovered.addEntry("1,1", jar.getAbsolutePath()));
                assertEquals(1, recovered.getLRUSortedEntries().size());
            } finally {
                recovered.unlock();
            }
        } finally {
            recovered.close();
        }
        byte[] after = java.nio.file.Files.readAllBytes(legacy.toPath());
        assertEquals(new String(before, java.nio.charset.StandardCharsets.UTF_8),
                new String(after, java.nio.charset.StandardCharsets.UTF_8));
        File[] quarantined = dbRoot.listFiles((d, n) -> n.contains(".corrupt-"));
        assertTrue("corrupt file should be quarantined", quarantined != null && quarantined.length > 0);
        assertFalse(new File(dbRoot, SqliteCacheCatalog.FAILED_MARKER).isFile());
    }

    @Test
    public void busyOrWalDoesNotQuarantineALiveCatalog() throws Exception {
        File dbFile = new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME);
        wrapper.lock();
        try {
            wrapper.load();
        } finally {
            wrapper.unlock();
        }
        assertTrue(SqliteCacheCatalog.looksLikeSqliteHeader(dbFile));
        SQLException busy = new SQLException("database is locked", "HY000", 5);
        assertFalse(SqliteCacheCatalog.shouldQuarantine(busy, dbFile));
        File wal = new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME + "-wal");
        assertTrue(wal.createNewFile() || wal.isFile());
        SQLException notAdb = new SQLException("file is not a database", "HY000", 26);
        assertFalse("live WAL must not be renamed away",
                SqliteCacheCatalog.shouldQuarantine(notAdb, dbFile));
        wal.delete();
        File garbage = new File(tmp.newFolder("garbage-db"), SqliteCacheCatalog.DB_FILE_NAME);
        java.nio.file.Files.write(garbage.toPath(),
                "this is not a sqlite database".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse("fresh invalid bytes may be a peer still writing the header",
                SqliteCacheCatalog.shouldQuarantine(notAdb, garbage));
        assertTrue(garbage.setLastModified(System.currentTimeMillis() - 60_000L));
        assertTrue("stale invalid bytes can be quarantined",
                SqliteCacheCatalog.shouldQuarantine(notAdb, garbage));
        assertTrue(SqliteCacheCatalog.peerCatalogLooksLive(dbFile));
        assertFalse(SqliteCacheCatalog.peerCatalogLooksLive(garbage));
        SQLException corrupt = new SQLException("database disk image is malformed", "HY000", 11);
        assertFalse("valid header must not be renamed even if sqlite says CORRUPT",
                SqliteCacheCatalog.shouldQuarantine(corrupt, dbFile));
        File empty = new File(tmp.newFolder("empty-db"), SqliteCacheCatalog.DB_FILE_NAME);
        assertTrue(empty.createNewFile());
        assertFalse("peer may still be writing the header",
                SqliteCacheCatalog.shouldQuarantine(notAdb, empty));
        assertTrue(empty.setLastModified(System.currentTimeMillis() - 60_000L));
        assertTrue("stale 0-byte leftover can be replaced",
                SqliteCacheCatalog.shouldQuarantine(notAdb, empty));
        assertTrue(SqliteCacheCatalog.isBusy(busy));
        assertFalse(SqliteCacheCatalog.isBusy(new SQLException("UNIQUE constraint failed", "HY000", 19)));
        SQLException initHeld = new SQLException(
                "catalog init mutex held; leaving peer catalog in place: x");
        assertFalse("init mutex wait is not SQLITE_BUSY",
                SqliteCacheCatalog.isBusy(initHeld));
        assertFalse("init mutex wait must not quarantine",
                SqliteCacheCatalog.shouldQuarantine(initHeld, empty));
        File staleGarbage = new File(tmp.newFolder("stale-with-lock"), SqliteCacheCatalog.DB_FILE_NAME);
        java.nio.file.Files.write(staleGarbage.toPath(),
                "this is not a sqlite database".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(staleGarbage.setLastModified(System.currentTimeMillis() - 60_000L));
        File recentLockDir = SqliteCacheCatalog.initLockDir(staleGarbage);
        assertTrue(recentLockDir.mkdir());
        assertFalse("recent initlock dir means a peer is still creating",
                SqliteCacheCatalog.shouldQuarantine(notAdb, staleGarbage));
    }

    @Test
    public void twoThreadsFirstCreateShareOneCatalog() throws Exception {
        File parent = tmp.newFolder("dual-thread-create");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger added = new AtomicInteger();
        AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        Runnable work = new Runnable() {
            @Override
            public void run() {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    CacheLRUWrapper w = CacheLRUWrapper.createForTests(true, parent);
                    try {
                        File db = w.getCacheDir().getFile();
                        long tid = Thread.currentThread().getId();
                        for (int i = 0; i < 20; i++) {
                            File jar = new File(db, tid + "/http/thread.example/u" + i + ".jar");
                            assertTrue(jar.getParentFile().mkdirs() || jar.getParentFile().isDirectory());
                            assertTrue(jar.exists() || jar.createNewFile());
                            w.lock();
                            try {
                                w.load();
                                if (w.addEntry(System.nanoTime() + "," + tid, jar.getAbsolutePath())) {
                                    added.incrementAndGet();
                                }
                                w.store();
                            } finally {
                                w.unlock();
                            }
                        }
                    } finally {
                        w.close();
                    }
                } catch (Throwable t) {
                    err.compareAndSet(null, t);
                }
            }
        };
        Thread a = new Thread(work, "catalog-a");
        Thread b = new Thread(work, "catalog-b");
        a.start();
        b.start();
        start.countDown();
        a.join(30_000L);
        b.join(30_000L);
        if (err.get() != null) {
            throw new AssertionError(err.get());
        }
        assertEquals(40, added.get());
        File dbDir = CacheLRUWrapper.sqliteCacheRoot(parent);
        File[] bad = dbDir.listFiles((d, n) -> n.contains(".corrupt-")
                || n.equals(SqliteCacheCatalog.FAILED_MARKER));
        assertTrue("split/quarantine: " + java.util.Arrays.toString(bad),
                bad == null || bad.length == 0);
        assertTrue(SqliteCacheCatalog.looksLikeSqliteHeader(
                new File(dbDir, SqliteCacheCatalog.DB_FILE_NAME)));
    }

    @Test
    public void waiterOpensHeldInitLockCatalogInsteadOfCreating() throws Exception {
        File parent = tmp.newFolder("held-initlock");
        File dbDir = CacheLRUWrapper.sqliteCacheRoot(parent);
        assertTrue(dbDir.mkdirs() || dbDir.isDirectory());
        File dbFile = new File(dbDir, SqliteCacheCatalog.DB_FILE_NAME);
        File lockDir = SqliteCacheCatalog.initLockDir(dbFile);
        CountDownLatch locked = new CountDownLatch(1);
        AtomicReference<Throwable> holderErr = new AtomicReference<Throwable>();
        Thread holder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    assertTrue(lockDir.mkdir());
                    locked.countDown();
                    Thread.sleep(150);
                    Class.forName("org.sqlite.JDBC");
                    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                         Statement st = c.createStatement()) {
                        st.execute("PRAGMA journal_mode=WAL");
                        st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
                    }
                    Thread.sleep(400);
                } catch (Throwable t) {
                    holderErr.compareAndSet(null, t);
                } finally {
                    lockDir.delete();
                }
            }
        }, "hold-initlock");
        holder.start();
        assertTrue(locked.await(5, TimeUnit.SECONDS));
        CacheLRUWrapper w = CacheLRUWrapper.createForTests(true, parent);
        try {
            w.lock();
            try {
                w.load();
                File jar = new File(dbDir, "1/http/wait.example/x.jar");
                assertTrue(jar.getParentFile().mkdirs() || jar.getParentFile().isDirectory());
                assertTrue(jar.createNewFile());
                assertTrue(w.addEntry("1,1", jar.getAbsolutePath()));
            } finally {
                w.unlock();
            }
        } finally {
            w.close();
        }
        holder.join(15_000L);
        if (holderErr.get() != null) {
            throw new AssertionError(holderErr.get());
        }
        File[] bad = dbDir.listFiles((d, n) -> n.contains(".corrupt-"));
        assertTrue("must not quarantine while peer holds initlock: " + java.util.Arrays.toString(bad),
                bad == null || bad.length == 0);
        assertTrue(SqliteCacheCatalog.looksLikeSqliteHeader(dbFile));
    }

    @Test
    public void heldInitLockDirDoesNotPublishLiveCatalog() throws Exception {
        File parent = tmp.newFolder("block-initlock");
        File dbDir = CacheLRUWrapper.sqliteCacheRoot(parent);
        assertTrue(dbDir.mkdirs() || dbDir.isDirectory());
        File dbFile = new File(dbDir, SqliteCacheCatalog.DB_FILE_NAME);
        assertTrue(dbFile.createNewFile());
        File lockDir = SqliteCacheCatalog.initLockDir(dbFile);
        assertTrue(lockDir.mkdir());
        try {
            CacheLRUWrapper w = CacheLRUWrapper.createForTests(true, parent);
            try {
                w.lock();
                try {
                    w.load();
                } finally {
                    w.unlock();
                }
            } finally {
                w.close();
            }
        } finally {
            lockDir.delete();
        }
        assertFalse("blocked waiter must not publish a live catalog",
                SqliteCacheCatalog.looksLikeSqliteHeader(dbFile));
        File[] bad = dbDir.listFiles((d, n) -> n.contains(".corrupt-")
                || n.equals(SqliteCacheCatalog.FAILED_MARKER));
        assertTrue("must not quarantine while initlock dir is held: " + java.util.Arrays.toString(bad),
                bad == null || bad.length == 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void createForTestsRejectsPropertiesBackend() {
        CacheLRUWrapper.createForTests(false, parentCache);
    }

    @Test
    public void leftoverStickyFailMarkerDoesNotSwitchToProperties() throws Exception {
        File marker = new File(dbRoot, SqliteCacheCatalog.FAILED_MARKER);
        assertTrue(marker.createNewFile());

        CacheLRUWrapper fallen = CacheLRUWrapper.createForTests(true, parentCache);
        try {
            assertTrue("product path stays sqlite even if an old marker is on disk",
                    fallen.isSqliteMode());
            File jar = new File(dbRoot, "2/http/sticky.example/app.jar");
            assertTrue(jar.getParentFile().mkdirs() || jar.getParentFile().isDirectory());
            assertTrue(jar.createNewFile());
            fallen.lock();
            try {
                fallen.load();
                assertTrue(fallen.addEntry(fallen.generateKey(jar.getAbsolutePath()), jar.getAbsolutePath()));
            } finally {
                fallen.unlock();
            }
            assertFalse(new File(dbRoot, "recently_used").isFile());
        } finally {
            fallen.close();
        }
    }

    @Test
    public void nextFolderIdUsesCatalogMaxThenFilesystemConfirm() throws Exception {
        File f0 = new File(dbRoot, "0/http/fold.example/a.jar");
        File f5 = new File(dbRoot, "5/http/fold.example/b.jar");
        assertTrue(f0.getParentFile().mkdirs() || f0.getParentFile().isDirectory());
        assertTrue(f5.getParentFile().mkdirs() || f5.getParentFile().isDirectory());
        assertTrue(f0.createNewFile());
        assertTrue(f5.createNewFile());
        wrapper.lock();
        try {
            wrapper.load();
            assertTrue(wrapper.addEntry("1000,0", f0.getAbsolutePath()));
            assertTrue(wrapper.addEntry("2000,5", f5.getAbsolutePath()));
            // filesystem ahead of catalog: dir 6 exists empty
            File extra = new File(dbRoot, "6");
            assertTrue(extra.mkdir() || extra.isDirectory());
            assertEquals(7, wrapper.nextFolderId());
            assertTrue("mkdir claim should create the allocated folder",
                    new File(dbRoot, "7").isDirectory());
        } finally {
            wrapper.unlock();
        }
    }

    @Test(timeout = 10000)
    public void claimFolderIdMkdirIsUniqueAcrossThreads() throws Exception {
        final File root = tmp.newFolder("claim-root");
        final int perThread = 30;
        final java.util.Set<Integer> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(2);
        Runnable body = new Runnable() {
            @Override
            public void run() {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ids.add(CacheCatalog.claimFolderId(root, 0));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }
        };
        Thread a = new Thread(body, "claim-a");
        Thread b = new Thread(body, "claim-b");
        a.start();
        b.start();
        start.countDown();
        assertTrue(done.await(8, TimeUnit.SECONDS));
        assertEquals(perThread * 2, ids.size());
    }

    @Test
    public void reserveTouchAndIndexedFind() throws Exception {
        File jar = new File(dbRoot, "3/http/example.com/app.jar");
        jar.getParentFile().mkdirs();
        assertTrue(jar.createNewFile());
        new File(jar.getPath() + CacheDirectory.INFO_SUFFIX).createNewFile();

        wrapper.lock();
        try {
            wrapper.load();
            String key = wrapper.generateKey(jar.getAbsolutePath());
            assertTrue(wrapper.addEntry(key, jar.getAbsolutePath()));
            assertTrue(wrapper.store());

            String urlPath = CacheUtil.pathToURLPath(jar.getAbsolutePath(), dbRoot.getAbsolutePath());
            List<Entry<String, String>> found = wrapper.findEntriesByUrlPath(urlPath);
            assertEquals(1, found.size());
            assertEquals(jar.getAbsolutePath(), found.get(0).getValue());

            assertTrue(wrapper.updateEntry(found.get(0).getKey()));
            List<Entry<String, String>> afterTouch = wrapper.findEntriesByUrlPath(urlPath);
            assertEquals(1, afterTouch.size());
            assertFalse(afterTouch.get(0).getKey().equals(key));
        } finally {
            wrapper.unlock();
        }
    }

    @Test
    public void isolationLegacyPathNotReturned() throws Exception {
        File legacy = new File(parentCache, "0/http/evil.example/legacy.jar");
        String legacyUrlPath = CacheUtil.pathToURLPath(legacy.getAbsolutePath(), parentCache.getAbsolutePath());

        wrapper.lock();
        try {
            wrapper.load();
            assertTrue(wrapper.findEntriesByUrlPath(legacyUrlPath).isEmpty());
            assertTrue(wrapper.getLRUSortedEntries().isEmpty());
        } finally {
            wrapper.unlock();
        }
    }

    @Test(timeout = 60000)
    public void concurrentUpdatesSameCatalog() throws Exception {
        // Keep modest: high fan-out + Windows WAL was observed to stall Surefire occasionally.
        final int threads = 4;
        final int perThread = 15;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger ok = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            Thread th = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            File jar = new File(dbRoot, tid + "/http/ex/" + i + ".jar");
                            jar.getParentFile().mkdirs();
                            jar.createNewFile();
                            new File(jar.getPath() + CacheDirectory.INFO_SUFFIX).createNewFile();
                            wrapper.lock();
                            try {
                                wrapper.load();
                                String key = System.nanoTime() + "," + tid;
                                if (wrapper.addEntry(key, jar.getAbsolutePath())) {
                                    ok.incrementAndGet();
                                }
                                wrapper.store();
                            } finally {
                                wrapper.unlock();
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                }
            }, "sqlite-catalog-" + t);
            th.setDaemon(true);
            th.start();
        }
        start.countDown();
        assertTrue("concurrent catalog updates timed out (possible lock inversion)",
                done.await(30, TimeUnit.SECONDS));
        assertEquals(threads * perThread, ok.get());
        wrapper.lock();
        try {
            assertEquals(threads * perThread, wrapper.getLRUSortedEntries().size());
        } finally {
            wrapper.unlock();
        }
    }

    /**
     * Two threads calling lock()/load()/unlock() must not invert the wrapper
     * monitor with the catalog ReentrantLock.
     */
    @Test(timeout = 5000)
    public void lockUnlockFromTwoThreadsDoesNotDeadlock() throws Exception {
        final CountDownLatch started = new CountDownLatch(2);
        final CountDownLatch done = new CountDownLatch(2);
        Runnable body = new Runnable() {
            @Override
            public void run() {
                started.countDown();
                try {
                    started.await(2, TimeUnit.SECONDS);
                    for (int i = 0; i < 50; i++) {
                        wrapper.lock();
                        try {
                            wrapper.load();
                        } finally {
                            wrapper.unlock();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }
        };
        Thread a = new Thread(body, "lock-a");
        Thread b = new Thread(body, "lock-b");
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
        assertTrue("lock/unlock deadlock between threads", done.await(4, TimeUnit.SECONDS));
    }

    /**
     * Uncommitted WAL work must not appear after reopen (crash / abrupt close).
     */
    @Test
    public void crashMidTransactionRollsBackUncommitted() throws Exception {
        wrapper.lock();
        try {
            wrapper.load();
            File jar = new File(dbRoot, "1/http/crash.example/ok.jar");
            jar.getParentFile().mkdirs();
            assertTrue(jar.createNewFile());
            assertTrue(wrapper.addEntry("1000,1", jar.getAbsolutePath()));
            wrapper.store();
            assertEquals(1, wrapper.getLRUSortedEntries().size());
        } finally {
            wrapper.unlock();
        }
        wrapper.close();

        File dbFile = new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME);
        Class.forName("org.sqlite.JDBC");
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath().replace('\\', '/'));
        try {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
                st.executeUpdate("INSERT INTO cache_entry"
                        + "(lru_key, resource_url, path, folder_id, last_access, state, created_at) VALUES ("
                        + "'9999,99','http/crash.example/ghost.jar','/tmp/ghost.jar',99,9999,'ready',9999)");
            }
            // Abrupt close without commit — simulates process kill mid-txn.
            c.close();
        } finally {
            if (!c.isClosed()) {
                c.close();
            }
        }

        CacheLRUWrapper reopened = CacheLRUWrapper.createForTests(true, parentCache);
        try {
            reopened.lock();
            try {
                reopened.load();
                assertEquals("uncommitted ghost row must not survive reopen",
                        1, reopened.getLRUSortedEntries().size());
            } finally {
                reopened.unlock();
            }
        } finally {
            reopened.close();
        }
    }

    @Test
    public void multiGenerationNewestFirstSkipsGhost() throws Exception {
        File older = new File(dbRoot, "10/http/gen.example/app.jar");
        File newerGhost = new File(dbRoot, "11/http/gen.example/app.jar");
        assertTrue(older.getParentFile().mkdirs() || older.getParentFile().isDirectory());
        assertTrue(newerGhost.getParentFile().mkdirs() || newerGhost.getParentFile().isDirectory());
        assertTrue(older.createNewFile());
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(older)) {
            out.write(new byte[] {'P', 'K', 3, 4});
        }
        // newer generation listed but jar missing (ghost)
        wrapper.lock();
        try {
            wrapper.load();
            assertTrue(wrapper.addEntry("1000,10", older.getAbsolutePath()));
            assertTrue(wrapper.addEntry("2000,11", newerGhost.getAbsolutePath()));
            wrapper.store();
            String url = CacheUtil.pathToURLPath(older.getAbsolutePath(), dbRoot.getAbsolutePath());
            List<Entry<String, String>> found = wrapper.findEntriesByUrlPath(url);
            assertEquals(2, found.size());
            assertEquals(newerGhost.getAbsolutePath(), found.get(0).getValue());
            File recovered = null;
            for (Entry<String, String> e : found) {
                File candidate = new File(e.getValue());
                if (candidate.isFile() && candidate.length() > 0) {
                    recovered = candidate;
                    break;
                }
            }
            assertEquals(older.getAbsolutePath(), recovered.getAbsolutePath());
        } finally {
            wrapper.unlock();
        }
    }

    @Test(timeout = 15000)
    public void busyTimeoutAllowsSecondConnectionToWait() throws Exception {
        wrapper.lock();
        try {
            wrapper.load();
        } finally {
            wrapper.unlock();
        }
        wrapper.close();

        File dbFile = new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME);
        Class.forName("org.sqlite.JDBC");
        final CountDownLatch holding = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger errors = new AtomicInteger();
        Thread holder = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Connection c = DriverManager.getConnection(
                            "jdbc:sqlite:" + dbFile.getAbsolutePath().replace('\\', '/'));
                    try {
                        c.createStatement().execute("PRAGMA busy_timeout=5000");
                        c.createStatement().execute("BEGIN IMMEDIATE");
                        holding.countDown();
                        Thread.sleep(400);
                        c.createStatement().execute("COMMIT");
                    } finally {
                        c.close();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    holding.countDown();
                } finally {
                    done.countDown();
                }
            }
        }, "sqlite-busy-holder");
        holder.setDaemon(true);
        holder.start();
        assertTrue(holding.await(5, TimeUnit.SECONDS));

        CacheLRUWrapper other = CacheLRUWrapper.createForTests(true, parentCache);
        try {
            File jar = new File(dbRoot, "8/http/busy.example/app.jar");
            assertTrue(jar.getParentFile().mkdirs() || jar.getParentFile().isDirectory());
            assertTrue(jar.createNewFile());
            long t0 = System.nanoTime();
            other.lock();
            try {
                other.load();
                assertTrue(other.addEntry(System.nanoTime() + ",8", jar.getAbsolutePath()));
            } finally {
                other.unlock();
            }
            long waitedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue("expected to wait on busy lock, waitedMs=" + waitedMs, waitedMs >= 200);
        } finally {
            other.close();
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
    }

    @Test
    public void cacheFsyncTogglesPragmaSynchronous() throws Exception {
        Boolean prev = SqliteCacheCatalog.fsyncOverrideForTests;
        try {
            SqliteCacheCatalog.fsyncOverrideForTests = Boolean.FALSE;
            assertEquals(1, pragmaSynchronous(tmp.newFolder("sync-normal")));
            SqliteCacheCatalog.fsyncOverrideForTests = Boolean.TRUE;
            assertEquals(2, pragmaSynchronous(tmp.newFolder("sync-full")));
        } finally {
            SqliteCacheCatalog.fsyncOverrideForTests = prev;
        }
    }

    private static int pragmaSynchronous(File parentCache) throws Exception {
        CacheLRUWrapper w = CacheLRUWrapper.createForTests(true, parentCache);
        try {
            w.lock();
            try {
                w.load();
                return w.sqlitePragmaSynchronous();
            } finally {
                w.unlock();
            }
        } finally {
            w.close();
        }
    }

    @Test(timeout = 15000)
    public void closeDoesNotBlockWhileAnotherConnectionIsOpen() throws Exception {
        CacheLRUWrapper other = CacheLRUWrapper.createForTests(true, parentCache);
        try {
            other.lock();
            try {
                other.load();
                other.store();
            } finally {
                other.unlock();
            }
            long t0 = System.nanoTime();
            wrapper.close();
            wrapper = null;
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue("close blocked on other connection: " + ms + "ms", ms < 8000);
        } finally {
            other.close();
        }
    }

    @Test
    public void parentCacheDirOfPrefersCacheFolderNotEarlierOrLaterDigitSegments() {
        // Earlier path segment "3" must not win; later URL path "/7/" must not truncate.
        String path = "C:/Users/3/work/cache/db/3/https/cdn.example.com/7/lib/app.jar";
        String parent = SqliteCacheCatalog.parentCacheDirOf(path, 3);
        assertEquals(new File("C:/Users/3/work/cache/db").getPath(), new File(parent).getPath());

        String resourceUrl = CacheUtil.pathToURLPath(
                path.replace('/', File.separatorChar), parent);
        String normalized = resourceUrl.replace('\\', '/');
        assertTrue("resource_url should keep URL path including /7/: " + normalized,
                normalized.contains("cdn.example.com/7/lib/app.jar"));
    }

    @Test
    public void parentCacheDirOfRejectsEmbeddedDigitsInLargerFolderId() {
        String path = "C:/cache/db/17/https/host/a.jar";
        // Searching for folder 7 must not match inside "17"
        String parent = SqliteCacheCatalog.parentCacheDirOf(path, 7);
        String norm = parent == null ? "" : parent.replace('\\', '/');
        assertFalse("must not treat /17/ as folder 7: " + norm, norm.endsWith("/db"));
    }

    @Test
    public void addEntryResourceUrlSurvivesFolderIdDigitsInUrlPath() throws Exception {
        File jar = new File(dbRoot, "3/https/cdn.example.com/7/lib/app.jar");
        assertTrue(jar.getParentFile().mkdirs() || jar.getParentFile().isDirectory());
        assertTrue(jar.createNewFile());
        wrapper.lock();
        try {
            wrapper.load();
            String key = SqliteCacheCatalog.lruKey(System.currentTimeMillis(), 3);
            assertTrue(wrapper.addEntry(key, jar.getAbsolutePath()));
            String url = CacheUtil.pathToURLPath(jar.getAbsolutePath(), dbRoot.getAbsolutePath());
            List<Entry<String, String>> found = wrapper.findEntriesByUrlPath(url);
            assertEquals(1, found.size());
            assertEquals(jar.getAbsolutePath(), found.get(0).getValue());
        } finally {
            wrapper.unlock();
        }
    }

    @Test
    public void sameMillisecondLruKeysRemainUniqueAcrossGenerateAndUpdate() throws Exception {
        File jarA = new File(dbRoot, "7/http/collide.example/a.jar");
        File jarB = new File(dbRoot, "7/http/collide.example/b.jar");
        assertTrue(jarA.getParentFile().mkdirs() || jarA.getParentFile().isDirectory());
        assertTrue(jarB.createNewFile() || jarB.isFile());
        assertTrue(jarA.createNewFile() || jarA.isFile());

        long fixed = 1_700_000_000_000L;
        String k1 = SqliteCacheCatalog.lruKey(fixed, 7);
        String k2 = SqliteCacheCatalog.lruKey(fixed, 7);
        assertFalse("seq must disambiguate same-ms keys", k1.equals(k2));
        assertTrue(k1.startsWith(fixed + ",7,"));
        assertTrue(k2.startsWith(fixed + ",7,"));

        wrapper.lock();
        try {
            wrapper.load();
            assertTrue(wrapper.addEntry(k1, jarA.getAbsolutePath()));
            assertTrue(wrapper.addEntry(k2, jarB.getAbsolutePath()));
            assertTrue(wrapper.updateEntry(k1));
            assertTrue(wrapper.updateEntry(k2));
            assertEquals(2, wrapper.getLRUSortedEntries().size());
        } finally {
            wrapper.unlock();
        }
    }

    @Test
    public void removeContainsClearAndGetValueRoundTrip() throws Exception {
        File jarA = new File(dbRoot, "4/http/round.example/a.jar");
        File jarB = new File(dbRoot, "5/http/round.example/b.jar");
        assertTrue(jarA.getParentFile().mkdirs() || jarA.getParentFile().isDirectory());
        assertTrue(jarB.getParentFile().mkdirs() || jarB.getParentFile().isDirectory());
        assertTrue(jarA.createNewFile());
        assertTrue(jarB.createNewFile());

        wrapper.lock();
        try {
            wrapper.load();
            String keyA = wrapper.generateKey(jarA.getAbsolutePath());
            String keyB = wrapper.generateKey(jarB.getAbsolutePath());
            assertTrue(wrapper.addEntry(keyA, jarA.getAbsolutePath()));
            assertTrue(wrapper.addEntry(keyB, jarB.getAbsolutePath()));
            assertTrue(wrapper.store());

            assertTrue(wrapper.containsKey(keyA));
            assertTrue(wrapper.containsValue(jarB.getAbsolutePath()));
            assertEquals(jarA.getAbsolutePath(), wrapper.getValue(keyA));
            assertEquals(2, wrapper.getLRUSortedEntries().size());

            assertTrue(wrapper.removeEntry(keyA));
            assertFalse(wrapper.containsKey(keyA));
            assertEquals(1, wrapper.getLRUSortedEntries().size());

            wrapper.clearLRUSortedEntries();
            assertTrue(wrapper.getLRUSortedEntries().isEmpty());
            assertFalse(wrapper.containsValue(jarB.getAbsolutePath()));
            assertFalse(wrapper.removeEntry(keyB)); // already cleared
        } finally {
            wrapper.unlock();
        }
    }

    @Test
    public void listMarkedForDeleteIsIndexedQueryNotFullScan() throws Exception {
        File keep = new File(dbRoot, "8/http/keep.example/keep.jar");
        File trash = new File(dbRoot, "9/http/trash.example/trash.jar");
        assertTrue(keep.getParentFile().mkdirs() || keep.getParentFile().isDirectory());
        assertTrue(trash.getParentFile().mkdirs() || trash.getParentFile().isDirectory());
        assertTrue(keep.createNewFile());
        assertTrue(trash.createNewFile());

        wrapper.lock();
        try {
            wrapper.load();
            String keyKeep = wrapper.generateKey(keep.getAbsolutePath());
            String keyTrash = wrapper.generateKey(trash.getAbsolutePath());
            assertTrue(wrapper.addEntry(keyKeep, keep.getAbsolutePath()));
            assertTrue(wrapper.addEntry(keyTrash, trash.getAbsolutePath()));
            CacheEntryMeta keepMeta = new CacheEntryMeta();
            keepMeta.path = keep.getAbsolutePath();
            keepMeta.markedDelete = false;
            keepMeta.contentLength = 4L;
            wrapper.putMeta(keepMeta);
            CacheEntryMeta trashMeta = new CacheEntryMeta();
            trashMeta.path = trash.getAbsolutePath();
            trashMeta.markedDelete = true;
            wrapper.putMeta(trashMeta);

            List<CacheCleanupRow> marked = wrapper.listMarkedForDelete();
            assertEquals(1, marked.size());
            assertEquals(trash.getAbsolutePath(), marked.get(0).path);

            List<CacheCleanupRow> live = wrapper.listUnmarkedLruNewestFirst();
            assertEquals(1, live.size());
            assertEquals(keep.getAbsolutePath(), live.get(0).path);
            assertEquals(Long.valueOf(4L), live.get(0).contentLength);
        } finally {
            wrapper.unlock();
        }
    }

    @Test
    public void transactRemoveIfUnlinkedRollsBackOnlyTheFailedFile() throws Exception {
        File keep = new File(dbRoot, "11/http/tx.example/keep.jar");
        File drop = new File(dbRoot, "12/http/tx.example/drop.jar");
        assertTrue(keep.getParentFile().mkdirs() || keep.getParentFile().isDirectory());
        assertTrue(drop.getParentFile().mkdirs() || drop.getParentFile().isDirectory());
        assertTrue(keep.createNewFile());
        assertTrue(drop.createNewFile());

        wrapper.lock();
        try {
            wrapper.load();
            assertTrue(wrapper.addEntry(wrapper.generateKey(keep.getAbsolutePath()), keep.getAbsolutePath()));
            assertTrue(wrapper.addEntry(wrapper.generateKey(drop.getAbsolutePath()), drop.getAbsolutePath()));

            assertFalse(wrapper.transactRemoveIfUnlinked(keep.getAbsolutePath(), () -> Boolean.FALSE));
            assertTrue("failed unlink must leave the row", wrapper.containsValue(keep.getAbsolutePath()));
            assertTrue(keep.isFile());

            assertTrue(wrapper.transactRemoveIfUnlinked(drop.getAbsolutePath(),
                    () -> CacheUtil.unlinkCachePath(drop.getAbsolutePath())));
            assertFalse(wrapper.containsValue(drop.getAbsolutePath()));
            assertFalse(drop.exists());
            assertTrue("other file's rollback must be independent", wrapper.containsValue(keep.getAbsolutePath()));
            assertTrue(keep.isFile());
        } finally {
            wrapper.unlock();
        }
    }

    @Test
    public void transactRemoveIfUnlinkedTreatsFileNotFoundAsCommit() throws Exception {
        File gone = new File(dbRoot, "13/http/tx.example/gone.jar");
        assertTrue(gone.getParentFile().mkdirs() || gone.getParentFile().isDirectory());
        wrapper.lock();
        try {
            wrapper.load();
            assertTrue(wrapper.addEntry(wrapper.generateKey(gone.getAbsolutePath()), gone.getAbsolutePath()));
            assertTrue(wrapper.transactRemoveIfUnlinked(gone.getAbsolutePath(),
                    () -> CacheUtil.unlinkCachePath(gone.getAbsolutePath())));
            assertFalse(wrapper.containsValue(gone.getAbsolutePath()));
        } finally {
            wrapper.unlock();
        }
    }

    @Test
    public void closeThenDeleteDbDirRecreatesCatalogWithoutTouchingLegacy() throws Exception {
        File legacy = new File(parentCache, "recently_used");
        byte[] before = java.nio.file.Files.readAllBytes(legacy.toPath());
        File jar = new File(dbRoot, "3/http/clear.example/app.jar");
        assertTrue(jar.getParentFile().mkdirs() || jar.getParentFile().isDirectory());
        assertTrue(jar.createNewFile());
        wrapper.lock();
        try {
            wrapper.load();
            assertTrue(wrapper.addEntry("1000,3", jar.getAbsolutePath()));
        } finally {
            wrapper.unlock();
        }
        File nativeDir = new File(dbRoot, "native");
        if (!nativeDir.isDirectory()) {
            assertTrue(nativeDir.mkdirs());
        }
        File nativeMarker = new File(nativeDir, "keep-me.dll");
        if (!nativeMarker.isFile()) {
            assertTrue(nativeMarker.createNewFile());
        }
        File dbFile = new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME);
        assertTrue(dbFile.isFile());
        wrapper.close();
        CacheUtil.deleteCacheContentsKeepingNative(dbRoot);
        assertTrue("JNI extract dir must survive clear-cache", nativeMarker.isFile());
        assertFalse(dbFile.exists());
        wrapper.lock();
        try {
            wrapper.load();
            assertTrue(wrapper.getLRUSortedEntries().isEmpty());
        } finally {
            wrapper.unlock();
        }
        assertTrue(new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME).isFile());
        byte[] after = java.nio.file.Files.readAllBytes(legacy.toPath());
        assertEquals(new String(before, java.nio.charset.StandardCharsets.UTF_8),
                new String(after, java.nio.charset.StandardCharsets.UTF_8));
    }
}
