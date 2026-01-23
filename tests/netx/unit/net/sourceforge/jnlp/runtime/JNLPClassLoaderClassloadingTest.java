/*Copyright (C) 2024 IcedTea-Web Contributors

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
package net.sourceforge.jnlp.runtime;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import net.sourceforge.jnlp.LaunchException;
import net.sourceforge.jnlp.cache.UpdatePolicy;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.mock.DummyJNLPFileWithJar;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.security.appletextendedsecurity.AppletSecurityLevel;
import net.sourceforge.jnlp.security.appletextendedsecurity.AppletStartupSecuritySettings;
import net.sourceforge.jnlp.util.FileTestUtils;

public class JNLPClassLoaderClassloadingTest {

    private static AppletSecurityLevel level;
    private static String askUser;

    private static File testJarsDir;
    private static File testJarFile;

    @BeforeAll
    public static void setUpTestJars() throws Exception {
        // Create a subfolder for test JARs
        File tempBase = new File(System.getProperty("java.io.tmpdir"));
        testJarsDir = new File(tempBase, "icedtea-web-test-jars-" + System.currentTimeMillis());
        testJarsDir.mkdirs();
        testJarsDir.deleteOnExit();
        System.out.println("Created test JARs directory: " + testJarsDir.getAbsolutePath());
        
        // Create the test JAR file in advance
        System.out.println("Creating test JAR file in advance...");
        testJarFile = new File(testJarsDir, "test-classloader.jar");
        createTestJarInAdvance(testJarFile);
        System.out.println("Test JAR created: " + testJarFile.getAbsolutePath() + " (" + testJarFile.length() + " bytes)");
        
        // Set permissions
        level = AppletStartupSecuritySettings.getInstance().getSecurityLevel();
        JNLPRuntime.getConfiguration().setProperty(DeploymentConfiguration.KEY_SECURITY_LEVEL, AppletSecurityLevel.ALLOW_UNSIGNED.toChars());
        askUser = JNLPRuntime.getConfiguration().getProperty(DeploymentConfiguration.KEY_SECURITY_PROMPT_USER);
        JNLPRuntime.getConfiguration().setProperty(DeploymentConfiguration.KEY_SECURITY_PROMPT_USER, Boolean.toString(false));
    }
    
    /**
     * Create a test JAR file in advance with a simple TestClass
     */
    private static void createTestJarInAdvance(File jarFile) throws Exception {
        // Create a temporary directory for compilation
        File tempDir = FileTestUtils.createTempDirectory();
        tempDir.deleteOnExit();
        
        // Create Java source file
        File sourceFile = new File(tempDir, "TestClass.java");
        String sourceCode = 
            "public class TestClass {\n" +
            "    public TestClass() {\n" +
            "        System.out.println(\"[TestClass] Constructor called!\");\n" +
            "    }\n" +
            "    public String getMessage() {\n" +
            "        System.out.println(\"[TestClass] getMessage() called!\");\n" +
            "        return \"Hello from TestClass\";\n" +
            "    }\n" +
            "    public static void main(String[] args) {\n" +
            "        System.out.println(\"[TestClass] main() called with args: \" + java.util.Arrays.toString(args));\n" +
            "        TestClass tc = new TestClass();\n" +
            "        System.out.println(\"[TestClass] Message: \" + tc.getMessage());\n" +
            "    }\n" +
            "}\n";
        Files.write(sourceFile.toPath(), sourceCode.getBytes());
        
        // Compile
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("Java compiler not available");
        }
        
        java.io.ByteArrayOutputStream errorOutput = new java.io.ByteArrayOutputStream();
        javax.tools.StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends javax.tools.JavaFileObject> compilationUnits = fileManager.getJavaFileObjects(sourceFile);
        Iterable<String> options = java.util.Arrays.asList("-d", tempDir.getAbsolutePath());
        java.io.PrintWriter errWriter = new java.io.PrintWriter(new java.io.PrintStream(errorOutput), true);
        javax.tools.JavaCompiler.CompilationTask task = compiler.getTask(
            errWriter,
            fileManager,
            null,
            options,
            null,
            compilationUnits
        );
        boolean success = task.call();
        fileManager.close();
        
        if (!success) {
            throw new RuntimeException("Compilation failed: " + errorOutput.toString());
        }
        
        // Find the compiled class file
        File classFile = new File(tempDir, "TestClass.class");
        if (!classFile.exists()) {
            throw new RuntimeException("Class file not found after compilation");
        }
        
        // Create JAR
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        
        try (JarOutputStream jarOut = new JarOutputStream(
                new FileOutputStream(jarFile), manifest)) {
            JarEntry entry = new JarEntry("TestClass.class");
            jarOut.putNextEntry(entry);
            Files.copy(classFile.toPath(), jarOut);
            jarOut.closeEntry();
        }
    }

    @AfterAll
    public static void resetPermissions() {
        JNLPRuntime.getConfiguration().setProperty(DeploymentConfiguration.KEY_SECURITY_LEVEL, level.toChars());
        JNLPRuntime.getConfiguration().setProperty(DeploymentConfiguration.KEY_SECURITY_PROMPT_USER, askUser);
    }

    /**
     * Test that we can load a class from a pre-created JAR using JNLPClassLoader
     */
    @Test
    public void testLoadClassFromJar() throws Exception {
        System.out.println("\n=== testLoadClassFromJar ===");
        long startTime = System.nanoTime();
        
        // Use the pre-created JAR file from the subfolder
        File jarFile = testJarFile;
        System.out.println("Using pre-created JAR file: " + jarFile.getAbsolutePath());
        System.out.println("  Size: " + jarFile.length() + " bytes");
        System.out.println("  JAR URL: " + jarFile.toURI().toURL());
        assertTrue(jarFile.exists(), "Pre-created JAR file should exist");

        // Create a JNLP file wrapper for the JAR
        System.out.println("Creating JNLP file wrapper...");
        DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarFile);
        System.out.println("✓ JNLP file wrapper created");
        System.out.println("  JAR URL in JNLP: " + jarFile.toURI().toURL());

        // Create JNLPClassLoader and load the class
        System.out.println("Creating JNLPClassLoader...");
        System.out.println("  -> This will process the JAR file and may create temporary copies");
        long loaderStart = System.nanoTime();
        JNLPClassLoader classLoader = new JNLPClassLoader(
            jnlpFile, 
            UpdatePolicy.ALWAYS
        );
        long loaderTime = System.nanoTime() - loaderStart;
        System.out.println("✓ JNLPClassLoader created successfully!");
        System.out.println("  Creation time: " + (loaderTime / 1_000_000.0) + " ms");
        
        // Try to find any temporary JAR files that might have been created
        try {
            java.io.File tempBase = new java.io.File(System.getProperty("java.io.tmpdir"), "icedtea-web");
            if (tempBase.exists()) {
                System.out.println("  Checking for temporary JAR files in: " + tempBase.getAbsolutePath());
                java.io.File[] tempFiles = tempBase.listFiles((dir, name) -> name.endsWith(".jar"));
                if (tempFiles != null && tempFiles.length > 0) {
                    System.out.println("  Found " + tempFiles.length + " temporary JAR file(s):");
                    for (java.io.File tf : tempFiles) {
                        System.out.println("    - " + tf.getAbsolutePath() + " (" + tf.length() + " bytes)");
                    }
                } else {
                    System.out.println("  No temporary JAR files found (JAR may be used directly)");
                }
            }
        } catch (Exception e) {
            System.out.println("  Could not check for temporary files: " + e.getMessage());
        }

        // Load the class from the JAR
        System.out.println("Loading class 'TestClass' from JAR...");
        System.out.println("  -> This will read the class file from the JAR");
        long loadClassStart = System.nanoTime();
        Class<?> loadedClass = classLoader.loadClass("TestClass");
        long loadClassTime = System.nanoTime() - loadClassStart;
        System.out.println("✓ Class 'TestClass' loaded successfully!");
        System.out.println("  Load time: " + (loadClassTime / 1_000_000.0) + " ms");

        // Verify the class was loaded correctly
        assertNotNull(loadedClass, "Class should be loaded");
        assertEquals("TestClass", loadedClass.getSimpleName());
        System.out.println("Class name verified: " + loadedClass.getName());

        // Instantiate and use the class
        System.out.println("Instantiating TestClass...");
        System.out.println("  -> This will trigger the constructor which prints: [TestClass] Constructor called!");
        long instantiateStart = System.nanoTime();
        Object instance = loadedClass.getDeclaredConstructor().newInstance();
        long instantiateTime = System.nanoTime() - instantiateStart;
        System.out.println("Instance created (took " + (instantiateTime / 1_000_000.0) + " ms)");

        System.out.println("Calling getMessage() method...");
        System.out.println("  -> This will trigger getMessage() which prints: [TestClass] getMessage() called!");
        long methodStart = System.nanoTime();
        String message = (String) loadedClass.getMethod("getMessage").invoke(instance);
        long methodTime = System.nanoTime() - methodStart;
        System.out.println("Method returned: '" + message + "' (took " + (methodTime / 1_000_000.0) + " ms)");
        assertEquals("Hello from TestClass", message);
        
        long totalTime = System.nanoTime() - startTime;
        System.out.println("=== Test completed in " + (totalTime / 1_000_000.0) + " ms ===\n");
    }

    /**
     * Test loading a class with a package name
     */
    @Test
    public void testLoadClassWithPackageFromJar() throws Exception {
        System.out.println("\n=== testLoadClassWithPackageFromJar ===");
            long startTime = System.nanoTime();
            
            File tempDir = FileTestUtils.createTempDirectory();
            tempDir.deleteOnExit();
            System.out.println("Created temp directory: " + tempDir.getAbsolutePath());
            
            // Create package directory structure
            File packageDir = new File(tempDir, "com/example");
            packageDir.mkdirs();
            System.out.println("Created package directory: " + packageDir.getAbsolutePath());
            
            // Create Java source with package
            File sourceFile = new File(packageDir, "MyClass.java");
            String sourceCode = 
                "package com.example;\n" +
                "public class MyClass {\n" +
                "    public MyClass() {\n" +
                "        System.out.println(\"[com.example.MyClass] Constructor called!\");\n" +
                "    }\n" +
                "    public int getValue() {\n" +
                "        System.out.println(\"[com.example.MyClass] getValue() called!\");\n" +
                "        return 42;\n" +
                "    }\n" +
                "}\n";
            Files.write(sourceFile.toPath(), sourceCode.getBytes());
            System.out.println("Created source file: " + sourceFile.getAbsolutePath());
            
            // Compile
            long compileStart = System.nanoTime();
            File classFile = compileJavaFile(sourceFile, tempDir);
            long compileTime = System.nanoTime() - compileStart;
            System.out.println("Compiled class file: " + classFile.getAbsolutePath() + 
                             " (took " + (compileTime / 1_000_000.0) + " ms)");
            
            // Create JAR
            long jarStart = System.nanoTime();
            File jarFile = new File(tempDir, "test-package.jar");
            createJarWithClass(jarFile, classFile, tempDir);
            long jarTime = System.nanoTime() - jarStart;
            System.out.println("Created JAR file: " + jarFile.getAbsolutePath() + 
                             " (size: " + jarFile.length() + " bytes, took " + (jarTime / 1_000_000.0) + " ms)");
            
            // Load class
            DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarFile);
            System.out.println("Created JNLP file wrapper for: " + jarFile.toURI().toURL());
            
            long loaderStart = System.nanoTime();
            JNLPClassLoader classLoader = new JNLPClassLoader(
                jnlpFile, 
                UpdatePolicy.ALWAYS
            );
            long loaderTime = System.nanoTime() - loaderStart;
            System.out.println("Created JNLPClassLoader (took " + (loaderTime / 1_000_000.0) + " ms)");

            System.out.println("Loading class 'com.example.MyClass' from JAR...");
            long loadClassStart = System.nanoTime();
            Class<?> loadedClass = classLoader.loadClass("com.example.MyClass");
            long loadClassTime = System.nanoTime() - loadClassStart;
            System.out.println("Class loaded successfully (took " + (loadClassTime / 1_000_000.0) + " ms)");
            assertNotNull(loadedClass);
            System.out.println("Class name: " + loadedClass.getName());
            
            System.out.println("Instantiating com.example.MyClass...");
            long instantiateStart = System.nanoTime();
            Object instance = loadedClass.getDeclaredConstructor().newInstance();
            long instantiateTime = System.nanoTime() - instantiateStart;
            System.out.println("Instance created (took " + (instantiateTime / 1_000_000.0) + " ms)");

            System.out.println("Calling getValue() method...");
            long methodStart = System.nanoTime();
            int value = (Integer) loadedClass.getMethod("getValue").invoke(instance);
            long methodTime = System.nanoTime() - methodStart;
            System.out.println("Method returned: " + value + " (took " + (methodTime / 1_000_000.0) + " ms)");
            assertEquals(42, value);
        
            long totalTime = System.nanoTime() - startTime;
            System.out.println("=== Test completed in " + (totalTime / 1_000_000.0) + " ms ===\n");
    }

    /**
     * Test that ClassNotFoundException is thrown for non-existent classes
     */
    @Test
    public void testLoadNonExistentClassThrowsException() throws Exception {
        System.out.println("\n=== testLoadNonExistentClassThrowsException ===");
            long startTime = System.nanoTime();
            
            File tempDir = FileTestUtils.createTempDirectory();
            tempDir.deleteOnExit();
            System.out.println("Created temp directory: " + tempDir.getAbsolutePath());

            // Create empty JAR (just manifest)
            long jarStart = System.nanoTime();
            File jarFile = new File(tempDir, "empty.jar");
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            FileTestUtils.createJarWithContents(jarFile, manifest);
            long jarTime = System.nanoTime() - jarStart;
            System.out.println("Created empty JAR file: " + jarFile.getAbsolutePath() + 
                             " (size: " + jarFile.length() + " bytes, took " + (jarTime / 1_000_000.0) + " ms)");

            DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarFile);
            System.out.println("Created JNLP file wrapper for: " + jarFile.toURI().toURL());
            
            long loaderStart = System.nanoTime();
            JNLPClassLoader classLoader = new JNLPClassLoader(
                jnlpFile, 
                UpdatePolicy.ALWAYS
            );
            long loaderTime = System.nanoTime() - loaderStart;
            System.out.println("Created JNLPClassLoader (took " + (loaderTime / 1_000_000.0) + " ms)");

            // Should throw ClassNotFoundException
            System.out.println("Attempting to load non-existent class 'NonExistentClass'...");
            long loadStart = System.nanoTime();
            assertThrows(ClassNotFoundException.class, () -> {
                classLoader.loadClass("NonExistentClass");
            });
            long loadTime = System.nanoTime() - loadStart;
            System.out.println("ClassNotFoundException thrown as expected (took " + (loadTime / 1_000_000.0) + " ms)");
        
            long totalTime = System.nanoTime() - startTime;
            System.out.println("=== Test completed in " + (totalTime / 1_000_000.0) + " ms ===\n");
    }

    /**
     * Test loading resources (not just classes) from JAR
     */
    @Test
    public void testLoadResourceFromJar() throws Exception {
        System.out.println("\n=== testLoadResourceFromJar ===");
            long startTime = System.nanoTime();
            
            File tempDir = FileTestUtils.createTempDirectory();
            tempDir.deleteOnExit();
            System.out.println("Created temp directory: " + tempDir.getAbsolutePath());

            // Create a text file to include in JAR
            File textFile = new File(tempDir, "test.txt");
            String content = "Test resource content";
            Files.write(textFile.toPath(), content.getBytes());
            System.out.println("Created text file: " + textFile.getAbsolutePath() + 
                             " (size: " + textFile.length() + " bytes)");

            // Create JAR with the text file
            long jarStart = System.nanoTime();
            File jarFile = new File(tempDir, "test-resource.jar");
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            FileTestUtils.createJarWithContents(jarFile, manifest, textFile);
            long jarTime = System.nanoTime() - jarStart;
            System.out.println("Created JAR file: " + jarFile.getAbsolutePath() + 
                             " (size: " + jarFile.length() + " bytes, took " + (jarTime / 1_000_000.0) + " ms)");

            // Load resource
            DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarFile);
            System.out.println("Created JNLP file wrapper for: " + jarFile.toURI().toURL());
            
            long loaderStart = System.nanoTime();
            JNLPClassLoader classLoader = new JNLPClassLoader(
                jnlpFile, 
                UpdatePolicy.ALWAYS
            );
            long loaderTime = System.nanoTime() - loaderStart;
            System.out.println("Created JNLPClassLoader (took " + (loaderTime / 1_000_000.0) + " ms)");

            System.out.println("Searching for resource 'test.txt'...");
            long findStart = System.nanoTime();
            URL resource = classLoader.findResource("test.txt");
            long findTime = System.nanoTime() - findStart;
            assertNotNull(resource, "Resource should be found");
            System.out.println("Resource found: " + resource + " (took " + (findTime / 1_000_000.0) + " ms)");

            // Read resource content
            System.out.println("Reading resource content...");
            long readStart = System.nanoTime();
            byte[] readContent = resource.openStream().readAllBytes();
            long readTime = System.nanoTime() - readStart;
            String contentStr = new String(readContent);
            System.out.println("Resource content: '" + contentStr + "' (took " + (readTime / 1_000_000.0) + " ms)");
            assertEquals("Test resource content", contentStr);
        
            long totalTime = System.nanoTime() - startTime;
            System.out.println("=== Test completed in " + (totalTime / 1_000_000.0) + " ms ===\n");
    }

    /**
     * Helper method: Compile a Java source file to a .class file
     */
    private File compileJavaFile(File sourceFile, File outputDir) throws IOException {
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("Java compiler not available. " +
                "Make sure you're running with JDK (not JRE)");
        }

        // Compile the source file - use simple approach without sourcepath for now
        System.out.println("  Compiling with javac...");
        System.err.println("  [STDERR] ===== Starting compilation process =====");
        System.out.println("    Output dir: " + outputDir.getAbsolutePath());
        System.out.println("    Source file: " + sourceFile.getAbsolutePath());
        System.out.flush();
        System.err.flush();
        
        System.err.println("  [STDERR] Creating ByteArrayOutputStreams...");
        System.err.flush();
        java.io.ByteArrayOutputStream errorOutput = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream stdoutCapture = new java.io.ByteArrayOutputStream();
        System.err.println("  [STDERR] ByteArrayOutputStreams created");
        System.err.flush();
        
        System.err.println("  [STDERR] Creating PrintStreams...");
        System.err.flush();
        java.io.PrintStream stdoutStream = new java.io.PrintStream(stdoutCapture, true);
        java.io.PrintStream stderrStream = new java.io.PrintStream(errorOutput, true);
        System.err.println("  [STDERR] PrintStreams created");
        System.err.flush();
        
        System.out.println("  Running javac compiler...");
        System.err.println("  [STDERR] About to call compiler.run()");
        System.out.flush();
        System.err.flush();
        
        long compilerStart = System.nanoTime();
        System.err.println("  [STDERR] ===== About to call compiler.run() NOW =====");
        System.err.flush();
        
        // Verify files exist before compiling
        System.err.println("  [STDERR] Verifying files exist...");
        System.err.println("  [STDERR]   Source file exists: " + sourceFile.exists() + " (" + sourceFile.getAbsolutePath() + ")");
        System.err.println("  [STDERR]   Output dir exists: " + outputDir.exists() + " (" + outputDir.getAbsolutePath() + ")");
        System.err.println("  [STDERR]   Output dir is directory: " + outputDir.isDirectory());
        System.err.println("  [STDERR]   Output dir is writable: " + outputDir.canWrite());
        System.err.flush();
        
        boolean success = false;
        try {
            System.err.println("  [STDERR] ===== Using getTask() instead of run() =====");
            System.err.flush();
            
            // Use getTask() for better control and to avoid potential blocking issues
            javax.tools.StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
            System.err.println("  [STDERR] Got StandardJavaFileManager");
            System.err.flush();
            
            Iterable<? extends javax.tools.JavaFileObject> compilationUnits = 
                fileManager.getJavaFileObjects(sourceFile);
            System.err.println("  [STDERR] Got compilation units");
            System.err.flush();
            
            Iterable<String> options = java.util.Arrays.asList("-d", outputDir.getAbsolutePath());
            System.err.println("  [STDERR] Created options, calling getTask()...");
            System.err.flush();
            
            java.io.PrintWriter errWriter = new java.io.PrintWriter(stderrStream, true);
            javax.tools.JavaCompiler.CompilationTask task = compiler.getTask(
                errWriter,     // Writer for diagnostic output
                fileManager,   // fileManager
                null,          // DiagnosticListener (null = use default)
                options,       // options
                null,          // classes (for annotation processing)
                compilationUnits
            );
            System.err.println("  [STDERR] Got task, calling call()...");
            System.err.flush();
            
            success = task.call();
            System.err.println("  [STDERR] ===== task.call() returned: " + success + " =====");
            System.err.flush();
            
            fileManager.close();
        } catch (Throwable t) {
            System.err.println("  [STDERR] ===== EXCEPTION during compilation: " + t.getMessage() + " =====");
            t.printStackTrace();
            System.err.flush();
            throw new RuntimeException("Compilation failed: " + t.getMessage(), t);
        }
        
        int result = success ? 0 : 1;
        long compilerEnd = System.nanoTime();
        System.err.println("  [STDERR] Compilation result: " + result + ", took: " + ((compilerEnd - compilerStart) / 1_000_000.0) + " ms");
        if (stdoutCapture.size() > 0) {
            System.err.println("  [STDERR] Compiler stdout: " + stdoutCapture.toString());
        }
        if (errorOutput.size() > 0) {
            System.err.println("  [STDERR] Compiler stderr: " + errorOutput.toString());
        }
        System.err.flush();
        
        System.out.println("  ✓ Compiler completed in " + ((compilerEnd - compilerStart) / 1_000_000.0) + " ms, result code: " + result);
        System.err.println("  [STDERR] Compiler completed, result: " + result + ", time: " + ((compilerEnd - compilerStart) / 1_000_000.0) + " ms");
        System.out.flush();
        System.err.flush();

        System.err.println("  [STDERR] Checking compilation result: " + result);
        System.err.flush();
        
        if (result != 0) {
            String errorMsg = errorOutput.toString();
            System.out.println("  Compilation errors: " + errorMsg);
            System.err.println("  [STDERR] Compilation errors: " + errorMsg);
            System.out.flush();
            System.err.flush();
            throw new RuntimeException("Compilation failed for: " + sourceFile.getAbsolutePath() + 
                                     "\nCompiler errors:\n" + errorMsg);
        }
        System.out.println("  ✓ Compilation successful");
        System.err.println("  [STDERR] ===== ✓ Compilation successful =====");
        System.out.flush();
        System.err.flush();

        // Find the compiled .class file
        System.err.println("  [STDERR] Finding compiled .class file...");
        System.err.flush();
        String className = sourceFile.getName().replace(".java", "");
        
        // Try direct path first (for package-less classes)
        File classFile = new File(outputDir, className + ".class");
        System.err.println("  [STDERR] Checking for class file at: " + classFile.getAbsolutePath());
        System.err.flush();
        
        // If not found, try to find it manually (for package structure)
        if (!classFile.exists()) {
            System.out.println("  Class file not at expected location, checking if it's in a package...");
            System.err.println("  [STDERR] Class file not found at: " + classFile.getAbsolutePath());
            
            // Try to read the source file to determine package
            try {
                String sourceContent = new String(Files.readAllBytes(sourceFile.toPath()));
                if (sourceContent.contains("package ")) {
                    // Extract package name
                    String[] lines = sourceContent.split("\n");
                    for (String line : lines) {
                        if (line.trim().startsWith("package ")) {
                            String pkg = line.trim().substring(8).replace(";", "").trim();
                            String packagePath = pkg.replace(".", java.io.File.separator);
                            File packageClassFile = new File(outputDir, packagePath + java.io.File.separator + className + ".class");
                            System.out.println("  Trying package path: " + packageClassFile.getAbsolutePath());
                            if (packageClassFile.exists()) {
                                classFile = packageClassFile;
                                System.out.println("  ✓ Found class file in package: " + classFile.getAbsolutePath());
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("  [STDERR] Error reading source file: " + e.getMessage());
            }
            
            if (!classFile.exists()) {
                System.out.println("  ✗ Class file not found. Expected at: " + classFile.getAbsolutePath());
                System.err.println("  [STDERR] Class file still not found");
                throw new IOException("Compiled class file not found: " + className + ".class in " + outputDir.getAbsolutePath());
            }
        }

        System.out.println("  ✓ Returning class file: " + classFile.getAbsolutePath());
        System.err.println("  [STDERR] Returning class file: " + classFile.getAbsolutePath());
        System.out.flush();
        System.err.flush();
        return classFile;
    }

    /**
     * Helper method: Create a JAR file containing a .class file
     */
    private void createJarWithClass(File jarFile, File classFile, File baseDir) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

        try (JarOutputStream jarOut = new JarOutputStream(
                new FileOutputStream(jarFile), manifest)) {

            // Calculate the entry name (path within JAR)
            // Get relative path from baseDir to classFile
            Path basePath = baseDir.toPath();
            Path classPath = classFile.toPath();
            Path relativePath = basePath.relativize(classPath);
            
            // Convert to JAR entry format (forward slashes)
            String entryName = relativePath.toString().replace('\\', '/');

            // Add class file to JAR
            JarEntry entry = new JarEntry(entryName);
            jarOut.putNextEntry(entry);
            Files.copy(classFile.toPath(), jarOut);
            jarOut.closeEntry();
        }
    }
}

