package net.sourceforge.jnlp.util;

import java.util.List;

/**
 * Helpers for comparing the running JRE feature release.
 */
public final class JavaVersionUtils {

    /** JDK 18+ rejects {@code setSecurityManager} unless {@code java.security.manager=allow}. */
    public static final int SECURITY_MANAGER_ALLOW_REQUIRED_MAJOR = 18;

    /** SecurityManager was removed in JDK 24 (JEP 486). */
    public static final int SECURITY_MANAGER_REMOVED_MAJOR = 24;

    private static final String SECURITY_MANAGER_PROPERTY = "java.security.manager";
    private static final String SECURITY_MANAGER_ALLOW = "allow";

    private JavaVersionUtils() {
    }

    public static int getRunningMajorVersion() {
        return parseMajorVersion(System.getProperty("java.specification.version"));
    }

    static int parseMajorVersion(String spec) {
        if (spec == null || spec.isEmpty()) {
            return 8;
        }
        if (spec.startsWith("1.")) {
            String[] parts = spec.split("\\.");
            if (parts.length > 1) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    return 8;
                }
            }
            return 8;
        }
        try {
            return Integer.parseInt(spec.split("\\.")[0]);
        } catch (NumberFormatException ignored) {
            return 8;
        }
    }

    public static boolean needsSecurityManagerAllowFlag(int javaMajorVersion) {
        return javaMajorVersion >= SECURITY_MANAGER_ALLOW_REQUIRED_MAJOR
                && javaMajorVersion < SECURITY_MANAGER_REMOVED_MAJOR;
    }

    public static boolean isSecurityManagerSupported() {
        int major = getRunningMajorVersion();
        if (major >= SECURITY_MANAGER_REMOVED_MAJOR) {
            return false;
        }
        if (needsSecurityManagerAllowFlag(major)) {
            return SECURITY_MANAGER_ALLOW.equals(System.getProperty(SECURITY_MANAGER_PROPERTY));
        }
        return true;
    }

    public static void addSecurityManagerCompatibilityArgs(List<String> vmArgs, String javaHome) {
        int major = resolveMajorVersion(javaHome);
        if (!needsSecurityManagerAllowFlag(major) || hasSecurityManagerProperty(vmArgs)) {
            return;
        }
        vmArgs.add("-D" + SECURITY_MANAGER_PROPERTY + "=" + SECURITY_MANAGER_ALLOW);
    }

    public static String formatSecurityManagerCompatibilityJvmArgs(String javaHome) {
        int major = resolveMajorVersion(javaHome);
        if (!needsSecurityManagerAllowFlag(major)) {
            return "";
        }
        return "-D" + SECURITY_MANAGER_PROPERTY + "=" + SECURITY_MANAGER_ALLOW;
    }

    private static int resolveMajorVersion(String javaHome) {
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            return JvmAutodetector.majorVersionOfJvmHome(javaHome.trim());
        }
        return getRunningMajorVersion();
    }

    private static boolean hasSecurityManagerProperty(List<String> vmArgs) {
        if (vmArgs == null) {
            return false;
        }
        String prefix = "-D" + SECURITY_MANAGER_PROPERTY + "=";
        for (String arg : vmArgs) {
            if (arg != null && arg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
