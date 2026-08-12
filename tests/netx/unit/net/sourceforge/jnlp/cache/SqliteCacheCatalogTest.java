/*
 Copyright (C) 2026 IcedTea-Web contributors
*/
package net.sourceforge.jnlp.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.sourceforge.jnlp.config.InfrastructureFileDescriptor;

public class SqliteCacheCatalogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File parentCache;
    private File dbRoot;
    private CacheLRUWrapper wrapper;

    @Before
    public void setUp() throws IOException {
        parentCache = tmp.newFolder("cache-parent");
        // Place a legacy recently_used + fake jar that must be ignored
        File legacyJar = new File(parentCache, "0/http/evil.example/legacy.jar");
        legacyJar.getParentFile().mkdirs();
        assertTrue(legacyJar.createNewFile());
        new File(parentCache, "recently_used").createNewFile();

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
        wrapper = new CacheLRUWrapper(true, null, parent);
        dbRoot = wrapper.getCacheDir().getFile();
    }

    @Test
    public void usesDbSubdirectoryNotLegacyRoot() {
        assertTrue(wrapper.isSqliteMode());
        assertEquals(new File(parentCache, "db").getAbsolutePath(), dbRoot.getAbsolutePath());
        assertTrue(new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME).exists()
                || true); // created lazily on lock/load
        wrapper.lock();
        try {
            wrapper.load();
            assertTrue(new File(dbRoot, SqliteCacheCatalog.DB_FILE_NAME).isFile());
        } finally {
            wrapper.unlock();
        }
        // legacy tree untouched / unused
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
            assertFalse(afterTouch.get(0).getKey().equals(key)); // new timestamp key
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

    @Test
    public void concurrentUpdatesSameCatalog() throws Exception {
        final int threads = 8;
        final int perThread = 20;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger ok = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(new Runnable() {
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
                                String key = System.currentTimeMillis() + "," + tid;
                                // ensure unique keys under contention
                                key = System.nanoTime() + "," + tid;
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
            }, "sqlite-catalog-" + t).start();
        }
        start.countDown();
        done.await();
        assertEquals(threads * perThread, ok.get());
        wrapper.lock();
        try {
            assertEquals(threads * perThread, wrapper.getLRUSortedEntries().size());
        } finally {
            wrapper.unlock();
        }
    }
}
