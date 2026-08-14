package net.sourceforge.jnlp.cache.download;

import java.util.Locale;

public final class GroupStats {

    public long groupStartMillis, groupEndMillis;
    public long wallDurationMillis;

    final long[]   ttfbMillis;
    final long[]   durationMillis;
    final long[]   queueWaitMillis;
    final long[]   transferMillis;
    final long[]   transferred;
    final long[]   decompressedBytes;
    final boolean[] compressed;
    final double[] throughputKBps;
    final MetricKind[] kind;
    final boolean[] retried;
    final JarSlot[] slots;

    public int total, downloadedCount, cachedCount, failedCount, retriedCount;
    public int reusedConnections;
    public int handshakes;

    public long sumDurationMillis;
    public long sumQueueWaitMillis;
    public long totalBytes;
    public long totalDecompressedBytes;
    public double compressionRatio;
    public double meanDurationMillis;
    public double meanQueueWaitMillis;
    public double meanTTFBMillis;
    public double maxDurationMillis;
    public double meanThroughputKBps;
    public double minThroughputKBps;
    public double meanDurationPerKB;

    static GroupStats from(JarGroupState g) {
        int n = g.jars.length;
        GroupStats s = new GroupStats(n);
        s.groupStartMillis = g.groupStartMillis;
        s.groupEndMillis = g.groupEndMillis > 0 ? g.groupEndMillis : System.currentTimeMillis();
        s.wallDurationMillis = s.groupEndMillis - s.groupStartMillis;

        int dl = 0, cached = 0, failed = 0, retried = 0, reused = 0;
        long sumDur = 0, sumQwait = 0, sumBytes = 0, sumDecomp = 0;
        long sumTtfb = 0, sumTransfer = 0;
        double maxDur = 0, minThr = Double.MAX_VALUE;

        for (int i = 0; i < n; i++) {
            JarSlot slot = g.jars[i];
            MetricKind k = slot.kind;
            s.kind[i] = k;
            s.slots[i] = slot;
            s.retried[i] = slot.retried.get();
            if (slot.retried.get()) retried++;

            s.transferred[i] = slot.transferred.get();
            s.decompressedBytes[i] = slot.decompressedBytes;
            s.compressed[i] = slot.compressed;
            s.ttfbMillis[i] = slot.ttfbMillis();
            s.durationMillis[i] = slot.durationMillis();
            s.queueWaitMillis[i] = slot.queueWaitMillis();
            s.transferMillis[i] = slot.transferMillis();
            s.throughputKBps[i] = slot.throughputKBps();

            if (k == MetricKind.CACHED) { cached++; continue; }
            if (k == MetricKind.FAILED) { failed++; continue; }

            dl++;
            sumBytes += s.transferred[i];
            sumDecomp += s.decompressedBytes[i] > 0 ? s.decompressedBytes[i] : s.transferred[i];
            if (s.durationMillis[i] > 0) {
                sumDur += s.durationMillis[i];
                if (s.durationMillis[i] > maxDur) maxDur = s.durationMillis[i];
            }
            if (s.queueWaitMillis[i] > 0) {
                sumQwait += s.queueWaitMillis[i];
            }
            if (s.ttfbMillis[i] > 0) sumTtfb += s.ttfbMillis[i];
            long tm = slot.transferMillisForThroughput();
            if (tm > 0) sumTransfer += tm;
            if (s.throughputKBps[i] > 0 && s.throughputKBps[i] < minThr) minThr = s.throughputKBps[i];
            // Reused keep-alive: connect start→end is near-instant. Do NOT compare against
            // slot.startMillis (shared group start) — late jars would look like handshakes.
            if (slot.connectMillis >= 0 && slot.connectStartMillis >= 0
                    && slot.connectMillis - slot.connectStartMillis <= 1) {
                reused++;
            }
        }

        s.total = n;
        s.downloadedCount = dl;
        s.cachedCount = cached;
        s.failedCount = failed;
        s.retriedCount = retried;
        s.reusedConnections = reused;
        s.handshakes = dl - reused;

        s.sumDurationMillis = sumDur;
        s.sumQueueWaitMillis = sumQwait;
        s.totalBytes = sumBytes;
        s.totalDecompressedBytes = sumDecomp;
        s.compressionRatio = sumBytes > 0 ? (double) sumDecomp / sumBytes : 1.0;
        s.meanDurationMillis = dl > 0 ? (double) sumDur / dl : -1;
        s.meanQueueWaitMillis = dl > 0 ? (double) sumQwait / dl : -1;
        s.meanTTFBMillis = dl > 0 ? (double) sumTtfb / dl : -1;
        s.maxDurationMillis = maxDur;
        s.meanThroughputKBps = sumTransfer > 0 ? (double) sumBytes / sumTransfer * 1000.0 / 1024.0 : -1;
        s.minThroughputKBps = minThr == Double.MAX_VALUE ? -1 : minThr;
        s.meanDurationPerKB = sumBytes > 0 ? (double) sumDur / (sumBytes / 1024.0) : -1;

        return s;
    }

    private GroupStats(int n) {
        this.ttfbMillis = new long[n];
        this.durationMillis = new long[n];
        this.queueWaitMillis = new long[n];
        this.transferMillis = new long[n];
        this.transferred = new long[n];
        this.decompressedBytes = new long[n];
        this.compressed = new boolean[n];
        this.throughputKBps = new double[n];
        this.kind = new MetricKind[n];
        this.retried = new boolean[n];
        this.slots = new JarSlot[n];
        this.total = n;
    }

    public String summaryLine() {
        return String.format(Locale.ROOT,
            "wall=%s sum=%s mean=%s max=%s ttfb=%s qwait=%s thr=%s minThr=%s " +
            "bytes=%s decomp=%s ratio=%s reused=%d/%d dl=%d cached=%d failed=%d retried=%d",
            DownloadMetricFormat.ms(wallDurationMillis),
            DownloadMetricFormat.ms(sumDurationMillis),
            DownloadMetricFormat.meanMs(meanDurationMillis),
            DownloadMetricFormat.meanMs(maxDurationMillis),
            DownloadMetricFormat.meanMs(meanTTFBMillis),
            DownloadMetricFormat.ms(sumQueueWaitMillis),
            DownloadMetricFormat.throughput(meanThroughputKBps),
            DownloadMetricFormat.throughput(minThroughputKBps),
            DownloadMetricFormat.grouped(totalBytes),
            DownloadMetricFormat.grouped(totalDecompressedBytes),
            DownloadMetricFormat.compressionPercent(totalBytes, totalDecompressedBytes),
            reusedConnections, handshakes + reusedConnections,
            downloadedCount, cachedCount, failedCount, retriedCount);
    }

    public String[] jarLines() {
        String[] lines = new String[slots.length];
        for (int i = 0; i < lines.length; i++) {
            String k = kind[i] == null ? "?" : kind[i].name();
            String comp = compressed[i] ? "gz" : "-";
            lines[i] = String.format("  [%d] %s kind=%s ttfb=%s dur=%s qwait=%s thr=%s bytes=%s decomp=%s ratio=%s comp=%s retried=%s",
                i, slots[i].location(), k,
                DownloadMetricFormat.ms(ttfbMillis[i]),
                DownloadMetricFormat.ms(durationMillis[i]),
                DownloadMetricFormat.ms(queueWaitMillis[i]),
                DownloadMetricFormat.throughput(throughputKBps[i]),
                transferred[i] >= 0 ? DownloadMetricFormat.grouped(transferred[i]) : "-",
                decompressedBytes[i] >= 0 ? DownloadMetricFormat.grouped(decompressedBytes[i]) : "-",
                DownloadMetricFormat.compressionPercent(transferred[i], decompressedBytes[i]),
                comp, retried[i]);
        }
        return lines;
    }
}
