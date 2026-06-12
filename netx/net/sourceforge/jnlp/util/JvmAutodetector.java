package net.sourceforge.jnlp.util;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import net.sourceforge.jnlp.runtime.JNLPRuntime;

/**
 * Discovers installed JDK homes from common vendor locations and environment
 * variables. Uses predefined directory patterns only (no full filesystem walk).
 */
public final class JvmAutodetector {

    private JvmAutodetector() {
    }

    public static List<String> discoverValidJvmHomes() {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> valid = new ArrayList<>();
        for (String candidate : discoverCandidateHomes()) {
            String canonical = canonicalHome(candidate);
            if (canonical == null || !seen.add(canonical)) {
                continue;
            }
            JvmDescriptor descriptor = JvmDescriptor.describe(canonical);
            if (descriptor.isValid()) {
                valid.add(canonical);
            }
        }
        return valid;
    }

    static List<String> discoverCandidateHomes() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addEnvCandidates(candidates);
        if (JNLPRuntime.isWindows()) {
            addWindowsCandidates(candidates);
        } else if (isMacOs()) {
            addMacCandidates(candidates);
        } else {
            addUnixCandidates(candidates);
        }
        return new ArrayList<>(candidates);
    }

    private static void addEnvCandidates(LinkedHashSet<String> candidates) {
        addCandidate(candidates, System.getenv("JAVA_HOME"));
        addCandidate(candidates, System.getenv("JDK_HOME"));
        addCandidate(candidates, System.getenv("JRE_HOME"));
        addCandidate(candidates, System.getenv("JDK8_HOME"));
        addCandidate(candidates, System.getenv("JDK11_HOME"));
        addCandidate(candidates, System.getenv("JDK17_HOME"));
        addCandidate(candidates, System.getenv("JDK21_HOME"));
        addCandidate(candidates, System.getenv("JDK25_HOME"));
        addCandidate(candidates, System.getProperty("java.home"));
    }

    private static void addUnixCandidates(LinkedHashSet<String> candidates) {
        addChildren(candidates, "/usr/lib/jvm");
        addChildren(candidates, "/usr/lib64/jvm");
        addChildren(candidates, "/usr/local/lib/jvm");
        addChildren(candidates, "/usr/java");
        addChildren(candidates, "/usr/local/java");
        addChildren(candidates, "/opt/java");
        addChildren(candidates, "/opt/jdk");
        addChildren(candidates, "/opt/openjdk");
        addChildren(candidates, "/opt/homebrew/opt");
        addChildren(candidates, "/usr/local/opt");
    }

    private static void addMacCandidates(LinkedHashSet<String> candidates) {
        addMacBundleHomes(candidates, "/Library/Java/JavaVirtualMachines");
        addMacBundleHomes(candidates, "/System/Library/Java/JavaVirtualMachines");
        addChildren(candidates, "/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home");
        addChildren(candidates, "/usr/local/opt/openjdk");
        addChildren(candidates, "/opt/homebrew/opt/openjdk");
    }

    private static void addWindowsCandidates(LinkedHashSet<String> candidates) {
        String programFiles = System.getenv("ProgramFiles");
        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        if (programFiles != null) {
            addChildren(candidates, programFiles + "\\Java");
            addChildren(candidates, programFiles + "\\Eclipse Adoptium");
            addChildren(candidates, programFiles + "\\Amazon Corretto");
            addChildren(candidates, programFiles + "\\Microsoft");
            addChildren(candidates, programFiles + "\\BellSoft");
            addChildren(candidates, programFiles + "\\Zulu");
            addChildren(candidates, programFiles + "\\OpenJDK");
        }
        if (programFilesX86 != null) {
            addChildren(candidates, programFilesX86 + "\\Java");
        }
    }

    private static void addMacBundleHomes(LinkedHashSet<String> candidates, String rootPath) {
        File root = new File(rootPath);
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            addCandidate(candidates, new File(child, "Contents/Home").getAbsolutePath());
            addCandidate(candidates, child.getAbsolutePath());
        }
    }

    private static void addChildren(LinkedHashSet<String> candidates, String rootPath) {
        if (rootPath == null || rootPath.trim().isEmpty()) {
            return;
        }
        File root = new File(rootPath);
        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                addCandidate(candidates, child.getAbsolutePath());
            }
        }
    }

    private static void addCandidate(LinkedHashSet<String> candidates, String path) {
        if (path != null && !path.trim().isEmpty()) {
            candidates.add(path.trim());
        }
    }

    private static String canonicalHome(String path) {
        try {
            File home = new File(path).getCanonicalFile();
            if (!home.isDirectory()) {
                return null;
            }
            File javaBinary = new File(home, "bin" + File.separator + "java"
                    + (JNLPRuntime.isWindows() ? ".exe" : ""));
            if (!javaBinary.isFile()) {
                return null;
            }
            return home.getAbsolutePath();
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isMacOs() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).startsWith("mac");
    }

    public static int majorVersionOfJvmHome(String homePath) {
        if (homePath == null || homePath.trim().isEmpty()) {
            return 0;
        }
        return JvmSelector.parseMajor(JvmDescriptor.describe(homePath.trim()).getVersion());
    }
}
