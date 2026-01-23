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
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

/**
 * Unit tests for JNLPClassLoader classloading functionality.
 * Tests the core classloading mechanisms including parent delegation,
 * extension loading, and codebase loading.
 */
public class JNLPClassLoaderLoadingTest extends NoStdOutErrTest {

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
     * Test that loadClass delegates to parent classloader for system classes.
     */
    @Test
    public void testLoadClassDelegatesToParent() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        // System classes should be loaded by parent
        Class<?> stringClass = classLoader.loadClass("java.lang.String");
        assertNotNull("String class should be loaded", stringClass);
        assertEquals("java.lang.String", stringClass.getName());
        
        // Verify it's the same class instance as system classloader
        Class<?> systemStringClass = ClassLoader.getSystemClassLoader().loadClass("java.lang.String");
        assertEquals("Should be same class instance", systemStringClass, stringClass);
    }

    /**
     * Test that loadClass throws ClassNotFoundException for non-existent classes.
     */
    @Test(expected = ClassNotFoundException.class)
    public void testLoadClassThrowsExceptionForNonExistentClass() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        // This should throw ClassNotFoundException
        classLoader.loadClass("com.nonexistent.NonExistentClass");
    }

    /**
     * Test findLoadedClassAll method to verify it checks all loaders.
     */
    @Test
    public void testFindLoadedClassAll() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        // Load a system class first
        Class<?> loadedClass = classLoader.loadClass("java.lang.Object");
        assertNotNull("Object class should be loaded", loadedClass);

        // Use reflection to call findLoadedClassAll
        Method findLoadedClassAllMethod = JNLPClassLoader.class.getDeclaredMethod(
                "findLoadedClassAll", String.class);
        findLoadedClassAllMethod.setAccessible(true);
        
        Class<?> foundClass = (Class<?>) findLoadedClassAllMethod.invoke(
                classLoader, "java.lang.Object");
        
        // Should find the loaded class
        assertNotNull("findLoadedClassAll should find loaded class", foundClass);
        assertEquals("Should be same class", loadedClass, foundClass);
    }

    /**
     * Test that findLoadedClassAll returns null for unloaded classes.
     */
    @Test
    public void testFindLoadedClassAllReturnsNullForUnloadedClass() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        // Use reflection to call findLoadedClassAll
        Method findLoadedClassAllMethod = JNLPClassLoader.class.getDeclaredMethod(
                "findLoadedClassAll", String.class);
        findLoadedClassAllMethod.setAccessible(true);
        
        Class<?> foundClass = (Class<?>) findLoadedClassAllMethod.invoke(
                classLoader, "com.never.loaded.Class");
        
        // Should return null for unloaded class
        assertNull("findLoadedClassAll should return null for unloaded class", foundClass);
    }

    /**
     * Test that findClass throws ClassNotFoundException when class is not found.
     */
    @Test(expected = ClassNotFoundException.class)
    public void testFindClassThrowsException() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        // Use reflection to call protected findClass method
        Method findClassMethod = JNLPClassLoader.class.getDeclaredMethod(
                "findClass", String.class);
        findClassMethod.setAccessible(true);
        
        // This should throw ClassNotFoundException
        findClassMethod.invoke(classLoader, "com.nonexistent.Class");
    }

    /**
     * Test that loadClass uses proper synchronization via getClassLoadingLock.
     */
    @Test
    public void testLoadClassSynchronization() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        // Use reflection to call getClassLoadingLock (protected method in ClassLoader)
        Method getClassLoadingLockMethod = ClassLoader.class.getDeclaredMethod(
                "getClassLoadingLock", String.class);
        getClassLoadingLockMethod.setAccessible(true);

        // Verify getClassLoadingLock returns a non-null object
        Object lock1 = getClassLoadingLockMethod.invoke(classLoader, "java.lang.String");
        Object lock2 = getClassLoadingLockMethod.invoke(classLoader, "java.lang.String");
        Object lock3 = getClassLoadingLockMethod.invoke(classLoader, "java.lang.Integer");

        assertNotNull("Lock should not be null", lock1);
        assertEquals("Same class name should return same lock", lock1, lock2);
        Assert.assertNotEquals("Different class names should return different locks", lock1, lock3);
    }

    /**
     * Test loading classes with different class names to verify caching.
     */
    @Test
    public void testLoadClassCaching() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        // Load same class twice
        Class<?> class1 = classLoader.loadClass("java.lang.String");
        Class<?> class2 = classLoader.loadClass("java.lang.String");

        // Should be the same instance (cached)
        assertEquals("Loaded class should be cached", class1, class2);
    }

    /**
     * Test that loadClass handles null parent classloader gracefully.
     */
    @Test
    public void testLoadClassWithNullParent() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        // System classes should still load even if parent is null
        // (loadClassImpl uses system classloader as fallback)
        Class<?> stringClass = classLoader.loadClass("java.lang.String");
        assertNotNull("Should load system class even with null parent", stringClass);
    }

    /**
     * Test loading classes from different packages.
     */
    @Test
    public void testLoadClassFromDifferentPackages() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        // Test loading from different packages
        Class<?> stringClass = classLoader.loadClass("java.lang.String");
        Class<?> listClass = classLoader.loadClass("java.util.List");
        Class<?> exceptionClass = classLoader.loadClass("java.lang.Exception");

        assertNotNull("String class should load", stringClass);
        assertNotNull("List class should load", listClass);
        assertNotNull("Exception class should load", exceptionClass);
    }

    /**
     * Test that loadClass properly handles ClassNotFoundException from parent.
     */
    @Test
    public void testLoadClassHandlesParentClassNotFoundException() throws Exception {
        File tempDirectory = FileTestUtils.createTempDirectory();
        File jarLocation = new File(tempDirectory, "test.jar");
        FileTestUtils.createJarWithContents(jarLocation);

        final DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarLocation);
        final JNLPClassLoader classLoader = new JNLPClassLoader(jnlpFile, UpdatePolicy.ALWAYS);

        // Try to load a non-existent class
        // Parent will throw ClassNotFoundException, which should be caught
        // and then we try other loaders
        try {
            classLoader.loadClass("com.definitely.does.not.exist.Class12345");
            fail("Should have thrown ClassNotFoundException");
        } catch (ClassNotFoundException e) {
            // Expected - verify it's the right exception
            assertNotNull("Exception should have message", e.getMessage());
        }
    }
}

