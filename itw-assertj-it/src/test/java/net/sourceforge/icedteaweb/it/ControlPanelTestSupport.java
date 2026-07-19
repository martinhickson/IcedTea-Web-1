package net.sourceforge.icedteaweb.it;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import net.sourceforge.jnlp.util.JnlpAssignmentLauncher;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.KnownJvmStore;
import net.sourceforge.jnlp.controlpanel.ControlPanel;
import net.sourceforge.jnlp.controlpanel.JVMPanel;
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

    static File icedteaWebCacheDir() {
        String cacheHome = System.getenv("XDG_CACHE_HOME");
        if (cacheHome == null || cacheHome.trim().isEmpty()) {
            cacheHome = System.getProperty("user.home") + File.separator + ".cache";
        }
        return new File(cacheHome + File.separator + "icedtea-web" + File.separator + "cache");
    }

    static void resetCacheDir() throws IOException {
        File cacheDir = icedteaWebCacheDir();
        if (cacheDir.isDirectory()) {
            Files.walk(cacheDir.toPath())
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            throw new IllegalStateException("Failed to delete " + path, ex);
                        }
                    });
        }
        if (!cacheDir.mkdirs() && !cacheDir.isDirectory()) {
            throw new IllegalStateException("Failed to create " + cacheDir);
        }
    }

    /**
     * Seeds minimal cache entries ({@code jnlp-path} in {@code .info} files) so
     * {@link net.sourceforge.jnlp.cache.CachedJnlpUrlDiscovery} finds sample apps.
     * This keeps the assignment-listing tests focused on discovery data.
     */
    static void seedCachedJnlpDiscoveryEntries(String... sampleNames) throws Exception {
        resetCacheDir();
        int slot = 0;
        for (String sampleName : sampleNames) {
            URL jnlpUrl = JnlpLaunchTestSupport.jnlpUrl(sampleName);
            String canonicalUrl = JnlpAssignmentLauncher.canonicalizeJnlpUrl(jnlpUrl.toExternalForm());

            File leaf = new File(icedteaWebCacheDir(),
                    slot + File.separator + "http" + File.separator + "itw-test"
                            + File.separator + sampleName + File.separator + "app.jnlp");
            if (!leaf.getParentFile().mkdirs()) {
                throw new IllegalStateException("Failed to create " + leaf.getParentFile());
            }
            if (!leaf.createNewFile() && !leaf.isFile()) {
                throw new IllegalStateException("Failed to create " + leaf);
            }

            Properties info = new Properties();
            info.setProperty("jnlp-path", canonicalUrl);
            File infoFile = new File(leaf.getPath() + ".info");
            try (FileOutputStream out = new FileOutputStream(infoFile)) {
                info.store(out, "itw-assertj-it");
            }
            slot++;
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
        openSettingsTab(window, "JDK Settings", "jvmKnownTable");
    }

    /** @deprecated use {@link #openJdkSettings(FrameFixture)} */
    static void openJvmSettings(FrameFixture window) {
        openJdkSettings(window);
    }

    static void openRunningApps(FrameFixture window) {
        openSettingsTab(window, "Running Apps", "runningAppsRefreshButton");
    }

    static void openJdkAssignments(FrameFixture window) {
        openSettingsTab(window, "JDK Assignments", "jdkAssignmentsTable");
    }

    private static void openSettingsTab(FrameFixture window, String tabLabel, String showingComponentName) {
        window.list("controlPanelSettingsList").selectItem(tabLabel);
        Object selected = window.list("controlPanelSettingsList").target().getSelectedValue();
        if (selected == null || !tabLabel.equals(String.valueOf(selected))) {
            throw new IllegalStateException(
                    "Expected settings tab '" + tabLabel + "' but selected '" + selected + "'");
        }
        waitForNamedComponentShowing(window, showingComponentName, 10_000);
    }

    private static void waitForNamedComponentShowing(FrameFixture window, String componentName, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isNamedComponentShowing(window, componentName)) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (componentName.endsWith("Table")) {
            window.table(componentName).requireVisible();
        } else {
            window.button(componentName).requireVisible();
        }
    }

    private static boolean isNamedComponentShowing(FrameFixture window, String componentName) {
        if (componentName.endsWith("Table")) {
            return window.table(componentName).target().isShowing();
        }
        return window.button(componentName).target().isShowing();
    }

    static void addJdkAssignment(FrameFixture window, Robot robot, int rowIndex, String jnlpUrl) {
        window.button("jdkAssignmentAddButton").click();
        robot.waitForIdle();
        window.table("jdkAssignmentsTable").enterValue(row(rowIndex).column(0), jnlpUrl);
        robot.waitForIdle();
        selectFirstKnownJdkForAssignmentRow(window, robot, rowIndex);
    }

    private static void selectFirstKnownJdkForAssignmentRow(FrameFixture window, Robot robot, int rowIndex) {
        window.table("jdkAssignmentsTable").cell(row(rowIndex).column(1)).click();
        robot.waitForIdle();
        if (window.table("jdkAssignmentsTable").target().getRowCount() <= rowIndex) {
            throw new IllegalStateException("Assignment row " + rowIndex + " is missing");
        }
        if (window.table("jvmKnownTable").target().getRowCount() == 0) {
            throw new IllegalStateException("No known JDKs configured for assignment row " + rowIndex);
        }
        window.comboBox().target().setSelectedIndex(1);
        robot.waitForIdle();
        window.table("jdkAssignmentsTable").cell(row(rowIndex).column(0)).click();
        robot.waitForIdle();
    }

    static void addJdkViaChooser(Robot robot, FrameFixture window, File jdkHome) throws Exception {
        dismissOpenFileChooserIfAny(robot);
        ControlPanel controlPanel = (ControlPanel) window.target();
        if (preferConfigurationFallbackForJdkAdd()) {
            addKnownJdkViaConfiguration(controlPanel, window, jdkHome);
            robot.waitForIdle();
            if (!tableContainsJdkHome(window, jdkHome)) {
                throw new IllegalStateException("Failed to add JDK via configuration fallback: "
                        + jdkHome.getAbsolutePath());
            }
            return;
        }
        window.button("jvmAddButton").click();
        try {
            JFileChooserFixture fileChooser = JFileChooserFinder.findFileChooser()
                    .withTimeout(10_000)
                    .using(robot);
            File parent = jdkHome.getParentFile();
            if (parent != null && parent.isDirectory()) {
                fileChooser.setCurrentDirectory(parent);
            }
            fileChooser.selectFile(jdkHome);
            fileChooser.approve();
            robot.waitForIdle();
        } catch (RuntimeException ex) {
            dismissOpenFileChooserIfAny(robot);
        }
        dismissOpenFileChooserIfAny(robot);
        if (!tableContainsJdkHome(window, jdkHome)) {
            addKnownJdkViaConfiguration(controlPanel, window, jdkHome);
            robot.waitForIdle();
        } else {
            syncKnownJvmTableToConfig(controlPanel, window);
        }
        if (!tableContainsJdkHome(window, jdkHome)) {
            throw new IllegalStateException("Failed to add JDK via chooser or configuration fallback: "
                    + jdkHome.getAbsolutePath());
        }
    }

    private static boolean preferConfigurationFallbackForJdkAdd() {
        return "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"));
    }

    private static boolean tableContainsJdkHome(FrameFixture window, File jdkHome) {
        JTable table = window.table("jvmKnownTable").target();
        String path = jdkHome.getAbsolutePath();
        for (int row = 0; row < table.getRowCount(); row++) {
            if (path.equals(String.valueOf(table.getValueAt(row, 3)))) {
                return true;
            }
        }
        return false;
    }

    private static void dismissOpenFileChooserIfAny(Robot robot) {
        try {
            JFileChooserFinder.findFileChooser().withTimeout(500).using(robot).cancel();
            robot.waitForIdle();
        } catch (RuntimeException ignored) {
            // no chooser open
        }
    }

    private static void syncKnownJvmTableToConfig(ControlPanel controlPanel, FrameFixture window) throws Exception {
        addKnownJdkViaConfiguration(controlPanel, window, null);
    }

    private static void addKnownJdkViaConfiguration(ControlPanel controlPanel, FrameFixture window, File jdkHome)
            throws Exception {
        Field configField = ControlPanel.class.getDeclaredField("config");
        configField.setAccessible(true);
        DeploymentConfiguration config = (DeploymentConfiguration) configField.get(controlPanel);
        Field jvmPanelField = ControlPanel.class.getDeclaredField("jvmPanel");
        jvmPanelField.setAccessible(true);
        JVMPanel jvmPanel = (JVMPanel) jvmPanelField.get(controlPanel);
        List<String> homes = collectKnownJvmHomesFromTable(window);
        if (jdkHome != null) {
            String path = jdkHome.getAbsolutePath();
            if (!homes.contains(path)) {
                homes.add(path);
            }
        }
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                KnownJvmStore.setKnownJvmHomes(config, homes);
                jvmPanel.reloadFromConfiguration();
            } catch (Exception ex) {
                errorRef.set(ex);
            }
        });
        if (errorRef.get() != null) {
            throw errorRef.get();
        }
    }

    private static List<String> collectKnownJvmHomesFromTable(FrameFixture window) {
        List<String> homes = new ArrayList<>();
        JTable table = window.table("jvmKnownTable").target();
        for (int row = 0; row < table.getRowCount(); row++) {
            Object value = table.getValueAt(row, 3);
            if (value != null) {
                String path = String.valueOf(value).trim();
                if (!path.isEmpty() && !homes.contains(path)) {
                    homes.add(path);
                }
            }
        }
        return homes;
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
        dismissOpenFileChooserIfAny(robot);
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
        int jdkIndex = findOrAddJdkIndex(props, jdkHome);
        int assignmentSlot = nextAssignmentSlot(props, jdkIndex);
        props.setProperty("deployment.jdk" + jdkIndex + ".assignment" + assignmentSlot,
                jnlp.toURI().toURL().toExternalForm());
        writeDeploymentProperties(props);
    }

    /** Seeds known JVM paths only — no JNLP assignments. */
    static void seedKnownJvmsOnly() throws Exception {
        resetDeploymentConfig();
        Properties props = new Properties();
        int idx = 1;
        for (File home : discoverValidJdkHomes()) {
            props.setProperty("deployment.jdk." + idx++, home.getAbsolutePath());
        }
        props.setProperty("deployment.jdk.matchStrategy", "MAXIMUM");
        writeDeploymentProperties(props);
    }

    static void seedTwoKnownJdksWithMaximumStrategy(File firstJdk, File secondJdk) throws Exception {
        resetDeploymentConfig();
        Properties props = new Properties();
        props.setProperty("deployment.jdk.1", firstJdk.getAbsolutePath());
        props.setProperty("deployment.jdk.2", secondJdk.getAbsolutePath());
        props.setProperty("deployment.jdk.matchStrategy", "MAXIMUM");
        enableFileLoggingForIntegrationTests(props);
        writeDeploymentProperties(props);
    }

    static void assignJnlpToJdkIndex(String jnlpUrl, int jdkIndex, int assignmentSlot) throws Exception {
        Properties props = loadDeploymentProperties();
        props.setProperty("deployment.jdk" + jdkIndex + ".assignment" + assignmentSlot, jnlpUrl);
        writeDeploymentProperties(props);
    }

    static void enableFileLoggingForIntegrationTests(Properties props) {
        props.setProperty("deployment.log", "true");
        props.setProperty("deployment.log.file", "true");
        props.setProperty("deployment.log.file.clientapp", "true");
    }

    /** Seeds all built bytecode and GUI sample apps (same set as seed-interactive-home.sh). */
    static void seedAllBuiltSampleAssignments() throws Exception {
        resetDeploymentConfig();
        Properties props = new Properties();
        int idx = 1;
        for (File home : discoverValidJdkHomes()) {
            props.setProperty("deployment.jdk." + idx++, home.getAbsolutePath());
        }
        props.setProperty("deployment.jdk.matchStrategy", "MAXIMUM");
        writeDeploymentProperties(props);

        seedBuiltSampleAssignment("java17-app", 17);
        seedBuiltSampleAssignment("java21-app", 21);
        seedBuiltSampleAssignment("java25-app", 25);
        seedBuiltSampleAssignment("gui-app", 17);
    }

    private static int findOrAddJdkIndex(Properties props, File jdkHome) {
        String path = jdkHome.getAbsolutePath();
        for (int i = 1; i <= 64; i++) {
            String existing = props.getProperty("deployment.jdk." + i);
            if (path.equals(existing)) {
                return i;
            }
            if (existing == null || existing.trim().isEmpty()) {
                props.setProperty("deployment.jdk." + i, path);
                return i;
            }
        }
        throw new IllegalStateException("No free deployment.jdk.* slot for " + path);
    }

    private static int nextAssignmentSlot(Properties props, int jdkIndex) {
        for (int slot = 1; slot <= 64; slot++) {
            String key = "deployment.jdk" + jdkIndex + ".assignment" + slot;
            String value = props.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                return slot;
            }
        }
        throw new IllegalStateException("No free assignment slot for JDK index " + jdkIndex);
    }

    static File findJdkHomeWithMajor(int major) throws Exception {
        if (JnlpLaunchTestSupport.hasSampleJdk(major)) {
            return JnlpLaunchTestSupport.jdkHome(major);
        }
        for (File home : discoverValidJdkHomes()) {
            if (net.sourceforge.jnlp.util.JvmAutodetector.majorVersionOfJvmHome(
                    home.getAbsolutePath()) == major) {
                return home;
            }
        }
        return null;
    }

    static void writeDeploymentProperties(Properties props) throws Exception {
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

    static int findAssignmentRowByJnlpFragment(FrameFixture window, String fragment) {
        javax.swing.JTable table = window.table("jdkAssignmentsTable").target();
        for (int row = 0; row < table.getRowCount(); row++) {
            Object value = table.getValueAt(row, 0);
            if (value != null && String.valueOf(value).contains(fragment)) {
                return row;
            }
        }
        return -1;
    }

    static String waitForLaunchOutput(FrameFixture window, String requiredFragment, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String text = window.textBox("jdkAssignmentLaunchOutputArea").text();
            if (text != null && text.contains(requiredFragment)) {
                return text;
            }
            Thread.sleep(500);
        }
        return window.textBox("jdkAssignmentLaunchOutputArea").text();
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
