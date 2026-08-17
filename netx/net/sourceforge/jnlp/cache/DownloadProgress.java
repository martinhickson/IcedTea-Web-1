package net.sourceforge.jnlp.cache;

import net.sourceforge.jnlp.cache.download.PackUnpackAdmission;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.OutputController;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Determinate download progress: overall bytes vs HEAD-known total, mean
 * throughput since start, 10-second instantaneous throughput, and ETA from
 * the instant rate. Pack200 unpack uses a second meter (output bytes vs
 * a learned expansion estimate). Per-lane slot meters are updated only
 * while {@link #isActive()} so the write path stays a boolean check when
 * the window is off.
 */
public final class DownloadProgress {

    static final long INSTANT_WINDOW_MS = 10_000L;
    static final int SAMPLE_CAP = 16;
    /** Hide the unpack bar for tiny jars that finish in a blink. */
    static final long UNPACK_UI_MIN_BYTES = 1L << 20;
    static final double DEFAULT_UNPACK_RATIO = 4.0;

    private static final ThreadLocal<Integer> LANE = new ThreadLocal<Integer>();
    private static volatile boolean active;
    private static volatile DownloadProgress instance;
    /** Old CacheUtil calls markComplete/end after wait; only finishLaunch may close. */
    private static volatile boolean closeAllowed;

    final Slot[] slots;
    /** GET body only. Never Pack200 output. */
    final AtomicLong bytes = new AtomicLong();
    /** Open GET drains. Unpacking is forbidden while this is &gt; 0. */
    final AtomicInteger wireOpen = new AtomicInteger();
    volatile boolean wireStarted;
    volatile long startMillis;
    /** Sum of HTTP Content-Lengths. Never unpacked jar lengths. */
    volatile long knownTotal;
    /** Local file bytes when settling from cache (not wire). */
    final AtomicLong cacheBytes = new AtomicLong();
    volatile long cacheKnown;
    volatile boolean cacheLoad;
    volatile String title = "";
    volatile ResourceTracker tracker;
    volatile URL[] resources;
    /** True only just before main() — wire complete is not launch-complete. */
    volatile boolean complete;
    /** Unpack started after the wire bar hit 99% (simple UI switches label). */
    volatile boolean deferredUnpack;

    private final ConcurrentHashMap<Object, UnpackJob> unpacks = new ConcurrentHashMap<Object, UnpackJob>();
    private final AtomicLong ratioWire = new AtomicLong();
    private final AtomicLong ratioOut = new AtomicLong();

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

    /** Jar names, rates, details, and a separate unpack bar. Default off. */
    public static boolean isAdvanced() {
        try {
            String v = JNLPRuntime.getConfiguration()
                    .getProperty(DeploymentConfiguration.KEY_HTTP_DOWNLOAD_PROGRESS_ADVANCED);
            return v != null && Boolean.parseBoolean(v.trim());
        } catch (Exception e) {
            return false;
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
        if (active && instance != null) {
            DownloadProgress p = instance;
            if (title != null && !title.isEmpty()) {
                p.title = title;
            }
            if (knownTotal > p.knownTotal) {
                p.knownTotal = knownTotal;
            }
            p.tracker = tracker;
            p.resources = resources;
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                    "Download progress begin reuse known=" + p.knownTotal
                            + " incoming=" + knownTotal
                            + " resources=" + (resources == null ? 0 : resources.length));
            return;
        }
        DownloadProgress next = new DownloadProgress(slotCount);
        next.title = title != null ? title : "";
        next.knownTotal = knownTotal;
        next.tracker = tracker;
        next.resources = resources;
        instance = next;
        active = true;
        closeAllowed = false;
        OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                "Download progress begin known=" + knownTotal
                        + " resources=" + (resources == null ? 0 : resources.length));
        DownloadProgressWindow.open(next);
    }

    /** Denominator for the Loading bar: sum of local cache file lengths. */
    public static void setCacheKnown(long n) {
        DownloadProgress p = instance;
        if (!active || p == null || n <= 0L) {
            return;
        }
        p.cacheLoad = true;
        if (n > p.cacheKnown) {
            p.cacheKnown = n;
        }
    }

    /** Numerator for the Loading bar. Local file length only. */
    public static void noteCacheHit(long localBytes) {
        DownloadProgress p = instance;
        if (!active || p == null || localBytes <= 0L) {
            return;
        }
        p.cacheLoad = true;
        p.cacheBytes.addAndGet(localBytes);
    }

    public static void markComplete() {
        if (!closeAllowed) {
            return;
        }
        DownloadProgress p = instance;
        if (p != null) {
            p.complete = true;
        }
    }

    /** Close the window a few instructions before {@code main}. */
    public static void finishLaunch() {
        closeAllowed = true;
        DownloadProgress p = instance;
        if (p != null) {
            p.complete = true;
        }
        end();
    }

    public static void end() {
        if (!closeAllowed) {
            return;
        }
        active = false;
        instance = null;
        LANE.remove();
        DownloadProgressWindow.close();
        closeAllowed = false;
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

    /** One GET body started. Pair with {@link #noteWireEnd()}. */
    public static void noteWireStart() {
        DownloadProgress p = instance;
        if (!active || p == null) {
            return;
        }
        p.wireStarted = true;
        p.wireOpen.incrementAndGet();
    }

    /** That GET body finished (Pack200 may still run). */
    public static void noteWireEnd() {
        DownloadProgress p = instance;
        if (!active || p == null) {
            return;
        }
        p.wireOpen.decrementAndGet();
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

    /**
     * Count Pack200 output bytes. Returns {@code out} unchanged when the
     * window is off so the unpack path pays only a boolean check.
     */
    public static OutputStream countingOutput(OutputStream out) {
        if (!active || instance == null || out == null) {
            return out;
        }
        return new FilterOutputStream(out) {
            @Override
            public void write(int b) throws IOException {
                out.write(b);
                addUnpackBytes(1L);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
                if (len > 0) {
                    addUnpackBytes(len);
                }
            }
        };
    }

    public static void beginUnpack(String name, long wireBytes) {
        DownloadProgress p = instance;
        if (!active || p == null) {
            return;
        }
        p.startUnpack(unpackKey(), name, wireBytes);
    }

    public static void addUnpackBytes(long n) {
        DownloadProgress p = instance;
        if (!active || p == null || n <= 0) {
            return;
        }
        p.addUnpack(unpackKey(), n);
    }

    public static void endUnpack(long actualOut) {
        DownloadProgress p = instance;
        if (!active || p == null) {
            return;
        }
        p.finishUnpack(unpackKey(), actualOut);
    }

    private static Object unpackKey() {
        Integer lane = LANE.get();
        return lane != null ? lane : Thread.currentThread();
    }

    void startUnpack(Object key, String name, long wireBytes) {
        if (wireDone()) {
            deferredUnpack = true;
        }
        long est = estimateUnpackBytes(wireBytes);
        unpacks.put(key, new UnpackJob(name, wireBytes, est));
        if (key instanceof Integer) {
            int lane = ((Integer) key).intValue();
            if (lane >= 0 && lane < slots.length) {
                slots[lane].enterUnpack(name, est);
            }
        }
    }

    void addUnpack(Object key, long n) {
        UnpackJob job = unpacks.get(key);
        if (job == null) {
            return;
        }
        job.bytes.addAndGet(n);
        if (key instanceof Integer) {
            int lane = ((Integer) key).intValue();
            if (lane >= 0 && lane < slots.length) {
                slots[lane].add(n);
            }
        }
    }

    void finishUnpack(Object key, long actualOut) {
        UnpackJob job = unpacks.remove(key);
        if (job == null) {
            return;
        }
        long out = actualOut > 0 ? actualOut : job.bytes.get();
        if (job.wire > 0 && out > 0) {
            ratioWire.addAndGet(job.wire);
            ratioOut.addAndGet(out);
        }
    }

    long estimateUnpackBytes(long wireBytes) {
        long wire = Math.max(0L, wireBytes);
        if (wire <= 0L) {
            return UNPACK_UI_MIN_BYTES;
        }
        long sampledW = ratioWire.get();
        long sampledO = ratioOut.get();
        double ratio = DEFAULT_UNPACK_RATIO;
        if (sampledW > UNPACK_UI_MIN_BYTES && sampledO > 0L) {
            ratio = sampledO / (double) sampledW;
        }
        if (ratio < 1.0) {
            ratio = 1.0;
        } else if (ratio > 8.0) {
            ratio = 8.0;
        }
        return Math.max(wire, (long) (wire * ratio));
    }

    /**
     * Wire is done only when no GET is open and every resource has
     * finished its HTTP body (or already settled). Queued jars with a
     * known Content-Length and 0 wire bytes are still downloading.
     */
    boolean wireDone() {
        if (wireOpen.get() > 0) {
            return false;
        }
        if (tracker != null && resources != null) {
            for (int i = 0; i < resources.length; i++) {
                try {
                    long ws = tracker.getWireSize(resources[i]);
                    long wt = tracker.getWireTransferred(resources[i]);
                    if (ws > 0L) {
                        if (wt < ws) {
                            return false;
                        }
                        continue;
                    }
                    if (!tracker.checkResource(resources[i])) {
                        return false;
                    }
                } catch (RuntimeException ignored) {
                    return false;
                }
            }
            return true;
        }
        return knownTotal > 0L && bytes.get() >= knownTotal;
    }

    public static void refreshKnownTotal(ResourceTracker tracker, URL[] resources) {
        DownloadProgress p = instance;
        if (!active || p == null || tracker == null || resources == null) {
            return;
        }
        long wireTotal = 0L;
        long wireRead = 0L;
        for (int i = 0; i < resources.length; i++) {
            long s = tracker.getWireSize(resources[i]);
            if (s <= 0L) {
                continue;
            }
            wireTotal += s;
            long r = tracker.getWireTransferred(resources[i]);
            if (r > 0L) {
                wireRead += Math.min(r, s);
            }
        }
        if (wireTotal > p.knownTotal) {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                    "Download progress refresh known " + p.knownTotal + " -> " + wireTotal
                            + " wireRead=" + wireRead);
            p.knownTotal = wireTotal;
        }
        // Never pull unpacked jar lengths into the wire counters.
        long have = p.bytes.get();
        if (wireRead > have) {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                    "Download progress refresh bytes " + have + " -> " + wireRead
                            + " known=" + p.knownTotal);
            p.bytes.set(wireRead);
        }
    }

    Snapshot snapshot() {
        if (tracker != null && resources != null) {
            refreshKnownTotal(tracker, resources);
        }
        long now = System.currentTimeMillis();
        boolean loading = cacheLoad && !wireStarted;
        long b = loading ? cacheBytes.get() : bytes.get();
        long known = loading && cacheKnown > 0L ? cacheKnown : knownTotal;
        recordSample(now, b);
        long elapsed = Math.max(1L, now - startMillis);
        double meanBps = (b * 1000.0) / elapsed;
        long[] instant = instantWindow(now, b);
        double nowBps = instant[1] > 0 ? (instant[0] * 1000.0) / instant[1] : 0.0;
        long remain = known > b ? known - b : 0L;
        long etaMs = nowBps > 1.0 ? (long) (remain / nowBps * 1000.0) : -1L;
        int wirePct = known > 0 ? (int) Math.min(100L, (b * 100L) / known) : 0;
        // Wire / cache bytes can finish before LaunchPrep. Never paint 100%
        // until wait() returns or users think it hung.
        int pct = complete ? 100 : Math.min(99, wirePct);
        UnpackSnap unpack = unpackSnapshot();
        // Pack200 on a download worker is still Downloading. Unpacking is only
        // after every GET body is finished — not when a percent hits 99.
        boolean unpacking = !complete && !loading && wireDone()
                && (deferredUnpack || unpack.active || unpack.queued > 0);
        String finishing = "";
        if (isAdvanced()) {
            if (unpack.active) {
                finishing = "unpacking " + unpack.name;
            } else if (!complete && wirePct >= 99) {
                finishing = finishingNames();
            }
        }
        SlotSnap[] snaps = new SlotSnap[slots.length];
        for (int i = 0; i < slots.length; i++) {
            snaps[i] = slots[i].snapshot(now);
        }
        return new Snapshot(title, b, known, pct, wirePct, wireOpen.get(), wireDone(),
                meanBps, nowBps, etaMs, finishing, unpacking, loading, unpack, snaps);
    }

    UnpackSnap unpackSnapshot() {
        long b = 0L;
        long total = 0L;
        StringBuilder names = new StringBuilder();
        int n = 0;
        for (UnpackJob job : unpacks.values()) {
            n++;
            long written = job.bytes.get();
            b += written;
            long est = job.estimate;
            if (written >= est) {
                est = written + Math.max(1L, written / 20L);
            }
            total += est;
            if (names.length() > 0) {
                names.append(", ");
            }
            if (names.length() < 80) {
                names.append(job.name);
            }
        }
        int queued = 0;
        try {
            queued = PackUnpackAdmission.getInstance().waitingUnpackers();
        } catch (Throwable ignored) {
            // tests / early init
        }
        int pct = 0;
        if (n > 0 && total > 0L) {
            pct = (int) Math.min(99L, (b * 100L) / total);
        }
        return new UnpackSnap(n > 0, names.toString(), b, total, pct, queued);
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

        void enterUnpack(String name, long estimate) {
            if (name != null && !name.isEmpty()) {
                this.name = name;
            }
            this.size = estimate;
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

    static final class UnpackJob {
        final String name;
        final long wire;
        final long estimate;
        final AtomicLong bytes = new AtomicLong();

        UnpackJob(String name, long wire, long estimate) {
            this.name = name != null ? name : "";
            this.wire = wire;
            this.estimate = Math.max(1L, estimate);
        }
    }

    static final class UnpackSnap {
        final boolean active;
        final String name;
        final long bytes;
        final long total;
        final int percent;
        final int queued;

        UnpackSnap(boolean active, String name, long bytes, long total, int percent, int queued) {
            this.active = active;
            this.name = name != null ? name : "";
            this.bytes = bytes;
            this.total = total;
            this.percent = percent;
            this.queued = queued;
        }

        boolean showBar(int downloadPercent, boolean complete) {
            if (complete) {
                return false;
            }
            if (active && total >= UNPACK_UI_MIN_BYTES) {
                return true;
            }
            return queued > 0 && downloadPercent >= 99;
        }
    }

    static final class Snapshot {
        final String title;
        final long bytes;
        final long knownTotal;
        final int percent;
        final int wirePct;
        final int wireOpen;
        final boolean wireDone;
        final double meanBps;
        final double nowBps;
        final long etaMs;
        final String finishing;
        final boolean unpacking;
        final boolean loading;
        final UnpackSnap unpack;
        final SlotSnap[] slots;

        Snapshot(String title, long bytes, long knownTotal, int percent, int wirePct,
                int wireOpen, boolean wireDone, double meanBps, double nowBps, long etaMs,
                String finishing, boolean unpacking, boolean loading, UnpackSnap unpack,
                SlotSnap[] slots) {
            this.title = title;
            this.bytes = bytes;
            this.knownTotal = knownTotal;
            this.percent = percent;
            this.wirePct = wirePct;
            this.wireOpen = wireOpen;
            this.wireDone = wireDone;
            this.meanBps = meanBps;
            this.nowBps = nowBps;
            this.etaMs = etaMs;
            this.finishing = finishing;
            this.unpacking = unpacking;
            this.loading = loading;
            this.unpack = unpack != null ? unpack : new UnpackSnap(false, "", 0L, 0L, 0, 0);
            this.slots = slots;
        }

        /** Raw longs used by the bar. Keep units as bytes — do not format. */
        String mathLine() {
            long remain = knownTotal > bytes ? knownTotal - bytes : 0L;
            return percent + "% b=" + bytes + " known=" + knownTotal
                    + " remain=" + remain + " wirePct=" + wirePct
                    + " open=" + wireOpen + " wireDone=" + wireDone
                    + " loading=" + loading
                    + " unpacking=" + unpacking
                    + " unpackActive=" + unpack.active
                    + " queued=" + unpack.queued;
        }
    }
}
