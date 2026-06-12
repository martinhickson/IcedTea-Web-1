package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;
import java.util.Properties;
import net.sourceforge.jnlp.config.KnownJvmStore;
import net.sourceforge.jnlp.controlpanel.ControlPanel;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.Robot;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

class ControlPanelJvmSelectionIT {

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
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmSelectionIT#displayAvailable")
    void addKnownJdkAndApplyPersistsNumberedProperties() throws Exception {
        File jdkHome = ControlPanelTestSupport.discoverValidJdkHomes().get(0);

        ControlPanelTestSupport.openJvmSettings(window);
        assertThat(window.table("jvmKnownTable").target().getRowCount()).isZero();

        ControlPanelTestSupport.addJdkViaChooser(robot, window, jdkHome);
        assertThat(window.table("jvmKnownTable").target().getRowCount()).isEqualTo(1);
        assertThat(ControlPanelTestSupport.tablePathAt(window, 0)).isEqualTo(jdkHome.getAbsolutePath());

        ControlPanelTestSupport.clickApply(window, robot);

        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty("deployment.jdk.1")).isEqualTo(jdkHome.getAbsolutePath());
        assertThat(saved.getProperty("deployment.jre.dir")).isEqualTo(jdkHome.getAbsolutePath());
        assertThat(saved.getProperty("deployment.jdk.2")).isNull();
    }

    @Test
    @Timeout(90)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmSelectionIT#atLeastThreeJdksAvailable")
    void secondAndThirdJdkEntriesPersistInOrder() throws Exception {
        List<File> jdks = JvmSelectionTestSupport.discoverJdksWithDistinctMajors(3);
        assertThat(jdks.size()).isGreaterThanOrEqualTo(3);
        File first = jdks.get(0);
        File second = jdks.get(1);
        File third = jdks.get(2);

        ControlPanelTestSupport.openJvmSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, first);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, second);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, third);

        assertThat(window.table("jvmKnownTable").target().getRowCount()).isEqualTo(3);
        assertThat(ControlPanelTestSupport.tablePathAt(window, 0)).isEqualTo(first.getAbsolutePath());
        assertThat(ControlPanelTestSupport.tablePathAt(window, 1)).isEqualTo(second.getAbsolutePath());
        assertThat(ControlPanelTestSupport.tablePathAt(window, 2)).isEqualTo(third.getAbsolutePath());

        ControlPanelTestSupport.clickApply(window, robot);

        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty("deployment.jdk.1")).isEqualTo(first.getAbsolutePath());
        assertThat(saved.getProperty("deployment.jdk.2")).isEqualTo(second.getAbsolutePath());
        assertThat(saved.getProperty("deployment.jdk.3")).isEqualTo(third.getAbsolutePath());
        assertThat(saved.getProperty("deployment.jre.dir")).isEqualTo(first.getAbsolutePath());
        assertThat(saved.getProperty("deployment.jdk.4")).isNull();
    }

    @Test
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmSelectionIT#displayAvailable")
    void duplicateSecondJdkAddIsIgnored() throws Exception {
        File jdkHome = ControlPanelTestSupport.discoverValidJdkHomes().get(0);

        ControlPanelTestSupport.openJvmSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, jdkHome);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, jdkHome);

        assertThat(window.table("jvmKnownTable").target().getRowCount()).isEqualTo(1);
        assertThat(ControlPanelTestSupport.tablePathAt(window, 0)).isEqualTo(jdkHome.getAbsolutePath());

        ControlPanelTestSupport.clickApply(window, robot);

        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty("deployment.jdk.1")).isEqualTo(jdkHome.getAbsolutePath());
        assertThat(saved.getProperty("deployment.jdk.2")).isNull();
    }

    @Test
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmSelectionIT#displayAvailable")
    void cancelFileChooserDoesNotAddJdk() throws Exception {
        ControlPanelTestSupport.openJvmSettings(window);
        assertThat(window.table("jvmKnownTable").target().getRowCount()).isZero();

        ControlPanelTestSupport.cancelJdkChooser(robot, window);

        assertThat(window.table("jvmKnownTable").target().getRowCount()).isZero();

        assertThat(ControlPanelTestSupport.deploymentPropertiesFile()).doesNotExist();
    }

    @Test
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmSelectionIT#displayAvailable")
    void closingWithoutApplyDiscardsAddedJdks() throws Exception {
        File jdkHome = ControlPanelTestSupport.discoverValidJdkHomes().get(0);

        ControlPanelTestSupport.openJvmSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, jdkHome);
        assertThat(window.table("jvmKnownTable").target().getRowCount()).isEqualTo(1);

        window.cleanUp();
        window = null;
        ControlPanelTestSupport.disposeControlPanel(controlPanel);
        controlPanel = null;

        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty("deployment.jdk.1")).isNull();
        assertThat(saved.getProperty("deployment.jre.dir")).isNull();
    }

    @Test
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmSelectionIT#displayAvailable")
    void matchStrategyPersistsOnApply() throws Exception {
        ControlPanelTestSupport.openJdkSettings(window);
        JvmSelectionTestSupport.selectMatchStrategy(window, "Minimum");
        ControlPanelTestSupport.clickApply(window, robot);

        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty(KnownJvmStore.KEY_MATCH_STRATEGY)).isEqualTo("Minimum");
    }

    static boolean displayAvailable() {
        String display = System.getenv("DISPLAY");
        return display != null && !display.isBlank();
    }

    static boolean atLeastThreeJdksAvailable() throws Exception {
        return displayAvailable() && JvmSelectionTestSupport.discoverJdksWithDistinctMajors(3).size() >= 3;
    }
}
