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
    public void addBytesIsNoOpWhenInactive() {
        DownloadProgress.end();
        DownloadProgress.addBytes(999);
        assertTrue(DownloadProgress.current() == null);
        assertTrue(!DownloadProgress.isActive());
    }
}
