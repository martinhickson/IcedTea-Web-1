package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Properties;
import net.sourceforge.jnlp.config.ItwFeatureFlags;
import net.sourceforge.jnlp.config.KnownJvmTuningStore;
import net.sourceforge.jnlp.controlpanel.ControlPanel;
import net.sourceforge.jnlp.util.JvmTuningCapabilities;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.Robot;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.swing.data.TableCell.row;

class ControlPanelJvmTuningIT {

    private static final String SAMPLE_JNLP_URL = "https://example.com/apps/tuned.jnlp";

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
    @Timeout(120)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmTuningIT#tuningItAvailable")
    void jvmTuningPersistsGcAndSoftMaxOnApply() throws Exception {
        File jdkHome = ControlPanelTestSupport.discoverValidJdkHomes().get(0);
        int jdkMajor = JvmTuningCapabilities.majorVersionOfJvmHome(jdkHome.getAbsolutePath());

        ControlPanelTestSupport.openJdkSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, jdkHome);
        ControlPanelTestSupport.clickApply(window, robot);

        ControlPanelTestSupport.openJvmTuning(window);
        ControlPanelTestSupport.addJvmTuningEntry(window, robot, 0, SAMPLE_JNLP_URL);

        if (JvmTuningCapabilities.supportsGcType(jdkMajor, net.sourceforge.jnlp.config.JvmTuningGcType.G1GC)) {
            window.table("jvmTuningTable").cell(row(0).column(2)).click();
            robot.waitForIdle();
            window.comboBox().selectItem("G1GC");
            robot.waitForIdle();
        }

        if (JvmTuningCapabilities.supportsSoftMaxHeap(jdkMajor)) {
            window.table("jvmTuningTable").cell(row(0).column(4)).click();
            robot.waitForIdle();
            window.spinner().enterText("80");
            robot.waitForIdle();
        }

        ControlPanelTestSupport.clickApply(window, robot);

        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty(KnownJvmTuningStore.tuningKey(1, 1, "url"))).isEqualTo(SAMPLE_JNLP_URL);
        if (JvmTuningCapabilities.supportsGcType(jdkMajor, net.sourceforge.jnlp.config.JvmTuningGcType.G1GC)) {
            assertThat(saved.getProperty(KnownJvmTuningStore.tuningKey(1, 1, "gc"))).isEqualTo("G1GC");
        }
        if (JvmTuningCapabilities.supportsSoftMaxHeap(jdkMajor)) {
            assertThat(saved.getProperty(KnownJvmTuningStore.tuningKey(1, 1, "softMax"))).isEqualTo("80");
        }
    }

    @Test
    @Timeout(90)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmTuningIT#tuningItAvailable")
    void softMaxShowsUnavailableForJdkWithoutSupport() throws Exception {
        File jdkHome = findJdkHomeBelowMajor(13);
        if (jdkHome == null) {
            return;
        }

        ControlPanelTestSupport.openJdkSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, jdkHome);
        ControlPanelTestSupport.clickApply(window, robot);

        ControlPanelTestSupport.openJvmTuning(window);
        ControlPanelTestSupport.addJvmTuningEntry(window, robot, 0, SAMPLE_JNLP_URL);

        Object softMaxDisplay = window.table("jvmTuningTable").target().getValueAt(0, 4);
        assertThat(String.valueOf(softMaxDisplay)).contains("Unavailable");
    }

    private static File findJdkHomeBelowMajor(int major) throws Exception {
        for (File home : ControlPanelTestSupport.discoverValidJdkHomes()) {
            if (JvmTuningCapabilities.majorVersionOfJvmHome(home.getAbsolutePath()) < major) {
                return home;
            }
        }
        return null;
    }

    static boolean tuningItAvailable() {
        return ControlPanelJvmSelectionIT.displayAvailable() && ItwFeatureFlags.isJvmTuningTabEnabled();
    }
}
