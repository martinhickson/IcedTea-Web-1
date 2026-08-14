package net.sourceforge.jnlp.cache.download;

import java.util.Locale;

/** Shared formatting for download-stat log lines (thousands commas, 1-d.p. percents). */
final class DownloadMetricFormat {

    private DownloadMetricFormat() {
    }

    static String grouped(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    static String ms(long millis) {
        return millis >= 0 ? grouped(millis) + "ms" : "-";
    }

    static String meanMs(double millis) {
        return String.format(Locale.US, "%,.1fms", millis);
    }

    static String throughput(double kbps) {
        return kbps >= 0 ? String.format(Locale.US, "%,.1fKB/s", kbps) : "-";
    }

    /**
     * Wire size as a percentage of decompressed size (1 d.p.).
     * {@code ratio=2.00} (decomp/bytes) becomes {@code 50.0%}.
     */
    static String compressionPercent(long transferred, long decompressed) {
        if (decompressed <= 0) {
            return "-";
        }
        return String.format(Locale.US, "%.1f%%", 100.0 * transferred / decompressed);
    }
}
