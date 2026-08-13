package net.sourceforge.jnlp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.sourceforge.jnlp.cache.download.JarState;
import net.sourceforge.jnlp.event.DownloadEvent;
import net.sourceforge.jnlp.event.DownloadListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceTrackerApiCoverageTest {

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
    void requestMethodsExposeHeadAndGetOnly() {
        ResourceTracker.RequestMethods[] methods = ResourceTracker.RequestMethods.getValidRequestMethods();
        assertEquals(2, methods.length);
        assertEquals(ResourceTracker.RequestMethods.HEAD, methods[0]);
        assertEquals(ResourceTracker.RequestMethods.GET, methods[1]);
    }

    @Test
    void addResourceRejectsNullLocation() {
        ResourceTracker rt = new ResourceTracker();
        assertThrows(IllegalResourceDescriptorException.class,
                () -> rt.addResource(null, null, null, UpdatePolicy.NEVER));
    }

    @Test
    void untrackedLocationThrowsOnLookupApis() throws Exception {
        ResourceTracker rt = new ResourceTracker();
        URL missing = url("http://localhost/not-tracked-" + System.nanoTime() + ".jar");
        assertThrows(IllegalResourceDescriptorException.class, () -> rt.checkResource(missing));
        assertThrows(IllegalResourceDescriptorException.class, () -> rt.getAmountRead(missing));
        assertThrows(IllegalResourceDescriptorException.class, () -> rt.getTotalSize(missing));
        assertThrows(IllegalResourceDescriptorException.class, () -> rt.startResource(missing));
        assertThrows(IllegalResourceDescriptorException.class, () -> rt.removeResource(missing));
        assertThrows(IllegalResourceDescriptorException.class, () -> rt.getCacheURL(missing));
    }

    @Test
    void fireDownloadEventNotifiesAndDedupesListeners() throws Exception {
        ResourceTracker rt = new ResourceTracker();
        AtomicInteger completes = new AtomicInteger();
        DownloadListener listener = new DownloadListener() {
            @Override
            public void updateStarted(DownloadEvent downloadEvent) {
            }

            @Override
            public void downloadStarted(DownloadEvent downloadEvent) {
            }

            @Override
            public void downloadCompleted(DownloadEvent downloadEvent) {
                completes.incrementAndGet();
            }
        };
        rt.addDownloadListener(listener);
        rt.addDownloadListener(listener); // must not double-notify
        rt.removeDownloadListener(listener);
        rt.addDownloadListener(listener);

        Path file = tmp.resolve("tracked.txt");
        Files.write(file, "ok".getBytes(StandardCharsets.UTF_8));
        URL u = url("http://localhost/tracked-" + System.nanoTime() + ".txt");
        Resource r = Resource.getResource(u, null, UpdatePolicy.NEVER);
        r.setLocalFile(file.toFile());
        r.setTerminalState(JarState.GOOD);
        rt.fireDownloadEvent(r);
        assertEquals(1, completes.get());

        r.setTerminalState(JarState.SETTLED_BAD);
        rt.fireDownloadEvent(r);
        assertEquals(2, completes.get());

        rt.removeDownloadListener(listener);
        rt.fireDownloadEvent(r);
        assertEquals(2, completes.get());
    }

    @Test
    void waitForEmptyResourcesReturnsImmediately() throws Exception {
        ResourceTracker rt = new ResourceTracker();
        assertTrue(rt.waitForResources(new URL[0], 1L));
    }

    @Test
    void addAndRemoveResourceAroundLocalFileUrl() throws Exception {
        Path file = tmp.resolve("local-res.bin");
        Files.write(file, "data".getBytes(StandardCharsets.UTF_8));
        URL u = file.toUri().toURL();
        ResourceTracker rt = new ResourceTracker();
        rt.addResource(u, null, null, UpdatePolicy.NEVER);
        // Tracked: amount/size lookups must not throw even if not yet DOWNLOADED.
        assertEquals(0L, rt.getAmountRead(u));
        rt.getTotalSize(u);
        rt.checkResource(u);
        rt.removeResource(u);
        assertThrows(IllegalResourceDescriptorException.class, () -> rt.checkResource(u));
    }
}
