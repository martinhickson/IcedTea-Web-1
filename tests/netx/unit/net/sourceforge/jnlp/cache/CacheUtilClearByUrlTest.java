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

    @Test
    public void unknownClearIdsDoNotAlertSiblingApps() throws Exception {
        URL jnlp = new URL("http://127.0.0.1:4200/jnlp/console/app.jnlp");
        URL jar = new URL("http://127.0.0.1:4200/jnlp/console/app.jar");
        File jnlpFile = writeCachedResource(jnlp, "<jnlp/>");
        File jarFile = writeCachedResource(jar, "jar-bytes");
        writeJnlpPath(jnlpFile, jnlp.toString());
        writeJnlpPath(jarFile, jnlp.toString());

        String[] unknown = {
            "http://127.0.0.1:4200/jnlp/no-such/app.jar",
            "http://127.0.0.1:4200/jnlp/no-such/app.jnlp",
            "http://127.0.0.1:4200/jnlp/missing-other/app.jnlp"
        };
        for (String id : unknown) {
            Assert.assertFalse(id, CacheUtil.clearCache(id, true, true));
            Assert.assertTrue(id, jnlpFile.isFile());
            Assert.assertTrue(id, jarFile.isFile());
        }
    }

    @Test
    public void looksLikeCacheDomainIdRejectsUrls() {
        Assert.assertTrue(CacheUtil.looksLikeCacheDomainId("127.0.0.1"));
        Assert.assertTrue(CacheUtil.looksLikeCacheDomainId("example.com"));
        Assert.assertFalse(CacheUtil.looksLikeCacheDomainId(
                "http://127.0.0.1:4200/jnlp/no-such/app.jar"));
        Assert.assertFalse(CacheUtil.looksLikeCacheDomainId("  "));
        Assert.assertFalse(CacheUtil.looksLikeCacheDomainId(null));
    }

    @Test
    public void clearByDomainIdStillRemovesHostFiles() throws Exception {
        URL jnlp = new URL("http://127.0.0.1:4200/jnlp/console/app.jnlp");
        URL otherHost = new URL("http://127.0.0.2:4200/jnlp/console/app.jnlp");
        File jnlpFile = writeCachedResource(jnlp, "<jnlp/>");
        File otherFile = writeCachedResource(otherHost, "<jnlp/>");

        Assert.assertTrue(CacheUtil.clearCache("127.0.0.1", true, true));
        Assert.assertFalse("same-host JNLP should be cleared by domain id", jnlpFile.exists());
        Assert.assertTrue("other host must stay", otherFile.isFile());
    }

    @Test
    public void cachedResourceMatchesApplicationAfterCanonicalizingCacheRoot() throws Exception {
        URL jnlp = new URL("http://127.0.0.1:4200/jnlp/console/app.jnlp");
        File jnlpFile = writeCachedResource(jnlp, "<jnlp/>");
        File info = new File(jnlpFile.getPath() + CacheDirectory.INFO_SUFFIX);
        Assert.assertTrue(info.isFile());

        String nonCanonicalRoot = new File(tempCache, ".." + File.separator + tempCache.getName())
                .getAbsolutePath();
        PathsAndFiles.CACHE_DIR.setValue(nonCanonicalRoot);

        Assert.assertTrue(CacheUtil.cachedResourceMatchesApplication(
                info.getCanonicalFile().toPath(), jnlp.toString()));
    }

    private static File writeCachedResource(URL source, String contents) throws Exception {
        File cacheFile = CacheUtil.makeNewCacheFile(source, null);
        Files.write(cacheFile.toPath(), contents.getBytes(StandardCharsets.UTF_8));
        return cacheFile;
    }

    private static void writeJnlpPath(File cacheFile, String jnlpPath) {
        File info = new File(cacheFile.getPath() + CacheDirectory.INFO_SUFFIX);
        net.sourceforge.jnlp.util.PropertiesFile pf =
                new net.sourceforge.jnlp.util.PropertiesFile(info);
        pf.setProperty(CacheEntry.KEY_JNLP_PATH, jnlpPath);
        pf.store();
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
