package net.sourceforge.jnlp.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.sourceforge.jnlp.Launcher;
import net.sourceforge.jnlp.runtime.Boot;
import net.sourceforge.jnlp.runtime.Translator;

/**
 * Launches a JNLP URL through the external javaws wrapper.
 */
public final class JnlpAssignmentLauncher {

    private JnlpAssignmentLauncher() {
    }

    public interface LaunchOutputConsumer {
        void accept(String chunk);
    }

    public static final class LaunchResult {
        private final List<String> command;
        private final String output;
        private final Integer exitCode;
        private final boolean stillRunning;

        LaunchResult(List<String> command, String output, Integer exitCode, boolean stillRunning) {
            this.command = command;
            this.output = output == null ? "" : output;
            this.exitCode = exitCode;
            this.stillRunning = stillRunning;
        }

        public String getCommandLine() {
            return String.join(" ", command);
        }

        public String getOutput() {
            return output;
        }

        public Integer getExitCode() {
            return exitCode;
        }

        public boolean isStillRunning() {
            return stillRunning;
        }
    }

    public static String canonicalizeJnlpUrl(String jnlpUrl) {
        if (jnlpUrl == null) {
            return "";
        }
        String trimmed = jnlpUrl.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        try {
            URI uri = new URI(trimmed);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return new java.io.File(uri).getCanonicalFile().toURI().toString();
            }
            return trimmed;
        } catch (Exception ex) {
            return trimmed;
        }
    }

    public static List<String> buildLaunchCommand(String javawsBin, String jnlpUrl, String javaHome) {
        List<String> command = new ArrayList<>();
        command.add(javawsBin);
        List<String> vmArgs = new ArrayList<>();
        JavaVersionUtils.removeLegacyJavaXmlBindAddModules(vmArgs, javaHome);
        JavaVersionUtils.addSecurityManagerCompatibilityArgs(vmArgs, javaHome);
        for (String vmArg : vmArgs) {
            command.add("-J" + vmArg);
        }
        command.add(canonicalizeJnlpUrl(jnlpUrl));
        return command;
    }

    public static Process launch(String jnlpUrl, String javaHome) throws IOException {
        String javawsBin = resolveJavawsBin();
        if (javawsBin == null) {
            throw new IOException("javaws launcher not found");
        }
        ProcessBuilder pb = new ProcessBuilder(buildLaunchCommand(javawsBin, jnlpUrl, javaHome));
        propagateLaunchEnvironment(pb);
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            pb.environment().put("JAVA_HOME", javaHome.trim());
        }
        return pb.start();
    }

    public static String formatLaunchMetrics(String jnlpUrl, String javaHome) {
        StringBuilder sb = new StringBuilder();
        String itwVersion = Boot.version != null && !Boot.version.trim().isEmpty()
                ? Boot.version.trim() : "unknown";
        sb.append(Translator.R("CPJDKAssignmentsLaunchOutputItwVersion")).append(' ').append(itwVersion).append('\n');
        String javawsBin = resolveJavawsBin();
        sb.append(Translator.R("CPJDKAssignmentsLaunchOutputLauncher")).append(' ');
        sb.append(javawsBin == null ? Translator.R("CPJDKAssignmentsLaunchNoLauncher") : javawsBin).append('\n');
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            sb.append(Translator.R("CPJDKAssignmentsLaunchOutputJavaHome")).append(' ').append(javaHome.trim()).append('\n');
            sb.append(Translator.R("CPJDKAssignmentsLaunchOutputJvm")).append(' ');
            sb.append(JvmDescriptor.describe(javaHome.trim()).getDisplayName()).append('\n');
        }
        sb.append(Translator.R("CPJDKAssignmentsLaunchOutputJnlp")).append(' ').append(jnlpUrl).append('\n');
        String compatArgs = JavaVersionUtils.formatSecurityManagerCompatibilityJvmArgs(javaHome);
        if (!compatArgs.isEmpty()) {
            sb.append(Translator.R("CPJDKAssignmentsLaunchOutputJvmArgs")).append(' ').append(compatArgs).append('\n');
        }
        sb.append("\n");
        sb.append(Translator.R("CPJDKAssignmentsLaunchOutputStarting")).append("\n\n");
        return sb.toString();
    }

    public static LaunchResult launchWithCapturedOutput(String jnlpUrl, String javaHome, long captureTimeoutMs)
            throws IOException {
        final StringBuilder captured = new StringBuilder();
        return launchWithStreamingOutput(jnlpUrl, javaHome, captureTimeoutMs, new LaunchOutputConsumer() {
            @Override
            public void accept(String chunk) {
                synchronized (captured) {
                    captured.append(chunk);
                }
            }
        });
    }

    public static LaunchResult launchWithStreamingOutput(String jnlpUrl, String javaHome, long captureTimeoutMs,
            LaunchOutputConsumer consumer) throws IOException {
        String javawsBin = resolveJavawsBin();
        if (javawsBin == null) {
            throw new IOException("javaws launcher not found");
        }
        List<String> command = buildLaunchCommand(javawsBin, jnlpUrl, javaHome);
        ProcessBuilder pb = new ProcessBuilder(command);
        propagateLaunchEnvironment(pb);
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            pb.environment().put("JAVA_HOME", javaHome.trim());
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();
        final StringBuilder captured = new StringBuilder();
        Thread reader = startOutputReader(process, captured, consumer);
        long deadline = captureTimeoutMs > 0 ? System.currentTimeMillis() + captureTimeoutMs : Long.MAX_VALUE;
        Integer exitCode = null;
        boolean stillRunning = true;
        while (System.currentTimeMillis() < deadline) {
            try {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                if (process.waitFor(Math.min(remaining, 250L), TimeUnit.MILLISECONDS)) {
                    exitCode = process.exitValue();
                    stillRunning = false;
                    break;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try {
            reader.join(1000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return new LaunchResult(command, captured.toString(), exitCode, stillRunning);
    }

    private static Thread startOutputReader(final Process process, final StringBuilder captured,
            final LaunchOutputConsumer consumer) {
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try (InputStream in = process.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                        synchronized (captured) {
                            captured.append(chunk);
                        }
                        if (consumer != null) {
                            consumer.accept(chunk);
                        }
                    }
                } catch (IOException ex) {
                    String chunk = ex.toString() + '\n';
                    synchronized (captured) {
                        captured.append(chunk);
                    }
                    if (consumer != null) {
                        consumer.accept(chunk);
                    }
                }
            }
        }, "jnlp-assignment-launch-output");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    public static String resolveJavawsBin() {
        return ItwLauncherPaths.resolveJavawsBin();
    }

    private static void propagateLaunchEnvironment(ProcessBuilder pb) {
        copyEnvIfSet(pb, "XDG_CONFIG_HOME");
        copyEnvIfSet(pb, "XDG_CACHE_HOME");
        copyEnvIfSet(pb, "XDG_DATA_HOME");
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.trim().isEmpty()) {
            pb.environment().put("HOME", userHome.trim());
        }
    }

    private static void copyEnvIfSet(ProcessBuilder pb, String name) {
        String value = System.getenv(name);
        if (value != null && !value.trim().isEmpty()) {
            pb.environment().put(name, value.trim());
        }
    }
}
