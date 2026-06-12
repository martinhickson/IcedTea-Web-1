package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import net.sourceforge.jnlp.controlpanel.ControlPanel;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.Robot;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

class ControlPanelJdkAssignmentListIT {

    private Robot robot;
    private FrameFixture window;
    private ControlPanel controlPanel;

    @BeforeEach
    void setUp() throws Exception {
        ControlPanelTestSupport.seedAllBuiltSampleAssignments();
        robot = BasicRobot.robotWithCurrentAwtHierarchy();
        controlPanel = ControlPanelTestSupport.launchControlPanel();
        window = new FrameFixture(robot, controlPanel);
        window.show();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (window != null) {
            window.cleanUp();
        }
        if (robot != null) {
            robot.cleanUp();
        }
        ControlPanelTestSupport.disposeControlPanel(controlPanel);
        ControlPanelTestSupport.resetDeploymentConfig();
    }

    @Test
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJdkAssignmentListIT#samplesAvailable")
    void jdkAssignmentsListIncludesGuiSampleApp() throws Exception {
        ControlPanelTestSupport.openJdkAssignments(window);
        int rows = window.table("jdkAssignmentsTable").target().getRowCount();
        assertThat(rows).isGreaterThanOrEqualTo(4);

        StringBuilder urls = new StringBuilder();
        for (int row = 0; row < rows; row++) {
            urls.append(window.table("jdkAssignmentsTable").target().getValueAt(row, 0)).append('\n');
        }
        assertThat(urls.toString()).contains("java17-app");
        assertThat(urls.toString()).contains("java21-app");
        assertThat(urls.toString()).contains("java25-app");
        assertThat(urls.toString()).contains("gui-app");
    }

    static boolean samplesAvailable() {
        return ControlPanelJvmSelectionIT.displayAvailable()
                && JnlpLaunchTestSupport.sampleBuilt("java17-app")
                && JnlpLaunchTestSupport.sampleBuilt("java21-app")
                && JnlpLaunchTestSupport.sampleBuilt("java25-app")
                && JnlpLaunchTestSupport.sampleBuilt("gui-app");
    }
}
