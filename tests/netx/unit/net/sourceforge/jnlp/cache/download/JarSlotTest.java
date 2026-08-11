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
    public void timestampsAndDerivedMetrics() {
        JarSlot s = slot(0, url("http://localhost/a.jar"));
        s.startMillis = 10;               // explicit so derived deltas are deterministic
        s.onConnect(10);
        s.onFirstByte(20);
        s.addTransferred(100);
        s.onLastByte(30);
        assertTrue(s.settleGood(40, false));
        assertEquals(10, s.ttfbMillis());        // 20 - 10 (start)
        assertEquals(30, s.durationMillis());    // 40 - 10
        assertEquals(10, s.transferMillis());    // 30 - 20
    }
}
