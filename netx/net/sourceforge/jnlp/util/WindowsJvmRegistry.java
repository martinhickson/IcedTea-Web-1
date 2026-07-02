package net.sourceforge.jnlp.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.jnlp.runtime.JNLPRuntime;

/**
 * Reads installed JDK {@code JavaHome} values from the Windows registry.
 * Mirrors the discovery logic in {@code .powershell/workflows/jdk.ps1}.
 */
final class WindowsJvmRegistry {

    private static final Pattern JAVA_HOME_LINE = Pattern.compile(
            "^\\s*JavaHome\\s+REG_(?:SZ|EXPAND_SZ)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    private static final String[] REGISTRY_ROOTS = {
        "HKLM\\SOFTWARE\\JavaSoft\\JDK",
        "HKLM\\SOFTWARE\\JavaSoft\\Java Development Kit",
        "HKLM\\SOFTWARE\\JavaSoft\\Java Runtime Environment",
        "HKLM\\SOFTWARE\\Eclipse Adoptium\\JDK",
        "HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft\\JDK",
        "HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft\\Java Development Kit",
        "HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft\\Java Runtime Environment",
        "HKLM\\SOFTWARE\\WOW6432Node\\Eclipse Adoptium\\JDK",
    };

    private WindowsJvmRegistry() {
    }

    static List<String> discoverJavaHomes() {
        if (!JNLPRuntime.isWindows()) {
            return List.of();
        }
        LinkedHashSet<String> homes = new LinkedHashSet<>();
        for (String root : REGISTRY_ROOTS) {
            addHomesFromRegistryRoot(homes, root);
        }
        return new ArrayList<>(homes);
    }

    private static void addHomesFromRegistryRoot(LinkedHashSet<String> homes, String registryRoot) {
        for (String home : parseRegQueryOutput(runRegQuery(registryRoot))) {
            if (home != null && !home.isEmpty()) {
                homes.add(home);
            }
        }
    }

    private static String runRegQuery(String registryRoot) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "reg", "query", registryRoot, "/s", "/v", "JavaHome");
            pb.redirectErrorStream(true);
            process = pb.start();
            StringBuilder output = new StringBuilder();
            Charset charset = Charset.defaultCharset();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            StreamUtils.waitForSafely(process);
            if (process.exitValue() != 0) {
                return "";
            }
            return output.toString();
        } catch (Exception ex) {
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    static List<String> parseRegQueryOutput(String output) {
        LinkedHashSet<String> homes = new LinkedHashSet<>();
        if (output == null || output.isEmpty()) {
            return List.of();
        }
        for (String line : output.split("\\R")) {
            Matcher matcher = JAVA_HOME_LINE.matcher(line);
            if (matcher.matches()) {
                homes.add(matcher.group(1).trim());
            }
        }
        return new ArrayList<>(homes);
    }
}
