package net.sourceforge.jnlp.cache.download;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackUnpackFunnelTest {

    private final PackUnpackFunnel funnel = PackUnpackFunnel.getInstance();

    @AfterEach
    void resetBudget() {
        funnel.setBudgetOverrideBytes(null);
    }

    @Test
    void alwaysAdmitsAtLeastOneUnpackEvenWhenReserveExceedsBudget() throws Exception {
        funnel.setBudgetOverrideBytes(1L << 20); // 1 MiB budget
        AtomicBoolean ran = new AtomicBoolean();
        funnel.runUnpack(64L << 20, () -> ran.set(true)); // 64 MiB reserve
        assertTrue(ran.get());
        assertEquals(0, funnel.activeUnpackers());
        assertEquals(0, funnel.inFlightBytes());
    }

    @Test
    void secondUnpackWaitsUntilBudgetFreesButPipelineStaysNonEmpty() throws Exception {
        funnel.setBudgetOverrideBytes(10L << 20); // 10 MiB
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger secondStarted = new AtomicInteger();

        Thread t1 = new Thread(() -> {
            try {
                funnel.runUnpack(8L << 20, () -> {
                    bumpMax(maxActive);
                    firstInside.countDown();
                    try {
                        releaseFirst.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "unpack-1");
        Thread t2 = new Thread(() -> {
            try {
                assertTrue(firstInside.await(5, TimeUnit.SECONDS));
                funnel.runUnpack(8L << 20, () -> {
                    secondStarted.incrementAndGet();
                    bumpMax(maxActive);
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "unpack-2");

        t1.start();
        assertTrue(firstInside.await(5, TimeUnit.SECONDS));
        t2.start();
        // While first holds budget, second should be waiting (active still 1).
        Thread.sleep(50);
        assertEquals(1, funnel.activeUnpackers());
        assertEquals(0, secondStarted.get());
        releaseFirst.countDown();
        t1.join(5000);
        t2.join(5000);
        assertEquals(1, secondStarted.get());
        assertTrue(maxActive.get() >= 1);
        assertEquals(0, funnel.activeUnpackers());
    }

    private static void bumpMax(AtomicInteger maxActive) {
        int a = PackUnpackFunnel.getInstance().activeUnpackers();
        maxActive.accumulateAndGet(a, Math::max);
    }
}
