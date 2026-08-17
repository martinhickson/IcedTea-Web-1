package net.sourceforge.jnlp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.sourceforge.jnlp.cache.download.JarGroupState;
import net.sourceforge.jnlp.cache.download.JarState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceDownloaderHeadCacheTest {

    @TempDir
    Path tmp;

    @AfterEach
    void resetQueue() {
        SizeFirstDownloadQueue.resetForTests();
    }

    @Test
    void headSizeMatchSettlesCachedWithoutGet() throws Exception {
        URL u = new URL("http://head-cache.test/plain-" + System.nanoTime() + ".bin");
        Resource r = Resource.getResource(u, null, UpdatePolicy.ALWAYS);
        Path file = tmp.resolve("plain.bin");
        byte[] body = "cached-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(file, body);
        r.setLocalFile(file.toFile());
        JarGroupState group = JarGroupState.forJars(Arrays.asList(u));
        r.setJarSlot(group.slot(0));

        SizeFirstDownloadQueue.recordHead(r, u, body.length, 1_700_000_000_000L);
        assertTrue(new ResourceDownloader(r, new Object()).trySettleFromHead(
                SizeFirstDownloadQueue.headMeta(r)));
        assertEquals(JarState.GOOD, r.getTerminalState());
        assertTrue(group.slot(0).settleStatsLine().contains("kind=CACHED"));
    }

    @Test
    void forcePolicyDoesNotSettleFromHead() throws Exception {
        URL u = new URL("http://head-cache.test/force-" + System.nanoTime() + ".bin");
        Resource r = Resource.getResource(u, null, UpdatePolicy.FORCE);
        Path file = tmp.resolve("force.bin");
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));
        r.setLocalFile(file.toFile());
        SizeFirstDownloadQueue.recordHead(r, u, 1L, 1L);
        assertFalse(ResourceDownloader.peekCacheHit(r, SizeFirstDownloadQueue.headMeta(r)));
        assertFalse(new ResourceDownloader(r, new Object()).trySettleFromHead(
                SizeFirstDownloadQueue.headMeta(r)));
    }
}
