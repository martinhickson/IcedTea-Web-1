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

        assertNull(JvmSelector.selectBest(Collections.singletonList(jdk11), "17+", JdkMatchStrategy.EXACT));
        assertNull(JvmSelector.selectBest(Collections.singletonList(jdk11), "17+", null));
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
    void selectBestUsesListPrecedenceWhenMultipleMajorsMatchMaximum() {
        JvmDescriptor jdk17 = descriptor("C:\\jdk17", "17", true);
        JvmDescriptor jdk21 = descriptor("C:\\jdk21", "21", true);

        // Both match 17+ under Maximum; configured order wins (not highest major).
        JvmDescriptor selected = JvmSelector.selectBest(
                Arrays.asList(jdk17, jdk21), "17+", JdkMatchStrategy.MAXIMUM);
        assertEquals("C:\\jdk17", selected.getHomePath());

        JvmDescriptor selectedReversed = JvmSelector.selectBest(
                Arrays.asList(jdk21, jdk17), "17+", JdkMatchStrategy.MAXIMUM);
        assertEquals("C:\\jdk21", selectedReversed.getHomePath());
    }

    @Test
    void selectBestUsesListPrecedenceWhenMultipleExactSameMajorMatch() {
        JvmDescriptor first = descriptor("C:\\corretto-17", "17", true);
        JvmDescriptor second = descriptor("C:\\temurin-17", "17", true);

        assertEquals("C:\\corretto-17",
                JvmSelector.selectBest(Arrays.asList(first, second), "17", JdkMatchStrategy.EXACT).getHomePath());
        assertEquals("C:\\temurin-17",
                JvmSelector.selectBest(Arrays.asList(second, first), "17", JdkMatchStrategy.EXACT).getHomePath());
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

    @Test
    void selectVersionAmongAlternativesPrefersKnownJvmOverFirstListed() {
        JvmDescriptor jdk17 = descriptor("C:\\jdk17", "17", true);
        String selected = JvmSelector.selectVersionAmongAlternatives(
                Arrays.asList("11", "17"),
                Collections.singletonList(jdk17),
                JdkMatchStrategy.EXACT,
                8);
        assertEquals("17", selected);
    }

    @Test
    void selectVersionAmongAlternativesPrefersCurrentRuntimeMatch() {
        JvmDescriptor jdk11 = descriptor("C:\\jdk11", "11", true);
        JvmDescriptor jdk17 = descriptor("C:\\jdk17", "17", true);
        String selected = JvmSelector.selectVersionAmongAlternatives(
                Arrays.asList("11", "17"),
                Arrays.asList(jdk11, jdk17),
                JdkMatchStrategy.EXACT,
                17);
        assertEquals("17", selected);
    }

    @Test
    void selectVersionAmongAlternativesFallsBackToFirstWhenNoneAvailable() {
        String selected = JvmSelector.selectVersionAmongAlternatives(
                Arrays.asList("11", "17"),
                Collections.<JvmDescriptor>emptyList(),
                JdkMatchStrategy.EXACT,
                8);
        assertEquals("11", selected);
    }

    @Test
    void selectVersionAmongAlternativesPrefersFirstListedWhenBothKnown() {
        JvmDescriptor jdk11 = descriptor("C:\\jdk11", "11", true);
        JvmDescriptor jdk17 = descriptor("C:\\jdk17", "17", true);
        String selected = JvmSelector.selectVersionAmongAlternatives(
                Arrays.asList("11", "17"),
                Arrays.asList(jdk11, jdk17),
                JdkMatchStrategy.EXACT,
                8);
        assertEquals("11", selected);
    }

    private static JvmDescriptor descriptor(String home, String version, boolean valid) {
        return new JvmDescriptor(home, "Test JDK", version, valid, null);
    }
}
