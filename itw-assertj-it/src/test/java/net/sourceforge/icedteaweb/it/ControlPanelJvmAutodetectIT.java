package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;
import java.util.Properties;
import net.sourceforge.jnlp.controlpanel.ControlPanel;
import net.sourceforge.jnlp.util.JvmAutodetector;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.Robot;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

class ControlPanelJvmAutodetectIT {

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
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmAutodetectIT#displayAvailable")
    void autodetectPopulatesTableWithoutAutoApply() throws Exception {
        List<String> expected = JvmAutodetector.discoverValidJvmHomes();
        assertThat(expected).isNotEmpty();

        ControlPanelTestSupport.openJdkSettings(window);
        window.button("jvmAutodetectButton").click();
        robot.waitForIdle();

        assertThat(window.table("jvmKnownTable").target().getRowCount()).isEqualTo(expected.size());
        Properties pending = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(pending.getProperty("deployment.jdk.1")).isNull();
    }

    @Test
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmAutodetectIT#displayAvailable")
    void autodetectPersistsAfterApply() throws Exception {
        ControlPanelTestSupport.openJdkSettings(window);
        window.button("jvmAutodetectButton").click();
        robot.waitForIdle();
        int rows = window.table("jvmKnownTable").target().getRowCount();
        assertThat(rows).isGreaterThan(0);

        ControlPanelTestSupport.clickApply(window, robot);

        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty("deployment.jdk.1")).isNotBlank();
        assertThat(saved.getProperty("deployment.jdk." + rows)).isNotBlank();
        assertThat(saved.getProperty("deployment.jdk." + (rows + 1))).isNull();
    }

    static boolean displayAvailable() {
        if (JvmAutodetector.discoverValidJvmHomes().isEmpty()) {
            return false;
        }
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            return true;
        }
        String display = System.getenv("DISPLAY");
        return display != null && !display.isBlank();
    }
}
