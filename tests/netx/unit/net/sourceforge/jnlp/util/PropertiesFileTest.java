/*  PropertiesFileTest.java
   Copyright (C) 2012 Thomas Meyer

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

package net.sourceforge.jnlp.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

public class PropertiesFileTest {

    private PropertiesFile propertiesFile;

    @Before
    public void setup() throws IOException {
        File lru = Files.createTempFile("properties_file", ".tmp").toFile();
        lru.createNewFile();
        lru.deleteOnExit();
        propertiesFile = new PropertiesFile(lru);
    }

    @Test
    public void testSetProperty() {
        propertiesFile.setProperty("key", "value");
        assertTrue(propertiesFile.containsKey("key") && propertiesFile.containsValue("value"));
    }

    @Test
    public void testGetProperty() {
        propertiesFile.setProperty("key", "value");
        String v = propertiesFile.getProperty("key");
        assertEquals("value", v);
    }

    @Test
    public void testGetDefaultProperty() {
        String v = propertiesFile.getProperty("key", "default");
        assertEquals("default", v);
    }

    @Test
    public void testStore() throws IOException {
        String key = "key";
        String value = "value";
        propertiesFile.setProperty(key, value);
        try {
            propertiesFile.lock();
            propertiesFile.store();
        } finally {
            propertiesFile.unlock();
        }

        File f = propertiesFile.getStoreFile();
        String output = new String(Files.readAllBytes(f.toPath()));
        assertTrue(output.contains(key + "=" + value));
    }

    @Test
    public void testReloadAfterStore() {
        try {
            boolean reloaded;
            propertiesFile.lock();

            // 1. clear entries + store
            clearPropertiesFile();

            // 2. load from file
            reloaded = propertiesFile.load();
            assertTrue("File was not reloaded!", reloaded);

            // 3. add some entries and store
            fillProperties(10);

            propertiesFile.store();
            reloaded = propertiesFile.load();

            assertTrue("File was not reloaded!", reloaded);
        } finally {
            propertiesFile.unlock();
        }
    }

    private void fillProperties(int noEntries) {
        for(int i = 0; i < noEntries; i++) {
            propertiesFile.setProperty(String.valueOf(i), String.valueOf(i));
        }
    }

    private void clearPropertiesFile() {
        try {
            propertiesFile.lock();

            // clear cache + store file
            propertiesFile.clear();
            propertiesFile.store();
        } finally {
            propertiesFile.unlock();
        }
    }

    @Test
    public void testLoad() throws InterruptedException {
        try {
            propertiesFile.lock();

            propertiesFile.setProperty("key", "value");
            propertiesFile.store();

            propertiesFile.setProperty("shouldNotRemainAfterLoad", "def");
            propertiesFile.load();

            assertFalse(propertiesFile.contains("shouldNotRemainAfterLoad"));
        } finally {
            propertiesFile.unlock();

        }
    }

    @Test
    public void testLoadWithNoChanges() throws InterruptedException {
        try {
            propertiesFile.lock();

            propertiesFile.setProperty("key", "value");
            propertiesFile.store();

            Thread.sleep(1000l);

            assertFalse(propertiesFile.load());
        } finally {
            propertiesFile.unlock();
        }
    }

    @Test
    public void testLock() throws IOException {
        try {
            propertiesFile.lock();
            assertTrue(propertiesFile.isHeldByCurrentThread());
        } finally {
            propertiesFile.unlock();
        }
    }

    @Test
    public void testUnlock() throws IOException {
        try {
            propertiesFile.lock();
        } finally {
            propertiesFile.unlock();
        }
        assertTrue(!propertiesFile.isHeldByCurrentThread());
    }

    /**
     * Paths with a backslash before {@code t} must round-trip. A torn in-place
     * {@code Properties.store()} can turn {@code \\t} into a literal TAB and
     * corrupt cache indexes such as {@code recently_used}.
     */
    @Test
    public void testStorePreservesBackslashBeforeT() throws IOException {
        String key = "http://127.0.0.1:8080/lib.jar,1";
        String path = "C:\\work\\cache\\8080\\transaction-api_1.2_spec.jar";
        try {
            propertiesFile.lock();
            propertiesFile.setProperty(key, path);
            propertiesFile.store();
        } finally {
            propertiesFile.unlock();
        }

        PropertiesFile reloaded = new PropertiesFile(propertiesFile.getStoreFile());
        try {
            reloaded.lock();
            assertEquals(path, reloaded.getProperty(key));
            assertFalse("path must not contain a literal TAB from a torn escape",
                    reloaded.getProperty(key).indexOf('\t') >= 0);
        } finally {
            reloaded.unlock();
        }
    }

    /**
     * Concurrent loaders must never observe a mid-write properties dump when
     * store() replaces the target atomically.
     */
    @Test
    public void testConcurrentStoreDoesNotExposePartialFile() throws Exception {
        final String key = "cache.key";
        final String valuePrefix = "C:\\cache\\8080\\transaction-";
        final int writers = 4;
        final int readers = 8;
        final int rounds = 40;
        final ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        final CyclicBarrier start = new CyclicBarrier(writers + readers);
        final CountDownLatch done = new CountDownLatch(writers + readers);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            final int writerId = w;
            futures.add(pool.submit(() -> {
                try {
                    start.await(30, TimeUnit.SECONDS);
                    for (int i = 0; i < rounds; i++) {
                        try {
                            propertiesFile.lock();
                            propertiesFile.setProperty(key, valuePrefix + writerId + "-" + i + ".jar");
                            propertiesFile.store();
                        } finally {
                            propertiesFile.unlock();
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }));
        }
        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await(30, TimeUnit.SECONDS);
                    PropertiesFile reader = new PropertiesFile(propertiesFile.getStoreFile());
                    for (int i = 0; i < rounds * 2; i++) {
                        try {
                            reader.lock();
                            reader.load();
                            String value = reader.getProperty(key);
                            if (value != null) {
                                if (value.indexOf('\t') >= 0) {
                                    fail("reader observed TAB-corrupted path: " + value);
                                }
                                if (!value.startsWith(valuePrefix) || !value.endsWith(".jar")) {
                                    fail("reader observed torn value: " + value);
                                }
                            }
                        } finally {
                            reader.unlock();
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }));
        }

        assertTrue("concurrent store/load timed out", done.await(60, TimeUnit.SECONDS));
        pool.shutdownNow();
        if (failure.get() != null) {
            if (failure.get() instanceof Error) {
                throw (Error) failure.get();
            }
            if (failure.get() instanceof Exception) {
                throw (Exception) failure.get();
            }
            throw new AssertionError(failure.get());
        }
        for (Future<?> future : futures) {
            future.get(1, TimeUnit.SECONDS);
        }
    }
}
