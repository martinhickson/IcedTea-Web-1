package net.sourceforge.jnlp.cache;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.sourceforge.jnlp.DownloadOptions;
import net.sourceforge.jnlp.JARDesc;
import net.sourceforge.jnlp.ServerAccess;
import net.sourceforge.jnlp.ServerLauncher;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.tools.JarCertVerifier;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;

/**
 * Replicates the Windows production failure mode exactly:
 * <ol>
 *   <li>JNLP version servlet returned {@code 11 Could not locate requested version}
 *       with HTTP 200</li>
 *   <li>ITW cached that text as {@code *.jar} with {@code last-modified=0}</li>
 *   <li>Next launch treated it as current and {@code JarCertVerifier} threw
 *       {@code ZipException: zip END header not found}</li>
 * </ol>
 * These tests must fail on the old behaviour and pass only when poison cannot stick
 * and cannot reach zip verification.
 */
public class StickyVersionMissCacheTest extends NoStdOutErrTest {

    private static final byte[] VERSION_MISS =
            "11 Could not locate requested version\r\n".getBytes(StandardCharsets.US_ASCII);

    private String originalCacheDir;
    private File tempCache;
    private ServerLauncher server;

    @Before
    public void setUp() throws Exception {
        originalCacheDir = PathsAndFiles.CACHE_DIR.getFullPath();
        tempCache = new File(System.getProperty("java.io.tmpdir"),
                "itw-sticky-version-miss-" + System.nanoTime());
        tempCache.mkdirs();
        PathsAndFiles.CACHE_DIR.setValue(tempCache.getAbsolutePath());

        File web = new File(System.getProperty("java.io.tmpdir"),
                "itw-sticky-version-miss-web-" + System.nanoTime());
        web.mkdirs();
        server = ServerAccess.getIndependentInstance(web.getAbsolutePath(), ServerAccess.findFreePort());
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
        try {
            CacheUtil.clearCache();
        } catch (Exception ignored) {
        }
        PathsAndFiles.CACHE_DIR.setValue(originalCacheDir);
        deleteRecursive(tempCache);
        if (server != null) {
            deleteRecursive(server.getDir());
        }
    }

    @Test
    public void stickyVersionMissPoisonIsNotCurrentAndIsRedownloadedAsRealJar() throws Exception {
        // Server has a real jar (the recovery case after a bad version response was cached).
        File goodJar = writeMinimalJar(new File(server.getDir(), "sonata-rda-launcher.jar"), "1.0");
        URL url = server.getUrl("sonata-rda-launcher.jar");

        // Seed cache exactly like production .info + poison payload.
        File cacheFile = CacheUtil.getCacheFile(url, null);
        assertNotNull(cacheFile);
        Files.write(cacheFile.toPath(), VERSION_MISS);
        CacheEntry poisoned = new CacheEntry(url, null);
        poisoned.setRemoteContentLength(VERSION_MISS.length);
        poisoned.setLastModified(0L);
        poisoned.setLastUpdated(System.currentTimeMillis());
        poisoned.store();

        // OLD BUG: isCached()==true && isCurrent(0)==true ⇒ never re-download ⇒ ZipException later.
        assertFalse("poison must not count as cached", poisoned.isCached());
        assertFalse("poison must not stick when Last-Modified is omitted (0)", poisoned.isCurrent(0L));
        assertFalse(CacheUtil.isValidJarFile(cacheFile));

        Resource resource = Resource.getResource(url, null, UpdatePolicy.NEVER);
        ResourceDownloader downloader = new ResourceDownloader(resource, new Object());
        resource.setStatusFlag(Resource.Status.PRECONNECT);
        resource.setDownloadOptions(new DownloadOptions(false, false));
        downloader.run();

        assertFalse("download must not end in ERROR when a real jar is available",
                resource.hasFlags(EnumSet.of(Resource.Status.ERROR)));
        assertTrue(resource.hasFlags(EnumSet.of(Resource.Status.DOWNLOADED)));
        File local = resource.getLocalFile();
        assertNotNull(local);
        assertTrue("must re-download a real jar over the sticky poison", CacheUtil.isValidJarFile(local));
        assertTrue(local.length() >= goodJar.length() / 2);
        byte[] head = Files.readAllBytes(local.toPath());
        assertTrue(head[0] == 'P' && head[1] == 'K');
    }

    @Test
    public void jarCertVerifierMustNotThrowZipExceptionOnStickyVersionMissPoison() throws Exception {
        File poison = File.createTempFile("sonata-rda-launcher", ".jar");
        poison.deleteOnExit();
        Files.write(poison.toPath(), VERSION_MISS);

        final URL url = new URL("http://127.0.0.88:8080/sonata/downloads/sonata-rda-launcher.jar");
        JARDesc jar = new JARDesc(url, new net.sourceforge.jnlp.Version("14.6.425948.28"),
                null, false, true, false, true);

        ResourceTracker tracker = new ResourceTracker() {
            @Override
            public File getCacheFile(URL location) {
                return poison;
            }
        };

        JarCertVerifier verifier = new JarCertVerifier(null);
        List<JARDesc> jars = new ArrayList<>();
        jars.add(jar);
        try {
            // OLD BUG: verifyJar → ZipException: zip END header not found (fatal LaunchException).
            verifier.add(jars, tracker);
        } catch (Exception ex) {
            Throwable t = ex;
            while (t != null) {
                if (t instanceof java.util.zip.ZipException
                        || (t.getMessage() != null && t.getMessage().contains("zip END header"))) {
                    fail("production failure mode still reaches JarCertVerifier zip open: " + ex);
                }
                t = t.getCause();
            }
            throw ex;
        }
    }

    private static File writeMinimalJar(File file, String manifestVersion) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, manifestVersion);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(file), manifest)) {
        }
        return file;
    }

    private static void deleteRecursive(File root) {
        if (root == null || !root.exists()) {
            return;
        }
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        root.delete();
    }
}
