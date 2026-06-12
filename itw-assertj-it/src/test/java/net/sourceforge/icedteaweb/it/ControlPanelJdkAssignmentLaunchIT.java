package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import net.sourceforge.jnlp.controlpanel.ControlPanel;
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
        assertThat(window.table("jdkAssignmentsTable").target().getRowCount()).isGreaterThan(0);
        window.table("jdkAssignmentsTable").cell(row(0).column(0)).click();
        robot.waitForIdle();
        window.button("jdkAssignmentLaunchButton").click();
        robot.waitForIdle();

        RunningProcess running = JnlpLaunchTestSupport.waitForRunningApp("Java 17 bytecode", 90_000);
        assertThat(running).isNotNull();

        ControlPanelTestSupport.openRunningApps(window);
        window.button("runningAppsRefreshButton").click();
        robot.waitForIdle();
        window.panel("runningAppRow-" + running.getPid()).requireVisible();

        JnlpLaunchTestSupport.stopLaunchedProcesses();
    }

    static boolean launchSelectedAvailable() {
        return ControlPanelJvmSelectionIT.displayAvailable()
                && JnlpLaunchTestSupport.javawsAvailable()
                && JnlpLaunchTestSupport.sampleBuilt("java17-app")
                && JnlpLaunchTestSupport.hasSampleJdk(17);
    }
}
