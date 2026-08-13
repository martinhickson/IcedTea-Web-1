package net.sourceforge.jnlp.cache;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * {@code -Xclearcache <jnlp-url>} must match on-disk cache paths, not only
 * exact {@code jnlp-path} / domain cache IDs.
 */
public class CacheUtilClearByUrlTest extends NoStdOutErrTest {

    private String originalCacheDir;
    private File tempCache;

    @Before
    public void setUp() throws Exception {
        originalCacheDir = PathsAndFiles.CACHE_DIR.getFullPath();
        tempCache = new File(System.getProperty("java.io.tmpdir"),
                "itw-clear-by-url-" + System.nanoTime());
        Assert.assertTrue(tempCache.mkdirs());
        PathsAndFiles.CACHE_DIR.setValue(tempCache.getAbsolutePath());
    }

    @After
    public void tearDown() {
        try {
            CacheUtil.clearCache();
        } catch (Exception ignored) {
        }
        PathsAndFiles.CACHE_DIR.setValue(originalCacheDir);
        deleteRecursive(tempCache);
    }

    @Test
    public void applicationToCacheRelativePathKeepsHostPortAndPath() throws Exception {
        String rel = CacheUtil.applicationToCacheRelativePath(
                "http://127.0.0.1:4200/jnlp/console/app.jnlp");
        Assert.assertNotNull(rel);
        Assert.assertTrue(rel.replace('\\', '/'),
                rel.replace('\\', '/').contains("/http/127.0.0.1/4200/jnlp/console/app.jnlp"));
    }

    @Test
    public void hrefFromCacheRelativePathRoundTripsHttpWithPort() {
        Assert.assertEquals("http://127.0.0.1:4200/jnlp/console/app.jnlp",
                CacheUtil.hrefFromCacheRelativePath("/http/127.0.0.1/4200/jnlp/console/app.jnlp"));
    }

    @Test
    public void clearByJnlpUrlRemovesAppWithoutJnlpPathMetadata() throws Exception {
        URL jnlp = new URL("http://127.0.0.1:4200/jnlp/console/app.jnlp");
        URL jar = new URL("http://127.0.0.1:4200/jnlp/console/app.jar");
        URL other = new URL("http://127.0.0.1:4200/jnlp/swing-gui/app.jnlp");

        File jnlpFile = writeCachedResource(jnlp, "<jnlp/>");
        File jarFile = writeCachedResource(jar, "jar-bytes");
        File otherFile = writeCachedResource(other, "<jnlp/>");

        Assert.assertTrue(jnlpFile.isFile());
        Assert.assertTrue(jarFile.isFile());
        Assert.assertTrue(otherFile.isFile());

        Assert.assertTrue(CacheUtil.clearCache(jnlp.toString(), true, true));

        Assert.assertFalse("console JNLP should be cleared", jnlpFile.exists());
        Assert.assertFalse("console jar next to the JNLP should be cleared", jarFile.exists());
        Assert.assertTrue("unrelated app must stay", otherFile.isFile());
    }

    @Test
    public void canClearApplicationCacheRejectsBlankId() {
        Assert.assertFalse(CacheUtil.canClearApplicationCache(null));
        Assert.assertFalse(CacheUtil.canClearApplicationCache("  "));
    }

    @Test
    public void jarCacheIdSharesDirectoryWithRunningJnlp() {
        String jnlp = "http://127.0.0.1:4200/jnlp/console/app.jnlp";
        Assert.assertTrue(CacheUtil.cacheIdSharesDirectoryWithJnlp(
                "http://127.0.0.1:4200/jnlp/console/app.jar", jnlp));
        Assert.assertTrue(CacheUtil.cacheIdSharesDirectoryWithJnlp(jnlp, jnlp));
        Assert.assertFalse(CacheUtil.cacheIdSharesDirectoryWithJnlp(
                "http://127.0.0.1:4200/jnlp/swing-gui/app.jar", jnlp));
        Assert.assertFalse(CacheUtil.cacheIdSharesDirectoryWithJnlp(null, jnlp));
        Assert.assertFalse(CacheUtil.cacheIdSharesDirectoryWithJnlp(
                "http://127.0.0.1:4200/jnlp/console/app.jar", null));
    }

    @Test
    public void clearByUnknownUrlDoesNotDeleteOtherApps() throws Exception {
        URL other = new URL("http://127.0.0.1:4200/jnlp/swing-gui/app.jnlp");
        File otherFile = writeCachedResource(other, "<jnlp/>");

        Assert.assertFalse(CacheUtil.clearCache(
                "http://127.0.0.1:4200/jnlp/console/app.jnlp", true, true));
        Assert.assertTrue(otherFile.isFile());
    }

    private static File writeCachedResource(URL source, String contents) throws Exception {
        File cacheFile = CacheUtil.makeNewCacheFile(source, null);
        Files.write(cacheFile.toPath(), contents.getBytes(StandardCharsets.UTF_8));
        return cacheFile;
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
