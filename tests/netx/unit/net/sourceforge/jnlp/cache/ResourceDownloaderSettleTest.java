package net.sourceforge.jnlp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.util.Arrays;
import net.sourceforge.jnlp.Version;
import net.sourceforge.jnlp.cache.download.JarGroupState;
import net.sourceforge.jnlp.cache.download.JarSlot;
import net.sourceforge.jnlp.cache.download.JarState;
import org.junit.jupiter.api.Test;

class ResourceDownloaderSettleTest {

    private static URL url(String s) {
        try {
            return new URL(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void settleSlotBadWithoutSlotMarksTerminalError() throws Exception {
        Resource r = Resource.getResource(url("http://localhost/no-slot.jar"), new Version("1.0"), UpdatePolicy.NEVER);
        r.setJarSlot(null);
        ResourceDownloader d = new ResourceDownloader(r, new Object());
        d.settleSlotBad();
        assertEquals(JarState.SETTLED_BAD, r.getTerminalState());
    }

    @Test
    void settleSlotBadFirstFailureParksRetryWithoutTerminal() throws Exception {
        URL u = url("http://localhost/retry-once.jar");
        Resource r = Resource.getResource(u, new Version("1.0"), UpdatePolicy.NEVER);
        JarGroupState group = JarGroupState.forJars(Arrays.asList(u));
        JarSlot slot = group.slot(0);
        r.setJarSlot(slot);
        ResourceDownloader d = new ResourceDownloader(r, new Object());

        d.settleSlotBad();
        assertEquals(JarState.RETRY_PENDING, slot.state());
        assertEquals(null, r.getTerminalState());
    }

    @Test
    void settleSlotBadAfterClaimedRetryMarksTerminal() throws Exception {
        URL u = url("http://localhost/retry-spent.jar");
        Resource r = Resource.getResource(u, new Version("1.0"), UpdatePolicy.NEVER);
        JarGroupState group = JarGroupState.forJars(Arrays.asList(u));
        JarSlot slot = group.slot(0);
        r.setJarSlot(slot);
        ResourceDownloader d = new ResourceDownloader(r, new Object());

        d.settleSlotBad();
        assertTrue(slot.claimRetry());
        assertEquals(JarState.IN_FLIGHT, slot.state());
        d.settleSlotBad();
        assertEquals(JarState.SETTLED_BAD, slot.state());
        assertEquals(JarState.SETTLED_BAD, r.getTerminalState());
        assertTrue(group.done().isDone());
    }

    @Test
    void failFastOutOfRetriesAbsorbsRetryPendingSlot() throws Exception {
        URL u = url("http://localhost/fail-fast.jar");
        Resource r = Resource.getResource(u, new Version("1.0"), UpdatePolicy.NEVER);
        JarGroupState group = JarGroupState.forJars(Arrays.asList(u));
        JarSlot slot = group.slot(0);
        r.setJarSlot(slot);
        assertFalse(slot.settleUnusable(1L)); // first failure parks, does not absorb
        assertEquals(JarState.RETRY_PENDING, slot.state());

        ResourceDownloader d = new ResourceDownloader(r, new Object());
        d.failFastOutOfRetries();

        assertEquals(JarState.SETTLED_BAD, slot.state());
        assertEquals(JarState.SETTLED_BAD, r.getTerminalState());
        assertTrue(group.done().isDone());
        assertTrue(r.isSet(Resource.Status.ERROR));
    }

    @Test
    void failFastOutOfRetriesWithoutSlotStillMarksError() throws Exception {
        Resource r = Resource.getResource(url("http://localhost/fail-noslot.jar"), null, UpdatePolicy.NEVER);
        r.setJarSlot(null);
        ResourceDownloader d = new ResourceDownloader(r, new Object());
        d.failFastOutOfRetries();
        assertEquals(JarState.SETTLED_BAD, r.getTerminalState());
        assertTrue(r.isSet(Resource.Status.ERROR));
    }

    @Test
    void packWireHintAndUrlRequestResultHelpers() throws Exception {
        assertEquals(0L, ResourceDownloader.packWireHintBytes(0L, 0L, 0L));
        ResourceDownloader.UrlRequestResult ok = new ResourceDownloader.UrlRequestResult(url("http://localhost/a.jar"));
        ok.result = 200;
        assertFalse(ok.shouldRedirect());
        assertFalse(ok.isInvalid());

        ResourceDownloader.UrlRequestResult redirect = new ResourceDownloader.UrlRequestResult();
        redirect.result = 302;
        redirect.URL = url("http://localhost/b.jar");
        assertTrue(redirect.shouldRedirect());
        assertTrue(redirect.isInvalid());

        ResourceDownloader.UrlRequestResult bad = new ResourceDownloader.UrlRequestResult();
        bad.result = 404;
        assertTrue(bad.isInvalid());
        assertFalse(bad.shouldRedirect());

        String text = ok.toString();
        assertTrue(text.contains("url:"), text);
        assertTrue(text.contains("length: null"), text);
        assertTrue(text.contains("lastModified: null"), text);
    }
}
