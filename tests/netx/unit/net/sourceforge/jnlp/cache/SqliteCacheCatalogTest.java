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
import java.sql.Statement;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    public void usesDbSubdirectoryNotLegacyRoot() {
        assertTrue(wrapper.isSqliteMode());
        assertEquals(new File(parentCache, "db").getAbsolutePath(), dbRoot.getAbsolutePath());
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
}
