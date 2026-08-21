package net.sourceforge.jnlp.util;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.FileLog;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * JDK-version relaunch handoff: spawn the selected-JVM wrapper child with
 * file/NUL stdio (never pipes that can freeze Windows when the parent exits),
 * write an audit record, then exit the parent without waiting.
 * <p>
 * The console wrapper ({@code javawsc}) never takes this path: scripts wait on
 * that binary, so the parent inherits IO and waits. {@code javaws} still
 * detaches by default.
 * <p>
 * Opt out for {@code javaws} with {@code deployment.keepJavawsProcess=true} or
 * {@code deployment.keepJavawsRelaunchProcess=true} to restore the legacy
 * inherit-IO + wait path. One keep-process knob is enough.
 */
public final class JavawsRelaunchHandoff {

    public static final String HANDOFF_STEP = "jdk-relaunch";
    private static final String CHILD_PID_PLACEHOLDER = "<pending>";

    private JavawsRelaunchHandoff() {
    }

    /**
     * @return {@code true} when the {@code javaws} parent should detach and exit
     *         (default); {@code false} for {@code javawsc}, or when
     *         {@code deployment.keepJavawsProcess} /
     *         {@code deployment.keepJavawsRelaunchProcess} is {@code true}
     */
    public static boolean shouldHandoff(DeploymentConfiguration config) {
        if (ItwLauncherPaths.isConsoleWrapperProcess()) {
            return false;
        }
        if (readBoolean(config, DeploymentConfiguration.KEY_KEEP_JAVAWS_PROCESS, false)) {
            return false;
        }
        return !readBoolean(config, DeploymentConfiguration.KEY_KEEP_JAVAWS_RELAUNCH_PROCESS, false);
    }

    /**
     * Start {@code command} with safe stdio redirection, write the handoff audit
     * log, then exit this JVM. Does not return on success.
     */
    public static void handoffAndExit(List<String> command, ProcessBuilder environmentSource) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("relaunch command is empty");
        }
        File logFile = createLogFile();
        Files.createDirectories(logFile.getParentFile().toPath());

        String executable = command.get(0);
        List<String> args = command.size() == 1
                ? new ArrayList<String>()
                : new ArrayList<>(command.subList(1, command.size()));

        String childJavaHome = environmentSource == null ? null : environmentSource.environment().get("JAVA_HOME");
        writeHandoffRecord(logFile, CHILD_PID_PLACEHOLDER, executable, command, childJavaHome);

        ProcessBuilder pb = new ProcessBuilder(command);
        if (environmentSource != null && environmentSource.environment() != null) {
            pb.environment().clear();
            pb.environment().putAll(environmentSource.environment());
        }
        applyDetachedStdio(pb, logFile);

        Process child = pb.start();
        long childPid = child.pid();
        patchChildPid(logFile, childPid);
        appendHandoffComplete(logFile, childPid);

        String summary = buildExitSummary(executable, args, childPid, logFile);
        OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL, summary);
        OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                "JDK relaunch handoff complete: parent exiting without waiting for child process.");

        JNLPRuntime.exit(0);
    }

    static void applyDetachedStdio(ProcessBuilder pb, File logFile) {
        // Never inheritIO() or use pipes here: on Windows a parent exit with live
        // console/pipe handles can stall the child (full STDOUT/STDERR buffer freeze).
        // Merge stderr into stdout and append both to one file; interleaving is fine.
        File nullDevice = nullDevice();
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice));
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
    }

    static File nullDevice() {
        return JNLPRuntime.isWindows() ? new File("NUL") : new File("/dev/null");
    }

    static File createLogFile() {
        File logDir = new File(PathsAndFiles.LOG_DIR.getFullPath());
        String stamp = FileLog.getStamp();
        long pid = JnlpRunningProcessSupport.currentPid();
        String base = "itw-javantx-" + stamp + "-" + pid + "-relaunch";
        return new File(logDir, base + ".log");
    }

    static void writeHandoffRecord(File logFile, String childPidToken, String executable, List<String> command)
            throws IOException {
        writeHandoffRecord(logFile, childPidToken, executable, command, null);
    }

    static void writeHandoffRecord(File logFile, String childPidToken, String executable, List<String> command,
            String childJavaHome) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("IcedTea-Web JDK relaunch handoff record").append('\n');
        builder.append("Handoff step: ").append(HANDOFF_STEP).append('\n');
        builder.append("Handoff status: SUCCESS").append('\n');
        builder.append("Parent process ID (handing off): ")
                .append(JnlpRunningProcessSupport.currentPid()).append('\n');
        builder.append("Parent process name: ").append(currentProcessName()).append('\n');
        builder.append("Parent JVM vendor: ").append(System.getProperty("java.vendor", "")).append('\n');
        builder.append("Parent JVM version: ").append(System.getProperty("java.version", "")).append('\n');
        builder.append("Parent java.home: ").append(System.getProperty("java.home", "")).append('\n');
        builder.append("Parent is exiting after handoff: true").append('\n');
        builder.append("Child process ID (handed to): ").append(childPidToken).append('\n');
        builder.append("Child executable: ").append(executable).append('\n');
        if (childJavaHome != null && !childJavaHome.trim().isEmpty()) {
            builder.append("Child JAVA_HOME: ").append(childJavaHome.trim()).append('\n');
        }
        builder.append("Standard Input stream: ")
                .append(nullDevice().getPath())
                .append(" (no pipe from parent; child cannot block parent on stdin)").append('\n');
        builder.append("Standard Output and Standard Error written to: ")
                .append(logFile.getAbsolutePath())
                .append(" (combined file redirect; parent exited; child writes directly; no pipe buffer stall risk)")
                .append('\n');
        builder.append("Working directory: ").append(System.getProperty("user.dir", "")).append('\n');
        builder.append("OS: ").append(System.getProperty("os.name", "")).append(' ')
                .append(System.getProperty("os.version", "")).append('\n');
        builder.append("Command:").append('\n');
        builder.append(joinCommand(command)).append('\n');
        builder.append('\n');
        Files.write(logFile.toPath(), builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void patchChildPid(File logFile, long childPid) throws IOException {
        String text = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        String patched = text.replace(
                "Child process ID (handed to): " + CHILD_PID_PLACEHOLDER,
                "Child process ID (handed to): " + childPid);
        if (!patched.equals(text)) {
            Files.write(logFile.toPath(), patched.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void appendHandoffComplete(File logFile, long childPid) throws IOException {
        String line = "Handoff complete: parent launcher exiting without waiting for child process "
                + childPid + ".\n";
        try (Writer writer = new OutputStreamWriter(
                Files.newOutputStream(logFile.toPath(),
                        java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.APPEND),
                StandardCharsets.UTF_8)) {
            writer.write(line);
        }
    }

    private static String buildExitSummary(String executable, List<String> args, long childPid, File logFile) {
        return "JDK relaunch handoff: parent pid=" + JnlpRunningProcessSupport.currentPid()
                + " (" + System.getProperty("java.vendor") + " " + System.getProperty("java.version") + ")"
                + " -> child pid=" + childPid
                + " executable=" + executable
                + " log=" + logFile.getAbsolutePath()
                + " args=" + args;
    }

    private static String currentProcessName() {
        try {
            String cmd = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return cmd == null ? "java" : cmd;
        } catch (Exception ex) {
            return "java";
        }
    }

    private static String joinCommand(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            String arg = command.get(i);
            if (arg.indexOf(' ') >= 0) {
                sb.append('"').append(arg).append('"');
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }

    private static boolean readBoolean(DeploymentConfiguration config, String key, boolean defaultValue) {
        if (config == null) {
            return defaultValue;
        }
        String raw = config.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(lower) || "1".equals(lower) || "yes".equals(lower)) {
            return true;
        }
        if ("false".equals(lower) || "0".equals(lower) || "no".equals(lower)) {
            return false;
        }
        return defaultValue;
    }

}
