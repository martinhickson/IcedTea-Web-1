package net.sourceforge.jnlp.util;

import java.util.List;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Helpers for comparing the running JRE feature release.
 */
public final class JavaVersionUtils {

    /** JDK 18+ rejects {@code setSecurityManager} unless {@code java.security.manager=allow}. */
    public static final int SECURITY_MANAGER_ALLOW_REQUIRED_MAJOR = 18;

    /** SecurityManager was removed in JDK 24 (JEP 486). */
    public static final int SECURITY_MANAGER_REMOVED_MAJOR = 24;

    /** JAXB and related EE modules were removed from the default module graph in JDK 11 (JEP 320). */
    public static final int LEGACY_EE_MODULES_FIRST_MAJOR = 11;

    /** JAXB modules were removed from the JDK entirely in JDK 17 (JEP 320 follow-on). */
    public static final int LEGACY_EE_MODULES_LAST_MAJOR = 16;

    private static final String ADD_MODULES_FLAG = "--add-modules";
    private static final String LEGACY_EE_MODULE_LIST = "java.xml.bind,java.activation";
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

    /**
     * Drops legacy {@code --add-modules=java.xml.bind} JNLP vm-args on JDK 11+.
     * IcedTea-Web supplies compatibility module settings on relaunch where applicable.
     */
    public static void removeLegacyJavaXmlBindAddModules(List<String> vmArgs, String javaHome) {
        if (vmArgs == null || vmArgs.isEmpty()) {
            return;
        }
        int major = resolveMajorVersion(javaHome);
        if (major < LEGACY_EE_MODULES_FIRST_MAJOR) {
            return;
        }
        for (int i = 0; i < vmArgs.size(); i++) {
            String arg = vmArgs.get(i);
            if (arg == null) {
                continue;
            }
            if (isJavaXmlBindAddModulesEqualsForm(arg)) {
                logIgnoredJavaXmlBindAddModules(major, arg);
                vmArgs.remove(i);
                i--;
                continue;
            }
            if (ADD_MODULES_FLAG.equals(arg) && i + 1 < vmArgs.size()) {
                String modules = vmArgs.get(i + 1);
                if (refersToJavaXmlBind(modules)) {
                    logIgnoredJavaXmlBindAddModules(major, ADD_MODULES_FLAG + " " + modules);
                    vmArgs.remove(i + 1);
                    vmArgs.remove(i);
                    i--;
                }
            }
        }
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

    private static boolean isJavaXmlBindAddModulesEqualsForm(String arg) {
        return arg.startsWith(ADD_MODULES_FLAG + "=") && refersToJavaXmlBind(arg.substring(ADD_MODULES_FLAG.length() + 1));
    }

    private static boolean refersToJavaXmlBind(String modules) {
        return modules != null && modules.contains("java.xml.bind");
    }

    private static void logIgnoredJavaXmlBindAddModules(int javaMajorVersion, String ignoredArg) {
        OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                "Ignoring java-vm-args entry for JDK " + javaMajorVersion + "+: " + ignoredArg
                        + " (the java.xml.bind module is not present on JDK 11 and later;"
                        + " IcedTea-Web adds compatibility module settings automatically where supported)");
    }

    private static boolean hasLegacyJavaEeModulesArg(List<String> vmArgs) {
        if (vmArgs == null) {
            return false;
        }
        for (int i = 0; i < vmArgs.size(); i++) {
            String arg = vmArgs.get(i);
            if (!ADD_MODULES_FLAG.equals(arg)) {
                continue;
            }
            if (i + 1 < vmArgs.size()) {
                String modules = vmArgs.get(i + 1);
                if (modules != null && modules.contains("java.xml.bind")) {
                    return true;
                }
            }
        }
        return false;
    }
}
