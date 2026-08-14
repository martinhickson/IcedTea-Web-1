package net.sourceforge.jnlp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.sourceforge.jnlp.Version;
import net.sourceforge.jnlp.cache.download.JarGroupState;
import net.sourceforge.jnlp.cache.download.JarState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ResourceTrackerPreSettleTest {

    @TempDir
    Path tmp;

    private static URL url(String s) {
        try { return new URL(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    public void alreadyDownloadedResourceIsPreSettledGood() throws Exception {
        Path file = tmp.resolve("existing.txt");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));
        URL u = url("http://localhost/existing.txt");
        Resource r = Resource.getResource(u, new Version("1.0"), UpdatePolicy.NEVER);
        r.setTerminalState(JarState.GOOD);
        r.setLocalFile(file.toFile());

        JarGroupState group = JarGroupState.forJars(Arrays.asList(u));
        List<Resource> needsRestart = new ArrayList<>();
        ResourceTracker.preSettleSlots(new Resource[]{r}, group, needsRestart);

        assertTrue(needsRestart.isEmpty());
        assertEquals(JarState.GOOD, group.slot(0).state());
        assertTrue(group.done().isDone(), "pre-settled GOOD must complete the group");
    }

    @Test
    public void retriedErrorResourceIsPreSettledBad() throws Exception {
        URL u = url("http://localhost/failed.txt");
        Resource r = Resource.getResource(u, new Version("1.0"), UpdatePolicy.NEVER);
        r.setTerminalState(JarState.SETTLED_BAD);
        r.consumeUnusableTerminalRetry(); // retry already consumed → settle SETTLED_BAD

        JarGroupState group = JarGroupState.forJars(Arrays.asList(u));
        List<Resource> needsRestart = new ArrayList<>();
        ResourceTracker.preSettleSlots(new Resource[]{r}, group, needsRestart);

        assertTrue(needsRestart.isEmpty());
        assertEquals(JarState.SETTLED_BAD, group.slot(0).state());
        assertTrue(group.done().isDone());
    }

    @Test
    public void prematureErrorIsConsumedAndRestarted() throws Exception {
        URL u = url("http://localhost/premature.txt");
        Resource r = Resource.getResource(u, new Version("1.0"), UpdatePolicy.NEVER);
        r.setTerminalState(JarState.SETTLED_BAD); // retry NOT consumed

        JarGroupState group = JarGroupState.forJars(Arrays.asList(u));
        List<Resource> needsRestart = new ArrayList<>();
        ResourceTracker.preSettleSlots(new Resource[]{r}, group, needsRestart);

        assertEquals(1, needsRestart.size());
        assertTrue(r.isUnusableTerminalRetried(), "one-shot retry must be consumed");
        assertEquals(JarState.IN_FLIGHT, group.slot(0).state());
        assertEquals(group.slot(0), r.getJarSlot(),
                "prepareRedownload must not unbind the wait() group slot");
        assertTrue(!group.done().isDone(), "retry-pending must not complete the group");
    }

    @Test
    public void ghostGoodWithoutLocalFileIsRestartedNotLeftInFlight() throws Exception {
        URL u = url("http://localhost/ghost.jar");
        Resource r = Resource.getResource(u, new Version("1.0"), UpdatePolicy.NEVER);
        r.setTerminalState(JarState.GOOD);
        r.setLocalFile(tmp.resolve("missing-ghost.jar").toFile()); // does not exist

        JarGroupState group = JarGroupState.forJars(Arrays.asList(u));
        List<Resource> needsRestart = new ArrayList<>();
        ResourceTracker.preSettleSlots(new Resource[]{r}, group, needsRestart);

        assertEquals(1, needsRestart.size());
        assertEquals(null, r.getTerminalState());
        assertEquals(JarState.IN_FLIGHT, group.slot(0).state());
        assertEquals(group.slot(0), r.getJarSlot(),
                "ghost restart must stay bound to the wait() group slot");
        assertTrue(!group.done().isDone(), "ghost GOOD must not absorb the group as success");
    }

    @Test
    public void hasUsableLocalFileRejectsMissingEmptyAndNonJarPayload() throws Exception {
        URL jarUrl = url("http://localhost/lib/app.jar");
        Resource missing = Resource.getResource(jarUrl, null, UpdatePolicy.NEVER);
        missing.setLocalFile(tmp.resolve("nope.jar").toFile());
        assertTrue(!ResourceTracker.hasUsableLocalFile(missing));

        Path empty = tmp.resolve("empty.jar");
        Files.write(empty, new byte[0]);
        Resource emptyRes = Resource.getResource(jarUrl, null, UpdatePolicy.NEVER);
        emptyRes.setLocalFile(empty.toFile());
        assertTrue(!ResourceTracker.hasUsableLocalFile(emptyRes));

        Path poison = tmp.resolve("poison.jar");
        Files.write(poison, "11 Could not locate requested version\r\n".getBytes(StandardCharsets.UTF_8));
        Resource poisonRes = Resource.getResource(jarUrl, null, UpdatePolicy.NEVER);
        poisonRes.setLocalFile(poison.toFile());
        assertTrue(!ResourceTracker.hasUsableLocalFile(poisonRes));

        assertTrue(!ResourceTracker.hasUsableLocalFile(null));

        Path plain = tmp.resolve("data.bin");
        Files.write(plain, "payload".getBytes(StandardCharsets.UTF_8));
        Resource plainRes = Resource.getResource(url("http://localhost/data.bin"), null, UpdatePolicy.NEVER);
        plainRes.setLocalFile(plain.toFile());
        assertTrue(ResourceTracker.hasUsableLocalFile(plainRes));
    }

    @Test
    public void canReuseMetricsGroupRequiresSameBoundSlots() throws Exception {
        URL a = url("http://localhost/a.jar");
        URL b = url("http://localhost/b.jar");
        Resource ra = Resource.getResource(a, null, UpdatePolicy.NEVER);
        Resource rb = Resource.getResource(b, null, UpdatePolicy.NEVER);
        JarGroupState group = JarGroupState.forJars(Arrays.asList(a, b));
        ra.setJarSlot(group.slot(0));
        rb.setJarSlot(group.slot(1));

        assertTrue(ResourceTracker.canReuseMetricsGroup(new Resource[]{ra, rb}, group));
        assertTrue(!ResourceTracker.canReuseMetricsGroup(new Resource[]{ra}, group));
        assertTrue(!ResourceTracker.canReuseMetricsGroup(new Resource[]{ra, rb}, null));

        // Mismatched slot binding → must allocate a fresh metrics group.
        rb.setJarSlot(null);
        assertTrue(!ResourceTracker.canReuseMetricsGroup(new Resource[]{ra, rb}, group));
    }

    @Test
    public void canReuseMetricsGroupRejectsCompletedGroup() throws Exception {
        URL u = url("http://localhost/done.jar");
        Resource r = Resource.getResource(u, null, UpdatePolicy.NEVER);
        JarGroupState group = JarGroupState.forJars(Arrays.asList(u));
        r.setJarSlot(group.slot(0));
        assertTrue(group.slot(0).settleGood(System.currentTimeMillis(), true));
        assertTrue(group.done().isDone());
        assertTrue(!ResourceTracker.canReuseMetricsGroup(new Resource[]{r}, group));
    }
}
