package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.JdkMatchStrategy;
import net.sourceforge.jnlp.config.KnownJvmAssignmentStore;
import net.sourceforge.jnlp.config.KnownJvmStore;
import net.sourceforge.jnlp.util.JvmSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Verifies per-JNLP JDK assignment takes precedence over JNLP {@code j2se} version
 * and {@link JdkMatchStrategy#MAXIMUM} selection.
 */
class JvmAssignmentOverrideIT {

    @Test
    @EnabledIf("net.sourceforge.icedteaweb.it.JvmAssignmentOverrideIT#overrideScenarioAvailable")
    void maximumStrategySelectsHighestJdkWithoutAssignment() throws Exception {
        File jdk21 = JnlpLaunchTestSupport.jdkHome(21);
        File jdk25 = JnlpLaunchTestSupport.jdkHome(25);
        String jnlpUrl = JnlpLaunchTestSupport.jnlpUrl("java21-app").toExternalForm();

        DeploymentConfiguration config = configurationWithJdks(jdk21, jdk25);
        String requested = "21+";

        assertThat(JvmSelector.selectBestJvmHome(config, requested, jnlpUrl))
                .isEqualTo(jdk25.getAbsolutePath());
    }

    @Test
    @EnabledIf("net.sourceforge.icedteaweb.it.JvmAssignmentOverrideIT#overrideScenarioAvailable")
    void jdkAssignmentOverridesMaximumStrategyForJnlpUrl() throws Exception {
        File jdk21 = JnlpLaunchTestSupport.jdkHome(21);
        File jdk25 = JnlpLaunchTestSupport.jdkHome(25);
        String jnlpUrl = JnlpLaunchTestSupport.jnlpUrl("java21-app").toExternalForm();

        DeploymentConfiguration config = configurationWithJdks(jdk21, jdk25);
        KnownJvmAssignmentStore.setAssignments(config, Collections.singletonList(
                new KnownJvmAssignmentStore.JvmAssignment(1, 0, jnlpUrl)));

        assertThat(JvmSelector.selectBestJvmHome(config, "21+", jnlpUrl))
                .isEqualTo(jdk21.getAbsolutePath());
    }

    @Test
    @EnabledIf("net.sourceforge.icedteaweb.it.JvmAssignmentOverrideIT#overrideScenarioAvailable")
    void deploymentPropertiesAssignmentOverridesMaximumStrategy() throws Exception {
        File jdk21 = JnlpLaunchTestSupport.jdkHome(21);
        File jdk25 = JnlpLaunchTestSupport.jdkHome(25);
        String jnlpUrl = JnlpLaunchTestSupport.jnlpUrl("java21-app").toExternalForm();

        ControlPanelTestSupport.resetDeploymentConfig();
        ControlPanelTestSupport.seedTwoKnownJdksWithMaximumStrategy(jdk21, jdk25);
        ControlPanelTestSupport.assignJnlpToJdkIndex(jnlpUrl, 1, 1);

        assertThat(JvmSelectionTestSupport.selectBestJvmHomeForJnlp("21+", jnlpUrl))
                .isEqualTo(jdk21.getAbsolutePath());
    }

    private static DeploymentConfiguration configurationWithJdks(File jdk21, File jdk25) throws Exception {
        DeploymentConfiguration config = new DeploymentConfiguration();
        config.beginEditorSession();
        config.load();
        KnownJvmStore.setKnownJvmHomes(config, Arrays.asList(
                jdk21.getAbsolutePath(), jdk25.getAbsolutePath()));
        KnownJvmStore.setMatchStrategy(config, JdkMatchStrategy.MAXIMUM);
        // config.load() may include leftover assignments from earlier Control Panel ITs
        KnownJvmAssignmentStore.setAssignments(config, Collections.<KnownJvmAssignmentStore.JvmAssignment>emptyList());
        return config;
    }

    static boolean overrideScenarioAvailable() {
        return JnlpLaunchTestSupport.sampleBuilt("java21-app")
                && JnlpLaunchTestSupport.hasSampleJdk(21)
                && JnlpLaunchTestSupport.hasSampleJdk(25);
    }
}
