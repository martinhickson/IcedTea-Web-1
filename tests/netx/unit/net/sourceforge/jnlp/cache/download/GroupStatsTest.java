package net.sourceforge.jnlp.cache.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class GroupStatsTest {

    private static URL url(String s) {
        try {
            return new URL(s);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void aggregatesDownloadedCachedFailedAndCompression() {
        JarGroupState g = JarGroupState.forJars(Arrays.asList(
                url("http://localhost/a.jar"),
                url("http://localhost/b.jar"),
                url("http://localhost/c.jar")));

        JarSlot dl = g.slot(0);
        // Late jar relative to group start, but connect itself is instant → reused.
        dl.onConnect(dl.startMillis + 5_000, dl.startMillis + 5_000);
        dl.onFirstByte(dl.startMillis + 5_005);
        dl.onLastByte(dl.startMillis + 5_025);
        dl.addTransferred(2048);
        dl.onDecompressed(4096, true);
        assertTrue(dl.settleGood(dl.startMillis + 30, false));

        JarSlot cached = g.slot(1);
        assertTrue(cached.settleGood(cached.startMillis + 1, true));

        JarSlot failed = g.slot(2);
        assertTrue(failed.settleBadFinal(failed.startMillis + 2));

        GroupStats s = g.stats();
        assertEquals(3, s.total);
        assertEquals(1, s.downloadedCount);
        assertEquals(1, s.cachedCount);
        assertEquals(1, s.failedCount);
        assertEquals(2048, s.totalBytes);
        assertEquals(4096, s.totalDecompressedBytes);
        assertEquals(2.0, s.compressionRatio, 0.001);
        assertEquals(1, s.reusedConnections);
        assertEquals(0, s.handshakes);

        String summary = s.summaryLine();
        assertTrue(summary.contains("failed=1"), summary);
        assertTrue(summary.contains("cached=1"), summary);
        assertTrue(summary.contains("dl=1"), summary);
        assertTrue(summary.contains("ratio=50.0%"), summary);
        assertTrue(summary.contains("qwait="), summary);
        assertTrue(summary.contains("bytes=2,048"), summary);

        String[] lines = s.jarLines();
        assertEquals(3, lines.length);
        assertTrue(lines[0].contains("kind=DOWNLOADED") && lines[0].contains("comp=gz")
                && lines[0].contains("ratio=50.0%"), lines[0]);
        assertTrue(lines[1].contains("kind=CACHED"), lines[1]);
        assertTrue(lines[2].contains("kind=FAILED"), lines[2]);
    }

    @Test
    public void countsRetriedAndHandshakeWhenConnectIsSlow() {
        JarGroupState g = JarGroupState.forJars(Arrays.asList(
                url("http://localhost/a.jar"),
                url("http://localhost/b.jar")));

        JarSlot a = g.slot(0);
        a.settleUnusable(a.startMillis + 1);
        assertTrue(a.claimRetry());
        a.onConnect(a.startMillis + 50, a.startMillis + 80); // 30ms connect = handshake
        a.onFirstByte(a.startMillis + 90);
        a.onLastByte(a.startMillis + 160);
        a.addTransferred(1024);
        assertTrue(a.settleGood(a.startMillis + 200, false));

        assertTrue(g.slot(1).settleGood(g.slot(1).startMillis + 1, true));

        GroupStats s = g.stats();
        assertEquals(1, s.retriedCount);
        assertEquals(0, s.reusedConnections);
        assertEquals(1, s.handshakes);
        assertTrue(s.summaryLine().contains("retried=1"), s.summaryLine());
        assertTrue(s.jarLines()[0].contains("retried=true"), s.jarLines()[0]);
    }

    @Test
    public void lateJarWithInstantConnectCountsAsReusedNotHandshake() {
        JarGroupState g = JarGroupState.forJars(Arrays.asList(url("http://localhost/late.jar")));
        JarSlot late = g.slot(0);
        long t = late.startMillis + 10_000; // far after group start
        late.onConnect(t, t); // zero-duration connect
        late.onFirstByte(t + 1);
        late.onLastByte(t + 10);
        late.addTransferred(512);
        assertTrue(late.settleGood(t + 20, false));
        GroupStats s = g.stats();
        assertEquals(1, s.reusedConnections);
        assertEquals(0, s.handshakes);
        assertEquals(1.0, s.meanTTFBMillis, 0.001);
    }

    @Test
    public void clientReusedFlagCountsEvenWhenConnectIncludesTtfb() {
        JarGroupState g = JarGroupState.forJars(Arrays.asList(url("http://localhost/keep-alive.jar")));
        JarSlot slot = g.slot(0);
        long t = slot.startMillis + 1_000;
        slot.onConnect(t, t + 80, true); // 80ms TTFB on a reused socket
        slot.onFirstByte(t + 80);
        slot.onLastByte(t + 200);
        slot.addTransferred(1024);
        assertTrue(slot.settleGood(t + 220, false));
        GroupStats s = g.stats();
        assertEquals(1, s.reusedConnections);
        assertEquals(0, s.handshakes);
    }

    @Test
    public void emptyDownloadSetLeavesMeansNegative() {
        JarGroupState g = JarGroupState.forJars(Arrays.asList(url("http://localhost/a.jar")));
        assertTrue(g.slot(0).settleGood(g.slot(0).startMillis + 1, true));
        GroupStats s = g.stats();
        assertEquals(0, s.downloadedCount);
        assertEquals(1, s.cachedCount);
        assertEquals(-1, s.meanDurationMillis, 0.001);
        assertEquals(-1, s.meanThroughputKBps, 0.001);
        assertEquals(1.0, s.compressionRatio, 0.001);
    }
}
