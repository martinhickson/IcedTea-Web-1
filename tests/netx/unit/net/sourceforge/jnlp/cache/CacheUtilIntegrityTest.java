package net.sourceforge.jnlp.cache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheUtilIntegrityTest {

    @TempDir
    File temp;

    @Test
    void verifyJarIntegrityAcceptsUnsignedWellFormedJar() throws Exception {
        File jar = new File(temp, "ok.jar");
        writeMinimalJar(jar, "hello.txt", "hello");
        String result = CacheUtil.verifyJarIntegrity(jar);
        assertTrue(result.contains("file has integrity"), result);
        assertTrue(result.contains("unsigned"), result);
        assertTrue(CacheUtil.isValidJarFile(jar));
    }

    @Test
    void verifyJarIntegrityRejectsPlainTextPosingAsJar() throws Exception {
        File fake = new File(temp, "fake.jar");
        Files.write(fake.toPath(), "11 Could not locate requested version\r\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(CacheUtil.isValidJarFile(fake));
        IOException ex = assertThrows(IOException.class, () -> CacheUtil.verifyJarIntegrity(fake));
        assertTrue(ex.getMessage().contains("not a valid JAR"), ex.getMessage());
    }

    @Test
    void verifyJarIntegrityRejectsTruncatedZipAfterMagic() throws Exception {
        File truncated = new File(temp, "trunc.jar");
        // Local-file header magic only — open/enumerate must fail
        Files.write(truncated.toPath(), new byte[] { 'P', 'K', 3, 4, 0, 0 });
        assertTrue(CacheUtil.isValidJarFile(truncated), "magic-only still looks like a jar to the header check");
        assertThrows(IOException.class, () -> CacheUtil.verifyJarIntegrity(truncated));
    }

    @Test
    void verifyJarIntegrityRejectsNullAndMissing() {
        assertThrows(IOException.class, () -> CacheUtil.verifyJarIntegrity(null));
        assertThrows(IOException.class, () -> CacheUtil.verifyJarIntegrity(new File(temp, "missing.jar")));
    }

    @Test
    void previewFileHeadReturnsPrintableSnippet() throws Exception {
        File f = new File(temp, "body.bin");
        Files.write(f.toPath(), "ABC\u0001DEF".getBytes(StandardCharsets.ISO_8859_1));
        String preview = CacheUtil.previewFileHead(f, 80);
        assertTrue(preview.contains("ABC"));
        assertTrue(preview.contains("DEF"));
    }

    @Test
    void previewFileHeadRejectsNullMissingOrNonPositiveLimit() throws Exception {
        assertNull(CacheUtil.previewFileHead(null, 10));
        assertNull(CacheUtil.previewFileHead(new File(temp, "missing.bin"), 10));
        File f = new File(temp, "ok.bin");
        Files.write(f.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertNull(CacheUtil.previewFileHead(f, 0));
        assertNull(CacheUtil.previewFileHead(f, -1));
    }

    @Test
    void isJarResourceUrlRecognizesJarAndVersionEncodedNames() throws Exception {
        assertTrue(CacheUtil.isJarResourceUrl(new URL("http://host/lib/foo.jar")));
        assertTrue(CacheUtil.isJarResourceUrl(new URL("http://host/lib/foo__V1.2.3.jar")));
        assertTrue(CacheUtil.isJarResourceUrl(new URL("http://host/lib/FOO.JAR")));
        assertFalse(CacheUtil.isJarResourceUrl(new URL("http://host/lib/foo.jar.pack.gz")));
        assertFalse(CacheUtil.isJarResourceUrl(new URL("http://host/app.jnlp")));
        assertFalse(CacheUtil.isJarResourceUrl(null));
    }

    @Test
    void verifyJarIntegrityAcceptsEmptyJarWithOnlyManifest() throws Exception {
        File jar = new File(temp, "empty.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar), manifest)) {
            // manifest-only
        }
        String result = CacheUtil.verifyJarIntegrity(jar);
        assertTrue(result.contains("file has integrity"), result);
    }

    private static void writeMinimalJar(File jar, String entryName, String body) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar), manifest)) {
            jos.putNextEntry(new ZipEntry(entryName));
            jos.write(body.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }
}
