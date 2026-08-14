package net.sourceforge.jnlp.cache.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class JarSlotTest {

    private static JarSlot slot(int idx, URL url) {
        JarGroupState g = JarGroupState.forJars(Arrays.asList(url));
        return g.slot(idx);
    }

    private static URL url(String s) {
        try { return new URL(s); } catch (MalformedURLException e) { throw new RuntimeException(e); }
    }

    @Test
    public void settlesGoodOnFirstAttempt() {
        JarSlot s = slot(0, url("http://localhost/a.jar"));
        assertFalse(s.state().isAbsorbing());
        assertTrue(s.settleGood(1000L, false));
        assertEquals(JarState.GOOD, s.state());
        assertTrue(s.state().isAbsorbing());
        assertTrue(s.settled().isDone());
    }

    @Test
    public void settleStatsLineIncludesKindBytesAndRetried() {
        JarSlot s = slot(0, url("http://localhost/stats.jar"));
        s.onConnect(1000L);
        s.onFirstByte(1100L);
        s.addTransferred(2048L);
        s.onLastByte(1200L);
        s.onDecompressed(4096L, true);
        assertTrue(s.settleGood(1300L, false));
        String line = s.settleStatsLine();
        assertTrue(line.contains("Download complete:"), line);
        assertTrue(line.contains("kind=DOWNLOADED"), line);
        assertTrue(line.contains("bytes=2,048"), line);
        assertTrue(line.contains("ratio=50.0%"), line);
        assertTrue(line.contains("qwait="), line);
        assertTrue(line.contains("retried=false"), line);
    }

    @Test
    public void settleBadFinalMarksFailedWithoutRetryPark() {
        JarSlot s = slot(0, url("http://localhost/fail.jar"));
        assertTrue(s.settleBadFinal(1000L));
        assertEquals(JarState.SETTLED_BAD, s.state());
        assertEquals(MetricKind.FAILED, s.kind);
        assertTrue(s.settleStatsLine().contains("kind=FAILED"), s.settleStatsLine());
    }

    @Test
    public void settleBadFinalAbsorbsFromRetryPending() {
        // fail-fast / Error path: settleUnusable parks RETRY_PENDING; must still absorb.
        JarSlot s = slot(0, url("http://localhost/oom.jar"));
        assertFalse(s.settleUnusable(1000L));
        assertEquals(JarState.RETRY_PENDING, s.state());
        assertTrue(s.settleBadFinal(1100L));
        assertEquals(JarState.SETTLED_BAD, s.state());
        assertTrue(s.settled().isDone());
        assertTrue(s.state().isAbsorbing());
    }

    @Test
    public void settleGoodIsIdempotent() {
        JarSlot s = slot(0, url("http://localhost/a.jar"));
        assertTrue(s.settleGood(1000L, false));
        assertFalse(s.settleGood(2000L, false)); // second call loses the CAS
        assertEquals(JarState.GOOD, s.state());
    }

    @Test
    public void firstUnusableParksInRetryPending() {
        JarSlot s = slot(0, url("http://localhost/a.jar"));
        assertFalse(s.settleUnusable(1000L)); // parked, NOT settled
        assertEquals(JarState.RETRY_PENDING, s.state());
        assertFalse(s.settled().isDone());
    }

    @Test
    public void claimRetryThenUnusableSettlesBad() {
        JarSlot s = slot(0, url("http://localhost/a.jar"));
        s.settleUnusable(1000L);
        assertTrue(s.claimRetry());
        assertEquals(JarState.IN_FLIGHT, s.state());
        assertTrue(s.retried.get());
        // second unusable after retry consumed → SETTLED_BAD, NOT another RETRY_PENDING
        assertTrue(s.settleUnusable(2000L));
        assertEquals(JarState.SETTLED_BAD, s.state());
        assertEquals(MetricKind.FAILED, s.kind);
        assertTrue(s.settled().isDone());
    }

    @Test
    public void retryThenGoodSettlesGood() {
        JarSlot s = slot(0, url("http://localhost/a.jar"));
        s.settleUnusable(1000L);
        s.claimRetry();
        assertTrue(s.settleGood(2000L, false));
        assertEquals(JarState.GOOD, s.state());
        assertEquals(MetricKind.DOWNLOADED, s.kind);
        assertTrue(s.settled().isDone());
    }

    @Test
    public void exactlyOneRetryBounded() {
        // regression test for the design bug: the second unusable must NOT re-enter RETRY_PENDING
        JarSlot s = slot(0, url("http://localhost/a.jar"));
        s.settleUnusable(1000L);
        s.claimRetry();
        s.settleUnusable(2000L);   // → SETTLED_BAD
        assertEquals(JarState.SETTLED_BAD, s.state());
        assertTrue(s.settled().isDone());
    }

    @Test
    public void claimRetryFailsForSecondCaller() {
        JarSlot s = slot(0, url("http://localhost/a.jar"));
        s.settleUnusable(1000L);
        assertTrue(s.claimRetry());
        assertFalse(s.claimRetry()); // only one coordinator wins
    }

    @Test
    public void transferredAccumulatesInPlace() {
        JarSlot s = slot(0, url("http://localhost/a.jar"));
        s.addTransferred(10);
        s.addTransferred(20);
        assertEquals(30, s.transferred());
    }

    @Test
    public void settleStatsLineBeforeKindUsesPlaceholders() {
        JarSlot s = slot(0, url("http://localhost/pending.jar"));
        String line = s.settleStatsLine();
        assertTrue(line.contains("kind=?"), line);
        assertTrue(line.contains("ttfb=-"), line);
        assertTrue(line.contains("thr=-"), line);
        assertTrue(line.contains("decomp=-"), line);
    }

    @Test
    public void settleBadFinalIsNoOpAfterAlreadyGood() {
        JarSlot s = slot(0, url("http://localhost/a.jar"));
        assertTrue(s.settleGood(1L, false));
        assertFalse(s.settleBadFinal(2L));
        assertEquals(JarState.GOOD, s.state());
    }

    @Test
    public void timestampsAndDerivedMetrics() {
        JarSlot s = slot(0, url("http://localhost/a.jar"));
        s.startMillis = 10;               // explicit so derived deltas are deterministic
        s.onConnect(10);
        s.onFirstByte(20);
        s.addTransferred(100);
        s.onLastByte(30);
        assertTrue(s.settleGood(40, false));
        assertEquals(10, s.ttfbMillis());        // 20 - 10 (connect start)
        assertEquals(30, s.durationMillis());    // 40 - 10 (connect start)
        assertEquals(10, s.transferMillis());    // 30 - 20
    }

    @Test
    public void ttfbIsFromConnectNotGroupStart() {
        JarSlot s = slot(0, url("http://localhost/late.jar"));
        s.startMillis = 0; // group started long before this jar connected
        s.onConnect(5000L, 5020L);
        s.onFirstByte(5035L);
        s.onLastByte(5100L);
        s.addTransferred(100);
        assertTrue(s.settleGood(5200L, false));
        assertEquals(35, s.ttfbMillis());       // 5035 - 5000 connect start (includes 20ms handshake)
        assertEquals(200, s.durationMillis());  // 5200 - 5000 connect start
        assertEquals(65, s.transferMillis());   // 5100 - 5035
    }

    @Test
    public void ttfbIncludesConnectRoundTripNotJustWaitAfterHeaders() {
        JarSlot s = slot(0, url("http://localhost/wan.jar"));
        s.startMillis = 0L;
        s.onConnect(1000L, 1040L); // 40ms handshake / RTT
        s.onFirstByte(1045L);
        assertEquals(45, s.ttfbMillis());
        assertTrue(s.ttfbMillis() >= 40L, "TTFB must not be smaller than connect duration");
    }

    @Test
    public void ttfbIsUnknownUntilConnectEvenIfGroupStartAndFirstByteExist() {
        JarSlot s = slot(0, url("http://localhost/queued.jar"));
        s.startMillis = 0L;
        s.onFirstByte(180_000L);
        assertEquals(-1, s.ttfbMillis(), "TTFB must not fall back to firstByte-groupStart");
    }

    @Test
    public void onFirstByteKeepsTheFirstStamp() {
        JarSlot s = slot(0, url("http://localhost/a.jar"));
        s.onConnect(100L, 110L);
        s.onFirstByte(120L);
        s.onFirstByte(999L);
        assertEquals(20, s.ttfbMillis());
    }

    @Test
    public void durationUsesConnectStartNotGroupStart() {
        JarSlot s = slot(0, url("http://localhost/late.jar"));
        s.startMillis = 0L;
        s.onConnect(10_000L, 10_010L);
        s.onFirstByte(10_020L);
        s.onLastByte(10_100L);
        assertTrue(s.settleGood(10_200L, false));
        assertEquals(200, s.durationMillis());
        assertEquals(10_000L, s.queueWaitMillis());
    }

    @Test
    public void durationDoesNotFallBackToGroupEnqueue() {
        JarSlot s = slot(0, url("http://localhost/queued.jar"));
        s.startMillis = 0L;
        assertTrue(s.settleGood(180_000L, false));
        assertEquals(-1, s.durationMillis(), "dur must not include queue wait via startMillis");
        assertEquals(-1, s.queueWaitMillis());
    }

    @Test
    public void queueWaitIsEnqueueToConnectStart() {
        JarSlot s = slot(0, url("http://localhost/queued.jar"));
        s.startMillis = 1_000L;
        s.onConnect(6_000L, 6_010L);
        s.onFirstByte(6_020L);
        s.onLastByte(6_100L);
        assertTrue(s.settleGood(6_200L, false));
        assertEquals(5_000L, s.queueWaitMillis());
        assertEquals(200L, s.durationMillis());
    }

    @Test
    public void throughputUsesOneMsWhenLastByteMinusFirstByteNonPositive() {
        JarSlot s = slot(0, url("http://localhost/fast.jar"));
        s.onConnect(100L, 100L);
        s.onFirstByte(100L);
        s.onLastByte(100L);
        s.addTransferred(2048L);
        assertTrue(s.settleGood(100L, false));
        assertEquals(0, s.transferMillis());
        assertEquals(1, s.transferMillisForThroughput());
        assertEquals(2048.0 / 1024.0 * 1000.0, s.throughputKBps(), 0.001);
        assertTrue(s.settleStatsLine().contains("thr=2,000.0KB/s"), s.settleStatsLine());
    }
}
