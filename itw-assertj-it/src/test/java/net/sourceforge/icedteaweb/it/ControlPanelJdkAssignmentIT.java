package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Properties;
import net.sourceforge.jnlp.controlpanel.ControlPanel;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.Robot;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

class ControlPanelJdkAssignmentIT {

    private static final String SAMPLE_JNLP_URL = "https://example.com/apps/demo.jnlp";
    private static final String SECOND_JNLP_URL = "https://example.com/apps/other.jnlp";

    private Robot robot;
    private FrameFixture window;
    private ControlPanel controlPanel;

    @BeforeEach
    void setUp() throws Exception {
        ControlPanelTestSupport.resetDeploymentConfig();
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
    }

    @Test
    @Timeout(90)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmSelectionIT#displayAvailable")
    void jdkAssignmentPersistsOnApply() throws Exception {
        File jdkHome = ControlPanelTestSupport.discoverValidJdkHomes().get(0);

        ControlPanelTestSupport.openJdkSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, jdkHome);
        ControlPanelTestSupport.clickApply(window, robot);

        ControlPanelTestSupport.openJdkAssignments(window);
        ControlPanelTestSupport.addJdkAssignment(window, robot, 0, SAMPLE_JNLP_URL);
        ControlPanelTestSupport.clickApply(window, robot);

        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty("deployment.jdk1.assignment1")).isEqualTo(SAMPLE_JNLP_URL);
        assertThat(saved.getProperty("deployment.jdk1.assignment2")).isNull();
    }

    @Test
    @Timeout(90)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmSelectionIT#displayAvailable")
    void secondAssignmentForSameJdkUsesIncrementedPropertyKey() throws Exception {
        File jdkHome = ControlPanelTestSupport.discoverValidJdkHomes().get(0);

        ControlPanelTestSupport.openJdkSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, jdkHome);
        ControlPanelTestSupport.clickApply(window, robot);

        ControlPanelTestSupport.openJdkAssignments(window);
        ControlPanelTestSupport.addJdkAssignment(window, robot, 0, SAMPLE_JNLP_URL);
        ControlPanelTestSupport.addJdkAssignment(window, robot, 1, SECOND_JNLP_URL);
        ControlPanelTestSupport.clickApply(window, robot);

        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty("deployment.jdk1.assignment1")).isEqualTo(SAMPLE_JNLP_URL);
        assertThat(saved.getProperty("deployment.jdk1.assignment2")).isEqualTo(SECOND_JNLP_URL);
    }

    @Test
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmSelectionIT#displayAvailable")
    void assignmentWithoutApplyIsNotPersisted() throws Exception {
        File jdkHome = ControlPanelTestSupport.discoverValidJdkHomes().get(0);

        ControlPanelTestSupport.openJdkSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, jdkHome);
        ControlPanelTestSupport.clickApply(window, robot);

        ControlPanelTestSupport.openJdkAssignments(window);
        ControlPanelTestSupport.addJdkAssignment(window, robot, 0, SAMPLE_JNLP_URL);

        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty("deployment.jdk1.assignment1")).isNull();
    }
}
