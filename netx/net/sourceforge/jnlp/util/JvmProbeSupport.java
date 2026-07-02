package net.sourceforge.jnlp.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.jnlp.runtime.Boot;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.runtime.JavawsUberLauncher;

/**
 * Headless JVM version probing via the IcedTea-Web uber JAR {@code --java-version}
 * entry point (works with {@code javaw} on Windows without a console).
 */
public final class JvmProbeSupport {

    private static final Pattern QUOTED_JAVA_VERSION = Pattern.compile(
            "version \"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private JvmProbeSupport() {
    }

    public static File resolveLocalUberJar() {
        try {
            java.net.URL location = Boot.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            File jar = new File(location.toURI());
            return jar.isFile() ? jar : null;
        } catch (Exception ex) {
            return null;
        }
    }

    public static File resolveHeadlessProbeBinary(File javaBinary) {
        if (JNLPRuntime.isWindows()) {
            File javaw = new File(javaBinary.getParentFile(), "javaw.exe");
            if (javaw.isFile()) {
                return javaw;
            }
        }
        return javaBinary;
    }

    /**
     * @return JDK major version from {@link Boot#JAVA_VERSION_PROBE_ARG}, or {@code 0} on failure
     */
    public static int probeMajorVersion(String jdkHome) {
        if (jdkHome == null || jdkHome.trim().isEmpty()) {
            return 0;
        }
        File javaBinary = new File(jdkHome.trim() + File.separator + "bin" + File.separator + "java"
                + (JNLPRuntime.isWindows() ? ".exe" : ""));
        if (!javaBinary.isFile()) {
            return 0;
        }
        int probed = probeMajorVersionWithUberJar(javaBinary);
        if (probed > 0) {
            return probed;
        }
        return probeMajorVersionWithJavaVersion(javaBinary);
    }

    private static int probeMajorVersionWithUberJar(File javaBinary) {
        File uberJar = resolveLocalUberJar();
        if (uberJar == null) {
            return 0;
        }
        File probeBinary = resolveHeadlessProbeBinary(javaBinary);
        List<String> command = new ArrayList<>();
        command.add(probeBinary.getAbsolutePath());
        command.add("-Xms8m");
        command.add("-cp");
        command.add(uberJar.getAbsolutePath());
        command.add(JavawsUberLauncher.class.getName());
        command.add(Boot.JAVA_VERSION_PROBE_ARG);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        Process process = null;
        try {
            process = pb.start();
            String output = StreamUtils.readStreamAsString(process.getInputStream());
            StreamUtils.waitForSafely(process);
            if (process.exitValue() != 0) {
                return 0;
            }
            String firstLine = output.trim();
            int nl = firstLine.indexOf('\n');
            if (nl >= 0) {
                firstLine = firstLine.substring(0, nl).trim();
            }
            return Integer.parseInt(firstLine);
        } catch (Exception ex) {
            return 0;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    static int probeMajorVersionWithJavaVersion(File javaBinary) {
        if (javaBinary == null || !javaBinary.isFile()) {
            return 0;
        }
        File probeBinary = resolveHeadlessProbeBinary(javaBinary);
        ProcessBuilder pb = new ProcessBuilder(probeBinary.getAbsolutePath(), "-version");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        Process process = null;
        try {
            process = pb.start();
            String output = StreamUtils.readStreamAsString(process.getInputStream());
            StreamUtils.waitForSafely(process);
            return parseMajorVersionFromJavaVersionOutput(output);
        } catch (Exception ex) {
            return 0;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    static int parseMajorVersionFromJavaVersionOutput(String output) {
        if (output == null || output.isEmpty()) {
            return 0;
        }
        Matcher matcher = QUOTED_JAVA_VERSION.matcher(output);
        if (!matcher.find()) {
            return 0;
        }
        String versionToken = matcher.group(1).trim();
        if (versionToken.startsWith("1.")) {
            String[] parts = versionToken.split("[._-]");
            if (parts.length > 1) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            return 0;
        }
        String[] parts = versionToken.split("[._-]");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** Synthetic {@code java -version} text so existing parsers can read the major release. */
    public static String syntheticVersionOutput(int major) {
        if (major <= 0) {
            return "";
        }
        String quoted = major >= 9 ? Integer.toString(major) : "1." + major + ".0";
        return "version \"" + quoted + "\"";
    }
}
