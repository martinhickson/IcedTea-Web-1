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
}
