/*
 * Copyright (C) 2024 IcedTea-Web Contributors
 *
 * This file is part of IcedTea-Web.
 *
 * IcedTea-Web is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 2.
 *
 * IcedTea-Web is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IcedTea-Web; see the file COPYING.  If not, write to
 * the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */
package net.sourceforge.jnlp.runtime;

import net.sourceforge.jnlp.JNLPFile;
import net.sourceforge.jnlp.cache.UpdatePolicy;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.mock.DummyJNLPFileWithJar;
import net.sourceforge.jnlp.security.appletextendedsecurity.AppletSecurityLevel;
import net.sourceforge.jnlp.security.appletextendedsecurity.AppletStartupSecuritySettings;
import net.sourceforge.jnlp.util.FileTestUtils;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for thread safety of JNLPClassLoader classloading.
 * Tests concurrent classloading to ensure no deadlocks or race conditions.
 * Based on bug report RH976833 which addressed deadlock issues in multithreaded classloading.
 */
public class ClassLoadingThreadSafetyTest extends NoStdOutErrTest {

    private static AppletSecurityLevel level;
    private static String askUser;

    @BeforeClass
    public static void setUp() {
        level = AppletStartupSecuritySettings.getInstance().getSecurityLevel();
        JNLPRuntime.getConfiguration().setProperty(
                DeploymentConfiguration.KEY_SECURITY_LEVEL,
                AppletSecurityLevel.ALLOW_UNSIGNED.toChars());
        
        askUser = JNLPRuntime.getConfiguration().getProperty(
                DeploymentConfiguration.KEY_SECURITY_PROMPT_USER);
        JNLPRuntime.getConfiguration().setProperty(
                DeploymentConfiguration.KEY_SECURITY_PROMPT_USER,
                Boolean.toString(false));
    }

    @AfterClass
    public static void tearDown() {
        JNLPRuntime.getConfiguration().setProperty(
                DeploymentConfiguration.KEY_SECURITY_LEVEL,
                level.toChars());
        JNLPRuntime.getConfiguration().setProperty(
                DeploymentConfiguration.KEY_SECURITY_PROMPT_USER,
                askUser);
    }

    /**
     * Test concurrent loading of the same class from multiple threads.
     * This should not cause deadlocks due to per-class locking.
     */
    @Test
    public void testConcurrentLoadSameClass() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    Class<?> clazz = classLoader.loadClass("java.lang.String");
                    assertNotNull("Class should be loaded", clazz);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete (with timeout to detect deadlocks)
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue("All threads should complete without deadlock", completed);
        assertEquals("All threads should succeed", threadCount, successCount.get());
        assertTrue("No exceptions should occur", exceptions.isEmpty());
    }

    /**
     * Test concurrent loading of different classes from multiple threads.
     */
    @Test
    public void testConcurrentLoadDifferentClasses() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        String[] classNames = {
            "java.lang.String",
            "java.lang.Integer",
            "java.util.List",
            "java.util.ArrayList",
            "java.lang.Object",
            "java.lang.Exception",
            "java.io.File",
            "java.net.URL",
            "java.util.Map",
            "java.util.HashMap"
        };

        int threadCount = classNames.length;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final String className = classNames[i];
            executor.submit(() -> {
                try {
                    Class<?> clazz = classLoader.loadClass(className);
                    assertNotNull("Class should be loaded: " + className, clazz);
                    assertEquals("Class name should match", className, clazz.getName());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue("All threads should complete without deadlock", completed);
        assertEquals("All threads should succeed", threadCount, successCount.get());
        assertTrue("No exceptions should occur", exceptions.isEmpty());
    }

    /**
     * Test that multiple classloaders can load classes concurrently without interference.
     */
    @Test
    public void testConcurrentMultipleClassLoaders() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation1 = new File(tempDirectory, "test1.jar");
        File jarLocation2 = new File(tempDirectory, "test2.jar");
        FileTestUtils.createJarWithContents(jarLocation1);
        FileTestUtils.createJarWithContents(jarLocation2);

        final DummyJNLPFileWithJar jnlpFile1 = new DummyJNLPFileWithJar(jarLocation1);
        final DummyJNLPFileWithJar jnlpFile2 = new DummyJNLPFileWithJar(jarLocation2);
        final JNLPClassLoader classLoader1 = new JNLPClassLoader(jnlpFile1, UpdatePolicy.ALWAYS);
        final JNLPClassLoader classLoader2 = new JNLPClassLoader(jnlpFile2, UpdatePolicy.ALWAYS);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final JNLPClassLoader loader = (i % 2 == 0) ? classLoader1 : classLoader2;
            executor.submit(() -> {
                try {
                    Class<?> clazz = loader.loadClass("java.lang.String");
                    assertNotNull("Class should be loaded", clazz);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue("All threads should complete without deadlock", completed);
        assertEquals("All threads should succeed", threadCount, successCount.get());
        assertTrue("No exceptions should occur", exceptions.isEmpty());
    }

    /**
     * Test that classloading lock is per-class and doesn't block other classes.
     */
    @Test
    public void testPerClassLocking() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        // Use reflection to call getClassLoadingLock (protected method in ClassLoader)
        Method getClassLoadingLockMethod = ClassLoader.class.getDeclaredMethod(
                "getClassLoadingLock", String.class);
        getClassLoadingLockMethod.setAccessible(true);

        // Verify that different classes get different locks
        Object lock1 = getClassLoadingLockMethod.invoke(classLoader, "java.lang.String");
        Object lock2 = getClassLoadingLockMethod.invoke(classLoader, "java.lang.Integer");
        Object lock3 = getClassLoadingLockMethod.invoke(classLoader, "java.lang.String");

        assertNotNull("Lock should not be null", lock1);
        assertNotNull("Lock should not be null", lock2);
        assertEquals("Same class should get same lock", lock1, lock3);
        Assert.assertNotEquals("Different classes should get different locks", lock1, lock2);
    }

    /**
     * Test concurrent access to findLoadedClassAll from multiple threads.
     */
    @Test
    public void testConcurrentFindLoadedClassAll() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        // Load a class first
        Class<?> loadedClass = classLoader.loadClass("java.lang.String");
        assertNotNull("Class should be loaded", loadedClass);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    java.lang.reflect.Method method = JNLPClassLoader.class.getDeclaredMethod(
                            "findLoadedClassAll", String.class);
                    method.setAccessible(true);
                    Class<?> found = (Class<?>) method.invoke(classLoader, "java.lang.String");
                    if (found != null && found.equals(loadedClass)) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue("All threads should complete", completed);
        assertEquals("All threads should find the class", threadCount, successCount.get());
        assertTrue("No exceptions should occur", exceptions.isEmpty());
    }
}

