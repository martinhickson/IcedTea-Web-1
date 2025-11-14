package net.sourceforge.jnlp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;

public class JarFileFactoryAccessTest {

    @Test
    public void testGetCachedJarFile_validJar() throws IOException {
        // Use a known jar URL, e.g. a Java runtime JAR or a local file URL to a JAR on your system
        // Example: use java.home/lib/rt.jar (for JDK 8) or a test JAR in resources
        // Adjust path accordingly or use a dummy file URL for demonstration

        // Example for JDK 8 (may not exist on JDK 9+ modular JVMs):
        String javaHome = System.getProperty("java.home");
        String filePath = "C:\\Program Files\\Amazon Corretto\\jdk1.8.0_412\\jre\\lib\\rt.jar";
        File file = new File(filePath);
        URL jarUrl = file.toURI().toURL();

        JarFile jarFile = JarFileFactoryAccess.getCachedJarFile(jarUrl);
        assertNotNull(jarFile);
        // Compare system paths (both are non-URL-encoded paths)
        assertEquals(file.getAbsolutePath(), jarFile.getName());

        // Note: Do NOT close jarFile here - it comes from JDK's global cache
        // and is managed by the JDK itself. Closing it would cause issues.
    }

    @Test
    public void testIsCached_returnsTrueForCachedJar() throws IOException {
        String javaHome = System.getProperty("java.home");
        String filePath = "C:\\Program Files\\Amazon Corretto\\jdk1.8.0_412\\jre\\lib\\rt.jar";
        File file = new File(filePath);
        URL jarUrl = file.toURI().toURL();

        boolean cached = JarFileFactoryAccess.isCached(jarUrl);
        assertTrue(cached);
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
