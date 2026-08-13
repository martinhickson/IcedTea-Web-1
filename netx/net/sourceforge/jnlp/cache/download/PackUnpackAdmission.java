package net.sourceforge.jnlp.cache.download;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Lock-free admission control for concurrent pack200 unpacks.
 * <p>
 * Limits total <em>estimated</em> in-flight unpack size so many large pack.gz
 * jars do not expand at once and OOM the launcher. While work remains, at least
 * one unpack is always admitted (even if a single jar exceeds the budget) so the
 * pipeline never stalls with zero unpackers.
 * <p>
 * Reserve sizing is calibrated from a live {@code -Xmx1800m} OOM heap dump where
 * two concurrent class-heavy Pack200 unpacks retained ~725 MiB and ~579 MiB.
 * Wire→heap ratios for those packs were ~30–34×; {@link #WIRE_TO_HEAP_MULTIPLIER}
 * is 40× so two such unpacks cannot both clear the budget.
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
    /** Cap concurrent estimated working set (~one large class-heavy unpack). */
    static final long MAX_BUDGET_BYTES = 512L << 20; // 512 MiB
    /**
     * Measured peak retained / pack.gz wire on OOM dump ≈ 30–34×.
     * Use 40× so those reserves exceed {@link #MAX_BUDGET_BYTES} and serialize.
     */
    static final int WIRE_TO_HEAP_MULTIPLIER = 40;
    /** When wire/size unknown, reserve enough that only one unknown unpack fits the budget. */
    static final long DEFAULT_RESERVE_BYTES = 256L << 20; // 256 MiB

    private final AtomicLong inFlightBytes = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();

    /** Override for tests; {@code null} → heap-derived budget. */
    private volatile Long budgetOverrideBytes;

    public static PackUnpackAdmission getInstance() {
        return INSTANCE;
    }

    PackUnpackAdmission() {
    }

    void setBudgetOverrideBytes(Long bytes) {
        this.budgetOverrideBytes = bytes;
    }

    long budgetBytes() {
        Long override = budgetOverrideBytes;
        if (override != null) {
            return Math.max(MIN_RESERVE_BYTES, override);
        }
        long maxHeap = Runtime.getRuntime().maxMemory();
        // Keep concurrent unpack working set under ~1/4 heap.
        long derived = maxHeap / 4;
        if (derived < MIN_BUDGET_BYTES) {
            return MIN_BUDGET_BYTES;
        }
        return Math.min(MAX_BUDGET_BYTES, derived);
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
     * Uses {@link #WIRE_TO_HEAP_MULTIPLIER} on the best available packed-size signal.
     */
    public static long estimateReserveBytes(long packedWireBytes, long sizeHintBytes) {
        long packed = Math.max(0L, packedWireBytes);
        long hint = Math.max(0L, sizeHintBytes);
        long basis = Math.max(packed, hint);
        if (basis <= 0L) {
            return DEFAULT_RESERVE_BYTES;
        }
        long scaled = basis * (long) WIRE_TO_HEAP_MULTIPLIER;
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
                    // Empty pipeline: always admit exactly one unpacker (CAS), even if
                    // reserve alone exceeds budget. Do NOT use a plain a==0 check +
                    // separate byte CAS — two threads can both observe a==0 and both enter.
                    if (!active.compareAndSet(0, 1)) {
                        continue;
                    }
                    inFlightBytes.addAndGet(reserve);
                    if (spins > 0) {
                        logDebug("PackUnpackAdmission admitted first unpacker after wait spins=" + spins
                                + " reserve=" + reserve + " budget=" + budget);
                    }
                    return;
                }
                // Pipeline busy: require strict headroom ( >= so two DEFAULT_RESERVE
                // halves cannot both fit exactly on MAX_BUDGET ).
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

    private static void logDebug(String msg) {
        try {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, msg);
        } catch (Throwable ignored) {
            // early init / tests
        }
    }
}
