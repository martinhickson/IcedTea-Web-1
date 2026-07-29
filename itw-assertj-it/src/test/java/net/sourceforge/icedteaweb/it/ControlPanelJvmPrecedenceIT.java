package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.JdkMatchStrategy;
import net.sourceforge.jnlp.config.KnownJvmStore;
import net.sourceforge.jnlp.controlpanel.ControlPanel;
import net.sourceforge.jnlp.util.JvmDescriptor;
import net.sourceforge.jnlp.util.JvmSelector;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.Robot;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Release-gated Control Panel coverage for JDK preference order.
 */
class ControlPanelJvmPrecedenceIT {

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
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmPrecedenceIT#atLeastTwoJdksAvailable")
    void legacyJreDirShowsAtTopWithNumberedJdksAndMoveDownPersistsOrder() throws Exception {
        List<File> jdks = ControlPanelTestSupport.discoverValidJdkHomes();
        assertThat(jdks.size()).isGreaterThanOrEqualTo(2);
        File legacy = jdks.get(0);
        File numbered = jdks.get(1);
        assertThat(legacy.getAbsolutePath()).isNotEqualTo(numbered.getAbsolutePath());

        seedKnownJvms(legacy, numbered);
        relaunchControlPanel();

        ControlPanelTestSupport.openJvmSettings(window);
        assertThat(window.table("jvmKnownTable").target().getRowCount()).isEqualTo(2);
        assertThat(ControlPanelTestSupport.tablePathAt(window, 0)).isEqualTo(legacy.getAbsolutePath());
        assertThat(ControlPanelTestSupport.tablePathAt(window, 1)).isEqualTo(numbered.getAbsolutePath());

        window.table("jvmKnownTable").selectRows(0);
        window.button("jvmMoveDownButton").click();
        robot.waitForIdle();

        assertThat(ControlPanelTestSupport.tablePathAt(window, 0)).isEqualTo(numbered.getAbsolutePath());
        assertThat(ControlPanelTestSupport.tablePathAt(window, 1)).isEqualTo(legacy.getAbsolutePath());

        ControlPanelTestSupport.clickApply(window, robot);
        assertPersistedOrder(numbered, legacy);

        // Relaunch must show the applied preference order (not only properties file).
        relaunchControlPanel();
        ControlPanelTestSupport.openJvmSettings(window);
        assertThat(ControlPanelTestSupport.tablePathAt(window, 0)).isEqualTo(numbered.getAbsolutePath());
        assertThat(ControlPanelTestSupport.tablePathAt(window, 1)).isEqualTo(legacy.getAbsolutePath());
    }

    @Test
    @Timeout(90)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmPrecedenceIT#atLeastTwoJdksAvailable")
    void moveUpAndBoundaryClicksPersistExpectedOrder() throws Exception {
        List<File> jdks = ControlPanelTestSupport.discoverValidJdkHomes();
        File first = jdks.get(0);
        File second = jdks.get(1);

        // Seed properties (not chooser) so CI cannot bypass the real Move Up/Down path.
        seedKnownJvms(first, second);
        relaunchControlPanel();

        ControlPanelTestSupport.openJvmSettings(window);
        assertThat(window.table("jvmKnownTable").target().getRowCount()).isEqualTo(2);

        // Move Up on top row is a no-op.
        window.table("jvmKnownTable").selectRows(0);
        window.button("jvmMoveUpButton").click();
        robot.waitForIdle();
        assertThat(ControlPanelTestSupport.tablePathAt(window, 0)).isEqualTo(first.getAbsolutePath());
        assertThat(ControlPanelTestSupport.tablePathAt(window, 1)).isEqualTo(second.getAbsolutePath());

        // Move Down on bottom row is a no-op.
        window.table("jvmKnownTable").selectRows(1);
        window.button("jvmMoveDownButton").click();
        robot.waitForIdle();
        assertThat(ControlPanelTestSupport.tablePathAt(window, 0)).isEqualTo(first.getAbsolutePath());
        assertThat(ControlPanelTestSupport.tablePathAt(window, 1)).isEqualTo(second.getAbsolutePath());

        // Move Up on bottom row promotes second to top.
        window.table("jvmKnownTable").selectRows(1);
        window.button("jvmMoveUpButton").click();
        robot.waitForIdle();
        assertThat(ControlPanelTestSupport.tablePathAt(window, 0)).isEqualTo(second.getAbsolutePath());
        assertThat(ControlPanelTestSupport.tablePathAt(window, 1)).isEqualTo(first.getAbsolutePath());

        ControlPanelTestSupport.clickApply(window, robot);
        assertPersistedOrder(second, first);

        DeploymentConfiguration config = ControlPanelTestSupport.loadDeploymentConfiguration();
        List<JvmDescriptor> described = JvmSelector.describeKnownJvms(config);
        assertThat(described).hasSize(2);
        assertThat(JvmSelector.selectBest(described, null, JdkMatchStrategy.EXACT).getHomePath())
                .isEqualTo(second.getAbsolutePath());

        int major = JvmSelector.parseMajor(JvmDescriptor.describe(second.getAbsolutePath()).getVersion());
        assertThat(major).isGreaterThan(0);
        assertThat(JvmSelector.selectBest(described, Integer.toString(major), JdkMatchStrategy.EXACT).getHomePath())
                .isEqualTo(second.getAbsolutePath());
        assertThat(JvmSelector.selectBest(described, Integer.toString(major) + "+", JdkMatchStrategy.MAXIMUM)
                .getHomePath())
                .isEqualTo(second.getAbsolutePath());
    }

    @Test
    @Timeout(90)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmPrecedenceIT#atLeastOneJdkAvailable")
    void refreshReloadsJdksWrittenToDeploymentPropertiesOnDisk() throws Exception {
        File jdk = ControlPanelTestSupport.discoverValidJdkHomes().get(0);

        ControlPanelTestSupport.openJvmSettings(window);
        assertThat(window.table("jvmKnownTable").target().getRowCount()).isZero();

        // Simulate launch-time autodetect writing while Settings is already open.
        File propsFile = ControlPanelTestSupport.deploymentPropertiesFile();
        Files.createDirectories(propsFile.getParentFile().toPath());
        Files.write(propsFile.toPath(), (
                "deployment.jre.dir=" + escapeProp(jdk.getAbsolutePath()) + "\n"
                + "deployment.jdk.1=" + escapeProp(jdk.getAbsolutePath()) + "\n"
                ).getBytes(StandardCharsets.UTF_8));

        window.button("jvmRefreshButton").click();
        robot.waitForIdle();

        assertThat(window.table("jvmKnownTable").target().getRowCount()).isEqualTo(1);
        assertThat(ControlPanelTestSupport.tablePathAt(window, 0)).isEqualTo(jdk.getAbsolutePath());
    }

    @Test
    @Timeout(90)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmPrecedenceIT#atLeastOneJdkAvailable")
    void legacyDuplicateOfNumberedJdkShowsSingleRow() throws Exception {
        File only = ControlPanelTestSupport.discoverValidJdkHomes().get(0);
        File propsFile = ControlPanelTestSupport.deploymentPropertiesFile();
        Files.createDirectories(propsFile.getParentFile().toPath());
        Files.write(propsFile.toPath(), (
                "deployment.jre.dir=" + escapeProp(only.getAbsolutePath()) + "\n"
                + "deployment.jdk.1=" + escapeProp(only.getAbsolutePath()) + "\n"
                ).getBytes(StandardCharsets.UTF_8));

        relaunchControlPanel();
        ControlPanelTestSupport.openJvmSettings(window);
        assertThat(window.table("jvmKnownTable").target().getRowCount()).isEqualTo(1);
        assertThat(ControlPanelTestSupport.tablePathAt(window, 0)).isEqualTo(only.getAbsolutePath());
        assertThat(KnownJvmStore.getKnownJvmHomes(ControlPanelTestSupport.loadDeploymentConfiguration()))
                .containsExactly(only.getAbsolutePath());
    }

    @Test
    @Timeout(120)
    @EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmPrecedenceIT#correttoAndTemurinAvailable")
    void autodetectPlacesCorrettoBeforeTemurinInPreferenceList() throws Exception {
        ControlPanelTestSupport.openJvmSettings(window);
        window.button("jvmAutodetectButton").click();
        robot.waitForIdle();
        ControlPanelTestSupport.clickApply(window, robot);

        List<String> homes = KnownJvmStore.getKnownJvmHomes(
                ControlPanelTestSupport.loadDeploymentConfiguration());
        int firstCorretto = indexOfVendor(homes, "corretto");
        int firstTemurin = indexOfVendor(homes, "temurin", "adoptium");
        assertThat(firstCorretto).as("Corretto must be present after autodetect").isGreaterThanOrEqualTo(0);
        assertThat(firstTemurin).as("Temurin/Adoptium must be present after autodetect").isGreaterThanOrEqualTo(0);
        assertThat(JvmDescriptor.describe(homes.get(firstCorretto)).getFlavour().toLowerCase(Locale.ROOT))
                .contains("corretto");
        String temurinFlavour = JvmDescriptor.describe(homes.get(firstTemurin)).getFlavour()
                .toLowerCase(Locale.ROOT);
        assertThat(temurinFlavour.contains("temurin") || temurinFlavour.contains("adoptium"))
                .as("Temurin flavour should mention temurin/adoptium, was: %s", temurinFlavour)
                .isTrue();
        assertThat(firstCorretto)
                .as("autodetect preference: Corretto before Temurin")
                .isLessThan(firstTemurin);

        // Newly discovered homes: Corretto 17 before Corretto 21; Temurin after Correttos.
        List<Integer> correttoMajorsInOrder = majorsForVendor(homes, "corretto");
        assertThat(correttoMajorsInOrder)
                .as("need Corretto 17 and 21 installed for full autodetect rank check")
                .contains(17, 21);
        assertThat(correttoMajorsInOrder.indexOf(17))
                .as("autodetect preference: Corretto 17 before Corretto 21")
                .isLessThan(correttoMajorsInOrder.indexOf(21));

        List<Integer> temurinMajorsInOrder = majorsForVendor(homes, "temurin", "adoptium");
        assertThat(temurinMajorsInOrder)
                .as("Temurin 17 and/or 21 should be discovered")
                .isNotEmpty();
        if (temurinMajorsInOrder.contains(17) && temurinMajorsInOrder.contains(21)) {
            assertThat(temurinMajorsInOrder.indexOf(17))
                    .as("autodetect preference: Temurin 17 before Temurin 21")
                    .isLessThan(temurinMajorsInOrder.indexOf(21));
        }
    }

    static boolean displayAvailable() {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            return true;
        }
        String display = System.getenv("DISPLAY");
        return display != null && !display.isBlank();
    }

    static boolean atLeastOneJdkAvailable() throws Exception {
        return displayAvailable() && !ControlPanelTestSupport.discoverValidJdkHomes().isEmpty();
    }

    static boolean atLeastTwoJdksAvailable() throws Exception {
        return displayAvailable() && ControlPanelTestSupport.discoverValidJdkHomes().size() >= 2;
    }

    static boolean correttoAndTemurinAvailable() throws Exception {
        if (!displayAvailable()) {
            return false;
        }
        boolean corretto = false;
        boolean temurin = false;
        boolean corretto17 = false;
        boolean corretto21 = false;
        for (File home : ControlPanelTestSupport.discoverValidJdkHomes()) {
            String path = home.getAbsolutePath();
            JvmDescriptor desc = JvmDescriptor.describe(path);
            String marker = (desc.getFlavour() + " " + path).toLowerCase(Locale.ROOT);
            int major = JvmSelector.parseMajor(desc.getVersion());
            if (marker.contains("corretto")) {
                corretto = true;
                if (major == 17) {
                    corretto17 = true;
                }
                if (major == 21) {
                    corretto21 = true;
                }
            }
            if (marker.contains("temurin") || marker.contains("adoptium")) {
                temurin = true;
            }
        }
        return corretto && temurin && corretto17 && corretto21;
    }

    private void seedKnownJvms(File first, File second) throws Exception {
        File propsFile = ControlPanelTestSupport.deploymentPropertiesFile();
        Files.createDirectories(propsFile.getParentFile().toPath());
        Files.write(propsFile.toPath(), (
                "deployment.jre.dir=" + escapeProp(first.getAbsolutePath()) + "\n"
                + "deployment.jdk.1=" + escapeProp(first.getAbsolutePath()) + "\n"
                + "deployment.jdk.2=" + escapeProp(second.getAbsolutePath()) + "\n"
                ).getBytes(StandardCharsets.UTF_8));
    }

    private static void assertPersistedOrder(File first, File second) throws Exception {
        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty("deployment.jdk.1")).isEqualTo(first.getAbsolutePath());
        assertThat(saved.getProperty("deployment.jdk.2")).isEqualTo(second.getAbsolutePath());
        assertThat(saved.getProperty("deployment.jre.dir")).isEqualTo(first.getAbsolutePath());
        assertThat(KnownJvmStore.getKnownJvmHomes(ControlPanelTestSupport.loadDeploymentConfiguration()))
                .containsExactly(first.getAbsolutePath(), second.getAbsolutePath());
    }

    private void relaunchControlPanel() throws Exception {
        window.cleanUp();
        window = null;
        ControlPanelTestSupport.disposeControlPanel(controlPanel);
        controlPanel = ControlPanelTestSupport.launchControlPanel();
        window = new FrameFixture(robot, controlPanel);
        window.show();
    }

    private static int indexOfVendor(List<String> homes, String... tokens) {
        for (int i = 0; i < homes.size(); i++) {
            if (matchesVendor(homes.get(i), tokens)) {
                return i;
            }
        }
        return -1;
    }

    private static List<Integer> majorsForVendor(List<String> homes, String... tokens) {
        List<Integer> majors = new ArrayList<>();
        for (String home : homes) {
            if (matchesVendor(home, tokens)) {
                majors.add(JvmSelector.parseMajor(JvmDescriptor.describe(home).getVersion()));
            }
        }
        return majors;
    }

    private static boolean matchesVendor(String home, String... tokens) {
        JvmDescriptor desc = JvmDescriptor.describe(home);
        String marker = (desc.getFlavour() + " " + home).toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (marker.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String escapeProp(String value) {
        return value.replace("\\", "\\\\");
    }
}
