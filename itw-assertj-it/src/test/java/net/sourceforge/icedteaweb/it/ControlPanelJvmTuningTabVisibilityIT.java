package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import net.sourceforge.jnlp.config.ItwFeatureFlags;
import net.sourceforge.jnlp.controlpanel.ControlPanel;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.Robot;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

class ControlPanelJvmTuningTabVisibilityIT {

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
    @Timeout(30)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmTuningTabVisibilityIT#tuningTabDisabled")
    void jvmTuningTabHiddenWhenEnvUnset() {
        assertThat(ControlPanelTestSupport.settingsListContainsTab(window, "JVM Tuning")).isFalse();
    }

    @Test
    @Timeout(30)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmTuningTabVisibilityIT#tuningTabEnabled")
    void jvmTuningTabVisibleWhenEnvSet() {
        assertThat(ControlPanelTestSupport.settingsListContainsTab(window, "JVM Tuning")).isTrue();
    }

    static boolean tuningTabDisabled() {
        return ControlPanelJvmSelectionIT.displayAvailable() && !ItwFeatureFlags.isJvmTuningTabEnabled();
    }

    static boolean tuningTabEnabled() {
        return ControlPanelJvmSelectionIT.displayAvailable() && ItwFeatureFlags.isJvmTuningTabEnabled();
    }
}
