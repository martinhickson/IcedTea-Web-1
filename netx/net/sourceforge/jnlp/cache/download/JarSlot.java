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
        this.connectStartMillis = connectStart;
        this.connectMillis = connectEnd;
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
        if (firstByteMillis < 0 || startMillis < 0) return -1;
        return firstByteMillis - startMillis;
    }

    public long durationMillis() {
        if (endMillis < 0 || startMillis < 0) return -1;
        return endMillis - startMillis;
    }

    public long transferMillis() {
        if (lastByteMillis < 0 || firstByteMillis < 0) return -1;
        return lastByteMillis - firstByteMillis;
    }

    public double throughputKBps() {
        long tm = transferMillis();
        if (tm <= 0) return -1;
        return transferred.get() / (double) tm * 1000.0 / 1024.0;
    }

    /** One-line per-jar download stats for logging at settle time. */
    public String settleStatsLine() {
        String k = kind == null ? "?" : kind.name();
        long ttfb = ttfbMillis();
        long dur = durationMillis();
        double thr = throughputKBps();
        return String.format(java.util.Locale.ROOT,
                "Download complete: %s kind=%s ttfb=%s dur=%s thr=%s bytes=%d decomp=%s retried=%s",
                location,
                k,
                ttfb >= 0 ? ttfb + "ms" : "-",
                dur >= 0 ? dur + "ms" : "-",
                thr >= 0 ? String.format(java.util.Locale.ROOT, "%.1fKB/s", thr) : "-",
                transferred.get(),
                decompressedBytes >= 0 ? Long.toString(decompressedBytes) : "-",
                retried.get());
    }
}
