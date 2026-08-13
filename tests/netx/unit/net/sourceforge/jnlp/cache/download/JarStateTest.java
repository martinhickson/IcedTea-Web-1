package net.sourceforge.jnlp.cache.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JarStateTest {

    @Test
    void absorbingAndUsableFlags() {
        assertFalse(JarState.IN_FLIGHT.isAbsorbing());
        assertFalse(JarState.RETRY_PENDING.isAbsorbing());
        assertTrue(JarState.GOOD.isAbsorbing());
        assertTrue(JarState.SETTLED_BAD.isAbsorbing());

        assertTrue(JarState.GOOD.isUsable());
        assertFalse(JarState.SETTLED_BAD.isUsable());
        assertFalse(JarState.IN_FLIGHT.isUsable());
    }

    @Test
    void fromOrdinalRoundTrip() {
        for (JarState state : JarState.values()) {
            assertEquals(state, JarState.fromOrdinal(state.ordinal()));
        }
    }
}
