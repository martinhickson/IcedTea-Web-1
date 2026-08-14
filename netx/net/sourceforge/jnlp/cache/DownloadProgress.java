package net.sourceforge.jnlp.cache;

import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;

import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Determinate download progress: overall bytes vs HEAD-known total, mean
 * throughput since start, 10-second instantaneous throughput, and ETA from
 * the instant rate. Per-lane slot meters are updated only while
 * {@link #isActive()} so the write path stays a boolean check when the
 * window is off.
 */
public final class DownloadProgress {

    static final long INSTANT_WINDOW_MS = 10_000L;
    static final int SAMPLE_CAP = 16;

    private static final ThreadLocal<Integer> LANE = new ThreadLocal<Integer>();
    private static volatile boolean active;
    private static volatile DownloadProgress instance;

    final Slot[] slots;
    final AtomicLong bytes = new AtomicLong();
    volatile long startMillis;
    volatile long knownTotal;
    volatile String title = "";
    volatile ResourceTracker tracker;
    volatile URL[] resources;
    /** True only after wait() returns — wire complete is not launch-complete. */
    volatile boolean complete;

    private final Object sampleLock = new Object();
    private final long[] sampleAt = new long[SAMPLE_CAP];
    private final long[] sampleBytes = new long[SAMPLE_CAP];
    private int sampleCount;
    private int sampleHead;

    DownloadProgress(int slotCount) {
        int n = Math.max(1, Math.min(slotCount, AdaptiveBackgroundThreads.MAX_THREADS));
        this.slots = new Slot[n];
        for (int i = 0; i < n; i++) {
            this.slots[i] = new Slot();
        }
        this.startMillis = System.currentTimeMillis();
    }

    public static boolean isEnabled() {
        if (JNLPRuntime.isHeadless()) {
            return false;
        }
        try {
            String v = JNLPRuntime.getConfiguration()
                    .getProperty(DeploymentConfiguration.KEY_HTTP_DOWNLOAD_PROGRESS);
            if (v == null || v.trim().isEmpty()) {
                return true;
            }
            return Boolean.parseBoolean(v.trim());
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static DownloadProgress current() {
        return instance;
    }

    public static void begin(String title, ResourceTracker tracker, URL[] resources,
            int slotCount, long knownTotal) {
        if (!isEnabled()) {
            return;
        }
        DownloadProgress next = new DownloadProgress(slotCount);
        next.title = title != null ? title : "";
        next.knownTotal = knownTotal;
        next.tracker = tracker;
        next.resources = resources;
        instance = next;
        active = true;
        DownloadProgressWindow.open(next);
    }

    public static void markComplete() {
        DownloadProgress p = instance;
        if (p != null) {
            p.complete = true;
        }
    }

    public static void end() {
        active = false;
        instance = null;
        LANE.remove();
        DownloadProgressWindow.close();
    }

    public static void bindLane(int index, Resource resource) {
        if (!active || instance == null || resource == null) {
            return;
        }
        LANE.set(Integer.valueOf(index));
        if (index >= 0 && index < instance.slots.length) {
            String name = SizeFirstDownloadQueue.resourceName(resource);
            instance.slots[index].bind(name, resource.getSize());
        }
    }

    public static void unbindLane(int index) {
        LANE.remove();
        if (!active || instance == null) {
            return;
        }
        if (index >= 0 && index < instance.slots.length) {
            instance.slots[index].idle();
        }
    }

    /** Called from the copy loop only when {@link #isActive()}. */
    public static void addBytes(long n) {
        DownloadProgress p = instance;
        if (!active || p == null || n <= 0) {
            return;
        }
        p.bytes.addAndGet(n);
        Integer lane = LANE.get();
        if (lane != null && lane.intValue() >= 0 && lane.intValue() < p.slots.length) {
            p.slots[lane.intValue()].add(n);
        }
    }

    public static void refreshKnownTotal(ResourceTracker tracker, URL[] resources) {
        DownloadProgress p = instance;
        if (!active || p == null || tracker == null || resources == null) {
            return;
        }
        long total = 0L;
        long read = 0L;
        for (int i = 0; i < resources.length; i++) {
            long s = tracker.getTotalSize(resources[i]);
            if (s > 0) {
                total += s;
            }
            long r = tracker.getAmountRead(resources[i]);
            if (r > 0) {
                read += r;
            }
        }
        if (total > p.knownTotal) {
            p.knownTotal = total;
        }
        if (read > p.bytes.get()) {
            p.bytes.set(read);
        }
    }

    Snapshot snapshot() {
        if (tracker != null && resources != null) {
            refreshKnownTotal(tracker, resources);
        }
        long now = System.currentTimeMillis();
        long b = bytes.get();
        recordSample(now, b);
        long elapsed = Math.max(1L, now - startMillis);
        double meanBps = (b * 1000.0) / elapsed;
        long[] instant = instantWindow(now, b);
        double nowBps = instant[1] > 0 ? (instant[0] * 1000.0) / instant[1] : 0.0;
        long remain = knownTotal > b ? knownTotal - b : 0L;
        long etaMs = nowBps > 1.0 ? (long) (remain / nowBps * 1000.0) : -1L;
        int wirePct = knownTotal > 0 ? (int) Math.min(100L, (b * 100L) / knownTotal) : 0;
        // Wire bytes can finish a minute before Pack200 unpack / integrity.
        // Never paint 100% until wait() returns or users think it hung.
        int pct = complete ? 100 : Math.min(99, wirePct);
        String finishing = "";
        if (!complete && wirePct >= 99) {
            finishing = finishingNames();
        }
        SlotSnap[] snaps = new SlotSnap[slots.length];
        for (int i = 0; i < slots.length; i++) {
            snaps[i] = slots[i].snapshot(now);
        }
        return new Snapshot(title, b, knownTotal, pct, meanBps, nowBps, etaMs, finishing, snaps);
    }

    private String finishingNames() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < slots.length; i++) {
            if (!slots[i].busy) {
                continue;
            }
            n++;
            if (sb.length() > 0) {
                sb.append(", ");
            }
            if (sb.length() < 80) {
                sb.append(slots[i].name);
            }
        }
        if (n == 0) {
            return "finishing";
        }
        return "finishing " + sb.toString();
    }

    void recordSample(long now, long b) {
        synchronized (sampleLock) {
            if (sampleCount > 0) {
                int last = (sampleHead + SAMPLE_CAP - 1) % SAMPLE_CAP;
                if (now - sampleAt[last] < 400L) {
                    sampleBytes[last] = b;
                    sampleAt[last] = now;
                    return;
                }
            }
            sampleAt[sampleHead] = now;
            sampleBytes[sampleHead] = b;
            sampleHead = (sampleHead + 1) % SAMPLE_CAP;
            if (sampleCount < SAMPLE_CAP) {
                sampleCount++;
            }
        }
    }

    /** bytes and span-ms in the last {@link #INSTANT_WINDOW_MS}. */
    long[] instantWindow(long now, long b) {
        synchronized (sampleLock) {
            if (sampleCount == 0) {
                return new long[] {0L, 0L};
            }
            long target = now - INSTANT_WINDOW_MS;
            long bestAt = sampleAt[oldestIndex()];
            long bestB = sampleBytes[oldestIndex()];
            for (int i = 0; i < sampleCount; i++) {
                int idx = (oldestIndex() + i) % SAMPLE_CAP;
                if (sampleAt[idx] <= target) {
                    bestAt = sampleAt[idx];
                    bestB = sampleBytes[idx];
                } else {
                    break;
                }
            }
            long span = now - bestAt;
            if (span <= 0) {
                return new long[] {0L, 0L};
            }
            return new long[] {Math.max(0L, b - bestB), span};
        }
    }

    private int oldestIndex() {
        return sampleCount < SAMPLE_CAP ? 0 : sampleHead;
    }

    static String formatRate(double bps) {
        if (bps <= 0) {
            return "-";
        }
        double kb = bps / 1024.0;
        if (kb >= 1024.0) {
            return String.format(Locale.US, "%.1f MB/s", kb / 1024.0);
        }
        return String.format(Locale.US, "%.1f KB/s", kb);
    }

    static String formatBytes(long n) {
        if (n < 0) {
            return "-";
        }
        if (n >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024.0));
        }
        if (n >= 1024L) {
            return String.format(Locale.US, "%.1f KB", n / 1024.0);
        }
        return n + " B";
    }

    static String formatEta(long etaMs) {
        if (etaMs < 0) {
            return "-";
        }
        long sec = (etaMs + 999L) / 1000L;
        long min = sec / 60L;
        sec = sec % 60L;
        if (min >= 60L) {
            return String.format(Locale.US, "%d:%02d:%02d", min / 60L, min % 60L, sec);
        }
        return String.format(Locale.US, "%d:%02d", min, sec);
    }

    static final class Slot {
        volatile String name = "";
        volatile long size;
        volatile boolean busy;
        volatile long startMillis;
        final AtomicLong bytes = new AtomicLong();
        private final Object sampleLock = new Object();
        private final long[] sampleAt = new long[SAMPLE_CAP];
        private final long[] sampleBytes = new long[SAMPLE_CAP];
        private int sampleCount;
        private int sampleHead;

        void bind(String name, long size) {
            this.name = name != null ? name : "";
            this.size = size;
            this.busy = true;
            this.startMillis = System.currentTimeMillis();
            this.bytes.set(0L);
            synchronized (sampleLock) {
                sampleCount = 0;
                sampleHead = 0;
            }
        }

        void idle() {
            this.busy = false;
            this.name = "";
            this.size = 0L;
            this.bytes.set(0L);
        }

        void add(long n) {
            bytes.addAndGet(n);
        }

        SlotSnap snapshot(long now) {
            long b = bytes.get();
            record(now, b);
            long elapsed = Math.max(1L, now - (startMillis > 0 ? startMillis : now));
            double mean = busy ? (b * 1000.0) / elapsed : 0.0;
            long[] inst = instant(now, b);
            double nowBps = inst[1] > 0 ? (inst[0] * 1000.0) / inst[1] : 0.0;
            long remain = size > b ? size - b : 0L;
            long eta = busy && nowBps > 1.0 ? (long) (remain / nowBps * 1000.0) : -1L;
            int pct = size > 0 ? (int) Math.min(100L, (b * 100L) / size) : 0;
            if (busy && pct >= 100) {
                pct = 99;
            }
            return new SlotSnap(name, busy, b, size, pct, mean, nowBps, eta);
        }

        private void record(long now, long b) {
            synchronized (sampleLock) {
                if (sampleCount > 0) {
                    int last = (sampleHead + SAMPLE_CAP - 1) % SAMPLE_CAP;
                    if (now - sampleAt[last] < 400L) {
                        sampleBytes[last] = b;
                        sampleAt[last] = now;
                        return;
                    }
                }
                sampleAt[sampleHead] = now;
                sampleBytes[sampleHead] = b;
                sampleHead = (sampleHead + 1) % SAMPLE_CAP;
                if (sampleCount < SAMPLE_CAP) {
                    sampleCount++;
                }
            }
        }

        private long[] instant(long now, long b) {
            synchronized (sampleLock) {
                if (sampleCount == 0) {
                    return new long[] {0L, 0L};
                }
                int oldest = sampleCount < SAMPLE_CAP ? 0 : sampleHead;
                long target = now - INSTANT_WINDOW_MS;
                long bestAt = sampleAt[oldest];
                long bestB = sampleBytes[oldest];
                for (int i = 0; i < sampleCount; i++) {
                    int idx = (oldest + i) % SAMPLE_CAP;
                    if (sampleAt[idx] <= target) {
                        bestAt = sampleAt[idx];
                        bestB = sampleBytes[idx];
                    } else {
                        break;
                    }
                }
                long span = now - bestAt;
                if (span <= 0) {
                    return new long[] {0L, 0L};
                }
                return new long[] {Math.max(0L, b - bestB), span};
            }
        }
    }

    static final class SlotSnap {
        final String name;
        final boolean busy;
        final long bytes;
        final long size;
        final int percent;
        final double meanBps;
        final double nowBps;
        final long etaMs;

        SlotSnap(String name, boolean busy, long bytes, long size, int percent,
                double meanBps, double nowBps, long etaMs) {
            this.name = name;
            this.busy = busy;
            this.bytes = bytes;
            this.size = size;
            this.percent = percent;
            this.meanBps = meanBps;
            this.nowBps = nowBps;
            this.etaMs = etaMs;
        }
    }

    static final class Snapshot {
        final String title;
        final long bytes;
        final long knownTotal;
        final int percent;
        final double meanBps;
        final double nowBps;
        final long etaMs;
        final String finishing;
        final SlotSnap[] slots;

        Snapshot(String title, long bytes, long knownTotal, int percent,
                double meanBps, double nowBps, long etaMs, String finishing, SlotSnap[] slots) {
            this.title = title;
            this.bytes = bytes;
            this.knownTotal = knownTotal;
            this.percent = percent;
            this.meanBps = meanBps;
            this.nowBps = nowBps;
            this.etaMs = etaMs;
            this.finishing = finishing;
            this.slots = slots;
        }
    }
}
