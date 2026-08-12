package net.sourceforge.jnlp.cache;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.sourceforge.jnlp.DownloadOptions;
import net.sourceforge.jnlp.ServerAccess;
import net.sourceforge.jnlp.ServerLauncher;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;

/**
 * Production race: waitForJars / checkForMain saw DOWNLOADED or ERROR with no usable
 * local jar (ghost LRU / premature ERROR), reported "JAR ... not found", then
 * Unknown Main-Class — while a versioned {@code __V} GET was still able to succeed.
 * One automatic re-download must recover.
 */
public class UnusableTerminalDownloadRetryTest extends NoStdOutErrTest {

    private String originalCacheDir;
    private File tempCache;
    private ServerLauncher server;

    @Before
    public void setUp() throws Exception {
        originalCacheDir = PathsAndFiles.CACHE_DIR.getFullPath();
        tempCache = new File(System.getProperty("java.io.tmpdir"),
                "itw-unusable-terminal-" + System.nanoTime());
        tempCache.mkdirs();
        PathsAndFiles.CACHE_DIR.setValue(tempCache.getAbsolutePath());

        File web = new File(System.getProperty("java.io.tmpdir"),
                "itw-unusable-terminal-web-" + System.nanoTime());
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
    public void getCacheFileRedownloadsWhenDownloadedFlagSetWithoutUsableJar() throws Exception {
        // Unique href avoids WeakList Resource reuse (equals is URL-only) across tests.
        URL url = server.getUrl("recover-downloaded.jar");
        writeMinimalJar(new File(server.getDir(), "recover-downloaded.jar"), "1.0");
        ResourceTracker tracker = new ResourceTracker();
        tracker.addResource(url, null, new DownloadOptions(false, false), UpdatePolicy.ALWAYS);

        Resource resource = Resource.getResource(url, null, UpdatePolicy.ALWAYS);
        // Simulate premature terminal state: DOWNLOADED, localFile points at missing path.
        File ghost = new File(tempCache, "ghost/main-app.jar");
        resource.setLocalFile(ghost);
        resource.resetStatus();
        resource.setStatusFlag(Resource.Status.DOWNLOADED);
        resource.setStatusFlag(Resource.Status.CONNECTED);
        resource.setStatusFlag(Resource.Status.PROCESSING);

        assertFalse(ResourceTracker.hasUsableLocalFile(resource));

        File local = tracker.getCacheFile(url);
        assertNotNull("must recover with a real cache jar, not null (Unknown Main-Class path)", local);
        assertTrue(CacheUtil.isValidJarFile(local));
        assertTrue(ResourceTracker.hasUsableLocalFile(resource));
    }

    @Test
    public void waitForResourcesRedownloadsAfterPrematureError() throws Exception {
        URL url = server.getUrl("recover-error.jar");
        writeMinimalJar(new File(server.getDir(), "recover-error.jar"), "1.0");
        final AtomicInteger downloads = new AtomicInteger();
        ResourceTracker tracker = new ResourceTracker() {
            @Override
            protected void startDownloadThread(Resource resource) {
                downloads.incrementAndGet();
                super.startDownloadThread(resource);
            }
        };
        tracker.addResource(url, null, new DownloadOptions(false, false), UpdatePolicy.ALWAYS);

        Resource resource = Resource.getResource(url, null, UpdatePolicy.ALWAYS);
        // Simulate premature ERROR after addResource (production race), not a spent retry.
        resource.clearUnusableTerminalRetry();
        resource.setLocalFile(null);
        resource.setTerminalState(net.sourceforge.jnlp.cache.download.JarState.SETTLED_BAD);

        assertTrue("wait must complete", tracker.waitForResources(new URL[] { url }, 60_000L));
        File local = tracker.getCacheFile(url);
        assertNotNull("after premature ERROR, getCacheFile must recover; status="
                + resource.getCopyOfStatus() + " downloads=" + downloads.get()
                + " local=" + resource.getLocalFile()
                + " retried=" + resource.isUnusableTerminalRetried(), local);
        assertTrue(CacheUtil.isValidJarFile(local));
        assertTrue("expected a re-download after premature ERROR", downloads.get() >= 1);
    }

    private static File writeMinimalJar(File target, String version) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,
                "net.sourceforge.jnlp.integration.HeadlessJnlpMain");
        if (version != null) {
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        }
        target.getParentFile().mkdirs();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(target), manifest)) {
            jos.putNextEntry(new java.util.jar.JarEntry("META-INF/"));
            jos.closeEntry();
        }
        return target;
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
