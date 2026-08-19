package net.sourceforge.jnlp.cache;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport;
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
            CacheLRUWrapper lru = CacheLRUWrapper.getInstance();
            synchronized (lru) {
                lru.lock();
                try {
                    lru.load();
                    lru.clearLRUSortedEntries();
                    lru.store();
                } finally {
                    lru.unlock();
                }
            }
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
    public void catalogRowJnlpPathBlocksJarAndDomainClearIds() throws Exception {
        URL jnlp = new URL("http://127.0.0.1:4360/jnlp/g28/app.jnlp");
        URL jar = new URL("http://127.0.0.1:4360/jnlp/g28/app.jar");
        File jnlpFile = writeCachedResource(jnlp, "<jnlp/>");
        File jarFile = writeCachedResource(jar, "jar-bytes");
        writeJnlpPath(jnlpFile, jnlp.toString());
        writeJnlpPath(jarFile, jnlp.toString());

        Process holder = new ProcessBuilder("sleep", "60").start();
        try {
            CacheLRUWrapper.getInstance().registerRunningApp(
                    (int) holder.pid(), jnlp.toString(), processStartOf(holder));
            Assert.assertTrue("jar id uses catalog jnlp-path",
                    CacheUtil.catalogRowsForClearIdBlocked(jar.toString()));
            Assert.assertTrue("domain id uses catalog jnlp-path",
                    CacheUtil.catalogRowsForClearIdBlocked("127.0.0.1"));
            Assert.assertFalse(CacheUtil.canClearApplicationCache(jar.toString()));
            Assert.assertFalse(CacheUtil.canClearApplicationCache("127.0.0.1"));
        } finally {
            holder.destroyForcibly();
            CacheLRUWrapper.getInstance().unregisterRunningApp((int) holder.pid());
        }
    }

    @Test
    public void catalogRunningAppBlocksHeldJnlpJarDomainAndAllClear() throws Exception {
        URL jnlp = new URL("http://127.0.0.1:4350/jnlp/c401/app.jnlp");
        URL jar = new URL("http://127.0.0.1:4350/jnlp/c401/app.jar");
        File jnlpFile = writeCachedResource(jnlp, "<jnlp/>");
        File jarFile = writeCachedResource(jar, "jar-bytes");
        writeJnlpPath(jnlpFile, jnlp.toString());
        writeJnlpPath(jarFile, jnlp.toString());

        Process holder = new ProcessBuilder("sleep", "60").start();
        try {
            CacheLRUWrapper.getInstance().registerRunningApp(
                    (int) holder.pid(), jnlp.toString(), processStartOf(holder));

            Assert.assertFalse("jnlp id", CacheUtil.canClearApplicationCache(jnlp.toString()));
            Assert.assertFalse("jar id", CacheUtil.canClearApplicationCache(jar.toString()));
            Assert.assertFalse("domain id", CacheUtil.canClearApplicationCache("127.0.0.1"));
            Assert.assertFalse("all-clear", CacheUtil.checkToClearCache());

            Assert.assertFalse(CacheUtil.clearCache(jnlp.toString(), true, true));
            Assert.assertFalse(CacheUtil.clearCache(jar.toString(), true, true));
            Assert.assertFalse(CacheUtil.clearCache("127.0.0.1", true, true));
            Assert.assertFalse(CacheUtil.clearCache());

            Assert.assertTrue(jnlpFile.isFile());
            Assert.assertTrue(jarFile.isFile());
        } finally {
            holder.destroyForcibly();
            CacheLRUWrapper.getInstance().unregisterRunningApp((int) holder.pid());
        }
    }

    @Test
    public void unknownUrlStillNoMatchWhileCatalogAppIsRunning() throws Exception {
        URL jnlp = new URL("http://127.0.0.1:4350/jnlp/c401/app.jnlp");
        File jnlpFile = writeCachedResource(jnlp, "<jnlp/>");
        writeJnlpPath(jnlpFile, jnlp.toString());
        Process holder = new ProcessBuilder("sleep", "60").start();
        try {
            CacheLRUWrapper.getInstance().registerRunningApp(
                    (int) holder.pid(), jnlp.toString(), processStartOf(holder));
            Assert.assertFalse(CacheUtil.clearCache(
                    "http://127.0.0.1:4200/jnlp/no-such/app.jar", true, true));
            Assert.assertTrue(jnlpFile.isFile());
        } finally {
            holder.destroyForcibly();
            CacheLRUWrapper.getInstance().unregisterRunningApp((int) holder.pid());
        }
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
    public void unknownClearIdNotBlockedByHeldConsoleAndDoesNotDeleteIt() throws Exception {
        JnlpRunningProcessSupport.RunningProcess held =
                new JnlpRunningProcessSupport.RunningProcess(42, "console", "1.0",
                        "java -jar icedtea-web-uber.jar http://127.0.0.1:4200/jnlp/console/app.jnlp",
                        "http://127.0.0.1:4200/jnlp/console/app.jnlp");
        String unknown = "http://127.0.0.1:4200/jnlp/no-such/app.jar";
        Assert.assertTrue(held.blocksCacheClear("http://127.0.0.1:4200/jnlp/console/app.jnlp"));
        Assert.assertFalse(held.blocksCacheClear(unknown));

        URL jnlp = new URL("http://127.0.0.1:4200/jnlp/console/app.jnlp");
        File jnlpFile = writeCachedResource(jnlp, "<jnlp/>");
        writeJnlpPath(jnlpFile, jnlp.toString());
        Assert.assertFalse(CacheUtil.clearCache(unknown, true, true));
        Assert.assertTrue(jnlpFile.isFile());
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
        URL jnlp = new URL("http://192.0.2.1:4200/jnlp/console/app.jnlp");
        URL otherHost = new URL("http://192.0.2.2:4200/jnlp/console/app.jnlp");
        File jnlpFile = writeCachedResource(jnlp, "<jnlp/>");
        File otherFile = writeCachedResource(otherHost, "<jnlp/>");

        Assert.assertTrue(CacheUtil.clearCache("192.0.2.1", true, true));
        Assert.assertFalse("same-host JNLP should be cleared by domain id", jnlpFile.exists());
        Assert.assertTrue("other host must stay", otherFile.isFile());
    }

    @Test
    public void cacheIdIsRunningJnlpHostIsExactHost() {
        String running = "http://evil.example.com/app.jnlp";
        Assert.assertTrue(CacheUtil.cacheIdIsRunningJnlpHost("evil.example.com", running));
        Assert.assertFalse(CacheUtil.cacheIdIsRunningJnlpHost("example.com", running));
        Assert.assertFalse(CacheUtil.cacheIdIsRunningJnlpHost("com", running));
        Assert.assertTrue(CacheUtil.cacheIdIsRunningJnlpHost("127.0.0.1",
                "http://127.0.0.1:4200/jnlp/console/app.jnlp"));
    }

    @Test
    public void protocolFromCacheRelativePathReadsScheme() {
        Assert.assertEquals("http", CacheUtil.protocolFromCacheRelativePath(
                "/http/127.0.0.1/4200/jnlp/console/app.jnlp"));
        Assert.assertEquals("https", CacheUtil.protocolFromCacheRelativePath(
                "/https/example.com/app.jnlp"));
        Assert.assertNull(CacheUtil.protocolFromCacheRelativePath(null));
    }

    @Test
    public void hostFromCacheRelativePathReadsHttpHost() {
        Assert.assertEquals("127.0.0.1", CacheUtil.hostFromCacheRelativePath(
                "/http/127.0.0.1/4200/jnlp/console/app.jnlp"));
        Assert.assertEquals("127.0.0.1", CacheUtil.hostFromCacheRelativePath(
                "\\http\\127.0.0.1\\4200\\jnlp\\console\\app.jnlp"));
        Assert.assertEquals("example.com", CacheUtil.hostFromCacheRelativePath(
                "/https/example.com/app.jnlp"));
        Assert.assertNull(CacheUtil.hostFromCacheRelativePath(null));
        Assert.assertNull(CacheUtil.hostFromCacheRelativePath(""));
    }

    @Test
    public void directoryNodeReadsCatalogNotInfoSidecar() throws Exception {
        URL jnlp = new URL("http://127.0.0.1:4200/jnlp/console/app.jnlp");
        File jnlpFile = writeCachedResource(jnlp, "<jnlp/>");
        writeJnlpPath(jnlpFile, jnlp.toString());

        DirectoryNode node = new DirectoryNode(jnlpFile.getName(), jnlpFile, null);
        CacheEntryMeta meta = node.getMeta();
        Assert.assertNotNull(meta);
        Assert.assertEquals(jnlp.toString(), meta.jnlpPath);
        Assert.assertFalse(new File(jnlpFile.getPath() + CacheDirectory.INFO_SUFFIX).exists());
        Assert.assertTrue(meta.formatAsInfoText().contains("jnlp-path=" + jnlp));
    }

    @Test
    public void cacheViewerRowsComeFromCatalogNotDiskWalk() throws Exception {
        URL jnlp = new URL("http://127.0.0.1:4200/jnlp/console/app.jnlp");
        File jnlpFile = writeCachedResource(jnlp, "<jnlp/>");
        writeJnlpPath(jnlpFile, jnlp.toString());
        File nativeLib = new File(CacheLRUWrapper.getInstance().getCacheDir().getFile(),
                "native/sqlitejdbc.so");
        Assert.assertTrue(nativeLib.getParentFile().mkdirs() || nativeLib.getParentFile().isDirectory());
        Assert.assertTrue(nativeLib.createNewFile() || nativeLib.isFile());

        java.util.List<Object[]> rows = CacheDirectory.listViewerRows();
        Object[] row = null;
        for (Object[] candidate : rows) {
            if (jnlp.toString().equals(candidate[6])) {
                row = candidate;
                break;
            }
        }
        Assert.assertNotNull("catalog should include this JNLP", row);
        DirectoryNode node = (DirectoryNode) row[0];
        Assert.assertEquals(jnlpFile.getName(), node.toString());
        Assert.assertEquals("http", row[2]);
        Assert.assertEquals("127.0.0.1", row[3]);
        Assert.assertEquals(jnlp.toString(), row[6]);
        Assert.assertFalse(new File(jnlpFile.getPath() + CacheDirectory.INFO_SUFFIX).exists());
    }

    @Test
    public void removeByPathDropsCatalogRow() throws Exception {
        URL jnlp = new URL("http://127.0.0.1:4200/jnlp/console/app.jnlp");
        File jnlpFile = writeCachedResource(jnlp, "<jnlp/>");
        writeJnlpPath(jnlpFile, jnlp.toString());
        CacheLRUWrapper lru = CacheLRUWrapper.getInstance();
        Assert.assertNotNull(lru.getMetaByPath(jnlpFile.getPath()));
        DirectoryNode node = new DirectoryNode(jnlpFile.getName(), jnlpFile, null);
        node.removeCatalogRow();
        Assert.assertNull(lru.getMetaByPath(jnlpFile.getPath()));
        Assert.assertTrue(CacheDirectory.listViewerRows().isEmpty());
    }

    @Test
    public void clearIdForMetaPrefersJnlpPathThenHref() {
        CacheEntryMeta meta = new CacheEntryMeta();
        meta.resourceUrl = "/http/127.0.0.1/4200/jnlp/console/app.jar";
        Assert.assertEquals("http://127.0.0.1:4200/jnlp/console/app.jar",
                CacheUtil.clearIdForMeta(meta));
        meta.jnlpPath = "http://127.0.0.1:4200/jnlp/console/app.jnlp";
        Assert.assertEquals("http://127.0.0.1:4200/jnlp/console/app.jnlp",
                CacheUtil.clearIdForMeta(meta));
        Assert.assertNull(CacheUtil.clearIdForMeta(null));
    }

    @Test
    public void catalogRowMatchesJnlpUrlNotHost() throws Exception {
        URL jnlp = new URL("http://127.0.0.1:4200/jnlp/console/app.jnlp");
        File jnlpFile = writeCachedResource(jnlp, "<jnlp/>");
        CacheEntryMeta row = CacheLRUWrapper.getInstance().getMetaByPath(jnlpFile.getPath());
        Assert.assertNotNull(row);
        Assert.assertTrue(CacheUtil.catalogRowExactMatch(row, jnlp.toString(), true, true));
        Assert.assertFalse(CacheUtil.catalogRowExactMatch(row,
                "http://127.0.0.1:4200/jnlp/no-such/app.jar", true, true));
        Assert.assertEquals("127.0.0.1", CacheUtil.hostFromCacheRelativePath(row.resourceUrl));
    }

    private static String processStartOf(Process process) {
        return ProcessHandle.of(process.pid())
                .flatMap(handle -> handle.info().startInstant())
                .map(java.time.Instant::toString)
                .orElseThrow(() -> new AssertionError("holder has no startInstant"));
    }

    private static File writeCachedResource(URL source, String contents) throws Exception {
        File cacheFile = CacheUtil.makeNewCacheFile(source, null);
        Files.write(cacheFile.toPath(), contents.getBytes(StandardCharsets.UTF_8));
        return cacheFile;
    }

    private static void writeJnlpPath(File cacheFile, String jnlpPath) {
        CacheLRUWrapper lru = CacheLRUWrapper.getInstance();
        CacheEntryMeta meta = lru.getMetaByPath(cacheFile.getPath());
        if (meta == null) {
            meta = new CacheEntryMeta();
            meta.path = cacheFile.getPath();
        }
        meta.jnlpPath = jnlpPath;
        lru.putMeta(meta);
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
