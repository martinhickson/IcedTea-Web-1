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
 */
public final class PackUnpackFunnel {

    @FunctionalInterface
    public interface UnpackAction {
        void run() throws IOException;
    }

    private static final PackUnpackFunnel INSTANCE = new PackUnpackFunnel();

    /** Minimum reservation so tiny/unknown sizes still serialize somewhat. */
    static final long MIN_RESERVE_BYTES = 1L << 20; // 1 MiB
    static final long MIN_BUDGET_BYTES = 64L << 20;  // 64 MiB
    static final long MAX_BUDGET_BYTES = 512L << 20; // 512 MiB

    private final AtomicLong inFlightBytes = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();

    /** Override for tests; {@code null} → heap-derived budget. */
    private volatile Long budgetOverrideBytes;

    public static PackUnpackFunnel getInstance() {
        return INSTANCE;
    }

    PackUnpackFunnel() {
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
                        logDebug("PackUnpackFunnel admitted first unpacker after wait spins=" + spins
                                + " reserve=" + reserve + " budget=" + budget);
                    }
                    return;
                }
                // Pipeline busy: require budget headroom, then claim bytes + slot.
                if (inflight + reserve > budget) {
                    spins++;
                    LockSupport.parkNanos(5_000_000L); // 5ms
                    continue;
                }
                if (!inFlightBytes.compareAndSet(inflight, inflight + reserve)) {
                    continue;
                }
                active.incrementAndGet();
                if (spins > 0) {
                    logDebug("PackUnpackFunnel admitted after wait spins=" + spins
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
        inFlightBytes.addAndGet(-reserve);
        active.decrementAndGet();
    }

    private static void logDebug(String msg) {
        try {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, msg);
        } catch (Throwable ignored) {
            // early init / tests
        }
    }
}
