package net.sourceforge.jnlp.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import net.sourceforge.jnlp.cache.download.JarGroupState;
import net.sourceforge.jnlp.cache.download.JarState;
import org.junit.jupiter.api.Test;

/**
 * After EnumSet retirement, {@link Resource#resetStatus()} is a no-op.
 * Requeue must clear local file / slot / enqueue so a fresh download can start.
 */
public class ResourceRedownloadTest {

    @Test
    public void prepareRedownloadClearsTerminalBindingAndEnqueue() throws Exception {
        URL u = new URL("http://localhost/prep-" + System.nanoTime() + ".jar");
        Resource r = Resource.getResource(u, null, UpdatePolicy.NEVER);
        r.setLocalFile(new File("missing-" + System.nanoTime() + ".jar"));
        r.setTerminalState(JarState.SETTLED_BAD);
        r.setJarSlot(JarGroupState.forJars(Arrays.asList(u)).slot(0));
        assertTrue(r.tryEnqueue());
        assertTrue(r.isEnqueued());

        r.prepareRedownloadAfterUnusableTerminal();

        assertNull(r.getLocalFile());
        assertNull(r.getTerminalState());
        assertNull(r.getJarSlot());
        assertFalse(r.isEnqueued());
        assertTrue(r.tryEnqueue(), "enqueue must be claimable again");
    }

    @Test
    public void resetStatusIsNoOpAfterEnumSetRetirement() throws Exception {
        URL u = new URL("http://localhost/reset-" + System.nanoTime() + ".jar");
        Resource r = Resource.getResource(u, null, UpdatePolicy.NEVER);
        r.setTerminalState(JarState.SETTLED_BAD);
        r.setStatusFlag(Resource.Status.ERROR);
        r.resetStatus();
        assertEquals(JarState.SETTLED_BAD, r.getTerminalState());
        assertTrue(r.isSet(Resource.Status.ERROR));
    }
}
