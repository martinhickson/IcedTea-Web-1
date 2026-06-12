package net.sourceforge.icedteaweb.it;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.controlpanel.ControlPanel;
import org.assertj.swing.core.Robot;
import org.assertj.swing.finder.JFileChooserFinder;
import org.assertj.swing.fixture.FrameFixture;
import org.assertj.swing.fixture.JFileChooserFixture;
import static org.assertj.swing.data.TableCell.row;

final class ControlPanelTestSupport {

    private ControlPanelTestSupport() {
    }

    static File deploymentPropertiesFile() {
        String configHome = System.getenv("XDG_CONFIG_HOME");
        if (configHome == null || configHome.trim().isEmpty()) {
            configHome = System.getProperty("user.home") + File.separator + ".config";
        }
        return new File(configHome + File.separator + "icedtea-web", "deployment.properties");
    }

    static void resetDeploymentConfig() {
        File deploymentFile = deploymentPropertiesFile();
        if (deploymentFile.isFile() && !deploymentFile.delete()) {
            throw new IllegalStateException("Failed to delete " + deploymentFile);
        }
    }

    static Properties loadDeploymentProperties() throws Exception {
        File deploymentFile = deploymentPropertiesFile();
        Properties saved = new Properties();
        if (deploymentFile.isFile()) {
            try (FileInputStream in = new FileInputStream(deploymentFile)) {
                saved.load(in);
            }
        }
        return saved;
    }

    static List<File> discoverValidJdkHomes() throws Exception {
        List<File> valid = new ArrayList<>();
        for (String home : net.sourceforge.jnlp.util.JvmAutodetector.discoverValidJvmHomes()) {
            valid.add(new File(home).getCanonicalFile());
        }
        return valid;
    }

    static void openJdkSettings(FrameFixture window) {
        window.list("controlPanelSettingsList").selectItem("JDK Settings");
    }

    /** @deprecated use {@link #openJdkSettings(FrameFixture)} */
    static void openJvmSettings(FrameFixture window) {
        openJdkSettings(window);
    }

    static void openRunningApps(FrameFixture window) {
        window.list("controlPanelSettingsList").selectItem("Running Apps");
    }

    static void openJdkAssignments(FrameFixture window) {
        window.list("controlPanelSettingsList").selectItem("JDK Assignments");
    }

    static void openJvmTuning(FrameFixture window) {
        window.list("controlPanelSettingsList").selectItem("JVM Tuning");
    }

    static boolean settingsListContainsTab(FrameFixture window, String tabLabel) {
        for (int i = 0; i < window.list("controlPanelSettingsList").contents().length; i++) {
            if (tabLabel.equals(window.list("controlPanelSettingsList").contents()[i])) {
                return true;
            }
        }
        return false;
    }

    static void addJvmTuningEntry(FrameFixture window, Robot robot, int rowIndex, String jnlpUrl) {
        window.button("jvmTuningAddButton").click();
        robot.waitForIdle();
        window.table("jvmTuningTable").enterValue(row(rowIndex).column(0), jnlpUrl);
        robot.waitForIdle();
    }

    static void addJdkAssignment(FrameFixture window, Robot robot, int rowIndex, String jnlpUrl) {
        window.button("jdkAssignmentAddButton").click();
        robot.waitForIdle();
        window.table("jdkAssignmentsTable").enterValue(row(rowIndex).column(0), jnlpUrl);
        robot.waitForIdle();
    }

    static void addJdkViaChooser(Robot robot, FrameFixture window, File jdkHome) {
        int rowsBefore = window.table("jvmKnownTable").target().getRowCount();
        window.button("jvmAddButton").click();
        JFileChooserFixture fileChooser = JFileChooserFinder.findFileChooser()
                .withTimeout(5000)
                .using(robot);
        fileChooser.selectFile(jdkHome);
        fileChooser.approve();
        robot.waitForIdle();
        int rowsAfter = window.table("jvmKnownTable").target().getRowCount();
        if (rowsAfter <= rowsBefore) {
            for (int row = 0; row < rowsAfter; row++) {
                Object path = window.table("jvmKnownTable").target().getValueAt(row, 3);
                if (jdkHome.getAbsolutePath().equals(String.valueOf(path))) {
                    return;
                }
            }
        }
    }

    static void cancelJdkChooser(Robot robot, FrameFixture window) {
        window.button("jvmAddButton").click();
        JFileChooserFixture fileChooser = JFileChooserFinder.findFileChooser()
                .withTimeout(5000)
                .using(robot);
        fileChooser.cancel();
        robot.waitForIdle();
    }

    static void clickApply(FrameFixture window, Robot robot) {
        window.button("controlPanelApplyButton").click();
        robot.waitForIdle();
    }

    static void seedBuiltSampleAssignment(String sampleName, int jdkMajor) throws Exception {
        File jnlp = JnlpLaunchTestSupport.jnlpFile(sampleName);
        if (!jnlp.isFile()) {
            throw new IllegalStateException("Missing built sample: " + jnlp);
        }
        File jdkHome = findJdkHomeWithMajor(jdkMajor);
        if (jdkHome == null) {
            throw new IllegalStateException("No JDK " + jdkMajor + " available for sample " + sampleName);
        }
        Properties props = loadDeploymentProperties();
        int jdkIndex = 1;
        props.setProperty("deployment.jdk." + jdkIndex, jdkHome.getAbsolutePath());
        props.setProperty("deployment.jdk1.assignment1", jnlp.toURI().toURL().toExternalForm());
        writeDeploymentProperties(props);
    }

    static File findJdkHomeWithMajor(int major) throws Exception {
        if (JnlpLaunchTestSupport.hasSampleJdk(major)) {
            return JnlpLaunchTestSupport.jdkHome(major);
        }
        for (File home : discoverValidJdkHomes()) {
            if (net.sourceforge.jnlp.util.JvmTuningCapabilities.majorVersionOfJvmHome(
                    home.getAbsolutePath()) == major) {
                return home;
            }
        }
        return null;
    }

    private static void writeDeploymentProperties(Properties props) throws Exception {
        File deploymentFile = deploymentPropertiesFile();
        File parent = deploymentFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(deploymentFile)) {
            props.store(out, "itw-assertj-it");
        }
    }

    static String tablePathAt(FrameFixture window, int row) {
        return String.valueOf(window.table("jvmKnownTable").target().getValueAt(row, 3));
    }

    static ControlPanel launchControlPanel() throws Exception {
        AtomicReference<ControlPanel> panelRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        SwingUtilities.invokeAndWait(() -> {
            try {
                DeploymentConfiguration.move14AndOlderFilesTo15StructureCatched();
                DeploymentConfiguration config = new DeploymentConfiguration();
                config.load();
                try {
                    UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                } catch (Exception ignored) {
                    // not critical for tests
                }
                ControlPanel panel = new ControlPanel(config);
                panel.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
                java.awt.Dimension size = panel.getSize();
                int width = Math.min(size.width, screen.width - 40);
                int height = Math.min(size.height, screen.height - 40);
                panel.setSize(width, height);
                panel.setLocation(20, 20);
                panel.setVisible(true);
                panelRef.set(panel);
            } catch (Exception ex) {
                errorRef.set(ex);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for control panel to open");
        }
        if (errorRef.get() != null) {
            throw errorRef.get();
        }
        ControlPanel panel = panelRef.get();
        if (panel == null) {
            throw new IllegalStateException("Control panel was not created");
        }
        return panel;
    }

    static void disposeControlPanel(ControlPanel panel) throws Exception {
        if (panel == null) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            panel.dispose();
            latch.countDown();
        });
        latch.await(10, TimeUnit.SECONDS);
    }
}
