package net.sourceforge.jnlp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class AdaptiveBackgroundThreadsTest {

    private ThreadPoolExecutor pool;

    @AfterEach
    public void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private AdaptiveBackgroundThreads adaptive(boolean enabled, int base) {
        pool = new ThreadPoolExecutor(base, base, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());
        return new AdaptiveBackgroundThreads(enabled, base, pool);
    }

    @Test
    public void doublesOnceAfterFirstSuccessWhileMoreWorkQueued() {
        AdaptiveBackgroundThreads a = adaptive(true, 6);
        // Simulate cold-cache enqueue: work still queued when first jar finishes.
        pool.getQueue().offer(() -> { });
        a.onJarDownloadStarting();
        assertEquals(6, pool.getCorePoolSize());
        a.onJarDownloadSucceeded();
        assertTrue(a.hasDoubled());
        assertEquals(12, pool.getCorePoolSize());
        assertEquals(12, pool.getMaximumPoolSize());
        pool.getQueue().offer(() -> { });
        a.onJarDownloadSucceeded();
        assertEquals(12, pool.getCorePoolSize(), "no second doubling");
    }

    @Test
    public void lateStartAfterSuccessAlsoDoubles() {
        AdaptiveBackgroundThreads a = adaptive(true, 6);
        a.onJarDownloadSucceeded();
        assertFalse(a.hasDoubled(), "alone success with idle pool does not double");
        a.onJarDownloadStarting();
        assertTrue(a.hasDoubled());
        assertEquals(12, pool.getCorePoolSize());
    }

    @Test
    public void packGzBeforeDoubleOnlyBlocksFutureDouble() {
        AdaptiveBackgroundThreads a = adaptive(true, 6);
        a.onPackGzDetected();
        assertTrue(a.hasSeenPackGz());
        assertFalse(a.hasDoubled());
        pool.getQueue().offer(() -> { });
        a.onJarDownloadSucceeded();
        a.onJarDownloadStarting();
        assertFalse(a.hasDoubled());
        assertEquals(6, pool.getCorePoolSize());
    }

    @Test
    public void packGzAfterDoubleHalvesOnce() {
        AdaptiveBackgroundThreads a = adaptive(true, 6);
        pool.getQueue().offer(() -> { });
        a.onJarDownloadSucceeded();
        assertEquals(12, pool.getCorePoolSize());
        a.onPackGzDetected();
        assertTrue(a.hasHalved());
        assertEquals(6, pool.getCorePoolSize());
        a.onPackGzDetected();
        a.onJarDownloadStarting();
        assertEquals(6, pool.getCorePoolSize(), "no further half or re-double");
    }

    @Test
    public void disabledIsNoOp() {
        AdaptiveBackgroundThreads a = adaptive(false, 6);
        pool.getQueue().offer(() -> { });
        a.onJarDownloadSucceeded();
        a.onJarDownloadStarting();
        a.onPackGzDetected();
        assertFalse(a.hasDoubled());
        assertFalse(a.hasHalved());
        assertEquals(6, pool.getCorePoolSize());
    }

    @Test
    public void clampsDoubleToMaxThreads() {
        AdaptiveBackgroundThreads a = adaptive(true, 20);
        pool.getQueue().offer(() -> { });
        a.onJarDownloadSucceeded();
        assertEquals(AdaptiveBackgroundThreads.MAX_THREADS, pool.getCorePoolSize());
    }
}
