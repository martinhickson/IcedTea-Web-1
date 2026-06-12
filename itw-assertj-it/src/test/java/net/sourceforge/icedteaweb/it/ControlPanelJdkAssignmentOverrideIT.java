package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.swing.data.TableCell.row;

import java.io.File;
import net.sourceforge.jnlp.controlpanel.ControlPanel;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.Robot;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Verifies Control Panel Launch Selected runs the assigned JDK even when JNLP
 * {@code j2se version="21+"} and MAXIMUM strategy would pick a higher JDK.
 */
class ControlPanelJdkAssignmentOverrideIT {

    private Robot robot;
    private FrameFixture window;
    private ControlPanel controlPanel;

    @BeforeEach
    void setUp() throws Exception {
        ControlPanelTestSupport.resetDeploymentConfig();
        File jdk21 = JnlpLaunchTestSupport.jdkHome(21);
        File jdk25 = JnlpLaunchTestSupport.jdkHome(25);
        NzsdDeploymentConfigSupport.seedTwoKnownJdksWithMaximumStrategy(jdk21, jdk25);
        String jnlpUrl = JnlpLaunchTestSupport.jnlpUrl("java21-app").toExternalForm();
        NzsdDeploymentConfigSupport.assignJnlpToJdkIndex(jnlpUrl, 1, 1);

        robot = BasicRobot.robotWithCurrentAwtHierarchy();
        controlPanel = ControlPanelTestSupport.launchControlPanel();
        window = new FrameFixture(robot, controlPanel);
        window.show();
    }

    @AfterEach
    void tearDown() throws Exception {
        JnlpLaunchTestSupport.stopLaunchedProcesses();
        if (window != null) {
            window.cleanUp();
        }
        if (robot != null) {
            robot.cleanUp();
        }
        ControlPanelTestSupport.disposeControlPanel(controlPanel);
    }

    @Test
    @Timeout(180)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJdkAssignmentOverrideIT#overrideLaunchAvailable")
    void launchSelectedRunsAssignedJdkNotMaximumStrategySelection() throws Exception {
        ControlPanelTestSupport.openJdkAssignments(window);
        int rowIndex = ControlPanelTestSupport.findAssignmentRowByJnlpFragment(window, "java21-app");
        assertThat(rowIndex).isGreaterThanOrEqualTo(0);
        window.table("jdkAssignmentsTable").cell(row(rowIndex).column(0)).click();
        robot.waitForIdle();
        window.button("jdkAssignmentLaunchButton").click();
        window.dialog("jdkAssignmentLaunchOutputDialog").requireVisible();

        long launchStartedAt = System.currentTimeMillis();
        String output = ControlPanelTestSupport.waitForLaunchOutput(
                window, "ITW_INTEGRATION_SUCCESS", 90_000);
        assertThat(output).contains("ITW_INTEGRATION_SUCCESS");

        String javantxLog = JnlpLaunchTestSupport.readJavantxLogSince(launchStartedAt, 30_000);
        assertThat(javantxLog)
                .as("javantx log in %s", JnlpLaunchTestSupport.icedteaWebLogDir())
                .containsPattern("(?i)Selected JVM from JDK assignment")
                .contains(JnlpLaunchTestSupport.jdkHome(21).getAbsolutePath());
        assertThat(output).contains("jdk=21.");

        window.button("jdkAssignmentLaunchOutputOkButton").click();
        robot.waitForIdle();
        JnlpLaunchTestSupport.stopLaunchedProcesses();
    }

    static boolean overrideLaunchAvailable() {
        return ControlPanelJvmSelectionIT.displayAvailable()
                && JnlpLaunchTestSupport.javawsAvailable()
                && JnlpLaunchTestSupport.sampleBuilt("java21-app")
                && JnlpLaunchTestSupport.hasSampleJdk(21)
                && JnlpLaunchTestSupport.hasSampleJdk(25);
    }
}
