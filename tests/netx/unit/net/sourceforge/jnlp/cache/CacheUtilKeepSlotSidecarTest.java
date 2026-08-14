package net.sourceforge.jnlp.cache;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.util.PropertiesFile;
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
        File info = new File(jar.getPath() + CacheDirectory.INFO_SUFFIX);
        Assert.assertTrue(jar.delete());

        CacheUtil.cleanCacheOnShutdown();

        Assert.assertTrue("unmarked .info must stay when jar is not a file yet", info.isFile());
    }

    @Test
    public void markedDeleteStillRemovesSlotIncludingSidecar() throws Exception {
        File jar = writeCachedJar();
        File sidecar = writeSidecar(jar, "pack-bytes");
        File info = new File(jar.getPath() + CacheDirectory.INFO_SUFFIX);
        PropertiesFile pf = new PropertiesFile(info);
        pf.setProperty("delete", "true");
        pf.store();

        CacheUtil.cleanCacheOnShutdown();

        Assert.assertFalse("marked jar should be removed", jar.exists());
        Assert.assertFalse("sidecar goes with the marked slot", sidecar.exists());
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
