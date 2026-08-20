package net.sourceforge.jnlp.cache;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * GitHub #16: kept-slot cleanup must not delete Pack200 drain sidecars.
 */
public class CacheUtilKeepSlotSidecarTest extends NoStdOutErrTest {

    private String originalCacheDir;
    private File tempCache;

    @Before
    public void setUp() throws Exception {
        originalCacheDir = PathsAndFiles.CACHE_DIR.getFullPath();
        tempCache = new File(System.getProperty("java.io.tmpdir"),
                "itw-keep-sidecar-" + System.nanoTime());
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
    public void cleanCacheKeepsUnmarkedPack200Sidecar() throws Exception {
        File jar = writeCachedJar();
        File sidecar = writeSidecar(jar, "pack-bytes");

        CacheUtil.cleanCache();

        Assert.assertTrue("kept jar must stay", jar.isFile());
        Assert.assertTrue("unmarked sidecar must stay (GitHub #16)", sidecar.isFile());
    }

    @Test
    public void shutdownSweepKeepsUnmarkedPack200Sidecar() throws Exception {
        File jar = writeCachedJar();
        File sidecar = writeSidecar(jar, "pack-bytes");

        CacheUtil.cleanCacheOnShutdown();

        Assert.assertTrue(jar.isFile());
        Assert.assertTrue(sidecar.isFile());
    }

    @Test
    public void shutdownSweepKeepsSidecarWhenJarNotWrittenYet() throws Exception {
        File jar = writeCachedJar();
        File sidecar = writeSidecar(jar, "pack-bytes");
        Assert.assertTrue(jar.delete());
        Assert.assertTrue(CacheUtil.hasInFlightPack200Sidecar(jar));

        CacheUtil.cleanCacheOnShutdown();

        Assert.assertTrue("in-flight sidecar must survive missing jar", sidecar.isFile());
        Assert.assertFalse("jar was not on disk", jar.exists());
    }

    @Test
    public void shutdownSweepDoesNotTreatMissingJarAsGhost() throws Exception {
        File jar = writeCachedJar();
        String path = jar.getPath();
        Assert.assertTrue(jar.delete());

        CacheUtil.cleanCacheOnShutdown();

        CacheEntryMeta row = CacheLRUWrapper.getInstance().getMetaByPath(path);
        Assert.assertNotNull("unmarked catalog row must stay when jar is not a file yet", row);
        Assert.assertFalse(row.markedDelete);
    }

    @Test
    public void markedDeleteRemovesOnlyCatalogPathNotUnmarkedSidecar() throws Exception {
        File jar = writeCachedJar();
        File sidecar = writeSidecar(jar, "pack-bytes");
        markForDelete(jar.getPath());

        CacheUtil.cleanCacheOnShutdown();

        Assert.assertFalse("marked jar should be removed", jar.exists());
        Assert.assertTrue("unmarked sidecar must stay", sidecar.isFile());
    }

    @Test
    public void markedDeleteAlreadyGoneDoesNotThrow() throws Exception {
        File jar = writeCachedJar();
        String path = jar.getPath();
        markForDelete(path);
        Assert.assertTrue(jar.delete());

        CacheUtil.cleanCacheOnShutdown();

        Assert.assertFalse(jar.exists());
        Assert.assertNull("marked row must be dropped even if file was already gone",
                CacheLRUWrapper.getInstance().getMetaByPath(path));
    }

    @Test
    public void shutdownSweepDoesNotThrowOnCacheDbPath() throws Exception {
        CacheLRUWrapper lru = CacheLRUWrapper.getInstance();
        File cacheDir = lru.getCacheDir().getFile();
        Assert.assertTrue(cacheDir.mkdirs() || cacheDir.isDirectory());
        File dbPath = new File(cacheDir, "db");
        String path = dbPath.getPath();
        lru.lock();
        try {
            lru.load();
            // Key is explicit: generateKey requires a numbered slot path.
            Assert.assertTrue(lru.addEntry("1700000000000,0,1", path));
            CacheEntryMeta meta = new CacheEntryMeta();
            meta.path = path;
            meta.markedDelete = true;
            lru.putMeta(meta);
            lru.store();
        } finally {
            lru.unlock();
        }

        CacheUtil.cleanCacheOnShutdown();

        Assert.assertTrue("catalog dir must survive a row named db", cacheDir.isDirectory());
        Assert.assertNull(lru.getMetaByPath(path));
    }

    @Test
    public void deleteCachePathsIfPresentIgnoresFileNotFound() throws Exception {
        File present = writeCachedJar();
        File missing = new File(present.getParentFile(), "already-gone.jar");
        File missingParent = new File(new File(present.getParentFile(), "no-such-dir"), "gone.jar");
        Set<String> paths = new HashSet<String>();
        paths.add(present.getPath());
        paths.add(missing.getPath());
        paths.add(missingParent.getPath());
        paths.add(null);
        CacheUtil.deleteCachePathsIfPresent(paths);
        CacheUtil.deleteCachePathsIfPresent(Collections.singleton(missing.getPath()));
        Assert.assertFalse(present.exists());
        Assert.assertFalse(missing.exists());
    }

    private static void markForDelete(String path) {
        CacheLRUWrapper lru = CacheLRUWrapper.getInstance();
        lru.lock();
        try {
            lru.load();
            if (lru.getMetaByPath(path) == null) {
                Assert.assertTrue(lru.addEntry(lru.generateKey(path), path));
            }
            CacheEntryMeta meta = lru.getMetaByPath(path);
            if (meta == null) {
                meta = new CacheEntryMeta();
                meta.path = path;
            }
            meta.markedDelete = true;
            lru.putMeta(meta);
            lru.store();
        } finally {
            lru.unlock();
        }
    }

    private static File writeCachedJar() throws Exception {
        URL source = new URL("http://127.0.0.1:4200/jnlp/console/app.jar");
        File cacheFile = CacheUtil.makeNewCacheFile(source, null);
        Files.write(cacheFile.toPath(), "jar-bytes".getBytes(StandardCharsets.UTF_8));
        return cacheFile;
    }

    private static File writeSidecar(File jar, String contents) throws Exception {
        File sidecar = new File(jar.getPath() + ".pack.gz.download.deadbeef");
        Files.write(sidecar.toPath(), contents.getBytes(StandardCharsets.UTF_8));
        return sidecar;
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
