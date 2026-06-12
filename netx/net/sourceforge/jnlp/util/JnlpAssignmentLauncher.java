package net.sourceforge.jnlp.util;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import net.sourceforge.jnlp.Launcher;

/**
 * Launches a JNLP URL through the external javaws wrapper.
 */
public final class JnlpAssignmentLauncher {

    private JnlpAssignmentLauncher() {
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
                return new File(uri).getCanonicalFile().toURI().toString();
            }
            return trimmed;
        } catch (Exception ex) {
            return trimmed;
        }
    }

    public static List<String> buildLaunchCommand(String javawsBin, String jnlpUrl, String javaHome) {
        List<String> command = new ArrayList<>();
        command.add(javawsBin);
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

    public static String resolveJavawsBin() {
        String configured = System.getProperty(Launcher.KEY_JAVAWS_LOCATION);
        if (configured != null && !configured.trim().isEmpty()) {
            File file = new File(configured.trim());
            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        String fromPath = XDesktopEntry.getJavaWsBin();
        if (fromPath != null && !fromPath.trim().isEmpty() && !"javaws".equals(fromPath)) {
            File file = new File(fromPath.trim());
            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
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
