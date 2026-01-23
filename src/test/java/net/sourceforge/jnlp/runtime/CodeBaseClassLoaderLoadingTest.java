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
import net.sourceforge.jnlp.mock.DummyJNLPFile;
import net.sourceforge.jnlp.security.appletextendedsecurity.AppletSecurityLevel;
import net.sourceforge.jnlp.security.appletextendedsecurity.AppletStartupSecuritySettings;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;

import java.lang.reflect.Method;
import java.net.URL;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Unit tests for CodeBaseClassLoader classloading functionality.
 * Tests the codebase classloader which loads classes from the codebase URL.
 */
public class CodeBaseClassLoaderLoadingTest extends NoStdOutErrTest {

    private static AppletSecurityLevel level;
    private static String macStatus;

    @BeforeClass
    public static void setUp() {
        level = AppletStartupSecuritySettings.getInstance().getSecurityLevel();
        macStatus = JNLPRuntime.getConfiguration().getProperty(
                DeploymentConfiguration.KEY_ENABLE_MANIFEST_ATTRIBUTES_CHECK);
        JNLPRuntime.getConfiguration().setProperty(
                DeploymentConfiguration.KEY_SECURITY_LEVEL,
                AppletSecurityLevel.ALLOW_UNSIGNED.toChars());
        JNLPRuntime.getConfiguration().setProperty(
                DeploymentConfiguration.KEY_ENABLE_MANIFEST_ATTRIBUTES_CHECK,
                "NONE");
    }

    @AfterClass
    public static void tearDown() {
        JNLPRuntime.getConfiguration().setProperty(
                DeploymentConfiguration.KEY_SECURITY_LEVEL,
                level.toChars());
        JNLPRuntime.getConfiguration().setProperty(
                DeploymentConfiguration.KEY_ENABLE_MANIFEST_ATTRIBUTES_CHECK,
                macStatus);
    }

    /**
     * Test CodeBaseClassLoader constructor and initialization.
     */
    @Test
    public void testCodeBaseClassLoaderCreation() throws Exception {
        JNLPFile dummyJnlpFile = new DummyJNLPFile();
        JNLPClassLoader parent = new JNLPClassLoader(dummyJnlpFile, UpdatePolicy.ALWAYS);

        JNLPClassLoader.CodeBaseClassLoader codeBaseLoader =
                new JNLPClassLoader.CodeBaseClassLoader(
                        new URL[]{DummyJNLPFile.JAR_URL, DummyJNLPFile.CODEBASE_URL},
                        parent);

        assertNotNull("CodeBaseClassLoader should be created", codeBaseLoader);
        assertNotNull("Parent should be set", codeBaseLoader.parentJNLPClassLoader);
        Assertions.assertEquals(parent, codeBaseLoader.parentJNLPClassLoader, "Parent should match");
    }

    /**
     * Test that CodeBaseClassLoader can add URLs.
     */
    @Test
    public void testCodeBaseClassLoaderAddURL() throws Exception {
        JNLPFile dummyJnlpFile = new DummyJNLPFile();
        JNLPClassLoader parent = new JNLPClassLoader(dummyJnlpFile, UpdatePolicy.ALWAYS);

        JNLPClassLoader.CodeBaseClassLoader codeBaseLoader =
                new JNLPClassLoader.CodeBaseClassLoader(
                        new URL[]{DummyJNLPFile.JAR_URL},
                        parent);

        URL[] initialURLs = codeBaseLoader.getURLs();
        int initialCount = initialURLs.length;

        // Add a new URL
        URL newURL = new URL("http://example.com/test.jar");
        codeBaseLoader.addURL(newURL);

        URL[] afterAddURLs = codeBaseLoader.getURLs();
        Assertions.assertEquals(initialCount + 1, afterAddURLs.length, "Should have one more URL");
    }

    /**
     * Test findClassNonRecursive method with non-existent class.
     */
    @Test
    public void testFindClassNonRecursiveWithNonExistentClass() throws Exception {
        JNLPFile dummyJnlpFile = new DummyJNLPFile();
        JNLPClassLoader parent = new JNLPClassLoader(dummyJnlpFile, UpdatePolicy.ALWAYS);

        JNLPClassLoader.CodeBaseClassLoader codeBaseLoader =
                new JNLPClassLoader.CodeBaseClassLoader(
                        new URL[]{DummyJNLPFile.JAR_URL, DummyJNLPFile.CODEBASE_URL},
                        parent);

        // Use reflection to call findClassNonRecursive
        Method findClassNonRecursiveMethod =
                JNLPClassLoader.CodeBaseClassLoader.class.getDeclaredMethod(
                        "findClassNonRecursive", String.class);
        findClassNonRecursiveMethod.setAccessible(true);

        try {
            findClassNonRecursiveMethod.invoke(codeBaseLoader, "com.nonexistent.Class");
            fail("Should have thrown ClassNotFoundException");
        } catch (Exception e) {
            // Expected - verify it's wrapped ClassNotFoundException
            Throwable cause = e.getCause();
            Assert.assertTrue("Should be ClassNotFoundException",
                    cause instanceof ClassNotFoundException);
        }
    }

    /**
     * Test that findClassNonRecursive caches not-found classes.
     */
    @Test
    public void testFindClassNonRecursiveCaching() throws Exception {
        JNLPFile dummyJnlpFile = new DummyJNLPFile();
        JNLPClassLoader parent = new JNLPClassLoader(dummyJnlpFile, UpdatePolicy.ALWAYS);

        JNLPClassLoader.CodeBaseClassLoader codeBaseLoader =
                new JNLPClassLoader.CodeBaseClassLoader(
                        new URL[]{DummyJNLPFile.JAR_URL},
                        parent);

        // Use reflection to call findClassNonRecursive
        Method findClassNonRecursiveMethod =
                JNLPClassLoader.CodeBaseClassLoader.class.getDeclaredMethod(
                        "findClassNonRecursive", String.class);
        findClassNonRecursiveMethod.setAccessible(true);

        String className = "com.never.exists.Class12345";

        // First call should try to find the class
        try {
            findClassNonRecursiveMethod.invoke(codeBaseLoader, className);
            fail("Should have thrown ClassNotFoundException");
        } catch (Exception e) {
            // Expected
        }

        // Second call should use cache and fail faster
        long startTime = System.nanoTime();
        try {
            findClassNonRecursiveMethod.invoke(codeBaseLoader, className);
            fail("Should have thrown ClassNotFoundException");
        } catch (Exception e) {
            // Expected
        }
        long endTime = System.nanoTime();

        // Verify it was fast (cached)
        long duration = endTime - startTime;
        // Should be very fast (less than 1ms for cached lookup)
        Assert.assertTrue("Cached lookup should be fast", duration < 1_000_000);
    }

    /**
     * Test that CodeBaseClassLoader has access to parent classloader.
     */
    @Test
    public void testCodeBaseClassLoaderParentAccess() throws Exception {
        JNLPFile dummyJnlpFile = new DummyJNLPFile();
        JNLPClassLoader parent = new JNLPClassLoader(dummyJnlpFile, UpdatePolicy.ALWAYS);

        JNLPClassLoader.CodeBaseClassLoader codeBaseLoader =
                new JNLPClassLoader.CodeBaseClassLoader(
                        new URL[]{DummyJNLPFile.JAR_URL},
                        parent);

        ClassLoader codeBaseParent = codeBaseLoader.getParent();
        assertNotNull("Parent should not be null", codeBaseParent);
        Assertions.assertEquals(parent, codeBaseParent, "Parent should be JNLPClassLoader");
    }

    /**
     * Test findLoadedClassFromParent method.
     */
    @Test
    public void testFindLoadedClassFromParent() throws Exception {
        JNLPFile dummyJnlpFile = new DummyJNLPFile();
        JNLPClassLoader parent = new JNLPClassLoader(dummyJnlpFile, UpdatePolicy.ALWAYS);

        // Load a class in parent first
        Class<?> loadedClass = parent.loadClass("java.lang.String");
        assertNotNull("Class should be loaded in parent", loadedClass);

        JNLPClassLoader.CodeBaseClassLoader codeBaseLoader =
                new JNLPClassLoader.CodeBaseClassLoader(
                        new URL[]{DummyJNLPFile.JAR_URL},
                        parent);

        // Use reflection to call findLoadedClassFromParent
        Method findLoadedClassFromParentMethod =
                JNLPClassLoader.CodeBaseClassLoader.class.getDeclaredMethod(
                        "findLoadedClassFromParent", String.class);
        findLoadedClassFromParentMethod.setAccessible(true);

        Class<?> foundClass = (Class<?>) findLoadedClassFromParentMethod.invoke(
                codeBaseLoader, "java.lang.String");

        assertNotNull("Should find loaded class from parent", foundClass);
        Assertions.assertEquals(loadedClass, foundClass, "Should be same class");
    }

    /**
     * Test that findLoadedClassFromParent returns null for unloaded classes.
     */
    @Test
    public void testFindLoadedClassFromParentReturnsNull() throws Exception {
        JNLPFile dummyJnlpFile = new DummyJNLPFile();
        JNLPClassLoader parent = new JNLPClassLoader(dummyJnlpFile, UpdatePolicy.ALWAYS);

        JNLPClassLoader.CodeBaseClassLoader codeBaseLoader =
                new JNLPClassLoader.CodeBaseClassLoader(
                        new URL[]{DummyJNLPFile.JAR_URL},
                        parent);

        // Use reflection to call findLoadedClassFromParent
        Method findLoadedClassFromParentMethod =
                JNLPClassLoader.CodeBaseClassLoader.class.getDeclaredMethod(
                        "findLoadedClassFromParent", String.class);
        findLoadedClassFromParentMethod.setAccessible(true);

        Class<?> foundClass = (Class<?>) findLoadedClassFromParentMethod.invoke(
                codeBaseLoader, "com.never.loaded.Class");

        assertNull("Should return null for unloaded class", foundClass);
    }

    /**
     * Test CodeBaseClassLoader with empty URL array.
     */
    @Test
    public void testCodeBaseClassLoaderWithEmptyURLs() throws Exception {
        JNLPFile dummyJnlpFile = new DummyJNLPFile();
        JNLPClassLoader parent = new JNLPClassLoader(dummyJnlpFile, UpdatePolicy.ALWAYS);

        JNLPClassLoader.CodeBaseClassLoader codeBaseLoader =
                new JNLPClassLoader.CodeBaseClassLoader(
                        new URL[]{},
                        parent);

        assertNotNull("CodeBaseClassLoader should be created with empty URLs", codeBaseLoader);
        URL[] urls = codeBaseLoader.getURLs();
        Assertions.assertEquals(0, urls.length, "Should have no URLs");
    }
}

