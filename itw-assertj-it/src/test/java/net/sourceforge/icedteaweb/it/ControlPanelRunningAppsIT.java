package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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

class ControlPanelRunningAppsIT {

    private Robot robot;
    private FrameFixture window;
    private ControlPanel controlPanel;

    @BeforeEach
    void setUp() throws Exception {
        ControlPanelTestSupport.resetDeploymentConfig();
        JnlpLaunchTestSupport.seedKnownJvmsFromAutodetect();
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
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelRunningAppsIT#runningAppsTestAvailable")
    void runningAppsTabShowsLaunchedJava17App() throws Exception {
        JnlpLaunchTestSupport.launchHeldApp("java17-app");
        RunningProcess running = JnlpLaunchTestSupport.waitForRunningApp("Java 17 bytecode", 60_000);
        assertThat(running).isNotNull();

        ControlPanelTestSupport.openRunningApps(window);
        window.button("runningAppsRefreshButton").click();
        robot.waitForIdle();

        window.panel("runningAppRow-" + running.getPid()).requireVisible();
        window.label("runningAppJvmLabel-" + running.getPid()).requireVisible();
        assertThat(window.label("runningAppsStatusLabel").target().getText()).contains("1");

        window.button("runningAppStop-" + running.getPid()).click();
        robot.waitForIdle();

        awaitEmptyRunningApps(Duration.ofSeconds(30));
    }

    @Test
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmSelectionIT#displayAvailable")
    void runningAppsTabShowsEmptyStateWhenNothingRunning() {
        ControlPanelTestSupport.openRunningApps(window);
        window.button("runningAppsRefreshButton").click();
        robot.waitForIdle();
        window.label("runningAppsEmptyLabel").requireVisible();
    }

    private void awaitEmptyRunningApps(Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            window.button("runningAppsRefreshButton").click();
            robot.waitForIdle();
            if (findEmptyLabel() != null) {
                return;
            }
            Thread.sleep(500);
        }
        window.label("runningAppsEmptyLabel").requireVisible();
    }

    private javax.swing.JLabel findEmptyLabel() {
        java.awt.Component[] components = window.panel("runningAppsListPanel").target().getComponents();
        for (java.awt.Component component : components) {
            if ("runningAppsEmptyLabel".equals(component.getName())) {
                return (javax.swing.JLabel) component;
            }
        }
        return null;
    }

    static boolean runningAppsTestAvailable() {
        return ControlPanelJvmSelectionIT.displayAvailable()
                && JnlpLaunchTestSupport.javawsAvailable()
                && JnlpLaunchTestSupport.sampleBuilt("java17-app");
    }
}
