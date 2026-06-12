package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * End-to-end via normal {@code javaws}: JDK assignment is applied when resolving the
 * JNLP {@code j2se} JVM during the initial launch phase.
 *
 * <p>Assertions read ITW log files under {@code $XDG_CONFIG_HOME/icedtea-web/log/}:
 * {@code itw-javantx-*.log} for JVM selection, {@code itw-clienta-*.log} for app runtime.
 */
class JdkAssignmentJavawsLaunchIT {

    @BeforeEach
    void resetConfig() throws Exception {
        ControlPanelTestSupport.resetDeploymentConfig();
    }

    @AfterEach
    void stopApps() {
        JnlpLaunchTestSupport.stopLaunchedProcesses();
    }

    @Test
    @Timeout(240)
    @EnabledIf("net.sourceforge.icedteaweb.it.JdkAssignmentJavawsLaunchIT#overrideScenarioAvailable")
    void javawsAppliesJdkAssignmentOverJnlpJ2seAndMaximumStrategy() throws Exception {
        File jdk21 = JnlpLaunchTestSupport.jdkHome(21);
        File jdk25 = JnlpLaunchTestSupport.jdkHome(25);

        NzsdDeploymentConfigSupport.seedTwoKnownJdksWithMaximumStrategy(jdk21, jdk25);
        String jnlpUrl = JnlpLaunchTestSupport.jnlpUrl("java21-app").toExternalForm();
        NzsdDeploymentConfigSupport.assignJnlpToJdkIndex(jnlpUrl, 1, 1);

        long startedAt = System.currentTimeMillis();
        Process process = JnlpLaunchTestSupport.launchJnlpViaJavaws("java21-app", 3, jdk25);
        process.waitFor(180, TimeUnit.SECONDS);

        String javantxLog = JnlpLaunchTestSupport.readJavantxLogSince(startedAt, 30_000);
        assertThat(javantxLog)
                .as("javantx log in %s", JnlpLaunchTestSupport.icedteaWebLogDir())
                .isNotBlank()
                .containsPattern("(?i)Selected JVM from JDK assignment")
                .contains(jdk21.getAbsolutePath())
                .containsPattern("(?i)Relaunching with configured JRE:")
                .contains("Invoking main()");
        if (!process.isAlive()) {
            assertThat(process.exitValue()).isZero();
        }
    }

    @Test
    @Timeout(240)
    @EnabledIf("net.sourceforge.icedteaweb.it.JdkAssignmentJavawsLaunchIT#overrideScenarioAvailable")
    void javawsWithoutAssignmentUsesMaximumStrategyForJnlpJ2se() throws Exception {
        File jdk21 = JnlpLaunchTestSupport.jdkHome(21);
        File jdk25 = JnlpLaunchTestSupport.jdkHome(25);

        NzsdDeploymentConfigSupport.seedTwoKnownJdksWithMaximumStrategy(jdk21, jdk25);

        long startedAt = System.currentTimeMillis();
        Process process = JnlpLaunchTestSupport.launchJnlpViaJavaws("java21-app", 3, jdk21);
        process.waitFor(180, TimeUnit.SECONDS);

        String javantxLog = JnlpLaunchTestSupport.readJavantxLogSince(startedAt, 30_000);
        assertThat(javantxLog)
                .containsPattern("(?i)Selected JVM for JNLP request \\[21\\+\\] using Maximum strategy")
                .contains(jdk25.getAbsolutePath())
                .containsPattern("(?i)Relaunching with configured JRE:")
                .contains("Invoking main()");
        if (!process.isAlive()) {
            assertThat(process.exitValue()).isZero();
        }
    }

    static boolean overrideScenarioAvailable() {
        return JnlpLaunchTestSupport.javawsAvailable()
                && JnlpLaunchTestSupport.sampleBuilt("java21-app")
                && JnlpLaunchTestSupport.hasSampleJdk(21)
                && JnlpLaunchTestSupport.hasSampleJdk(25);
    }
}
