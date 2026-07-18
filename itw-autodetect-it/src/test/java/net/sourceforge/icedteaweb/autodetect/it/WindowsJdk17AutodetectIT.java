package net.sourceforge.icedteaweb.autodetect.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Windows-only: host a JDK-17 JNLP on Undertow, launch via {@code javaws.exe},
 * press Autodetect / Apply, verify the app starts, then confirm the control panel
 * lists the autodetected JDK 17.
 *
 * <p>Expected to fail until Windows JDK autodetect correctly finds JDK 17.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsJdk17AutodetectIT {

    private static final String JNLP_NAME = "java17-autodetect.jnlp";
    private static final int TIMEOUT_SECONDS = Integer.getInteger("itw.launch.timeout.seconds", 180);

    private UndertowJnlpServer server;
    private Path marker;
    private Process javawsProcess;
    private Robot robot;
    private FrameFixture controlPanelWindow;
    private ControlPanel controlPanel;

    @BeforeEach
    void setUp() throws Exception {
        AutodetectTestSupport.resetDeploymentConfig();
        marker = Files.createTempDirectory("itw-autodetect-marker").resolve("success.marker");
        server = UndertowJnlpServer.start(
                AutodetectTestSupport.sampleJar().toPath(), marker, JNLP_NAME);
    }

    @AfterEach
    void tearDown() throws Exception {
        AutodetectTestSupport.stopLaunchedProcesses();
        if (controlPanelWindow != null) {
            controlPanelWindow.cleanUp();
        }
        if (robot != null) {
            robot.cleanUp();
        }
        AutodetectTestSupport.disposeControlPanel(controlPanel);
        if (server != null) {
            server.close();
        }
        if (marker != null && marker.getParent() != null) {
            try {
                Files.deleteIfExists(marker);
                Files.deleteIfExists(marker.getParent());
            } catch (Exception ignored) {
                // best-effort
            }
        }
        AutodetectTestSupport.resetDeploymentConfig();
    }

    @Test
    @Timeout(300)
    @EnabledIf("net.sourceforge.icedteaweb.autodetect.it.WindowsJdk17AutodetectIT#scenarioAvailable")
    void javawsAutodetectFindsJdk17StartsAppAndShowsInControlPanel() throws Exception {
        File starterJdk11 = AutodetectTestSupport.jdkHome(11);
        File jdk17 = AutodetectTestSupport.jdkHome(17);
        String jnlpUrl = server.jnlpUrl(JNLP_NAME);

        javawsProcess = AutodetectTestSupport.launchJavaws(jnlpUrl, starterJdk11);

        // Dialog clicks happen in-process via AutodetectDialogAgent (javaagent).
        // Exact j2se "17" shows "Apply detected JDK"; missing JDK shows Autodetect.
        boolean started = AutodetectTestSupport.waitForMarker(marker, TIMEOUT_SECONDS);
        assertThat(started)
                .as("JNLP app should start after Autodetect finds JDK 17 and Apply is pressed")
                .isTrue();

        String markerBody = Files.readString(marker, StandardCharsets.UTF_8);
        assertThat(markerBody.toLowerCase(Locale.ROOT))
                .contains("jdk=")
                .doesNotContain("jdk=1.8")
                .doesNotContain("jdk=11");
        assertThat(markerBody)
                .as("app should report a Java 17+ runtime")
                .containsPattern("jdk=1?7(\\.|$)|jdk=1[89]|jdk=2[0-9]");

        // Prefer a direct match on the configured JDK 17 home when present in the marker.
        String jdk17Home = jdk17.getCanonicalFile().getAbsolutePath();
        Properties saved = AutodetectTestSupport.loadDeploymentProperties();
        assertThat(AutodetectTestSupport.deploymentContainsJdkMajor(17))
                .as("deployment.properties should contain an autodetected JDK 17 after Apply; props=%s",
                        saved)
                .isTrue();

        robot = BasicRobot.robotWithCurrentAwtHierarchy();
        controlPanel = AutodetectTestSupport.launchControlPanel();
        controlPanelWindow = new FrameFixture(robot, controlPanel);
        controlPanelWindow.show();
        controlPanelWindow.list("controlPanelSettingsList").selectItem("JDK Settings");
        robot.waitForIdle();

        int rows = controlPanelWindow.table("jvmKnownTable").target().getRowCount();
        assertThat(rows).as("control panel known JVMs table should list autodetected JDKs").isGreaterThan(0);

        boolean tableHasJdk17 = false;
        for (int row = 0; row < rows; row++) {
            Object path = controlPanelWindow.table("jvmKnownTable").target().getValueAt(row, 3);
            if (path != null && path.toString().equalsIgnoreCase(jdk17Home)) {
                tableHasJdk17 = true;
                break;
            }
            if (path != null) {
                File home = new File(path.toString());
                if (home.isDirectory()
                        && net.sourceforge.jnlp.util.JvmAutodetector.majorVersionOfJvmHome(
                                home.getAbsolutePath()) == 17) {
                    tableHasJdk17 = true;
                    break;
                }
            }
        }
        assertThat(tableHasJdk17)
                .as("control panel JDK Settings should show autodetected JDK 17 (%s)", jdk17Home)
                .isTrue();

        if (javawsProcess != null && javawsProcess.isAlive()) {
            javawsProcess.destroyForcibly();
        }
    }

    static boolean scenarioAvailable() {
        String agent = System.getProperty("itw.dialog.agent.jar");
        return AutodetectTestSupport.javawsAvailable()
                && AutodetectTestSupport.sampleBuilt()
                && AutodetectTestSupport.jdkHomeAvailable(11)
                && AutodetectTestSupport.jdkHomeAvailable(17)
                && agent != null
                && new File(agent).isFile();
    }
}
