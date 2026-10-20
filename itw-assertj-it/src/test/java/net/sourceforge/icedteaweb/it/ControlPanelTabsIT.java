package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Properties;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.KnownJvmStore;
import net.sourceforge.jnlp.controlpanel.ControlPanel;
import net.sourceforge.jnlp.util.JvmDescriptor;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport.RunningProcess;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.Robot;
import org.assertj.swing.exception.ComponentLookupException;
import org.assertj.swing.exception.WaitTimedOutError;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Framebuffer checks for Running Apps, JDK assignments, and JDK settings
 * beyond add-and-apply.
 */
@EnabledIf("net.sourceforge.icedteaweb.it.ControlPanelJvmSelectionIT#displayAvailable")
class ControlPanelTabsIT {

    private static final String SAMPLE_JNLP_URL = "https://example.com/apps/remove-me.jnlp";

    private Robot robot;
    private FrameFixture window;
    private ControlPanel controlPanel;

    @BeforeEach
    void setUp() throws Exception {
        ControlPanelTestSupport.resetDeploymentConfig();
        ControlPanelTestSupport.resetCacheDir();
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
    void forceStopDropsTheRowWithoutRefresh() throws Exception {
        assumeRunningAppsSample();
        JnlpLaunchTestSupport.seedKnownJvmsFromAutodetect();
        JnlpLaunchTestSupport.launchHeldApp("java17-app");
        RunningProcess running = JnlpLaunchTestSupport.waitForRunningApp("Java 17 bytecode", 60_000);
        assertThat(running).isNotNull();
        int pid = running.getPid();

        ControlPanelTestSupport.openRunningApps(window);
        window.panel("runningAppRow-" + pid).requireVisible();
        window.button("runningAppForceStop-" + pid).requireVisible();
        assertThat(buttonShowing("runningAppTrimHeap-" + pid))
                .as("Trim Heap stays hidden when deployment.runningapps.trim.heap is off")
                .isFalse();

        window.button("runningAppForceStop-" + pid).click();
        robot.waitForIdle();

        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline && processStillListed(pid)) {
            Thread.sleep(100);
        }
        assertThat(processStillListed(pid)).as("force stop kills the JNLP process").isFalse();
        assertThat(rowShowing(pid))
                .as("the row leaves when the process dies, without clicking Refresh")
                .isFalse();
        assertThat(emptyLabelShowing())
                .as("an empty running-apps list is shown after force stop")
                .isTrue();
        assertThat(window.label("runningAppsStatusLabel").target().getText())
                .as("the status line clears with the list")
                .isEmpty();
    }

    @Test
    @Timeout(180)
    void runningAppNamesTheJdkItIsUsing() throws Exception {
        assumeRunningAppsSample();
        JnlpLaunchTestSupport.seedKnownJvmsFromAutodetect();
        JnlpLaunchTestSupport.launchHeldApp("java17-app");
        RunningProcess running = JnlpLaunchTestSupport.waitForRunningApp("Java 17 bytecode", 60_000);
        assertThat(running).isNotNull();

        ControlPanelTestSupport.openRunningApps(window);
        String label = window.label("runningAppJvmLabel-" + running.getPid()).target().getText();
        File jdkHome = new File(System.getProperty("itw.jdk17.home", "/usr/lib/jvm/java-17-openjdk-amd64"));
        assertThat(label).as("the running-app JDK label names the install").doesNotContain("Unknown");
        if (jdkHome.getName().toLowerCase(java.util.Locale.ROOT).contains("openjdk")) {
            assertThat(label).as("an OpenJDK install is labeled OpenJDK").contains("OpenJDK");
        }
        assertThat(label).contains("17");
    }

    @Test
    @Timeout(180)
    void trimHeapButtonFollowsItsSettingAndLeavesTheAppRunning() throws Exception {
        assumeRunningAppsSample();
        JnlpLaunchTestSupport.seedKnownJvmsFromAutodetect();
        JnlpLaunchTestSupport.launchHeldApp("java17-app");
        RunningProcess running = JnlpLaunchTestSupport.waitForRunningApp("Java 17 bytecode", 60_000);
        assertThat(running).isNotNull();
        int pid = running.getPid();

        configFromPanel().setProperty(DeploymentConfiguration.KEY_RUNNING_APPS_TRIM_HEAP, "true");
        ControlPanelTestSupport.openRunningApps(window);
        window.panel("runningAppRow-" + pid).requireVisible();
        window.button("runningAppTrimHeap-" + pid).requireVisible();

        window.button("runningAppTrimHeap-" + pid).click();
        robot.waitForIdle();
        assertThat(optionPaneMessage())
                .as("Trim Heap completes without a failure dialog")
                .isNull();
        assertThat(processStillListed(pid)).as("Trim Heap does not stop the application").isTrue();
        window.panel("runningAppRow-" + pid).requireVisible();
    }

    @Test
    @Timeout(120)
    void disablingAJdkPersistsOnlyAfterApply() throws Exception {
        File jdkHome = requireJdk(17);
        ControlPanelTestSupport.openJdkSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, jdkHome);
        ControlPanelTestSupport.clickApply(window, robot);

        window.table("jvmKnownTable").selectRows(0);
        robot.waitForIdle();
        window.button("jvmDisableButton").click();
        robot.waitForIdle();

        assertThat(window.button("jvmDisableButton").target().getText()).isEqualTo("Enable JDK");
        assertThat(String.valueOf(window.table("jvmKnownTable").target().getValueAt(0, 0))).isEqualTo("off");
        Properties beforeApply = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(beforeApply.getProperty(KnownJvmStore.KEY_JDK_DISABLED))
                .as("disable is pending until Apply")
                .isNull();

        ControlPanelTestSupport.clickApply(window, robot);
        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty(KnownJvmStore.KEY_JDK_DISABLED))
                .contains(ControlPanelTestSupport.tablePathAt(window, 0));
        assertThat(saved.getProperty("deployment.jdk.1"))
                .isEqualTo(ControlPanelTestSupport.tablePathAt(window, 0));
    }

    @Test
    @Timeout(120)
    void moveUpChangesPreferenceOrderOnlyAfterApply() throws Exception {
        File first = requireJdk(17);
        File second = requireJdk(21);
        ControlPanelTestSupport.openJdkSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, first);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, second);
        ControlPanelTestSupport.clickApply(window, robot);
        assertThat(window.table("jvmKnownTable").target().getRowCount()).isEqualTo(2);

        String originalFirst = ControlPanelTestSupport.tablePathAt(window, 0);
        String originalSecond = ControlPanelTestSupport.tablePathAt(window, 1);
        window.table("jvmKnownTable").selectRows(1);
        robot.waitForIdle();
        window.button("jvmMoveUpButton").click();
        robot.waitForIdle();

        assertThat(ControlPanelTestSupport.tablePathAt(window, 0)).isEqualTo(originalSecond);
        assertThat(ControlPanelTestSupport.tablePathAt(window, 1)).isEqualTo(originalFirst);
        Properties beforeApply = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(beforeApply.getProperty("deployment.jdk.1")).isEqualTo(originalFirst);
        assertThat(beforeApply.getProperty(DeploymentConfiguration.KEY_JRE_DIR)).isEqualTo(originalFirst);

        ControlPanelTestSupport.clickApply(window, robot);
        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty("deployment.jdk.1")).isEqualTo(originalSecond);
        assertThat(saved.getProperty("deployment.jdk.2")).isEqualTo(originalFirst);
        assertThat(saved.getProperty(DeploymentConfiguration.KEY_JRE_DIR)).isEqualTo(originalSecond);
    }

    @Test
    @Timeout(120)
    void removingAJdkPersistsOnlyAfterApply() throws Exception {
        File first = requireJdk(17);
        File second = requireJdk(21);
        ControlPanelTestSupport.openJdkSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, first);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, second);
        ControlPanelTestSupport.clickApply(window, robot);

        String removed = ControlPanelTestSupport.tablePathAt(window, 0);
        String kept = ControlPanelTestSupport.tablePathAt(window, 1);
        window.table("jvmKnownTable").selectRows(0);
        robot.waitForIdle();
        window.button("jvmRemoveButton").click();
        robot.waitForIdle();
        assertThat(window.table("jvmKnownTable").target().getRowCount()).isEqualTo(1);

        Properties beforeApply = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(beforeApply.getProperty("deployment.jdk.1")).isEqualTo(removed);
        assertThat(beforeApply.getProperty(KnownJvmStore.KEY_JDK_REMOVED))
                .as("remove is pending until Apply")
                .isNull();

        ControlPanelTestSupport.clickApply(window, robot);
        Properties saved = ControlPanelTestSupport.loadDeploymentProperties();
        assertThat(saved.getProperty("deployment.jdk.1")).isEqualTo(kept);
        assertThat(saved.getProperty("deployment.jdk.2")).isNullOrEmpty();
        assertThat(saved.getProperty(KnownJvmStore.KEY_JDK_REMOVED)).contains(removed);
        assertThat(saved.getProperty(DeploymentConfiguration.KEY_JRE_DIR)).isEqualTo(kept);
    }

    @Test
    @Timeout(120)
    void removingAnAssignmentClearsItOnlyAfterApply() throws Exception {
        File jdkHome = requireJdk(17);
        ControlPanelTestSupport.openJdkSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, jdkHome);
        ControlPanelTestSupport.clickApply(window, robot);

        ControlPanelTestSupport.openJdkAssignments(window);
        ControlPanelTestSupport.addJdkAssignment(window, robot, 0, SAMPLE_JNLP_URL);
        ControlPanelTestSupport.clickApply(window, robot);
        assertThat(ControlPanelTestSupport.loadDeploymentProperties().getProperty("deployment.jdk1.assignment1"))
                .isEqualTo(SAMPLE_JNLP_URL);

        window.table("jdkAssignmentsTable").selectRows(0);
        robot.waitForIdle();
        window.button("jdkAssignmentRemoveButton").click();
        robot.waitForIdle();
        assertThat(window.table("jdkAssignmentsTable").target().getRowCount()).isZero();
        assertThat(ControlPanelTestSupport.loadDeploymentProperties().getProperty("deployment.jdk1.assignment1"))
                .as("remove is pending until Apply")
                .isEqualTo(SAMPLE_JNLP_URL);

        ControlPanelTestSupport.clickApply(window, robot);
        assertThat(ControlPanelTestSupport.loadDeploymentProperties().getProperty("deployment.jdk1.assignment1"))
                .isNullOrEmpty();
    }

    @Test
    @Timeout(120)
    void revertRestoresAnAssignmentRemovedBeforeApply() throws Exception {
        File jdkHome = requireJdk(17);
        ControlPanelTestSupport.openJdkSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, jdkHome);
        ControlPanelTestSupport.clickApply(window, robot);

        ControlPanelTestSupport.openJdkAssignments(window);
        ControlPanelTestSupport.addJdkAssignment(window, robot, 0, SAMPLE_JNLP_URL);
        ControlPanelTestSupport.clickApply(window, robot);

        window.table("jdkAssignmentsTable").selectRows(0);
        robot.waitForIdle();
        window.button("jdkAssignmentRemoveButton").click();
        robot.waitForIdle();
        window.button("controlPanelRevertButton").requireEnabled();
        window.button("controlPanelRevertButton").click();
        robot.waitForIdle();

        assertThat(window.table("jdkAssignmentsTable").target().getRowCount()).isEqualTo(1);
        assertThat(String.valueOf(window.table("jdkAssignmentsTable").target().getValueAt(0, 0)))
                .isEqualTo(SAMPLE_JNLP_URL);
        assertThat(ControlPanelTestSupport.loadDeploymentProperties().getProperty("deployment.jdk1.assignment1"))
                .isEqualTo(SAMPLE_JNLP_URL);
    }

    @Test
    @Timeout(120)
    void disabledJdkIsNotOfferedForAssignment() throws Exception {
        File disabledHome = requireJdk(17);
        File enabledHome = requireJdk(21);
        ControlPanelTestSupport.openJdkSettings(window);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, disabledHome);
        ControlPanelTestSupport.addJdkViaChooser(robot, window, enabledHome);
        ControlPanelTestSupport.clickApply(window, robot);

        ControlPanelTestSupport.openJdkAssignments(window);
        ControlPanelTestSupport.addJdkAssignment(window, robot, 0, SAMPLE_JNLP_URL);
        ControlPanelTestSupport.clickApply(window, robot);

        ControlPanelTestSupport.openJdkSettings(window);
        int disabledRow = rowWithPath(disabledHome);
        window.table("jvmKnownTable").selectRows(disabledRow);
        robot.waitForIdle();
        window.button("jvmDisableButton").click();
        robot.waitForIdle();
        ControlPanelTestSupport.clickApply(window, robot);
        String disabledPath = ControlPanelTestSupport.tablePathAt(window, disabledRow);
        String disabledLabel = JvmDescriptor.describe(disabledPath).getDisplayName();
        String enabledLabel = JvmDescriptor.describe(
                ControlPanelTestSupport.tablePathAt(window, rowWithPath(enabledHome))).getDisplayName();

        ControlPanelTestSupport.openJdkAssignments(window);
        JTable table = window.table("jdkAssignmentsTable").target();
        SwingUtilities.invokeAndWait(() -> table.editCellAt(0, 1));
        robot.waitForIdle();
        JComboBox<?> combo = (JComboBox<?>) table.getEditorComponent();
        assertThat(combo).isNotNull();
        boolean disabledOffered = false;
        boolean enabledOffered = false;
        for (int i = 0; i < combo.getItemCount(); i++) {
            String label = String.valueOf(combo.getItemAt(i));
            if (disabledLabel.equals(label)) {
                disabledOffered = true;
            }
            if (enabledLabel.equals(label)) {
                enabledOffered = true;
            }
        }
        if (table.getCellEditor() != null) {
            SwingUtilities.invokeAndWait(() -> table.getCellEditor().cancelCellEditing());
        }
        assertThat(enabledOffered).as("an enabled JDK stays an assignment choice").isTrue();
        assertThat(disabledOffered).as("a disabled JDK is not an assignment choice").isFalse();
    }

    @Test
    @Timeout(60)
    void launchWithoutASelectedAssignmentWarns() throws Exception {
        ControlPanelTestSupport.openJdkAssignments(window);
        window.button("jdkAssignmentLaunchButton").click();
        robot.waitForIdle();
        window.optionPane().requireVisible();
        window.optionPane().okButton().click();
        robot.waitForIdle();
    }

    private static void assumeRunningAppsSample() {
        Assumptions.assumeTrue(JnlpLaunchTestSupport.javawsAvailable());
        Assumptions.assumeTrue(JnlpLaunchTestSupport.sampleBuilt("java17-app"));
    }

    private static File requireJdk(int major) throws Exception {
        File home = ControlPanelTestSupport.findJdkHomeWithMajor(major);
        Assumptions.assumeTrue(home != null && home.isDirectory(), "JDK " + major + " is installed");
        return home;
    }

    private DeploymentConfiguration configFromPanel() throws Exception {
        Field configField = ControlPanel.class.getDeclaredField("config");
        configField.setAccessible(true);
        return (DeploymentConfiguration) configField.get(controlPanel);
    }

    private static boolean processStillListed(int pid) {
        for (RunningProcess process : JnlpRunningProcessSupport.listRunningJnlpProcesses()) {
            if (process.getPid() == pid) {
                return true;
            }
        }
        return false;
    }

    private boolean rowShowing(int pid) {
        return buttonShowing("runningAppRow-" + pid) || panelShowing("runningAppRow-" + pid);
    }

    private boolean panelShowing(String name) {
        try {
            return window.panel(name).target().isShowing();
        } catch (ComponentLookupException ex) {
            return false;
        }
    }

    private boolean buttonShowing(String name) {
        try {
            return window.button(name).target().isShowing();
        } catch (ComponentLookupException ex) {
            return false;
        }
    }

    private boolean emptyLabelShowing() {
        Component[] components = window.panel("runningAppsListPanel").target().getComponents();
        for (Component component : components) {
            if ("runningAppsEmptyLabel".equals(component.getName()) && component instanceof JLabel) {
                return component.isShowing();
            }
        }
        return false;
    }

    private String optionPaneMessage() {
        try {
            Object message = window.optionPane(org.assertj.swing.timing.Timeout.timeout(400)).target().getMessage();
            window.optionPane().okButton().click();
            return message == null ? "" : String.valueOf(message);
        } catch (WaitTimedOutError | ComponentLookupException ex) {
            return null;
        }
    }

    private int rowWithPath(File home) throws Exception {
        JTable table = window.table("jvmKnownTable").target();
        String canonical = home.getCanonicalPath();
        for (int row = 0; row < table.getRowCount(); row++) {
            String path = String.valueOf(table.getValueAt(row, 3));
            if (samePath(path, canonical)) {
                return row;
            }
        }
        throw new IllegalStateException("JDK not listed: " + canonical);
    }

    private static boolean samePath(String left, String right) throws Exception {
        if (left == null || right == null || "null".equals(left)) {
            return false;
        }
        return new File(left).getCanonicalFile().equals(new File(right).getCanonicalFile());
    }

}
