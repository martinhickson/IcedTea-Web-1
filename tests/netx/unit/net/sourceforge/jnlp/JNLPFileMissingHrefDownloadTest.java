package net.sourceforge.jnlp;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import net.sourceforge.jnlp.cache.CacheUtil;
import net.sourceforge.jnlp.cache.UpdatePolicy;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Local JNLP with a remote {@code href} that 404s must fail with a download
 * error, not {@code NullPointerException: name can't be null} from
 * {@code FileInputStream(null)}.
 */
public class JNLPFileMissingHrefDownloadTest extends NoStdOutErrTest {

    private String originalCacheDir;
    private File tempCache;
    private ServerLauncher server;

    @Before
    public void setUp() throws Exception {
        originalCacheDir = PathsAndFiles.CACHE_DIR.getFullPath();
        tempCache = new File(System.getProperty("java.io.tmpdir"),
                "itw-missing-href-" + System.nanoTime());
        Assert.assertTrue(tempCache.mkdirs());
        PathsAndFiles.CACHE_DIR.setValue(tempCache.getAbsolutePath());

        File web = new File(System.getProperty("java.io.tmpdir"),
                "itw-missing-href-web-" + System.nanoTime());
        Assert.assertTrue(web.mkdirs());
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
    public void openURLMissingResourceThrowsDownloadErrorNotNameNpe() throws Exception {
        URL missing = server.getUrl("missing-href.jnlp");
        try {
            JNLPFile.openURL(missing, null, UpdatePolicy.ALWAYS);
            Assert.fail("expected IOException when href JNLP is missing");
        } catch (IOException ex) {
            assertDownloadErrorWithoutNameNpe(ex, missing);
        }
    }

    @Test
    public void localJnlpWithMissingRemoteHrefThrowsDownloadErrorNotNameNpe() throws Exception {
        URL missing = server.getUrl("missing-href.jnlp");
        File local = new File(tempCache, "app.jnlp");
        String codebase = server.getUrl().toExternalForm();
        if (!codebase.endsWith("/")) {
            codebase = codebase + "/";
        }
        Files.write(local.toPath(), (
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"" + codebase + "\" href=\"missing-href.jnlp\">\n"
                + "  <information><title>Missing Href</title><vendor>ITW</vendor></information>\n"
                + "  <resources><j2se version=\"17+\"/></resources>\n"
                + "  <application-desc main-class=\"example.Main\"/>\n"
                + "</jnlp>\n"
                ).getBytes(StandardCharsets.UTF_8));

        try {
            new JNLPFile(local.toURI().toURL());
            Assert.fail("expected IOException when href JNLP cannot be downloaded");
        } catch (IOException ex) {
            assertDownloadErrorWithoutNameNpe(ex, missing);
        }
    }

    private static void assertDownloadErrorWithoutNameNpe(Throwable ex, URL missing) {
        Assert.assertFalse("must not surface FilePermission NPE: " + stack(ex),
                containsNameCantBeNull(ex));
        String text = stack(ex);
        Assert.assertTrue("error should name the missing JNLP: " + text,
                text.contains("missing-href.jnlp") || text.contains(missing.toString()));
        Assert.assertTrue("error should be a download failure: " + text,
                text.toLowerCase().contains("download") || text.contains("Could not download"));
    }

    private static boolean containsNameCantBeNull(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase().contains("name can't be null")) {
                return true;
            }
        }
        return false;
    }

    private static String stack(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return sb.toString();
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
