package net.sourceforge.jnlp.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdkMatchStrategyTest {

    @Test
    void fromConfigDefaultsToExactWhenUnset() {
        assertEquals(JdkMatchStrategy.EXACT, JdkMatchStrategy.fromConfig(null));
        assertEquals(JdkMatchStrategy.EXACT, JdkMatchStrategy.fromConfig(""));
        assertEquals(JdkMatchStrategy.EXACT, JdkMatchStrategy.fromConfig("   "));
    }

    @Test
    void fromConfigRecognizesKnownValues() {
        assertEquals(JdkMatchStrategy.MAXIMUM, JdkMatchStrategy.fromConfig("Maximum"));
        assertEquals(JdkMatchStrategy.MINIMUM, JdkMatchStrategy.fromConfig("minimum"));
        assertEquals(JdkMatchStrategy.EXACT, JdkMatchStrategy.fromConfig("Exact"));
    }

    @Test
    void fromConfigDefaultsToExactForUnknownValue() {
        assertEquals(JdkMatchStrategy.EXACT, JdkMatchStrategy.fromConfig("unknown"));
    }
}
