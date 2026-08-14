package net.sourceforge.jnlp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.sourceforge.jnlp.Version;
import net.sourceforge.jnlp.cache.download.JarGroupState;
import net.sourceforge.jnlp.cache.download.JarSlot;
import net.sourceforge.jnlp.cache.download.JarState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceDownloaderSettleTest {

    @TempDir
    Path tmp;

    private static URL url(String s) {
        try {
            return new URL(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void settleSlotGoodWithoutSlotMarksTerminalGoodForNonJar() throws Exception {
        URL u = url("http://localhost/plain-" + System.nanoTime() + ".bin");
        Resource r = Resource.getResource(u, null, UpdatePolicy.NEVER);
        Path file = tmp.resolve("plain.bin");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));
        r.setLocalFile(file.toFile());
        r.setJarSlot(null);
        assertTrue(r.tryEnqueue());
        new ResourceDownloader(r, new Object()).settleSlotGood(false);
        assertEquals(JarState.GOOD, r.getTerminalState());
        assertFalse(r.isEnqueued(), "enqueued must clear only after GOOD");
    }

    @Test
    void settleSlotGoodFromCacheGhostParksRetryPending() throws Exception {
        URL u = url("http://localhost/ghost-" + System.nanoTime() + ".jar");
        Resource r = Resource.getResource(u, null, UpdatePolicy.NEVER);
        JarGroupState group = JarGroupState.forJars(Arrays.asList(u));
        r.setJarSlot(group.slot(0));
        r.setLocalFile(null);
        assertTrue(r.tryEnqueue());
        new ResourceDownloader(r, new Object()).settleSlotGood(true);
        assertEquals(JarState.RETRY_PENDING, group.slot(0).state());
        assertEquals(null, r.getTerminalState());
        assertTrue(r.isEnqueued(), "RETRY_PENDING must keep enqueued so wait() cannot start a second GET");
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
        assertTrue(r.tryEnqueue());

        d.settleSlotBad();
        assertEquals(JarState.RETRY_PENDING, slot.state());
        assertEquals(null, r.getTerminalState());
        assertTrue(r.isEnqueued(), "first failure parks retry and must keep enqueued");
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
        assertFalse(r.isEnqueued(), "absorbing SETTLED_BAD must release enqueued");
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

    @Test
    void writeCountedStreamRecordsWireClocksNotUnpack() throws Exception {
        URL u = url("http://localhost/counted-" + System.nanoTime() + ".jar");
        Resource r = Resource.getResource(u, null, UpdatePolicy.NEVER);
        JarGroupState group = JarGroupState.forJars(Arrays.asList(u));
        JarSlot slot = group.slot(0);
        slot.onConnect(1L, 2L);
        r.setJarSlot(slot);

        Path dest = tmp.resolve("wire.bin");
        byte[] payload = new byte[12_000];
        Arrays.fill(payload, (byte) 7);
        ResourceDownloader.writeCountedStreamToFile(dest.toFile(),
                new java.io.ByteArrayInputStream(payload), r, slot);

        assertEquals(payload.length, dest.toFile().length());
        assertEquals(payload.length, r.getTransferred());
        assertEquals(payload.length, slot.transferred());
        assertTrue(slot.ttfbMillis() >= 0, "TTFB is first-byte minus connect start, recorded during drain");
        assertTrue(slot.transferMillis() >= 0, "last-byte clock must be set on drain complete");
    }
}
