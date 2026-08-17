package net.sourceforge.jnlp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class DownloadProgressTest {

    @Test
    public void formatsRatesBytesAndEta() {
        assertEquals("-", DownloadProgress.formatRate(0));
        assertTrue(DownloadProgress.formatRate(1500).contains("KB/s"));
        assertTrue(DownloadProgress.formatRate(2.5 * 1024 * 1024).contains("MB/s"));
        assertTrue(DownloadProgress.formatBytes(1500).contains("KB"));
        assertEquals("1:05", DownloadProgress.formatEta(65_000L));
        assertEquals("-", DownloadProgress.formatEta(-1L));
    }

    @Test
    public void instantWindowUsesLastTenSeconds() {
        DownloadProgress p = new DownloadProgress(2);
        p.knownTotal = 10_000L;
        long t0 = 1_000_000L;
        p.recordSample(t0, 0L);
        p.recordSample(t0 + 10_000L, 5_000L);
        p.bytes.set(5_000L);
        long[] w = p.instantWindow(t0 + 10_000L, 5_000L);
        assertEquals(5_000L, w[0]);
        assertEquals(10_000L, w[1]);
        DownloadProgress.Snapshot s = p.snapshot();
        assertEquals(50, s.percent);
        assertEquals(2, s.slots.length);
    }

    @Test
    public void wireCompleteStaysAt99UntilMarkedComplete() {
        DownloadProgress p = new DownloadProgress(2);
        p.knownTotal = 1_000L;
        p.bytes.set(1_000L);
        p.slots[0].bind("giant.jar", 1_000L);
        p.slots[0].add(1_000L);
        DownloadProgress.Snapshot s = p.snapshot();
        assertEquals(99, s.percent, "100% before wait() returns looks like a hang");
        assertTrue(!DownloadProgress.isAdvanced(), "simple mode is the default");
        assertEquals("", s.finishing, "simple mode hides jar names");
        p.complete = true;
        assertEquals(100, p.snapshot().percent);
    }

    @Test
    public void addBytesIsNoOpWhenInactive() {
        DownloadProgress.end();
        DownloadProgress.addBytes(999);
        assertTrue(DownloadProgress.current() == null);
        assertTrue(!DownloadProgress.isActive());
    }

    @Test
    public void midDownloadUnpackKeepsDownloadingLabelAndWirePercent() {
        DownloadProgress p = new DownloadProgress(2);
        p.knownTotal = 10_000L;
        p.bytes.set(2_900L);
        p.startUnpack(Integer.valueOf(0), "fonts.jar", 500_000L);
        DownloadProgress.Snapshot s = p.snapshot();
        assertTrue(s.unpack.active, "pack.gz may unpack while other GETs run");
        assertTrue(!s.unpacking, "still downloading — do not say Unpacking");
        assertEquals(29, s.percent, "percent is wire bytes, not unpack output");
        assertEquals(29, s.wirePct);
        assertEquals(2900L, s.bytes);
        assertEquals(10_000L, s.knownTotal);
        p.bytes.set(10_000L);
        s = p.snapshot();
        assertEquals(99, s.percent);
        assertTrue(s.unpacking, "wire done + unpack still running → Unpacking 99%");
    }

    @Test
    public void openGetKeepsDownloadingEvenAt99Percent() {
        DownloadProgress p = new DownloadProgress(2);
        p.knownTotal = 209_000_000L;
        p.bytes.set(207_000_000L);
        p.wireStarted = true;
        p.wireOpen.set(3);
        p.startUnpack(Integer.valueOf(0), "fonts.jar", 500_000L);
        DownloadProgress.Snapshot s = p.snapshot();
        assertEquals(99, s.percent);
        assertEquals(99, s.wirePct);
        assertEquals(3, s.wireOpen);
        assertTrue(!s.wireDone);
        assertTrue(s.unpack.active);
        assertTrue(!s.unpacking, "open GETs are still Downloading");
        p.bytes.set(209_000_000L);
        p.wireOpen.set(0);
        s = p.snapshot();
        assertTrue(s.unpacking, "all GETs finished → Unpacking");
    }

    @Test
    public void unpackBarTracksOutputAndStaysAt99UntilJobEnds() {
        DownloadProgress p = new DownloadProgress(2);
        p.knownTotal = 1_000L;
        p.bytes.set(1_000L);
        p.startUnpack(Integer.valueOf(0), "giant.jar", 17_000_000L);
        long est = p.estimateUnpackBytes(17_000_000L);
        assertEquals((long) (17_000_000L * DownloadProgress.DEFAULT_UNPACK_RATIO), est);
        p.addUnpack(Integer.valueOf(0), est / 2);
        DownloadProgress.Snapshot s = p.snapshot();
        assertTrue(s.unpack.active);
        assertTrue(s.unpacking, "wire-done unpack switches the simple label");
        assertEquals(50, s.unpack.percent, s.unpack.percent + " " + s.unpack.bytes + "/" + s.unpack.total);
        assertEquals("", s.finishing, "simple mode hides jar names");
        assertTrue(s.unpack.showBar(99, false));
        p.addUnpack(Integer.valueOf(0), est);
        s = p.snapshot();
        assertTrue(s.unpack.percent < 100, "must not paint 100% while still unpacking: " + s.unpack.percent);
        assertTrue(s.unpack.percent >= 90, "overrun should keep the bar moving: " + s.unpack.percent);
        p.finishUnpack(Integer.valueOf(0), est);
        s = p.snapshot();
        assertTrue(!s.unpack.active);
        assertTrue(!s.unpack.showBar(99, true));
    }

    @Test
    public void countingOutputIsIdentityWhenInactive() {
        DownloadProgress.end();
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        assertTrue(DownloadProgress.countingOutput(raw) == raw);
        DownloadProgress.addUnpackBytes(50);
        DownloadProgress.beginUnpack("x.jar", 10);
    }

    @Test
    public void finishLaunchMarksCompleteAndClears() {
        DownloadProgress.end();
        assertTrue(!DownloadProgress.isAdvanced());
        DownloadProgress p = new DownloadProgress(2);
        p.knownTotal = 100L;
        p.bytes.set(100L);
        p.deferredUnpack = true;
        assertTrue(p.snapshot().unpacking);
        assertEquals(99, p.snapshot().percent);
        p.complete = true;
        assertTrue(!p.snapshot().unpacking);
        assertEquals(100, p.snapshot().percent);
        DownloadProgress.finishLaunch();
        assertTrue(DownloadProgress.current() == null);
    }
}
