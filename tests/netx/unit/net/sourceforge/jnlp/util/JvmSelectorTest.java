package net.sourceforge.jnlp.util;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import net.sourceforge.jnlp.config.JdkMatchStrategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmSelectorTest {

    @Test
    void selectBestDoesNotFallBackToLowerJdkWhenNoMatchingMajorExists() {
        JvmDescriptor jdk11 = descriptor("C:\\jdk11", "11", true);

        assertNull(JvmSelector.selectBest(Collections.singletonList(jdk11), "17+", JdkMatchStrategy.MAXIMUM));
    }

    @Test
    void selectBestPicksMatchingJdk17ForJava17PlusRequest() {
        JvmDescriptor jdk11 = descriptor("C:\\jdk11", "11", true);
        JvmDescriptor jdk17 = descriptor("C:\\jdk17", "17", true);

        JvmDescriptor selected = JvmSelector.selectBest(
                Arrays.asList(jdk11, jdk17), "17+", JdkMatchStrategy.MAXIMUM);
        assertEquals("C:\\jdk17", selected.getHomePath());
    }

    @Test
    void selectBestIgnoresInvalidKnownJvmsEvenWhenPathLooksLikeJdk17() {
        JvmDescriptor jdk11 = descriptor("C:\\jdk11", "11", true);
        JvmDescriptor invalidJdk17 = descriptor("C:\\missing\\jdk17", "17", false);

        assertNull(JvmSelector.selectBest(
                Arrays.asList(jdk11, invalidJdk17), "17+", JdkMatchStrategy.MAXIMUM));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void matchesStrategyUsesProbedMajorWhenVersionLabelEmpty() {
        String home = "C:\\Program Files\\Amazon Corretto\\jdk17.0.16_8";
        if (!new java.io.File(home, "bin\\java.exe").isFile()) {
            return;
        }
        JvmDescriptor jdk17 = new JvmDescriptor(home, "Amazon Corretto", "", true, null);

        assertTrue(JvmSelector.matchesStrategy(jdk17, "17+", JdkMatchStrategy.MAXIMUM));
    }

    private static JvmDescriptor descriptor(String home, String version, boolean valid) {
        return new JvmDescriptor(home, "Test JDK", version, valid, null);
    }
}
