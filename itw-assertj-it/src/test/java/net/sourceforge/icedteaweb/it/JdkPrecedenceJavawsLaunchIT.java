package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.JdkMatchStrategy;
import net.sourceforge.jnlp.config.KnownJvmStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Release-gated: when several JDKs match a JNLP version requirement, configured
 * preference order must win at real {@code javaws} launch time (not only in-process
 * {@link net.sourceforge.jnlp.util.JvmSelector} calls).
 */
class JdkPrecedenceJavawsLaunchIT {

    private static final Pattern SELECTED_JVM_HOME = Pattern.compile(
            "Selected JVM for JNLP request \\[[^\\]]+\\] using [^:]+: .* \\(([^)]+)\\)");
    private static final Pattern SUCCESS_HOME = Pattern.compile(
            "ITW_INTEGRATION_SUCCESS .*\\bhome=(.+)$", Pattern.MULTILINE);

    @BeforeEach
    void resetConfig() throws Exception {
        ControlPanelTestSupport.resetDeploymentConfig();
        JnlpLaunchTestSupport.clearIcedTeaWebLogs();
        Properties props = new Properties();
        props.setProperty(DeploymentConfiguration.KEY_AUTODETECT_JDKS, "false");
        ControlPanelTestSupport.enableFileLoggingForIntegrationTests(props);
        ControlPanelTestSupport.writeDeploymentProperties(props);
    }

    @AfterEach
    void tearDown() {
        // Destroy Process handles only — avoid taskkill (can hang CreateProcess under @Timeout).
        JnlpLaunchTestSupport.stopLaunchedProcesses();
        ControlPanelTestSupport.resetDeploymentConfig();
    }

    @Test
    @Timeout(240)
    @EnabledIf("net.sourceforge.icedteaweb.it.JdkPrecedenceJavawsLaunchIT#java17PlusTwoMatchingJdksAvailable")
    void javawsSelectsHigherPreferenceJdkWhenMultipleMatchSeventeenPlus() throws Exception {
        File starterJdk11 = ControlPanelTestSupport.findJdkHomeWithMajor(11);
        File jdk17 = ControlPanelTestSupport.findJdkHomeWithMajor(17);
        File jdk21 = ControlPanelTestSupport.findJdkHomeWithMajor(21);

        // Preference: 17 before 21. Both match java17-app's 17+ under Maximum.
        writeKnownJvms(jdk17, jdk21, JdkMatchStrategy.MAXIMUM);
        assertLaunchSelects(jdk17, jdk21, "17");
    }

    @Test
    @Timeout(240)
    @EnabledIf("net.sourceforge.icedteaweb.it.JdkPrecedenceJavawsLaunchIT#java17PlusTwoMatchingJdksAvailable")
    void javawsSelectsReorderedPreferenceWhenTwentyOneIsFirst() throws Exception {
        File starterJdk11 = ControlPanelTestSupport.findJdkHomeWithMajor(11);
        File jdk17 = ControlPanelTestSupport.findJdkHomeWithMajor(17);
        File jdk21 = ControlPanelTestSupport.findJdkHomeWithMajor(21);

        writeKnownJvms(jdk21, jdk17, JdkMatchStrategy.MAXIMUM);
        assertLaunchSelects(jdk21, jdk17, "21");
    }

    @Test
    @Timeout(240)
    @EnabledIf("net.sourceforge.icedteaweb.it.JdkPrecedenceJavawsLaunchIT#java17PlusTwoMatchingJdksAvailable")
    void exactStrategyIgnoresHigherMajorEvenWhenFirstInPreference() throws Exception {
        File jdk17 = ControlPanelTestSupport.findJdkHomeWithMajor(17);
        File jdk21 = ControlPanelTestSupport.findJdkHomeWithMajor(21);

        // Exact strips 17+ → major 17 only; 21 must not win even as top preference.
        writeKnownJvms(jdk21, jdk17, JdkMatchStrategy.EXACT);
        assertLaunchSelects(jdk17, jdk21, "17");
    }

    @Test
    @Timeout(240)
    @EnabledIf("net.sourceforge.icedteaweb.it.JdkPrecedenceJavawsLaunchIT#twoSameMajorSeventeenJdksAvailable")
    void javawsSelectsFirstOfTwoSameMajorJdksByPreferenceOrder() throws Exception {
        List<File> seventeen = ControlPanelTestSupport.findJdkHomesWithMajor(17, 2);
        File first = seventeen.get(0);
        File second = seventeen.get(1);
        assertThat(first.getAbsolutePath()).isNotEqualTo(second.getAbsolutePath());

        writeKnownJvms(first, second, JdkMatchStrategy.MAXIMUM);
        assertLaunchSelects(first, second, "17");
        JnlpLaunchTestSupport.stopLaunchedProcesses();

        writeKnownJvms(second, first, JdkMatchStrategy.MAXIMUM);
        assertLaunchSelects(second, first, "17");
    }

    static boolean java17PlusTwoMatchingJdksAvailable() throws Exception {
        return JnlpLaunchTestSupport.javawsAvailable()
                && JnlpLaunchTestSupport.sampleBuilt("java17-app")
                && ControlPanelTestSupport.findJdkHomeWithMajor(11) != null
                && ControlPanelTestSupport.findJdkHomeWithMajor(17) != null
                && ControlPanelTestSupport.findJdkHomeWithMajor(21) != null;
    }

    static boolean twoSameMajorSeventeenJdksAvailable() throws Exception {
        return JnlpLaunchTestSupport.javawsAvailable()
                && JnlpLaunchTestSupport.sampleBuilt("java17-app")
                && ControlPanelTestSupport.findJdkHomeWithMajor(11) != null
                && ControlPanelTestSupport.findJdkHomesWithMajor(17, 2).size() >= 2;
    }

    private static void writeKnownJvms(File first, File second, JdkMatchStrategy strategy) throws Exception {
        Properties props = ControlPanelTestSupport.loadDeploymentProperties();
        props.setProperty(DeploymentConfiguration.KEY_AUTODETECT_JDKS, "false");
        props.setProperty(KnownJvmStore.KEY_MATCH_STRATEGY, strategy.getConfigValue());
        props.setProperty("deployment.jdk.1", first.getAbsolutePath());
        props.setProperty("deployment.jdk.2", second.getAbsolutePath());
        props.setProperty(DeploymentConfiguration.KEY_JRE_DIR, first.getAbsolutePath());
        ControlPanelTestSupport.enableFileLoggingForIntegrationTests(props);
        ControlPanelTestSupport.writeDeploymentProperties(props);
        DeploymentConfiguration config = ControlPanelTestSupport.loadDeploymentConfiguration();
        assertThat(KnownJvmStore.getKnownJvmHomes(config))
                .isEqualTo(Arrays.asList(first.getAbsolutePath(), second.getAbsolutePath()));
    }

    private static void assertLaunchSelects(File expected, File notExpected, String expectedMajorPrefix)
            throws Exception {
        File starterJdk11 = ControlPanelTestSupport.findJdkHomeWithMajor(11);
        JnlpLaunchTestSupport.clearIcedTeaWebLogs();
        long startedAt = System.currentTimeMillis();
        Process process = JnlpLaunchTestSupport.launchJnlpViaJavaws("java17-app", 5, starterJdk11);
        try {
            String combined = waitForLaunchEvidence(process, startedAt, 90_000);
            String selected = selectedJvmHomeFromLog(combined);
            String runtimeHome = runtimeHomeFromSuccess(combined);

            assertThat(combined)
                    .containsPattern("(?i)Selected JVM for JNLP request \\[17\\+\\]")
                    .contains("Invoking main()")
                    .contains("ITW_INTEGRATION_SUCCESS");
            assertThat(selected)
                    .as("Selected JVM log path")
                    .isEqualTo(expected.getAbsolutePath());
            assertThat(selected)
                    .as("must not select the lower-preference JDK")
                    .isNotEqualTo(notExpected.getAbsolutePath());
            assertThat(runtimeHomeCompatible(runtimeHome, expected))
                    .as("app runtime java.home (%s) must belong to selected JDK (%s)",
                            runtimeHome, expected.getAbsolutePath())
                    .isTrue();
            assertThat(combined)
                    .as("success marker should report selected major")
                    .containsPattern("ITW_INTEGRATION_SUCCESS .*\\bjdk=" + Pattern.quote(expectedMajorPrefix));
        } finally {
            JnlpLaunchTestSupport.stopLaunchedProcesses();
        }
    }

    private static boolean runtimeHomeCompatible(String runtimeHome, File jdkHome) throws Exception {
        String runtime = new File(runtimeHome.trim()).getCanonicalFile().getAbsolutePath();
        String home = jdkHome.getCanonicalFile().getAbsolutePath();
        return runtime.equalsIgnoreCase(home)
                || runtime.regionMatches(true, 0, home, 0, home.length());
    }

    private static String waitForLaunchEvidence(Process process, long startedAt, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String combined = "";
        while (System.currentTimeMillis() < deadline) {
            String output = JnlpLaunchTestSupport.readProcessOutput(process, 500);
            String javantxLog = JnlpLaunchTestSupport.readJavantxLogSince(startedAt, 1_000);
            combined = output + "\n" + javantxLog;
            if (combined.contains("ITW_INTEGRATION_SUCCESS")
                    && SELECTED_JVM_HOME.matcher(combined).find()) {
                return combined;
            }
            if (!process.isAlive() && combined.contains("Selected JVM")) {
                return combined;
            }
            Thread.sleep(250);
        }
        return combined;
    }

    private static String selectedJvmHomeFromLog(String combined) {
        Matcher matcher = SELECTED_JVM_HOME.matcher(combined);
        assertThat(matcher.find())
                .as("expected Selected JVM log line with home path in parentheses")
                .isTrue();
        return matcher.group(1).trim();
    }

    private static String runtimeHomeFromSuccess(String combined) {
        Matcher matcher = SUCCESS_HOME.matcher(combined);
        assertThat(matcher.find())
                .as("expected ITW_INTEGRATION_SUCCESS with home=")
                .isTrue();
        return matcher.group(1).trim();
    }
}
