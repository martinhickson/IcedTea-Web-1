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


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.sourceforge.jnlp.cache.UpdatePolicy;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.mock.DummyJNLPFileWithJar;
import net.sourceforge.jnlp.security.appletextendedsecurity.AppletSecurityLevel;
import net.sourceforge.jnlp.security.appletextendedsecurity.AppletStartupSecuritySettings;
import net.sourceforge.jnlp.util.FileTestUtils;

public class JNLPClassLoaderClassloadingTest {

    private static AppletSecurityLevel level;
    private static String askUser;

    @BeforeAll
    public static void setPermissions() {
        level = AppletStartupSecuritySettings.getInstance().getSecurityLevel();
        JNLPRuntime.getConfiguration().setProperty(DeploymentConfiguration.KEY_SECURITY_LEVEL, AppletSecurityLevel.ALLOW_UNSIGNED.toChars());
        askUser = JNLPRuntime.getConfiguration().getProperty(DeploymentConfiguration.KEY_SECURITY_PROMPT_USER);
        JNLPRuntime.getConfiguration().setProperty(DeploymentConfiguration.KEY_SECURITY_PROMPT_USER, Boolean.toString(false));
    }

    @AfterAll
    public static void resetPermissions() {
        JNLPRuntime.getConfiguration().setProperty(DeploymentConfiguration.KEY_SECURITY_LEVEL, level.toChars());
        JNLPRuntime.getConfiguration().setProperty(DeploymentConfiguration.KEY_SECURITY_PROMPT_USER, askUser);
    }

    /**
     * Test that we can create a JAR with a Java class and load it using JNLPClassLoader
     */
    @Test
    public void testLoadClassFromJar() throws Exception {
        System.out.println("\n=== testLoadClassFromJar ===");
        long startTime = System.nanoTime();

            // Step 1: Create a temporary directory for test files
            File tempDir = FileTestUtils.createTempDirectory();
            tempDir.deleteOnExit();
            System.out.println( "Created temp directory: " + tempDir.getAbsolutePath());

            // Step 2: Create a simple Java source file
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
            System.out.println( "Created source file: " + sourceFile.getAbsolutePath());

            // Step 3: Compile the Java source to a .class file
            System.out.println( "Compiling Java source file...");
            System.out.println( "  Source: " + sourceFile.getAbsolutePath());
            long compileStart = System.nanoTime();
            File classFile = null;
            try {
                System.out.println( "  Calling compileJavaFile...");
                classFile = compileJavaFile(sourceFile, tempDir);
                System.out.println( "  ✓ compileJavaFile returned: " + (classFile != null ? classFile.getAbsolutePath() : "null"));
            } catch (Exception e) {
                System.out.println( "✗ Compilation failed: " + e.getMessage());
                e.printStackTrace();
                throw e;
            }
            long compileTime = System.nanoTime() - compileStart;
            System.out.println( "  Compile time calculated: " + (compileTime / 1_000_000.0) + " ms");
            System.out.flush();
            if (classFile == null) {
                System.out.println( "✗ ERROR: classFile is null!");
                System.out.flush();
                throw new RuntimeException("classFile is null after compilation");
            }
            if (!classFile.exists()) {
                System.out.println( "✗ ERROR: classFile does not exist: " + classFile.getAbsolutePath());
                System.out.flush();
                throw new RuntimeException("classFile does not exist: " + classFile.getAbsolutePath());
            }
            System.out.println( "✓ Compilation successful!");
            System.err.println( "[STDERR] ✓ Compilation successful!");
            System.out.println( "  Class file: " + classFile.getAbsolutePath());
            System.err.println( "  [STDERR] Class file: " + classFile.getAbsolutePath());
            System.out.println( "  Class file size: " + classFile.length() + " bytes");
            System.err.println( "  [STDERR] Class file size: " + classFile.length() + " bytes");
            System.out.println( "  Compile time: " + (compileTime / 1_000_000.0) + " ms");
            System.err.println( "  [STDERR] Compile time: " + (compileTime / 1_000_000.0) + " ms");
            System.out.flush();
            System.err.flush();

            // Step 4: Create a JAR file containing the compiled class
            System.out.println( "Creating JAR file...");
            System.err.println( "[STDERR] Creating JAR file...");
            System.out.println( "  Class file to include: " + classFile.getAbsolutePath());
            System.err.println( "  [STDERR] Class file to include: " + classFile.getAbsolutePath());
            long jarStart = System.nanoTime();
            File jarFile = new File(tempDir, "test.jar");
            System.out.println( "  JAR file path: " + jarFile.getAbsolutePath());
            System.err.println( "  [STDERR] JAR file path: " + jarFile.getAbsolutePath());
            System.out.flush();
            System.err.flush();
            try {
                System.err.println( "  [STDERR] Calling createJarWithClass()...");
                createJarWithClass(jarFile, classFile, tempDir);
                System.err.println( "  [STDERR] createJarWithClass() completed");
                System.out.println( "  JAR creation call completed");
            } catch (Exception e) {
                System.out.println( "✗ JAR creation failed: " + e.getMessage());
                System.err.println( "  [STDERR] JAR creation failed: " + e.getMessage());
                e.printStackTrace();
                throw e;
            }
            long jarTime = System.nanoTime() - jarStart;
            assertTrue(jarFile.exists(), "JAR file should exist");
            System.out.println( "✓ JAR file created successfully!");
            System.err.println( "  [STDERR] ✓ JAR file created successfully!");
            System.out.println( "  Location: " + jarFile.getAbsolutePath());
            System.err.println( "  [STDERR] JAR Location: " + jarFile.getAbsolutePath());
            System.out.println( "  Size: " + jarFile.length() + " bytes");
            System.err.println( "  [STDERR] JAR Size: " + jarFile.length() + " bytes");
            System.out.println( "  Creation time: " + (jarTime / 1_000_000.0) + " ms");
            System.err.println( "  [STDERR] JAR Creation time: " + (jarTime / 1_000_000.0) + " ms");
            System.out.println( "  JAR URL: " + jarFile.toURI().toURL());
            System.err.println( "  [STDERR] JAR URL: " + jarFile.toURI().toURL());
            System.out.flush();
            System.err.flush();

            // Step 5: Create a JNLP file wrapper for the JAR
            System.out.println( "Creating JNLP file wrapper...");
            DummyJNLPFileWithJar jnlpFile = new DummyJNLPFileWithJar(jarFile);
            System.out.println( "✓ JNLP file wrapper created");
            System.out.println( "  JAR URL in JNLP: " + jarFile.toURI().toURL());

            // Step 6: Create JNLPClassLoader and load the class
            System.out.println( "Creating JNLPClassLoader...");
            System.err.println( "[STDERR] ===== Creating JNLPClassLoader =====");
            System.out.println( "  -> This will process the JAR file and may create temporary copies");
            System.err.println( "  [STDERR] This will process the JAR file and may create temporary copies");
            System.err.println( "  [STDERR] JAR file: " + jarFile.getAbsolutePath());
            long loaderStart = System.nanoTime();
            System.err.println( "  [STDERR] ===== About to instantiate JNLPClassLoader NOW =====");
            System.err.flush();
            JNLPClassLoader classLoader;
            try {
                classLoader = new JNLPClassLoader(
                    jnlpFile,
                    UpdatePolicy.ALWAYS
                );
                long loaderTime = System.nanoTime() - loaderStart;
                System.err.println( "  [STDERR] ===== JNLPClassLoader instantiated! Took " + (loaderTime / 1_000_000.0) + " ms =====");
                System.out.println( "✓ JNLPClassLoader created successfully!");
                System.err.println( "  [STDERR] ✓ JNLPClassLoader created successfully!");
                System.out.println( "  Creation time: " + (loaderTime / 1_000_000.0) + " ms");
                System.err.println( "  [STDERR] Creation time: " + (loaderTime / 1_000_000.0) + " ms");
                System.out.flush();
                System.err.flush();
            } catch (Exception e) {
                System.err.println( "  [STDERR] ===== EXCEPTION creating JNLPClassLoader: " + e.getMessage() + " =====");
                e.printStackTrace();
                System.err.flush();
                throw e;
            }

            // Try to find any temporary JAR files that might have been created
            try {
                java.io.File tempBase = new java.io.File(System.getProperty("java.io.tmpdir"), "icedtea-web");
                if (tempBase.exists()) {
                    System.out.println( "  Checking for temporary JAR files in: " + tempBase.getAbsolutePath());
                    java.io.File[] tempFiles = tempBase.listFiles((dir, name) -> name.endsWith(".jar"));
                    if (tempFiles != null && tempFiles.length > 0) {
                        System.out.println( "  Found " + tempFiles.length + " temporary JAR file(s):");
                        for (java.io.File tf : tempFiles) {
                            System.out.println( "    - " + tf.getAbsolutePath() + " (" + tf.length() + " bytes)");
                        }
                    } else {
                        System.out.println( "  No temporary JAR files found (JAR may be used directly)");
                    }
                }
            } catch (Exception e) {
                System.out.println( "  Could not check for temporary files: " + e.getMessage());
            }

            // Step 7: Load the class from the JAR
            System.out.println( "Loading class 'TestClass' from JAR...");
            System.out.println( "  -> This will read the class file from the JAR");
            long loadClassStart = System.nanoTime();
            Class<?> loadedClass = classLoader.loadClass("TestClass");
            long loadClassTime = System.nanoTime() - loadClassStart;
            System.out.println( "✓ Class 'TestClass' loaded successfully!");
            System.out.println( "  Load time: " + (loadClassTime / 1_000_000.0) + " ms");

            // Step 8: Verify the class was loaded correctly
            assertNotNull(loadedClass, "Class should be loaded");
            assertEquals("TestClass", loadedClass.getSimpleName());
            System.out.println( "Class name verified: " + loadedClass.getName());

            // Step 9: Instantiate and use the class
            System.out.println( "Instantiating TestClass...");
            System.out.println( "  -> This will trigger the constructor which prints: [TestClass] Constructor called!");
            long instantiateStart = System.nanoTime();
            Object instance = loadedClass.getDeclaredConstructor().newInstance();
            long instantiateTime = System.nanoTime() - instantiateStart;
            System.out.println( "Instance created (took " + (instantiateTime / 1_000_000.0) + " ms)");

            System.out.println( "Calling getMessage() method...");
            System.out.println( "  -> This will trigger getMessage() which prints: [TestClass] getMessage() called!");
            long methodStart = System.nanoTime();
            String message = (String) loadedClass.getMethod("getMessage").invoke(instance);
            long methodTime = System.nanoTime() - methodStart;
            System.out.println( "Method returned: '" + message + "' (took " + (methodTime / 1_000_000.0) + " ms)");
            assertEquals("Hello from TestClass", message);

            // Also try to invoke main to show it runs
            System.out.println( "Invoking main() method to demonstrate class execution...");
            try {
                java.lang.reflect.Method mainMethod = loadedClass.getMethod("main", String[].class);
                mainMethod.invoke(null, (Object) new String[]{"arg1", "arg2"});
                System.out.println( "  -> main() executed successfully");
            } catch (Exception e) {
                System.out.println( "  -> Note: main() output may be suppressed by NoStdOutErrTest");
            }

            long totalTime = System.nanoTime() - startTime;
            System.out.println( "=== Test completed in " + (totalTime / 1_000_000.0) + " ms ===\n");
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

//    /**
//     * Helper method: Compile a Java source file to a .class file
//     */
//    private File compileJavaFile(File sourceFile, File outputDir) throws IOException {
//        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
//        if (compiler == null) {
//            throw new RuntimeException("Java compiler not available. " +
//                "Make sure you're running with JDK (not JRE)");
//        }
//
//        // Compile the source file - use simple approach without sourcepath for now
//        System.out.println("  Compiling with javac...");
//        System.out.println("    Output dir: " + outputDir.getAbsolutePath());
//        System.out.println("    Source file: " + sourceFile.getAbsolutePath());
//        System.out.flush();
//
//        java.io.ByteArrayOutputStream errorOutput = new java.io.ByteArrayOutputStream();
//        java.io.ByteArrayOutputStream stdoutCapture = new java.io.ByteArrayOutputStream();
//        System.out.println("  Running javac compiler...");
//        System.err.println("  [STDERR] About to call compiler.run()");
//        System.out.flush();
//        System.err.flush();
//
//        long compilerStart = System.nanoTime();
//        System.err.println("  [STDERR] ===== About to call compiler.run() ===== ");
//        System.err.flush();
//        int result = -999;
//        try {
//            System.err.println("  [STDERR] Invoking compiler with args: -d " + outputDir.getAbsolutePath() + " " + sourceFile.getAbsolutePath());
//            System.err.flush();
//            System.out.println(ToolProvider.getSystemJavaCompiler());
//
//            result = compiler.run(
//                null,
//                new java.io.PrintStream(stdoutCapture, true), // Capture stdout, auto-flush
//                new java.io.PrintStream(errorOutput, true), // stderr, auto-flush
//                "-d", outputDir.getAbsolutePath(),
//                sourceFile.getAbsolutePath()
//            );
//            System.err.println("  [STDERR] ===== compiler.run() RETURNED, result=" + result + " =====");
//            System.err.flush();
//            if (stdoutCapture.size() > 0) {
//                System.err.println("  [STDERR] Compiler stdout: " + stdoutCapture.toString());
//                System.err.flush();
//            }
//            if (errorOutput.size() > 0) {
//                System.err.println("  [STDERR] Compiler stderr: " + errorOutput.toString());
//                System.err.flush();
//            }
//        } catch (Throwable t) {
//            System.err.println("  [STDERR] ===== EXCEPTION in compiler.run(): " + t.getMessage() + " =====");
//            t.printStackTrace();
//            System.err.flush();
//            throw t;
//        }
//        long compilerEnd = System.nanoTime();
//        System.err.println("  [STDERR] Compiler took: " + ((compilerEnd - compilerStart) / 1_000_000.0) + " ms");
//        System.err.flush();
//
//        System.out.println("  ✓ Compiler completed in " + ((compilerEnd - compilerStart) / 1_000_000.0) + " ms, result code: " + result);
//        System.err.println("  [STDERR] Compiler completed, result: " + result + ", time: " + ((compilerEnd - compilerStart) / 1_000_000.0) + " ms");
//        System.out.flush();
//        System.err.flush();
//
//        System.err.println("  [STDERR] Checking compilation result: " + result);
//        System.err.flush();
//
//        if (result != 0) {
//            String errorMsg = errorOutput.toString();
//            System.out.println("  Compilation errors: " + errorMsg);
//            System.err.println("  [STDERR] Compilation errors: " + errorMsg);
//            System.out.flush();
//            System.err.flush();
//            throw new RuntimeException("Compilation failed for: " + sourceFile.getAbsolutePath() +
//                                     "\nCompiler errors:\n" + errorMsg);
//        }
//        System.out.println("  ✓ Compilation successful");
//        System.err.println("  [STDERR] ===== ✓ Compilation successful =====");
//        System.out.flush();
//        System.err.flush();
//
//        // Find the compiled .class file
//        System.err.println("  [STDERR] Finding compiled .class file...");
//        System.err.flush();
//        String className = sourceFile.getName().replace(".java", "");
//
//        // Try direct path first (for package-less classes)
//        File classFile = new File(outputDir, className + ".class");
//        System.err.println("  [STDERR] Checking for class file at: " + classFile.getAbsolutePath());
//        System.err.flush();
//
//        // If not found, try to find it manually (for package structure)
//        if (!classFile.exists()) {
//            System.out.println("  Class file not at expected location, checking if it's in a package...");
//            System.err.println("  [STDERR] Class file not found at: " + classFile.getAbsolutePath());
//
//            // Try to read the source file to determine package
//            try {
//                String sourceContent = new String(Files.readAllBytes(sourceFile.toPath()));
//                if (sourceContent.contains("package ")) {
//                    // Extract package name
//                    String[] lines = sourceContent.split("\n");
//                    for (String line : lines) {
//                        if (line.trim().startsWith("package ")) {
//                            String pkg = line.trim().substring(8).replace(";", "").trim();
//                            String packagePath = pkg.replace(".", java.io.File.separator);
//                            File packageClassFile = new File(outputDir, packagePath + java.io.File.separator + className + ".class");
//                            System.out.println("  Trying package path: " + packageClassFile.getAbsolutePath());
//                            if (packageClassFile.exists()) {
//                                classFile = packageClassFile;
//                                System.out.println("  ✓ Found class file in package: " + classFile.getAbsolutePath());
//                                break;
//                            }
//                        }
//                    }
//                }
//            } catch (Exception e) {
//                System.err.println("  [STDERR] Error reading source file: " + e.getMessage());
//            }
//
//            if (!classFile.exists()) {
//                System.out.println("  ✗ Class file not found. Expected at: " + classFile.getAbsolutePath());
//                System.err.println("  [STDERR] Class file still not found");
//                throw new IOException("Compiled class file not found: " + className + ".class in " + outputDir.getAbsolutePath());
//            }
//        }
//
//        System.out.println("  ✓ Returning class file: " + classFile.getAbsolutePath());
//        System.err.println("  [STDERR] Returning class file: " + classFile.getAbsolutePath());
//        System.out.flush();
//        System.err.flush();
//        return classFile;
//    }

    /**
     * Modern, safe Java compiler helper using JavaCompiler API (no deadlocks).
     */
    private File compileJavaFile(File sourceFile, File outputDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("Java compiler not available. Run with a full JDK, not a JRE.");
        }

        System.out.println("  [Modern] Compiling with javac...");
        System.out.println("    Output dir: " + outputDir.getAbsolutePath());
        System.out.println("    Source file: " + sourceFile.getAbsolutePath());

        long start = System.nanoTime();

        // Prepare file manager
        StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(null, null, null);

        // Input source units
        Iterable<? extends JavaFileObject> units =
                fileManager.getJavaFileObjectsFromFiles(List.of(sourceFile));

        // Where to put output (.class files)
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir));

        // Capture diagnostics without blocking
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        // Compile
        Boolean ok = compiler.getTask(
                null,                  // default writer
                fileManager,           // file manager
                diagnostics,           // diagnostics collector
                null,                  // options (none for now)
                null,                  // classes to process
                units                  // source files
        ).call();

        fileManager.close();

        long end = System.nanoTime();

        System.out.println("  [Modern] Compiler completed in " + ((end - start) / 1_000_000.0) + " ms");

        if (!ok) {
            System.out.println("  ✗ Compilation failed.");
            StringBuilder sb = new StringBuilder();
            for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
                sb.append(d.toString()).append("\n");
            }
            throw new RuntimeException("Compilation failed for " + sourceFile + ":\n" + sb);
        }

        System.out.println("  ✓ Compilation successful");

        // Find resulting .class file
        String className = sourceFile.getName().replace(".java", "");
        File classFile = new File(outputDir, className + ".class");

        if (!classFile.exists()) {
            // Might be in a package: parse source file to find package name
            String content = Files.readString(sourceFile.toPath());
            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.startsWith("package ")) {
                    String pkg = line.substring(8).replace(";", "").trim();
                    String pkgPath = pkg.replace(".", File.separator);
                    classFile = new File(outputDir, pkgPath + File.separator + className + ".class");
                    break;
                }
            }
        }

        if (!classFile.exists()) {
            throw new IOException("Compiled class file not found for: " + className +
                    " in " + outputDir.getAbsolutePath());
        }

        System.out.println("  ✓ Returning class file: " + classFile.getAbsolutePath());
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

