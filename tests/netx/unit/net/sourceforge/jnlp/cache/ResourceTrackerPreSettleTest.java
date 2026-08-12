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
        assertTrue(!group.done().isDone(), "retry-pending must not complete the group");
    }
}
