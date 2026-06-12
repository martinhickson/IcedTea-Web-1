package net.sourceforge.icedteaweb.it;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport.RunningProcess;
import net.sourceforge.jnlp.util.JvmAutodetector;

final class JnlpLaunchTestSupport {

    private static final String JAVAWS_BIN = System.getProperty("itw.javaws.bin");
    private static final String JNLP_ROOT = System.getProperty("itw.test.jnlp.root");
    private static final List<Process> launched = new ArrayList<>();

    private JnlpLaunchTestSupport() {
    }

    static boolean javawsAvailable() {
        if (JAVAWS_BIN == null || JAVAWS_BIN.isBlank()) {
            return false;
        }
        File javaws = new File(JAVAWS_BIN);
        return javaws.isFile() && javaws.canExecute();
    }

    static boolean sampleBuilt(String sampleName) {
        return jnlpFile(sampleName).isFile() && jarFile(sampleName).isFile();
    }

    static File jnlpFile(String sampleName) {
        return new File(jnlpRoot(), sampleName + "/app.jnlp");
    }

    static File jarFile(String sampleName) {
        return new File(jnlpRoot(), sampleName + "/app.jar");
    }

    static URL jnlpUrl(String sampleName) throws IOException {
        return jnlpFile(sampleName).toURI().toURL();
    }

    static void seedKnownJvmsFromAutodetect() throws Exception {
        List<String> homes = JvmAutodetector.discoverValidJvmHomes();
        Properties props = new Properties();
        int index = 1;
        for (String home : homes) {
            props.setProperty("deployment.jdk." + index++, home);
        }
        if (!homes.isEmpty()) {
            props.setProperty("deployment.jre.dir", homes.get(0));
        }
        writeDeploymentProperties(props);
    }

    static Process launchHeldApp(String sampleName) throws Exception {
        return launchHeldApp(sampleName, 120);
    }

    static Process launchHeldApp(String sampleName, int holdSeconds) throws Exception {
        if (!javawsAvailable()) {
            throw new IllegalStateException("javaws launcher missing: " + JAVAWS_BIN);
        }
        File jnlp = jnlpFile(sampleName);
        if (!jnlp.isFile()) {
            throw new IOException("Missing sample JNLP: " + jnlp);
        }

        List<String> command = new ArrayList<>();
        command.add(JAVAWS_BIN);
        command.add("-headless");
        command.add("-verbose");
        command.add("-Xnofork");
        // Skip certificate trust dialogs in automated runs (SecurityDialogMessageHandler).
        command.add("-Xtrustall");
        command.add("-J-Ditw.test.hold.seconds=" + holdSeconds);
        command.add("-J-Djava.awt.headless=true");
        command.add("-J-Ddeployment.log=true");
        command.add("-J-Ddeployment.log.file=true");
        command.add(jnlp.toURI().toURL().toExternalForm());

        ProcessBuilder pb = new ProcessBuilder(command);
        File javaHome = javaHomeForSample(sampleName);
        if (javaHome != null) {
            pb.environment().put("JAVA_HOME", javaHome.getAbsolutePath());
        }
        pb.environment().put("XDG_CONFIG_HOME", testConfigHome());
        pb.environment().put("XDG_CACHE_HOME", testCacheHome());
        pb.environment().put("HOME", System.getProperty("user.home"));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        launched.add(process);
        return process;
    }

    static RunningProcess waitForRunningApp(String titleFragment, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (RunningProcess running : JnlpRunningProcessSupport.listRunningJnlpProcesses()) {
                String title = running.getAppTitle();
                if (title != null && title.contains(titleFragment)) {
                    return running;
                }
            }
            Thread.sleep(500);
        }
        return null;
    }

    static void stopLaunchedProcesses() {
        for (Process process : launched) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        launched.clear();
        for (RunningProcess running : JnlpRunningProcessSupport.listRunningJnlpProcesses()) {
            JnlpRunningProcessSupport.stopProcess(running.getPid(), true);
        }
    }

    static boolean hasSampleJdk(int major) {
        String property = System.getProperty("itw.jdk" + major + ".home");
        if (property == null || property.isBlank()) {
            return false;
        }
        return new File(property, "bin/java").canExecute();
    }

    static File jdkHome(int major) {
        return new File(System.getProperty("itw.jdk" + major + ".home"));
    }

    private static File javaHomeForSample(String sampleName) {
        if (sampleName.contains("17") && hasSampleJdk(17)) {
            return jdkHome(17);
        }
        if (sampleName.contains("21") && hasSampleJdk(21)) {
            return jdkHome(21);
        }
        if (sampleName.contains("25") && hasSampleJdk(25)) {
            return jdkHome(25);
        }
        return null;
    }

    private static File jnlpRoot() {
        if (JNLP_ROOT == null || JNLP_ROOT.isBlank()) {
            return new File("target/test-jnlp-samples");
        }
        return new File(JNLP_ROOT);
    }

    private static String testConfigHome() {
        return ControlPanelTestSupport.deploymentPropertiesFile().getParentFile().getParent();
    }

    private static String testCacheHome() {
        return testConfigHome().replace("/.config", "/.cache");
    }

    static String readProcessOutput(Process process, long timeoutMs) throws Exception {
        StringBuilder output = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (System.currentTimeMillis() < deadline) {
                while (reader.ready()) {
                    output.append((char) reader.read());
                }
                if (!process.isAlive()) {
                    break;
                }
                Thread.sleep(200);
            }
            while (reader.ready()) {
                output.append((char) reader.read());
            }
        }
        return output.toString();
    }

    private static void writeDeploymentProperties(Properties props) throws IOException {
        File file = ControlPanelTestSupport.deploymentPropertiesFile();
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            props.store(out, "itw-assertj-it");
        }
    }
}
