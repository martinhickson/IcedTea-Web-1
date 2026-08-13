package net.sourceforge.jnlp.cache.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class JarGroupStateTest {

    private static URL url(String s) {
        try { return new URL(s); } catch (MalformedURLException e) { throw new RuntimeException(e); }
    }

    @Test
    public void doneCompletesWhenAllJarsSettle() {
        JarGroupState g = JarGroupState.forJars(Arrays.asList(
                url("http://localhost/a.jar"), url("http://localhost/b.jar")));
        assertTrue(g.slot(0).settleGood(100L, false));
        assertTrue(g.slot(1).settleGood(200L, false));
        assertTrue(g.done().isDone());
        assertEquals(2, g.settledSoFar());
    }

    @Test
    public void doneCompletesWithMixedOutcomes() {
        JarGroupState g = JarGroupState.forJars(Arrays.asList(
                url("http://localhost/a.jar"), url("http://localhost/b.jar")));
        assertTrue(g.slot(0).settleGood(100L, false));
        g.slot(1).settleUnusable(100L);
        g.slot(1).claimRetry();
        assertTrue(g.slot(1).settleUnusable(200L)); // → SETTLED_BAD
        assertTrue(g.done().isDone());
    }

    @Test
    public void doneDoesNotCompleteWhileRetryPending() {
        JarGroupState g = JarGroupState.forJars(Arrays.asList(
                url("http://localhost/a.jar"), url("http://localhost/b.jar")));
        assertTrue(g.slot(0).settleGood(100L, false));
        g.slot(1).settleUnusable(100L); // parked in RETRY_PENDING, not settled
        assertTrue(!g.done().isDone());
    }

    @Test
    public void statsThrowsBeforeCompletion() {
        JarGroupState g = JarGroupState.forJars(Arrays.asList(url("http://localhost/a.jar")));
        try {
            g.stats();
            assertTrue(false, "stats() should throw before done");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void bytesSoFarSumsTransferred() {
        JarGroupState g = JarGroupState.forJars(Arrays.asList(
                url("http://localhost/a.jar"), url("http://localhost/b.jar")));
        g.slot(0).addTransferred(10);
        g.slot(1).addTransferred(20);
        assertEquals(30, g.bytesSoFar());
    }

    @Test
    public void reclaimRetryPendingClaimsOrphanedSlots() {
        JarGroupState g = JarGroupState.forJars(Arrays.asList(
                url("http://localhost/a.jar"), url("http://localhost/b.jar")));
        assertTrue(g.slot(0).settleGood(100L, false));
        g.slot(1).settleUnusable(100L); // RETRY_PENDING, no waiter claimed it
        assertEquals(JarState.RETRY_PENDING, g.slot(1).state());
        assertTrue(!g.done().isDone());

        java.util.List<Integer> reclaimed = g.reclaimRetryPending();
        assertEquals(java.util.Collections.singletonList(1), reclaimed);
        assertEquals(JarState.IN_FLIGHT, g.slot(1).state());
        assertTrue(g.slot(1).settleGood(200L, false));
        assertTrue(g.done().isDone());
        assertEquals(1, g.stats().retriedCount);
    }

    @Test
    public void reclaimRetryPendingIsNoOpWhenNothingParked() {
        JarGroupState g = JarGroupState.forJars(Arrays.asList(url("http://localhost/a.jar")));
        assertTrue(g.slot(0).settleGood(1L, false));
        assertTrue(g.reclaimRetryPending().isEmpty());
    }

    @Test
    public void awaitAndSlotLookupByUrl() throws Exception {
        URL a = url("http://localhost/a.jar");
        URL b = url("http://localhost/b.jar");
        JarGroupState g = JarGroupState.forJars(Arrays.asList(a, b));
        assertEquals(0, g.slot(a).index());
        assertEquals(1, g.slot(b).index());
        assertEquals(2, g.size());
        assertTrue(g.wallDurationMillis() >= 0);

        g.slot(a).settleGood(50L, false);
        g.slot(b).settleGood(60L, true);
        g.await(a).join();
        g.awaitAll();
        assertTrue(g.done().isDone());
        assertTrue(g.wallDurationMillis() >= 0);
    }
}
