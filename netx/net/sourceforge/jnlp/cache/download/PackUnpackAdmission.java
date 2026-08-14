package net.sourceforge.jnlp.cache.download;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Lock-free admission control for concurrent pack200 unpacks.
 * <p>
 * Limits total <em>estimated</em> in-flight unpack size so many large pack.gz
 * jars do not expand at once and OOM the launcher. While work remains, at least
 * one unpack is always admitted (even if a single jar exceeds the budget) so the
 * pipeline never stalls with zero unpackers.
 * <p>
 * Class-heavy packs use {@link #WIRE_TO_HEAP_MULTIPLIER}×. Packs at or above
 * {@link #LARGE_WIRE_MIB} (native-heavy, ~1× expand) use
 * {@link #LARGE_WIRE_MULTIPLIER}× so an ~81 MiB unpack shares the heap with
 * the two large class packs. No absolute budget ceiling.
 */
public final class PackUnpackAdmission {

    @FunctionalInterface
    public interface UnpackAction {
        void run() throws IOException;
    }

    private static final PackUnpackAdmission INSTANCE = new PackUnpackAdmission();

    /** Minimum reservation so tiny/unknown sizes still serialize somewhat. */
    static final long MIN_RESERVE_BYTES = 1L << 20; // 1 MiB
    static final long MIN_BUDGET_BYTES = 64L << 20;  // 64 MiB
    /**
     * Measured peak retained / pack.gz wire on the OOM dump ≈ 30–34×.
     * 30× lets the two largest class packs share an 1800 MiB heap; 40× did not.
     */
    static final int WIRE_TO_HEAP_MULTIPLIER = 30;
    /** Wire at or above this uses {@link #LARGE_WIRE_MULTIPLIER} (native-heavy). */
    static final int LARGE_WIRE_MIB = 40;
    static final int LARGE_WIRE_MULTIPLIER = 1;
    /** Fallback unknown-size reserve when heap math is unavailable. */
    static final long DEFAULT_RESERVE_BYTES = 256L << 20; // 256 MiB
    /** Same strings as {@code DeploymentConfiguration.KEY_HTTP_PACK200_ADMISSION_*}. */
    static final String KEY_WIRE_MULTIPLIER = "deployment.http.pack200.admission.wireMultiplier";
    static final String KEY_HEAP_PERCENT = "deployment.http.pack200.admission.heapPercent";
    static final String KEY_DEFAULT_RESERVE_MIB = "deployment.http.pack200.admission.defaultReserveMiB";
    static final String KEY_LARGE_WIRE_MIB = "deployment.http.pack200.admission.largeWireMiB";
    static final String KEY_LARGE_WIRE_MULTIPLIER = "deployment.http.pack200.admission.largeWireMultiplier";

    private final AtomicLong inFlightBytes = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicBoolean loggedSettings = new AtomicBoolean();

    /** Override for tests; {@code null} → heap-derived budget. */
    private volatile Long budgetOverrideBytes;
    private volatile Integer multiplierOverride;
    private volatile Long defaultReserveOverrideBytes;

    public static PackUnpackAdmission getInstance() {
        return INSTANCE;
    }

    PackUnpackAdmission() {
    }

    void setBudgetOverrideBytes(Long bytes) {
        this.budgetOverrideBytes = bytes;
    }

    void setWireMultiplierOverride(Integer multiplier) {
        this.multiplierOverride = multiplier;
    }

    void setDefaultReserveOverrideBytes(Long bytes) {
        this.defaultReserveOverrideBytes = bytes;
    }

    int wireMultiplier() {
        Integer override = multiplierOverride;
        if (override != null) {
            return Math.max(1, override.intValue());
        }
        return clamp(readInt(KEY_WIRE_MULTIPLIER, WIRE_TO_HEAP_MULTIPLIER), 1, 200);
    }

    int largeWireMiB() {
        return clamp(readInt(KEY_LARGE_WIRE_MIB, LARGE_WIRE_MIB), 1, 65536);
    }

    int largeWireMultiplier() {
        return clamp(readInt(KEY_LARGE_WIRE_MULTIPLIER, LARGE_WIRE_MULTIPLIER), 1, 200);
    }

    int multiplierForWire(long basisBytes) {
        long threshold = (long) largeWireMiB() << 20;
        if (basisBytes >= threshold) {
            return largeWireMultiplier();
        }
        return wireMultiplier();
    }

    long budgetBytes() {
        Long override = budgetOverrideBytes;
        if (override != null) {
            return Math.max(MIN_RESERVE_BYTES, override.longValue());
        }
        long maxHeap = Runtime.getRuntime().maxMemory();
        int percent = clamp(readInt(KEY_HEAP_PERCENT, 100), 1, 100);
        long derived = maxHeap * (long) percent / 100L;
        if (derived < MIN_BUDGET_BYTES) {
            return MIN_BUDGET_BYTES;
        }
        return derived;
    }

    long defaultReserveBytes() {
        Long override = defaultReserveOverrideBytes;
        if (override != null) {
            return Math.max(MIN_RESERVE_BYTES, override.longValue());
        }
        int mib = readInt(KEY_DEFAULT_RESERVE_MIB, 0);
        if (mib > 0) {
            return Math.max(MIN_RESERVE_BYTES, (long) mib << 20);
        }
        // Two unknowns fit, three do not: reserve = budget/3 + 1.
        return Math.max(MIN_RESERVE_BYTES, budgetBytes() / 3L + 1L);
    }

    public int activeUnpackers() {
        return active.get();
    }

    public int waitingUnpackers() {
        return waiting.get();
    }

    public long inFlightBytes() {
        return inFlightBytes.get();
    }

    /**
     * Pack200 heap reserve from wire size and/or a size hint (jar or Content-Length).
     * Uses {@link #wireMultiplier()} on the best available packed-size signal.
     */
    public static long estimateReserveBytes(long packedWireBytes, long sizeHintBytes) {
        PackUnpackAdmission admission = getInstance();
        admission.logSettingsOnce();
        long packed = Math.max(0L, packedWireBytes);
        long hint = Math.max(0L, sizeHintBytes);
        long basis = Math.max(packed, hint);
        if (basis <= 0L) {
            return admission.defaultReserveBytes();
        }
        long scaled = basis * (long) admission.multiplierForWire(basis);
        if (scaled < basis) {
            // overflow → treat as exclusive / oversized
            return Long.MAX_VALUE / 4;
        }
        return Math.max(MIN_RESERVE_BYTES, scaled);
    }

    /**
     * Run {@code action} after admitting {@code estimatedBytes} against the budget.
     * Always admits when no unpack is active (keep ≥1 unpacking while work exists).
     */
    public void runUnpack(long estimatedBytes, UnpackAction action) throws IOException {
        long reserve = Math.max(estimatedBytes, MIN_RESERVE_BYTES);
        acquire(reserve);
        try {
            action.run();
        } finally {
            release(reserve);
        }
    }

    private void acquire(long reserve) {
        waiting.incrementAndGet();
        try {
            int spins = 0;
            for (;;) {
                int a = active.get();
                long inflight = inFlightBytes.get();
                long budget = budgetBytes();
                if (a == 0) {
                    // Empty pipeline: always admit exactly one unpacker, even if reserve
                    // alone exceeds budget. Claim inFlight BEFORE active so a busy-path
                    // thread cannot observe active>=1 with inFlight==0 and also admit
                    // (that race stacked two DEFAULT_RESERVE under MAX_BUDGET).
                    if (!inFlightBytes.compareAndSet(0L, reserve)) {
                        continue;
                    }
                    if (!active.compareAndSet(0, 1)) {
                        inFlightBytes.addAndGet(-reserve);
                        continue;
                    }
                    if (spins > 0) {
                        logDebug("PackUnpackAdmission admitted first unpacker after wait spins=" + spins
                                + " reserve=" + reserve + " budget=" + budget);
                    }
                    return;
                }
                // Pipeline busy: require strict headroom (>= so two exact halves
                // of the budget cannot both enter).
                if (inflight + reserve >= budget) {
                    spins++;
                    LockSupport.parkNanos(5_000_000L); // 5ms
                    continue;
                }
                if (!inFlightBytes.compareAndSet(inflight, inflight + reserve)) {
                    continue;
                }
                active.incrementAndGet();
                if (spins > 0) {
                    logDebug("PackUnpackAdmission admitted after wait spins=" + spins
                            + " reserve=" + reserve + " inFlight=" + (inflight + reserve)
                            + " active=" + active.get() + " budget=" + budget);
                }
                return;
            }
        } finally {
            waiting.decrementAndGet();
        }
    }

    private void release(long reserve) {
        long left = inFlightBytes.addAndGet(-reserve);
        if (left < 0) {
            // Defensive: never leave a poisoned negative budget if release is mismatched.
            inFlightBytes.compareAndSet(left, 0L);
        }
        int a = active.decrementAndGet();
        if (a < 0) {
            active.compareAndSet(a, 0);
        }
    }

    private void logSettingsOnce() {
        if (!loggedSettings.compareAndSet(false, true)) {
            return;
        }
        logDebug("PackUnpackAdmission budget=" + budgetBytes()
                + " wireMultiplier=" + wireMultiplier()
                + " largeWire=" + largeWireMiB() + "MiB@" + largeWireMultiplier() + "x"
                + " defaultReserve=" + defaultReserveBytes()
                + " maxMemory=" + Runtime.getRuntime().maxMemory());
    }

    private static int readInt(String key, int fallback) {
        try {
            String v = JNLPRuntime.getConfiguration().getProperty(key);
            if (v == null || v.trim().isEmpty()) {
                return fallback;
            }
            return Integer.parseInt(v.trim());
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static void logDebug(String msg) {
        try {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, msg);
        } catch (Throwable ignored) {
            // early init / tests
        }
    }
}
