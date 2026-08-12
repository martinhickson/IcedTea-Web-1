package net.sourceforge.jnlp.cache;

import net.sourceforge.jnlp.util.logging.OutputController;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot adaptive resize of the download {@link ThreadPoolExecutor}.
 * <p>
 * When enabled: after at least one successful jar download, the next jar download
 * start doubles the pool size (once). If any pack200-gzip download is detected and
 * the pool was doubled, it is halved back to the base size (once). No further
 * doublings or halvings after those latches fire.
 */
public final class AdaptiveBackgroundThreads {

    /** Matches {@code DeploymentConfiguration} thread-count validator upper bound. */
    static final int MAX_THREADS = 24;

    private final boolean enabled;
    private final int baseThreads;
    private final ThreadPoolExecutor pool;
    private final AtomicInteger successfulJarDownloads = new AtomicInteger();
    private final AtomicBoolean doubled = new AtomicBoolean();
    private final AtomicBoolean halved = new AtomicBoolean();
    private final AtomicBoolean packGzSeen = new AtomicBoolean();

    AdaptiveBackgroundThreads(boolean enabled, int baseThreads, ThreadPoolExecutor pool) {
        this.enabled = enabled;
        this.baseThreads = Math.max(1, Math.min(baseThreads, MAX_THREADS));
        this.pool = pool;
    }

    int baseThreads() {
        return baseThreads;
    }

    int successfulJarDownloads() {
        return successfulJarDownloads.get();
    }

    boolean hasDoubled() {
        return doubled.get();
    }

    boolean hasHalved() {
        return halved.get();
    }

    boolean hasSeenPackGz() {
        return packGzSeen.get();
    }

    /**
     * Called when a jar download is about to be enqueued/started (including
     * late re-enqueues). After ≥1 success this is itself “more jars downloading”.
     */
    void onJarDownloadStarting() {
        if (!enabled || packGzSeen.get() || halved.get()) {
            return;
        }
        if (successfulJarDownloads.get() < 1) {
            return;
        }
        tryDoubleOnce();
    }

    /**
     * Called after a jar was freshly downloaded (not a cache hit).
     * Cold-cache launches enqueue every jar before any finish, so doubling is
     * also decided here when ≥1 success and other downloads are still active/queued.
     */
    void onJarDownloadSucceeded() {
        if (!enabled) {
            return;
        }
        successfulJarDownloads.incrementAndGet();
        if (packGzSeen.get() || halved.get()) {
            return;
        }
        // Completing thread still counts as active — need another worker or queued work.
        if (pool.getQueue().isEmpty() && pool.getActiveCount() <= 1) {
            return;
        }
        tryDoubleOnce();
    }

    private void tryDoubleOnce() {
        if (!doubled.compareAndSet(false, true)) {
            return;
        }
        int target = Math.min(baseThreads * 2, MAX_THREADS);
        resize(target);
        logAll("Adaptive download threads: doubled " + baseThreads + " -> " + target);
    }

    /**
     * Called when a pack200-gzip download is selected. If the pool was doubled,
     * halves it back to the base size once; otherwise only blocks a future double.
     */
    void onPackGzDetected() {
        if (!enabled) {
            return;
        }
        packGzSeen.set(true);
        if (!doubled.get()) {
            return;
        }
        if (!halved.compareAndSet(false, true)) {
            return;
        }
        resize(baseThreads);
        logAll("Adaptive download threads: halved back to " + baseThreads + " (pack200-gzip detected)");
    }

    private static void logAll(String message) {
        try {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, message);
        } catch (Throwable ignored) {
            // tests / early init may not have a logger
        }
    }

    private void resize(int n) {
        int target = Math.max(1, Math.min(n, MAX_THREADS));
        int current = pool.getCorePoolSize();
        if (target == current && pool.getMaximumPoolSize() == target) {
            return;
        }
        if (target > current) {
            pool.setMaximumPoolSize(target);
            pool.setCorePoolSize(target);
        } else {
            pool.setCorePoolSize(target);
            pool.setMaximumPoolSize(target);
        }
    }
}
