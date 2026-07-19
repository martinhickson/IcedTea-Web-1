package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.JdkMatchStrategy;
import net.sourceforge.jnlp.config.KnownJvmStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

class JdkAutodetectJavawsLaunchIT {

    @BeforeEach
    void resetConfig() throws Exception {
        ControlPanelTestSupport.resetDeploymentConfig();
        Properties props = new Properties();
        props.setProperty(DeploymentConfiguration.KEY_AUTODETECT_JDKS, "false");
        ControlPanelTestSupport.enableFileLoggingForIntegrationTests(props);
        ControlPanelTestSupport.writeDeploymentProperties(props);
    }

    @AfterEach
    void tearDown() {
        JnlpLaunchTestSupport.stopLaunchedProcesses();
        ControlPanelTestSupport.resetDeploymentConfig();
    }

    @Test
    @Timeout(120)
    @EnabledIf("net.sourceforge.icedteaweb.it.JdkAutodetectJavawsLaunchIT#exactJava18NoConfiguredJdkScenarioAvailable")
    void javawsFailsWhenExactJava18JnlpHasNoSuitableConfiguredJvm() throws Exception {
        File starterJdk11 = ControlPanelTestSupport.findJdkHomeWithMajor(11);
        File jdk21 = JnlpLaunchTestSupport.jdkHome(21);

        long startedAt = System.currentTimeMillis();
        Process process = JnlpLaunchTestSupport.launchJnlpViaJavaws("java18-app", 3, starterJdk11);
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);

        String output = JnlpLaunchTestSupport.readProcessOutput(process, 5_000);
        String javantxLog = JnlpLaunchTestSupport.readJavantxLogSince(startedAt, 10_000);
        String combined = output + "\n" + javantxLog;

        assertThat(finished).as("javaws should fail before launching the application").isTrue();
        assertThat(process.exitValue()).isNotZero();
        assertThat(combined)
                .contains("request [18]")
                .containsPattern("(?i)no suitable .*jvm");
        assertThat(combined)
                .doesNotContain("Invoking main()")
                .doesNotContain(jdk21.getAbsolutePath());
    }

    @Test
    @Timeout(240)
    @EnabledIf("net.sourceforge.icedteaweb.it.JdkAutodetectJavawsLaunchIT#java18PlusAutodetectScenarioAvailable")
    void javawsAutodetectsHigherJvmForJava18PlusJnlpWithoutConfiguredJvms() throws Exception {
        File starterJdk11 = ControlPanelTestSupport.findJdkHomeWithMajor(11);
        File jdk21 = JnlpLaunchTestSupport.jdkHome(21);

        Properties props = ControlPanelTestSupport.loadDeploymentProperties();
        props.setProperty(KnownJvmStore.KEY_MATCH_STRATEGY, JdkMatchStrategy.MINIMUM.getConfigValue());
        ControlPanelTestSupport.writeDeploymentProperties(props);

        long startedAt = System.currentTimeMillis();
        Process process = JnlpLaunchTestSupport.launchJnlpViaJavaws("java18-plus-app", 5, starterJdk11);
        process.waitFor(180, TimeUnit.SECONDS);

        String output = JnlpLaunchTestSupport.readProcessOutput(process, 5_000);
        String javantxLog = JnlpLaunchTestSupport.readJavantxLogSince(startedAt, 30_000);
        String combined = output + "\n" + javantxLog;
        assertThat(combined)
                .contains("18+")
                .containsPattern("(?i)Relaunching with configured JRE:")
                .contains(jdk21.getAbsolutePath())
                .contains("Invoking main()");
        if (!process.isAlive()) {
            assertThat(process.exitValue()).isZero();
        }
    }

    static boolean exactJava18NoConfiguredJdkScenarioAvailable() throws Exception {
        return JnlpLaunchTestSupport.javawsAvailable()
                && JnlpLaunchTestSupport.sampleBuilt("java18-app")
                && ControlPanelTestSupport.findJdkHomeWithMajor(11) != null
                && JnlpLaunchTestSupport.hasSampleJdk(21)
                && ControlPanelTestSupport.findJdkHomeWithMajor(18) == null;
    }

    static boolean java18PlusAutodetectScenarioAvailable() throws Exception {
        return JnlpLaunchTestSupport.javawsAvailable()
                && JnlpLaunchTestSupport.sampleBuilt("java18-plus-app")
                && ControlPanelTestSupport.findJdkHomeWithMajor(11) != null
                && JnlpLaunchTestSupport.hasSampleJdk(21);
    }
}
