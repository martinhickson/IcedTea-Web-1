package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import net.sourceforge.jnlp.controlpanel.ControlPanel;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport.RunningProcess;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.Robot;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.swing.data.TableCell.row;

class ControlPanelJdkAssignmentLaunchIT {

    private Robot robot;
    private FrameFixture window;
    private ControlPanel controlPanel;

    @BeforeEach
    void setUp() throws Exception {
        ControlPanelTestSupport.resetDeploymentConfig();
        ControlPanelTestSupport.resetCacheDir();
        ControlPanelTestSupport.seedBuiltSampleAssignment("java17-app", 17);
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
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJdkAssignmentLaunchIT#launchSelectedAvailable")
    void launchSelectedStartsAssignedJava17Sample() throws Exception {
        ControlPanelTestSupport.openJdkAssignments(window);
        int rowIndex = ControlPanelTestSupport.findAssignmentRowByJnlpFragment(window, "java17-app");
        assertThat(rowIndex).isGreaterThanOrEqualTo(0);
        window.table("jdkAssignmentsTable").cell(row(rowIndex).column(0)).click();
        robot.waitForIdle();
        window.button("jdkAssignmentLaunchButton").click();
        window.dialog("jdkAssignmentLaunchOutputDialog").requireVisible();
        String output = ControlPanelTestSupport.waitForLaunchOutput(
                window, "ITW_INTEGRATION_SUCCESS", 90_000);
        assertThat(output)
                .as("launch dialog")
                .containsPattern("(?i)icedtea-web version:")
                .contains("ITW_INTEGRATION_SUCCESS");

        RunningProcess running = JnlpLaunchTestSupport.waitForRunningApp("Java 17 bytecode", 30_000);
        assertThat(running)
                .as("running apps after launch. dialog:%n%s%nlisted:%n%s", output, runningAppDump())
                .isNotNull();
        window.button("jdkAssignmentLaunchOutputOkButton").click();
        robot.waitForIdle();

        ControlPanelTestSupport.openRunningApps(window);
        window.button("runningAppsRefreshButton").click();
        robot.waitForIdle();
        window.panel("runningAppRow-" + running.getPid()).requireVisible();

        JnlpLaunchTestSupport.stopLaunchedProcesses();
    }

    private static String runningAppDump() {
        StringBuilder seen = new StringBuilder();
        for (RunningProcess candidate : JnlpRunningProcessSupport.listRunningJnlpProcesses()) {
            seen.append(candidate.getPid()).append(' ')
                    .append(candidate.getAppTitle()).append(' ')
                    .append(candidate.getJnlpPath()).append('\n');
        }
        return seen.toString();
    }

    static boolean launchSelectedAvailable() {
        return ControlPanelJvmSelectionIT.displayAvailable()
                && JnlpLaunchTestSupport.javawsAvailable()
                && JnlpLaunchTestSupport.sampleBuilt("java17-app")
                && JnlpLaunchTestSupport.hasSampleJdk(17);
    }
}
