package net.sourceforge.jnlp.cache.download;

import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

public final class JarSlot {

    final int index;
    final URL location;
    final JarGroupState group;

    final AtomicInteger state = new AtomicInteger(JarState.IN_FLIGHT.ordinal());
    final AtomicBoolean retried = new AtomicBoolean(false);
    final CompletableFuture<Void> settled = new CompletableFuture<>();

    volatile long startMillis;
    /** Wall clock when HTTP connect began ({@code -1} if unknown). */
    volatile long connectStartMillis = -1;
    /** Wall clock when HTTP connect finished ({@code -1} if unknown). */
    volatile long connectMillis = -1;
    /** True when the HTTP client reused a keep-alive connection for this GET. */
    volatile boolean connectionReused;
    volatile long firstByteMillis = -1;
    volatile long lastByteMillis  = -1;
    volatile long endMillis       = -1;
    volatile MetricKind kind;

    volatile long decompressedBytes = -1;
    volatile boolean compressed;

    final AtomicLong transferred = new AtomicLong(0L);

    JarSlot(int index, URL location, JarGroupState group, long startMillis) {
        this.index = index; this.location = location; this.group = group;
        this.startMillis = startMillis;
    }

    public JarState state()                  { return JarState.fromOrdinal(state.get()); }
    public CompletableFuture<Void> settled() { return settled; }
    public long transferred()                { return transferred.get(); }
    public URL location()                    { return location; }
    public int index()                       { return index; }

    public void addTransferred(long deltaBytes) {
        transferred.addAndGet(deltaBytes);
    }

    /** Record connect completion; treat as zero-duration (reused) when start unknown. */
    public void onConnect(long now) {
        onConnect(now, now);
    }

    public void onConnect(long connectStart, long connectEnd) {
        onConnect(connectStart, connectEnd, connectEnd - connectStart <= 1);
    }

    public void onConnect(long connectStart, long connectEnd, boolean reused) {
        this.connectStartMillis = connectStart;
        this.connectMillis = connectEnd;
        this.connectionReused = reused;
    }

    public void onFirstByte(long now) {
        if (firstByteMillis == -1) firstByteMillis = now;
    }

    public void onLastByte(long now) {
        this.lastByteMillis = now;
    }

    public void onDecompressed(long bytes, boolean isCompressed) {
        this.decompressedBytes = bytes;
        this.compressed = isCompressed;
    }

    public boolean settleGood(long now, boolean fromCache) {
        this.kind = fromCache ? MetricKind.CACHED : MetricKind.DOWNLOADED;
        if (casState(JarState.IN_FLIGHT, JarState.GOOD)) {
            publishSettle(now);
            return true;
        }
        return false;
    }

    public boolean settleUnusable(long now) {
        if (retried.get()) {
            if (casState(JarState.IN_FLIGHT, JarState.SETTLED_BAD)) {
                this.kind = MetricKind.FAILED;
                publishSettle(now);
                return true;
            }
            return false;
        }
        // first unusable: park in RETRY_PENDING (NOT absorbing, NOT counted).
        // Returns false — the jar is not yet settled; a coordinator must claimRetry().
        casState(JarState.IN_FLIGHT, JarState.RETRY_PENDING);
        return false;
    }

    public boolean claimRetry() {
        if (casState(JarState.RETRY_PENDING, JarState.IN_FLIGHT)) {
            retried.set(true);
            return true;
        }
        return false;
    }

    /**
     * Force-settle SETTLED_BAD from {@link JarState#IN_FLIGHT} or
     * {@link JarState#RETRY_PENDING}. Used when retries are exhausted / fail-fast
     * (including unexpected {@code Error} after {@link #settleUnusable} parked the
     * slot) — must absorb so the jar group cannot hang on {@code done}.
     */
    public boolean settleBadFinal(long now) {
        if (casState(JarState.IN_FLIGHT, JarState.SETTLED_BAD)
                || casState(JarState.RETRY_PENDING, JarState.SETTLED_BAD)) {
            this.kind = MetricKind.FAILED;
            publishSettle(now);
            return true;
        }
        return false;
    }

    private boolean casState(JarState expect, JarState update) {
        return state.compareAndSet(expect.ordinal(), update.ordinal());
    }

    private void publishSettle(long now) {
        this.endMillis = now;
        settled.complete(null);
        group.onSettled();
    }

    public long ttfbMillis() {
        // HTTP TTFB: first body byte minus this jar's connect/request start.
        // Never group start — that made late jars look like 3-minute TTFB.
        // Never connect-end (headers already here) — that dropped the RTT and
        // made ttfb= undercut ping.
        long origin = connectStartMillis >= 0 ? connectStartMillis : connectMillis;
        if (firstByteMillis < 0 || origin < 0) {
            return -1;
        }
        return Math.max(0L, firstByteMillis - origin);
    }

    public long durationMillis() {
        // Work time only: connect/request start → settle. Never group enqueue
        // (startMillis) — that folds size-first / pool queue wait into dur.
        long origin = workStartMillis();
        if (endMillis < 0 || origin < 0) {
            return -1;
        }
        return Math.max(0L, endMillis - origin);
    }

    /**
     * Time sitting in the size-first / thread-pool queue before the GET starts.
     * Separate from {@link #durationMillis()}.
     */
    public long queueWaitMillis() {
        long workStart = workStartMillis();
        if (workStart < 0 || startMillis < 0) {
            return -1;
        }
        return Math.max(0L, workStart - startMillis);
    }

    /** Connect/GET start, else first body byte. Not group enqueue. */
    long workStartMillis() {
        if (connectStartMillis >= 0) {
            return connectStartMillis;
        }
        if (firstByteMillis >= 0) {
            return firstByteMillis;
        }
        return -1;
    }

    public long transferMillis() {
        if (lastByteMillis < 0 || firstByteMillis < 0) return -1;
        return lastByteMillis - firstByteMillis;
    }

    /**
     * Transfer time used for throughput. Unknown ({@code -1}) stays unknown.
     * If {@code lastByte - firstByte <= 0}, normalize to 1ms (LOGIC).
     */
    public long transferMillisForThroughput() {
        long tm = transferMillis();
        if (tm < 0) {
            return -1;
        }
        return tm <= 0 ? 1L : tm;
    }

    public double throughputKBps() {
        long tm = transferMillisForThroughput();
        if (tm < 0) return -1;
        return transferred.get() / (double) tm * 1000.0 / 1024.0;
    }

    /** One-line per-jar download stats for logging at settle time. */
    public String settleStatsLine() {
        String k = kind == null ? "?" : kind.name();
        return String.format(java.util.Locale.ROOT,
                "Download complete: %s kind=%s ttfb=%s dur=%s qwait=%s thr=%s bytes=%s decomp=%s ratio=%s retried=%s",
                location,
                k,
                DownloadMetricFormat.ms(ttfbMillis()),
                DownloadMetricFormat.ms(durationMillis()),
                DownloadMetricFormat.ms(queueWaitMillis()),
                DownloadMetricFormat.throughput(throughputKBps()),
                DownloadMetricFormat.grouped(transferred.get()),
                decompressedBytes >= 0 ? DownloadMetricFormat.grouped(decompressedBytes) : "-",
                DownloadMetricFormat.compressionPercent(transferred.get(), decompressedBytes),
                retried.get());
    }
}
