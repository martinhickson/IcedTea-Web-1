package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Cached JNLP apps appear in JDK Assignments with Default when no explicit assignments exist.
 */
class ControlPanelJdkAssignmentCacheListIT {

    private Robot robot;
    private FrameFixture window;
    private ControlPanel controlPanel;

    @BeforeEach
    void setUp() throws Exception {
        ControlPanelTestSupport.resetDeploymentConfig();
        ControlPanelTestSupport.resetCacheDir();
        ControlPanelTestSupport.seedKnownJvmsOnly();
        ControlPanelTestSupport.seedCachedJnlpDiscoveryEntries("java17-app", "java21-app");
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
        ControlPanelTestSupport.resetCacheDir();
    }

    @Test
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJdkAssignmentCacheListIT#samplesAvailable")
    void cachedJnlpAppsListedWithDefaultWhenNoAssignments() throws Exception {
        ControlPanelTestSupport.openJdkAssignments(window);
        robot.waitForIdle();

        javax.swing.JTable table = window.table("jdkAssignmentsTable").target();
        assertThat(table.getRowCount()).isGreaterThanOrEqualTo(2);

        StringBuilder urls = new StringBuilder();
        StringBuilder jdks = new StringBuilder();
        for (int row = 0; row < table.getRowCount(); row++) {
            urls.append(table.getValueAt(row, 0)).append('\n');
            jdks.append(table.getValueAt(row, 1)).append('\n');
        }
        assertThat(urls.toString()).contains("java17-app");
        assertThat(urls.toString()).contains("java21-app");
        assertThat(jdks.toString()).contains("Default");

        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.stringPropertyNames().stream()
                .anyMatch(name -> name.contains(".assignment"))).isFalse();
    }

    static boolean samplesAvailable() {
        return ControlPanelJvmSelectionIT.displayAvailable()
                && JnlpLaunchTestSupport.sampleBuilt("java17-app")
                && JnlpLaunchTestSupport.sampleBuilt("java21-app");
    }
}
