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

class PackUnpackAdmissionTest {

    private final PackUnpackAdmission admission = PackUnpackAdmission.getInstance();

    @AfterEach
    void resetBudget() {
        admission.setBudgetOverrideBytes(null);
        admission.setWireMultiplierOverride(null);
        admission.setDefaultReserveOverrideBytes(null);
    }

    @Test
    void alwaysAdmitsAtLeastOneUnpackEvenWhenReserveExceedsBudget() throws Exception {
        admission.setBudgetOverrideBytes(1L << 20); // 1 MiB budget
        AtomicBoolean ran = new AtomicBoolean();
        admission.runUnpack(64L << 20, () -> ran.set(true)); // 64 MiB reserve
        assertTrue(ran.get());
        assertEquals(0, admission.activeUnpackers());
        assertEquals(0, admission.inFlightBytes());
    }

    @Test
    void releasesBudgetWhenUnpackThrows() {
        admission.setBudgetOverrideBytes(32L << 20);
        try {
            admission.runUnpack(8L << 20, () -> {
                throw new IOException("boom");
            });
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("boom"));
        }
        assertEquals(0, admission.activeUnpackers());
        assertEquals(0, admission.inFlightBytes());
        assertEquals(0, admission.waitingUnpackers());
    }

    @Test
    void onlyOneFirstUnpackerWinsEmptyPipelineRace() throws Exception {
        admission.setBudgetOverrideBytes(1L << 20); // tiny — second must wait, not both enter
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        Runnable body = () -> {
            try {
                bothStarted.countDown();
                bothStarted.await(5, TimeUnit.SECONDS);
                admission.runUnpack(64L << 20, () -> {
                    entered.incrementAndGet();
                    bumpMax(maxActive);
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Thread t1 = new Thread(body, "race-1");
        Thread t2 = new Thread(body, "race-2");
        t1.start();
        t2.start();
        assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
        Thread.sleep(80);
        // Critical: empty-pipeline race must not admit both oversized reserves.
        assertEquals(1, admission.activeUnpackers());
        assertEquals(1, entered.get());
        release.countDown();
        t1.join(5000);
        t2.join(5000);
        assertEquals(2, entered.get());
        assertEquals(1, maxActive.get());
        assertEquals(0, admission.activeUnpackers());
    }

    @Test
    void secondUnpackWaitsUntilBudgetFreesButPipelineStaysNonEmpty() throws Exception {
        admission.setBudgetOverrideBytes(10L << 20); // 10 MiB
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger secondStarted = new AtomicInteger();

        Thread t1 = new Thread(() -> {
            try {
                admission.runUnpack(8L << 20, () -> {
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
                admission.runUnpack(8L << 20, () -> {
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
        assertEquals(1, admission.activeUnpackers());
        assertEquals(0, secondStarted.get());
        releaseFirst.countDown();
        t1.join(5000);
        t2.join(5000);
        assertEquals(1, secondStarted.get());
        assertTrue(maxActive.get() >= 1);
        assertEquals(0, admission.activeUnpackers());
    }

    @Test
    void inFlightBytesNeverNegativeAfterNormalRelease() throws Exception {
        admission.setBudgetOverrideBytes(32L << 20);
        admission.runUnpack(4L << 20, () -> { });
        assertEquals(0, admission.inFlightBytes());
        assertEquals(0, admission.activeUnpackers());
        assertTrue(admission.inFlightBytes() >= 0);
    }

    @Test
    void budgetOverrideBelowMinReserveStillAllowsAdmission() throws Exception {
        admission.setBudgetOverrideBytes(1L); // below MIN_RESERVE — clamp must keep pipeline usable
        assertTrue(admission.budgetBytes() >= PackUnpackAdmission.MIN_RESERVE_BYTES);
        AtomicBoolean ran = new AtomicBoolean();
        admission.runUnpack(1, () -> ran.set(true));
        assertTrue(ran.get());
        assertEquals(0, admission.activeUnpackers());
    }

    @Test
    void twoSmallUnpacksMayRunConcurrentlyUnderBudget() throws Exception {
        admission.setBudgetOverrideBytes(64L << 20);
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger maxActive = new AtomicInteger();

        Runnable body = () -> {
            try {
                admission.runUnpack(4L << 20, () -> {
                    bumpMax(maxActive);
                    bothInside.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        Thread t1 = new Thread(body, "small-1");
        Thread t2 = new Thread(body, "small-2");
        t1.start();
        t2.start();
        assertTrue(bothInside.await(5, TimeUnit.SECONDS));
        assertEquals(2, maxActive.get());
        release.countDown();
        t1.join(5000);
        t2.join(5000);
        assertEquals(0, admission.activeUnpackers());
        assertEquals(0, admission.inFlightBytes());
    }

    @Test
    void estimateReserveUsesMeasuredWireMultiplier() {
        admission.setWireMultiplierOverride(PackUnpackAdmission.WIRE_TO_HEAP_MULTIPLIER);
        long wireA = 17_193_474L;
        long reserveA = PackUnpackAdmission.estimateReserveBytes(wireA, 0L);
        assertEquals(wireA * 30L, reserveA);

        long wireB = 24_062_954L;
        long reserveB = PackUnpackAdmission.estimateReserveBytes(wireB, 0L);
        assertEquals(wireB * 30L, reserveB);
    }

    @Test
    void budgetIsHeapFractionWithNoAbsoluteCap() {
        admission.setBudgetOverrideBytes(null);
        long heap = Runtime.getRuntime().maxMemory();
        long expected = Math.max(PackUnpackAdmission.MIN_BUDGET_BYTES, heap);
        assertEquals(expected, admission.budgetBytes());
    }

    @Test
    void twoLargestClassPacksFitAndThirdWaitsOn1800mHeap() throws Exception {
        // Two large + one medium pack.gz wires (class-heavy, ~26 / ~23 / ~16 MiB).
        admission.setWireMultiplierOverride(30);
        admission.setBudgetOverrideBytes(1800L << 20);
        long packA = PackUnpackAdmission.estimateReserveBytes(27_742_491L, 0L);
        long packB = PackUnpackAdmission.estimateReserveBytes(24_062_954L, 0L);
        long packC = PackUnpackAdmission.estimateReserveBytes(17_193_474L, 0L);
        assertTrue(packA + packB < admission.budgetBytes(),
                "two largest must fit: " + (packA + packB) + " vs " + admission.budgetBytes());
        assertTrue(packA + packB + packC >= admission.budgetBytes(),
                "third class pack must not fit: " + (packA + packB + packC) + " vs " + admission.budgetBytes());
        long blob = PackUnpackAdmission.estimateReserveBytes(85_000_000L, 0L);
        assertEquals(85_000_000L, blob, "large-wire pack uses 1× (native-heavy)");
        assertTrue(packA + packB + blob < admission.budgetBytes(),
                "81MiB-class unpack must share the heap: " + (packA + packB + blob));

        CountDownLatch twoInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger thirdStarted = new AtomicInteger();

        Thread t1 = holdUnpack("large-a", packA, twoInside, release, maxActive);
        Thread t2 = holdUnpack("large-b", packB, twoInside, release, maxActive);
        t1.start();
        t2.start();
        assertTrue(twoInside.await(5, TimeUnit.SECONDS));
        assertEquals(2, maxActive.get());

        Thread t3 = new Thread(() -> {
            try {
                admission.runUnpack(packC, () -> {
                    thirdStarted.incrementAndGet();
                    bumpMax(maxActive);
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "medium");
        t3.start();
        Thread.sleep(80);
        assertEquals(0, thirdStarted.get());
        assertEquals(2, admission.activeUnpackers());
        release.countDown();
        t1.join(5000);
        t2.join(5000);
        t3.join(5000);
        assertEquals(1, thirdStarted.get());
        assertEquals(2, maxActive.get());
        assertEquals(0, admission.activeUnpackers());
    }

    @Test
    void twoAutoDefaultReservesFitAndThirdWaits() throws Exception {
        admission.setBudgetOverrideBytes(1800L << 20);
        admission.setDefaultReserveOverrideBytes(null);
        long reserve = admission.defaultReserveBytes();
        assertTrue(2L * reserve < admission.budgetBytes());
        assertTrue(3L * reserve >= admission.budgetBytes());

        CountDownLatch twoInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger thirdStarted = new AtomicInteger();

        Thread t1 = holdUnpack("unk-1", reserve, twoInside, release, maxActive);
        Thread t2 = holdUnpack("unk-2", reserve, twoInside, release, maxActive);
        t1.start();
        t2.start();
        assertTrue(twoInside.await(5, TimeUnit.SECONDS));
        assertEquals(2, maxActive.get());

        Thread t3 = new Thread(() -> {
            try {
                admission.runUnpack(reserve, () -> {
                    thirdStarted.incrementAndGet();
                    bumpMax(maxActive);
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "unk-3");
        t3.start();
        Thread.sleep(80);
        assertEquals(0, thirdStarted.get());
        release.countDown();
        t1.join(5000);
        t2.join(5000);
        t3.join(5000);
        assertEquals(1, thirdStarted.get());
        assertEquals(2, maxActive.get());
    }

    private Thread holdUnpack(String name, long reserve, CountDownLatch inside,
            CountDownLatch release, AtomicInteger maxActive) {
        return new Thread(() -> {
            try {
                admission.runUnpack(reserve, () -> {
                    bumpMax(maxActive);
                    inside.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, name);
    }

    private static void bumpMax(AtomicInteger maxActive) {
        int a = PackUnpackAdmission.getInstance().activeUnpackers();
        maxActive.accumulateAndGet(a, Math::max);
    }
}
