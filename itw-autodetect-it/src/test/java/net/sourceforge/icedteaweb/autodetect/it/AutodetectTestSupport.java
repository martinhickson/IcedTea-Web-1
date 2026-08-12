package net.sourceforge.icedteaweb.autodetect.it;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import net.sourceforge.jnlp.util.JvmAutodetector;

final class AutodetectTestSupport {

    private static final List<Process> LAUNCHED = new ArrayList<>();

    private AutodetectTestSupport() {
    }

    static File deploymentPropertiesFile() {
        String configHome = System.getenv("XDG_CONFIG_HOME");
        if (configHome == null || configHome.trim().isEmpty()) {
            configHome = System.getProperty("user.home") + File.separator + ".config";
        }
        return new File(configHome + File.separator + "icedtea-web", "deployment.properties");
    }

    static void resetDeploymentConfig() throws IOException {
        File deploymentFile = deploymentPropertiesFile();
        if (deploymentFile.isFile()) {
            Files.delete(deploymentFile.toPath());
        }
        Properties props = new Properties();
        props.setProperty(DeploymentConfiguration.KEY_AUTODETECT_JDKS, "false");
        props.setProperty(DeploymentConfiguration.KEY_CACHE_CATALOG_SQLITE, "true");
        props.setProperty("deployment.log", "true");
        props.setProperty("deployment.log.file", "true");
        writeDeploymentProperties(props);
        ensureFreshCacheIndex();
    }

    /**
     * Wipe the isolated cache so each run starts clean (avoids Windows
     * recently_used remap races / leftover {@code db/} catalog from prior launches).
     */
    static void ensureFreshCacheIndex() throws IOException {
        String cacheHome = System.getenv("XDG_CACHE_HOME");
        if (cacheHome == null || cacheHome.isBlank()) {
            cacheHome = System.getProperty("user.home") + File.separator + ".cache";
        }
        Path icedteaCache = Paths.get(cacheHome, "icedtea-web");
        if (Files.isDirectory(icedteaCache)) {
            Files.walk(icedteaCache)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best-effort
                        }
                    });
        }
        Path cacheDir = Paths.get(cacheHome, "icedtea-web", "cache");
        Files.createDirectories(cacheDir);
        // Legacy index placeholder (ignored when deployment.cache.catalog.sqlite=true).
        Files.writeString(cacheDir.resolve("recently_used"), PLANTED_LEGACY_INDEX, StandardCharsets.UTF_8);
        Files.createDirectories(cacheDir.resolve("db"));
    }

    /** Distinctive bytes planted in legacy {@code recently_used}; sqlite path must not rewrite them. */
    static final String PLANTED_LEGACY_INDEX = "PLANTED-LEGACY-DO-NOT-TOUCH\n";

    /** User cache root: {@code $XDG_CACHE_HOME/icedtea-web/cache}. */
    static Path userCacheRoot() {
        String cacheHome = System.getenv("XDG_CACHE_HOME");
        if (cacheHome == null || cacheHome.isBlank()) {
            cacheHome = System.getProperty("user.home") + File.separator + ".cache";
        }
        return Paths.get(cacheHome, "icedtea-web", "cache");
    }

    static Path sqliteCatalogFile() {
        return userCacheRoot().resolve("db").resolve("cache_catalog.sqlite");
    }

    static void writeDeploymentProperties(Properties props) throws IOException {
        File file = deploymentPropertiesFile();
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            props.store(out, "itw-autodetect-it");
        }
    }

    static Properties loadDeploymentProperties() throws IOException {
        Properties props = new Properties();
        File file = deploymentPropertiesFile();
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                props.load(in);
            }
        }
        return props;
    }

    static boolean javawsAvailable() {
        String bin = System.getProperty("itw.javaws.bin");
        if (bin == null || bin.isBlank()) {
            return false;
        }
        File javaws = new File(bin);
        return javaws.isFile();
    }

    static boolean jdkHomeAvailable(int major) {
        File home = jdkHome(major);
        return home != null && javaExecutable(home).isFile();
    }

    static File jdkHome(int major) {
        String property = System.getProperty("itw.jdk" + major + ".home");
        if (property == null || property.isBlank()) {
            return null;
        }
        return new File(property);
    }

    static File sampleJar() {
        String root = System.getProperty("itw.test.jnlp.root");
        if (root == null || root.isBlank()) {
            root = "target/test-jnlp-samples";
        }
        return new File(root, "java17-app/app.jar");
    }

    static boolean sampleBuilt() {
        return sampleJar().isFile();
    }

    static Process launchJavaws(String jnlpUrl, File starterJavaHome) throws IOException {
        String bin = System.getProperty("itw.javaws.bin");
        List<String> command = new ArrayList<>();
        command.add(bin);
        command.add("-verbose");
        command.add("-Xtrustall");
        command.add("--auto-accept-https-certificate=true");
        String dialogAgent = System.getProperty("itw.dialog.agent.jar");
        if (dialogAgent != null && !dialogAgent.isBlank() && new File(dialogAgent).isFile()) {
            Path agentLog = Paths.get(System.getProperty("java.io.tmpdir"),
                    "itw-autodetect-dialog-agent.log");
            try {
                Files.deleteIfExists(agentLog);
            } catch (IOException ignored) {
                // best-effort
            }
            // Ride inside the javaws JVM so we can see Swing windows directly
            // (AssertJ-style lookup). Multiple -javaagent flags are OK alongside
            // the distribution's byte-buddy-agent.
            // Agent args with '=' can confuse some launchers; pass log via -D only.
            command.add("-J-javaagent:" + new File(dialogAgent).getAbsolutePath());
            command.add("-J-Ditw.dialog.agent.log=" + agentLog.toAbsolutePath());
            // Dialog clicker enumerates sun.awt.AppContext / Window.getWindows(AppContext).
            command.add("-J--add-opens=java.desktop/sun.awt=ALL-UNNAMED");
            command.add("-J--add-opens=java.desktop/java.awt=ALL-UNNAMED");
        }
        command.add(jnlpUrl);

        ProcessBuilder pb = new ProcessBuilder(command);
        if (starterJavaHome != null) {
            pb.environment().put("JAVA_HOME", starterJavaHome.getAbsolutePath());
        }
        // Exercise filesystem/registry discovery — not env short-circuits.
        pb.environment().remove("JDK17_HOME");
        pb.environment().remove("JDK_HOME");
        pb.environment().remove("ITW_JDK17_HOME");
        String configHome = System.getenv("XDG_CONFIG_HOME");
        String cacheHome = System.getenv("XDG_CACHE_HOME");
        String userHome = System.getProperty("user.home");
        if (configHome != null) {
            // Normalize to OS separators so cache recently_used paths match.
            pb.environment().put("XDG_CONFIG_HOME", new File(configHome).getAbsolutePath());
        }
        if (cacheHome != null) {
            pb.environment().put("XDG_CACHE_HOME", new File(cacheHome).getAbsolutePath());
        }
        // Keep the real USERPROFILE so Windows/.NET launcher paths stay stable;
        // ITW config/cache isolation is via XDG_* only.
        if (userHome != null) {
            pb.environment().put("HOME", userHome);
        }
        pb.environment().put("ICEDTEA_WEB_SPLASH", "none");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // Drain stdout so a verbose javaws child cannot block on a full pipe.
        Thread drain = new Thread(() -> {
            try (java.io.InputStream in = process.getInputStream()) {
                byte[] buf = new byte[4096];
                while (in.read(buf) >= 0) {
                    // discard
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "itw-autodetect-javaws-drain");
        drain.setDaemon(true);
        drain.start();
        LAUNCHED.add(process);
        return process;
    }

    static boolean waitForMarker(Path marker, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(marker)) {
                return true;
            }
            Thread.sleep(250);
        }
        return Files.isRegularFile(marker);
    }

    static void stopLaunchedProcesses() {
        for (Process process : LAUNCHED) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        LAUNCHED.clear();
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
        SwingUtilities.invokeAndWait(panel::dispose);
    }

    static boolean deploymentContainsJdkMajor(int major) throws Exception {
        Properties props = loadDeploymentProperties();
        for (int i = 1; i <= 64; i++) {
            String home = props.getProperty("deployment.jdk." + i);
            if (home == null || home.isBlank()) {
                continue;
            }
            if (JvmAutodetector.majorVersionOfJvmHome(home) == major) {
                return true;
            }
        }
        return false;
    }

    private static File javaExecutable(File javaHome) {
        return new File(javaHome, "bin\\java.exe");
    }
}
