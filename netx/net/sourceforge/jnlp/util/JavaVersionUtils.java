package net.sourceforge.jnlp.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final String ADD_EXPORTS_FLAG = "--add-exports";
    private static final String ADD_OPENS_FLAG = "--add-opens";
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
     * Appends {@code --add-exports}/{@code --add-opens} required for IcedTea-Web on JDK 9+.
     * Mirrors the .NET / shell wrapper {@code ModularJdkArguments} lists so bare
     * {@code java -cp} relaunches do not die with {@code IllegalAccessError}.
     */
    public static void addModularJdkCompatibilityArgs(List<String> vmArgs, String javaHome) {
        if (vmArgs == null) {
            return;
        }
        int major = resolveMajorVersion(javaHome);
        if (major < 9) {
            return;
        }
        List<String> modular = modularJdkArguments();
        for (int i = 0; i + 1 < modular.size(); i += 2) {
            String flag = modular.get(i);
            String value = modular.get(i + 1);
            if (!containsFlagValuePair(vmArgs, flag, value)) {
                vmArgs.add(flag);
                vmArgs.add(value);
            }
        }
    }

    /**
     * Modular access flags matching {@code ModularJdkArguments} in the .NET launcher.
     */
    static List<String> modularJdkArguments() {
        List<String> args = new ArrayList<>();
        addExport(args, "java.base/sun.net.www.protocol.jar=ALL-UNNAMED");
        addOpen(args, "java.base/sun.net.www.protocol.jar=ALL-UNNAMED");
        addExport(args, "java.base/sun.security.action=ALL-UNNAMED");
        addExport(args, "java.base/sun.security.provider=ALL-UNNAMED");
        addExport(args, "java.base/sun.security.util=ALL-UNNAMED");
        addExport(args, "java.base/sun.security.validator=ALL-UNNAMED");
        addExport(args, "java.base/sun.security.x509=ALL-UNNAMED");
        addExport(args, "java.base/jdk.internal.util.jar=ALL-UNNAMED");
        addOpen(args, "java.base/jdk.internal.util.jar=ALL-UNNAMED");
        addExport(args, "java.base/sun.net.www.protocol.http=ALL-UNNAMED");
        addExport(args, "java.desktop/sun.applet=ALL-UNNAMED");
        addExport(args, "java.desktop/sun.awt=ALL-UNNAMED");
        addExport(args, "java.desktop/sun.awt.image=ALL-UNNAMED");
        addExport(args, "java.desktop/sun.swing.table=ALL-UNNAMED");
        addExport(args, "java.desktop/sun.swing=ALL-UNNAMED");
        addExport(args, "java.desktop/sun.swing.plaf=ALL-UNNAMED");
        addExport(args, "java.naming/com.sun.jndi.toolkit.url=ALL-UNNAMED");
        addOpen(args, "java.base/java.lang=ALL-UNNAMED");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            addExport(args, "java.desktop/sun.awt.windows=ALL-UNNAMED");
            addExport(args, "java.desktop/com.sun.java.swing.plaf.windows=ALL-UNNAMED");
        } else if (os.contains("linux")) {
            addExport(args, "java.desktop/sun.awt.X11=ALL-UNNAMED");
        }
        return args;
    }

    private static void addExport(List<String> args, String value) {
        args.add(ADD_EXPORTS_FLAG);
        args.add(value);
    }

    private static void addOpen(List<String> args, String value) {
        args.add(ADD_OPENS_FLAG);
        args.add(value);
    }

    private static boolean containsFlagValuePair(List<String> args, String flag, String value) {
        for (int i = 0; i + 1 < args.size(); i++) {
            if (flag.equals(args.get(i)) && value.equals(args.get(i + 1))) {
                return true;
            }
        }
        return false;
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
