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
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.Properties;
import net.sourceforge.jnlp.util.JavaVersionUtils;
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
        return launchHeldAppWithJavaHome(sampleName, holdSeconds, javaHomeForSample(sampleName));
    }

    /** Matches {@link net.sourceforge.jnlp.util.logging.FileLog#createFileLog()}. */
    static final String JAVANTX_LOG_PREFIX = "itw-javantx-";

    static Process launchJnlpViaJavaws(String sampleName, int holdSeconds, File starterJavaHome) throws Exception {
        return startJavawsProcess(sampleName, holdSeconds, starterJavaHome, false);
    }

    static Process launchHeldAppWithJavaHome(String sampleName, int holdSeconds, File javaHome) throws Exception {
        return startJavawsProcess(sampleName, holdSeconds, javaHome, true);
    }

    private static Process startJavawsProcess(String sampleName, int holdSeconds, File javaHome, boolean nofork)
            throws Exception {
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
        if (nofork) {
            command.add("-Xnofork");
        }
        command.add("-Xtrustall");
        List<String> vmArgs = new ArrayList<>();
        vmArgs.add("-Ditw.test.hold.seconds=" + holdSeconds);
        vmArgs.add("-Djava.awt.headless=true");
        JavaVersionUtils.addSecurityManagerCompatibilityArgs(vmArgs,
                javaHome != null ? javaHome.getAbsolutePath() : null);
        for (String vmArg : vmArgs) {
            command.add("-J" + vmArg);
        }
        command.add(jnlp.toURI().toURL().toExternalForm());

        ProcessBuilder pb = new ProcessBuilder(command);
        if (javaHome != null) {
            pb.environment().put("JAVA_HOME", javaHome.getAbsolutePath());
        }
        pb.environment().put("XDG_CONFIG_HOME", testConfigHome());
        pb.environment().put("XDG_CACHE_HOME", testCacheHome());
        pb.environment().put("HOME", System.getProperty("user.home"));
        propagateJdkDiscoveryEnvironment(pb);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        launched.add(process);
        return process;
    }

    /**
     * {@code deployment.user.logdir} default: {@code $XDG_CONFIG_HOME/icedtea-web/log}
     * ({@link net.sourceforge.jnlp.config.PathsAndFiles#LOG_DIR}).
     * Files: {@code itw-javantx-*.log}, {@code itw-clienta-*.log} ({@link net.sourceforge.jnlp.util.logging.FileLog}).
     */
    static File icedteaWebLogDir() {
        return new File(testConfigHome(), "icedtea-web/log");
    }

    static void clearIcedTeaWebLogs() throws IOException {
        File logDir = icedteaWebLogDir();
        if (!logDir.isDirectory()) {
            return;
        }
        File[] files = logDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && !file.delete()) {
                throw new IOException("Failed to delete " + file);
            }
        }
    }

    /** Matches {@link net.sourceforge.jnlp.util.logging.FileLog#createAppFileLog()}. */
    static final String CLIENTAPP_LOG_PREFIX = "itw-clienta-";

    static String readJavantxLogSince(long sinceMs, long timeoutMs) throws Exception {
        return readAllLogsSince(JAVANTX_LOG_PREFIX, sinceMs, timeoutMs);
    }

    static String readClientAppLogSince(long sinceMs, long timeoutMs) throws Exception {
        return readAllLogsSince(CLIENTAPP_LOG_PREFIX, sinceMs, timeoutMs);
    }

    private static String readAllLogsSince(String prefix, long sinceMs, long timeoutMs) throws Exception {
        List<File> logFiles = waitForLogFiles(prefix, sinceMs, timeoutMs);
        StringBuilder combined = new StringBuilder();
        for (File logFile : logFiles) {
            combined.append(Files.readString(logFile.toPath(), StandardCharsets.UTF_8));
            combined.append('\n');
        }
        return combined.toString();
    }

    private static List<File> waitForLogFiles(String prefix, long sinceMs, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<File> files = logFilesWithPrefixSince(prefix, sinceMs);
            if (!files.isEmpty()) {
                return files;
            }
            Thread.sleep(200);
        }
        return logFilesWithPrefixSince(prefix, sinceMs);
    }

    private static List<File> logFilesWithPrefixSince(String prefix, long sinceMs) {
        File logDir = icedteaWebLogDir();
        File[] files = logDir.listFiles((dir, name) -> name.startsWith(prefix));
        if (files == null || files.length == 0) {
            return List.of();
        }
        return Arrays.stream(files)
                .filter(f -> f.lastModified() >= sinceMs - 2000L)
                .sorted(Comparator.comparingLong(File::lastModified))
                .collect(Collectors.toList());
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
        return javaExecutable(new File(property)).canExecute();
    }

    private static File javaExecutable(File javaHome) {
        String name = System.getProperty("os.name", "").toLowerCase().contains("windows")
                ? "java.exe" : "java";
        return new File(javaHome, "bin/" + name);
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

    private static void propagateJdkDiscoveryEnvironment(ProcessBuilder pb) {
        copyEnvIfSet(pb, "DISPLAY");
        copyEnvIfSet(pb, "JDK11_HOME");
        copyEnvIfSet(pb, "JDK17_HOME");
        copyEnvIfSet(pb, "JDK21_HOME");
        copyEnvIfSet(pb, "JDK25_HOME");
        copyEnvIfSet(pb, "ITW_JDK17_HOME");
        copyEnvIfSet(pb, "ITW_JDK21_HOME");
        copyEnvIfSet(pb, "ITW_JDK25_HOME");
        for (int major : new int[] {11, 17, 21, 25}) {
            String property = System.getProperty("itw.jdk" + major + ".home");
            if (property != null && !property.isBlank()) {
                pb.environment().put("JDK" + major + "_HOME", property.trim());
                pb.environment().put("ITW_JDK" + major + "_HOME", property.trim());
            }
        }
    }

    private static void copyEnvIfSet(ProcessBuilder pb, String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            pb.environment().put(name, value.trim());
        }
    }

    private static String testConfigHome() {
        return ControlPanelTestSupport.deploymentPropertiesFile().getParentFile().getParent();
    }

    private static String testCacheHome() {
        String configHome = testConfigHome();
        if (configHome.contains(File.separator + ".config")) {
            return configHome.replace(File.separator + ".config",
                    File.separator + ".cache");
        }
        return configHome.replace("/.config", "/.cache");
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
