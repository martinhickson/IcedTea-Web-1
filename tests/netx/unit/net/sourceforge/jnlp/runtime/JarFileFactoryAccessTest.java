package net.sourceforge.jnlp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class JarFileFactoryAccessTest {

    private static File testJar;
    private static URL testJarUrl;

    @BeforeAll
    static void createTestJar() throws IOException {
        // deleteOnExit: JarFileFactory keeps the file open, so TempDir cleanup fails on Windows
        testJar = File.createTempFile("factory-access-test", ".jar");
        testJar.deleteOnExit();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(testJar), manifest)) {
            // empty jar is enough for cache lookup
        }
        testJarUrl = testJar.toURI().toURL();
    }

    @Test
    public void testGetCachedJarFile_validJar() throws IOException {
        JarFile jarFile = JarFileFactoryAccess.getCachedJarFile(testJarUrl);
        assertNotNull(jarFile);
        assertEquals(testJar.getAbsolutePath(), jarFile.getName());
        // Do not close jarFile — JDK global JarFile cache owns it
    }

    @Test
    public void testIsCached_returnsTrueForCachedJar() throws IOException {
        JarFileFactoryAccess.getCachedJarFile(testJarUrl);
        assertTrue(JarFileFactoryAccess.isCached(testJarUrl));
    }

    @Test
    public void testIsCached_returnsFalseForInvalidUrl() {
        try {
            URL badUrl = new URL("file:///non/existent/path/foo.jar");
            boolean cached = JarFileFactoryAccess.isCached(badUrl);
            assertFalse(cached);
        } catch (Exception e) {
            fail("URL creation failed unexpectedly");
        }
    }

    @Test
    public void testGetCachedJarFile_throwsIOExceptionForInvalidUrl() {
        assertThrows(IOException.class, () -> {
            URL badUrl = new URL("file:///non/existent/path/foo.jar");
            JarFileFactoryAccess.getCachedJarFile(badUrl);
        });
    }
}
