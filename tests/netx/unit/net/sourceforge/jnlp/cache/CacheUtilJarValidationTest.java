package net.sourceforge.jnlp.cache;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.Test;

public class CacheUtilJarValidationTest {

    @Test
    public void isJarResourceUrlRecognizesJarAndVersionEncodedNames() throws Exception {
        assertTrue(CacheUtil.isJarResourceUrl(new URL("http://host/app.jar")));
        assertTrue(CacheUtil.isJarResourceUrl(new URL("http://host/app__V1.2.3.jar")));
        assertTrue(CacheUtil.isJarResourceUrl(new URL("http://host/path/app.jar?x=1")));
        assertFalse(CacheUtil.isJarResourceUrl(new URL("http://host/app.jnlp")));
        assertFalse(CacheUtil.isJarResourceUrl(new URL("http://host/icon.png")));
    }

    @Test
    public void isValidJarFileAcceptsZipMagicAndRejectsVersionErrorBody() throws IOException {
        File good = File.createTempFile("CacheUtilJarValidation-good", ".jar");
        good.deleteOnExit();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(good), manifest)) {
        }
        assertTrue(CacheUtil.isValidJarFile(good));

        File bad = File.createTempFile("CacheUtilJarValidation-bad", ".jar");
        bad.deleteOnExit();
        Files.write(bad.toPath(), "11 Could not locate requested version\r\n".getBytes(StandardCharsets.US_ASCII));
        assertFalse(CacheUtil.isValidJarFile(bad));
        assertTrue(CacheUtil.previewFileHead(bad, 80).contains("Could not locate requested version"));
        assertNull(CacheUtil.previewFileHead(null, 80));
    }
}
