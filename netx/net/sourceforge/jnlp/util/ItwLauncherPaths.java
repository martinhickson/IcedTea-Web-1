package net.sourceforge.jnlp.util;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.sourceforge.jnlp.Launcher;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.runtime.JavawsUberLauncher;

/**
 * Resolves how to launch an external {@code javaws} process.
 * <p>
 * Native/shell wrappers ({@code javaws}, {@code javawsc}, {@code itweb-settings})
 * set {@link #ENV_NATIVE_LAUNCHER}{@code =1}. When that is present, external
 * launches use the {@code javaws} sibling in the same {@code bin/} directory.
 * When it is absent, this process was started with bare {@code java -cp} (or
 * equivalent) and external launches must use the same uber-JAR entry point.
 */
public final class ItwLauncherPaths {

    public static final String KEY_BIN_NAME = "icedtea-web.bin.name";
    public static final String ENV_JAVAWS_BIN = "ITW_JAVAWS_BIN";
    /** Set by .NET and shell wrappers; inherited by the JVM they start. */
    public static final String ENV_NATIVE_LAUNCHER = "ITW_NATIVE_LAUNCHER";
    /** Optional override of {@link #ENV_NATIVE_LAUNCHER} for tests / diagnostics. */
    public static final String PROP_NATIVE_LAUNCHER = "icedtea-web.native.launcher";

    private static final String JAVAWS_NAME = "javaws";
    private static final String JAVAWSC_NAME = "javawsc";
    private static final String POLICYEDITOR_NAME = "policyeditor";
    private static final String JAVAWS_MAIN = JavawsUberLauncher.class.getName();
    private static final String POLICYEDITOR_MAIN =
            "net.sourceforge.jnlp.security.policyeditor.PolicyEditor";
    private static final String SUN_SECURITY_PROVIDER = "sun.security.provider";

    private ItwLauncherPaths() {
    }

    /**
     * {@code true} when this JVM was started by a native/shell ITW wrapper.
     * {@code false} means bare {@code java -cp}/{@code -jar} (or tests).
     */
    public static boolean isNativeLauncherProcess() {
        String prop = System.getProperty(PROP_NATIVE_LAUNCHER);
        if (prop != null) {
            return isTruthy(prop);
        }
        return isTruthy(System.getenv(ENV_NATIVE_LAUNCHER));
    }

    public static boolean canLaunchExternally() {
        try {
            buildExternalLaunchCommand(new ArrayList<String>(), new ArrayList<String>(), null);
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    /**
     * Build a full process command to start javaws externally.
     *
     * @param vmArgs JVM arguments without a {@code -J} prefix
     * @param javawsArgs arguments for Boot/JavawsUberLauncher (e.g. JNLP URL)
     * @param javaHome optional JDK home for the child; {@code null} uses current {@code java.home}
     */
    public static List<String> buildExternalLaunchCommand(List<String> vmArgs, List<String> javawsArgs,
            String javaHome) {
        List<String> vm = vmArgs == null ? new ArrayList<String>() : new ArrayList<>(vmArgs);
        List<String> appArgs = javawsArgs == null ? new ArrayList<String>() : new ArrayList<>(javawsArgs);
        if (isNativeLauncherProcess()) {
            return buildNativeWrapperCommand(vm, appArgs, javaHome);
        }
        return buildJavaCpCommand(vm, appArgs, javaHome);
    }

    /**
     * {@code true} when this JVM can load {@code sun.security.provider.PolicyParser}
     * (JDK 8, or JDK 9+ with {@code --add-exports java.base/sun.security.provider=…}).
     * Control Panel Simple editor uses this to decide in-process vs a child JVM.
     */
    public static boolean canAccessSunSecurityProvider() {
        if (JavaVersionUtils.getRunningMajorVersion() < 9) {
            return true;
        }
        Module javaBase = Object.class.getModule();
        return javaBase.isExported(SUN_SECURITY_PROVIDER, ItwLauncherPaths.class.getModule());
    }

    /**
     * Command to start PolicyEditor with JPMS exports, so Simple editor works
     * even when the Control Panel JVM was started without {@code --add-exports}.
     */
    public static List<String> buildPolicyEditorLaunchCommand(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("policy file path is required");
        }
        if (isNativeLauncherProcess()) {
            return buildNativePolicyEditorCommand(filePath.trim());
        }
        return buildJavaCpPolicyEditorCommand(filePath.trim(), null);
    }

    public static String resolveJavawsBin() {
        if (!isNativeLauncherProcess()) {
            // Bare java -cp: there is no wrapper binary; callers should use
            // buildExternalLaunchCommand instead.
            return null;
        }
        File fromEnv = asExecutableFile(System.getenv(ENV_JAVAWS_BIN));
        if (fromEnv != null) {
            return fromEnv.getAbsolutePath();
        }

        File fromLocation = resolveJavawsFromLauncherLocation(
                System.getProperty(Launcher.KEY_JAVAWS_LOCATION));
        if (fromLocation != null) {
            return fromLocation.getAbsolutePath();
        }

        File fromPath = findJavawsOnPath();
        if (fromPath != null) {
            return fromPath.getAbsolutePath();
        }
        return null;
    }

    static File resolveJavawsFromLauncherLocation(String location) {
        File launcher = asExecutableFile(location);
        if (launcher == null) {
            // Allow .cmd which may not report canExecute on some JDKs.
            if (location != null && location.trim().toLowerCase(Locale.ROOT).endsWith(".cmd")) {
                File cmd = new File(location.trim());
                if (cmd.isFile()) {
                    launcher = cmd;
                }
            }
        }
        if (launcher == null) {
            return null;
        }
        if (isJavawsLauncherFile(launcher)) {
            File windowsNative = preferWindowsNativeLauncher(launcher.getParentFile());
            return windowsNative != null ? windowsNative : launcher;
        }
        File parent = launcher.getParentFile();
        if (parent == null) {
            return null;
        }
        return findSiblingJavaws(parent);
    }

    static boolean isJavawsLauncherName(String name) {
        if (name == null) {
            return false;
        }
        String base = stripExtension(name.trim().toLowerCase(Locale.ROOT));
        return JAVAWS_NAME.equals(base) || JAVAWSC_NAME.equals(base);
    }

    static File resolveUberJar() {
        String location = System.getProperty(Launcher.KEY_JAVAWS_LOCATION);
        File fromProp = asJarFile(location);
        if (fromProp != null) {
            return fromProp;
        }
        File fromCodeSource = codeSourceJar(ItwLauncherPaths.class);
        if (fromCodeSource != null) {
            return fromCodeSource;
        }
        fromCodeSource = codeSourceJar(JavawsUberLauncher.class);
        if (fromCodeSource != null) {
            return fromCodeSource;
        }
        String classPath = System.getProperty("java.class.path");
        if (classPath != null) {
            for (String entry : classPath.split(File.pathSeparator)) {
                File jar = asJarFile(entry);
                if (jar != null && jar.getName().toLowerCase(Locale.ROOT).contains("icedtea-web")
                        && jar.getName().toLowerCase(Locale.ROOT).contains("uber")) {
                    return jar;
                }
            }
            for (String entry : classPath.split(File.pathSeparator)) {
                File jar = asJarFile(entry);
                if (jar != null && jar.getName().toLowerCase(Locale.ROOT).contains("icedtea-web")) {
                    return jar;
                }
            }
        }
        return null;
    }

    static File resolveJavaExecutable(String javaHome) {
        String home = javaHome;
        if (home == null || home.trim().isEmpty()) {
            home = System.getProperty("java.home");
        }
        if (home == null || home.trim().isEmpty()) {
            return null;
        }
        String name = JNLPRuntime.isWindows() ? "java.exe" : "java";
        File java = new File(new File(home.trim(), "bin"), name);
        if (java.isFile()) {
            return java;
        }
        return null;
    }

    private static List<String> buildNativePolicyEditorCommand(String filePath) {
        File policyEditor = resolvePolicyEditorBin();
        if (policyEditor == null) {
            throw new IllegalStateException("policyeditor launcher not found next to native ITW wrapper");
        }
        List<String> commands = new ArrayList<>();
        commands.add(policyEditor.getAbsolutePath());
        commands.add("-file");
        commands.add(filePath);
        return commands;
    }

    private static List<String> buildJavaCpPolicyEditorCommand(String filePath, String javaHome) {
        File java = resolveJavaExecutable(javaHome);
        File uberJar = resolveUberJar();
        if (java == null) {
            throw new IllegalStateException("java executable not found for PolicyEditor launch");
        }
        if (uberJar == null) {
            throw new IllegalStateException("icedtea-web uber JAR not found for PolicyEditor launch");
        }
        List<String> commands = new ArrayList<>();
        commands.add(java.getAbsolutePath());
        commands.add("-Xms8m");
        List<String> modularVmArgs = new ArrayList<>();
        JavaVersionUtils.addModularJdkCompatibilityArgs(modularVmArgs, javaHome);
        commands.addAll(modularVmArgs);
        commands.add("-D" + KEY_BIN_NAME + "=" + POLICYEDITOR_NAME);
        commands.add("-D" + Launcher.KEY_JAVAWS_LOCATION + "=" + uberJar.getAbsolutePath());
        commands.add("-cp");
        commands.add(uberJar.getAbsolutePath());
        commands.add(POLICYEDITOR_MAIN);
        commands.add("-file");
        commands.add(filePath);
        return commands;
    }

    static File resolvePolicyEditorBin() {
        String location = System.getProperty(Launcher.KEY_JAVAWS_LOCATION);
        File fromLocation = resolveSiblingNamed(location, POLICYEDITOR_NAME);
        if (fromLocation != null) {
            return fromLocation;
        }
        return findNamedOnPath(POLICYEDITOR_NAME);
    }

    private static File resolveSiblingNamed(String location, String baseName) {
        File launcher = asExecutableFile(location);
        if (launcher == null && location != null && location.trim().toLowerCase(Locale.ROOT).endsWith(".cmd")) {
            File cmd = new File(location.trim());
            if (cmd.isFile()) {
                launcher = cmd;
            }
        }
        if (launcher == null) {
            return null;
        }
        if (isLauncherName(launcher.getName(), baseName)) {
            File windowsNative = preferWindowsNativeNamed(launcher.getParentFile(), baseName);
            return windowsNative != null ? windowsNative : launcher;
        }
        File parent = launcher.getParentFile();
        if (parent == null) {
            return null;
        }
        File windowsNative = preferWindowsNativeNamed(parent, baseName);
        if (windowsNative != null) {
            return windowsNative;
        }
        return findNamedInDirectory(parent, baseName);
    }

    private static boolean isLauncherName(String name, String baseName) {
        if (name == null) {
            return false;
        }
        return baseName.equals(stripExtension(name.trim().toLowerCase(Locale.ROOT)));
    }

    private static File preferWindowsNativeNamed(File binDirectory, String baseName) {
        if (!JNLPRuntime.isWindows() || binDirectory == null) {
            return null;
        }
        File exe = new File(binDirectory, baseName + ".exe");
        if (asExecutableFile(exe.getPath()) != null) {
            return exe;
        }
        File cmd = new File(binDirectory, baseName + ".cmd");
        if (cmd.isFile()) {
            return cmd;
        }
        return null;
    }

    private static File findNamedInDirectory(File binDirectory, String baseName) {
        File[] candidates = JNLPRuntime.isWindows()
                ? new File[] {new File(binDirectory, baseName)}
                : new File[] {new File(binDirectory, baseName), new File(binDirectory, baseName + ".exe")};
        for (File candidate : candidates) {
            if (asExecutableFile(candidate.getPath()) != null) {
                return candidate;
            }
        }
        return null;
    }

    private static File findNamedOnPath(String baseName) {
        String path = System.getenv("PATH");
        if (path == null || path.trim().isEmpty()) {
            path = System.getenv("Path");
        }
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        String[] names = JNLPRuntime.isWindows()
                ? new String[] {baseName + ".exe", baseName + ".cmd", baseName}
                : new String[] {baseName, baseName + ".exe"};
        for (String dir : path.split(File.pathSeparator)) {
            if (dir == null || dir.trim().isEmpty()) {
                continue;
            }
            for (String name : names) {
                File candidate = new File(dir.trim(), name);
                if (name.endsWith(".cmd") && candidate.isFile()) {
                    return candidate;
                }
                File executable = asExecutableFile(candidate.getPath());
                if (executable != null) {
                    return executable;
                }
            }
        }
        return null;
    }

    private static List<String> buildNativeWrapperCommand(List<String> vmArgs, List<String> javawsArgs,
            String javaHome) {
        String pathToWebstartBinary = resolveJavawsBin();
        if (pathToWebstartBinary == null) {
            throw new IllegalStateException("javaws launcher not found next to native ITW wrapper");
        }
        List<String> commands = new ArrayList<>();
        commands.add(pathToWebstartBinary);
        for (String arg : vmArgs) {
            if (arg != null && !arg.isEmpty()) {
                commands.add("-J" + arg);
            }
        }
        commands.addAll(javawsArgs);
        return commands;
    }

    private static List<String> buildJavaCpCommand(List<String> vmArgs, List<String> javawsArgs,
            String javaHome) {
        File java = resolveJavaExecutable(javaHome);
        File uberJar = resolveUberJar();
        if (java == null) {
            throw new IllegalStateException("java executable not found for external launch");
        }
        if (uberJar == null) {
            throw new IllegalStateException("icedtea-web uber JAR not found for java -cp launch");
        }
        List<String> commands = new ArrayList<>();
        commands.add(java.getAbsolutePath());
        // Match .NET/shell wrappers: heap floor + modular access before caller VM args.
        if (!containsXmsArg(vmArgs)) {
            commands.add("-Xms8m");
        }
        List<String> modularVmArgs = new ArrayList<>();
        JavaVersionUtils.addModularJdkCompatibilityArgs(modularVmArgs, javaHome);
        commands.addAll(modularVmArgs);
        for (String arg : vmArgs) {
            if (arg != null && !arg.isEmpty()) {
                commands.add(arg);
            }
        }
        commands.add("-D" + KEY_BIN_NAME + "=" + JAVAWS_NAME);
        commands.add("-D" + Launcher.KEY_JAVAWS_LOCATION + "=" + uberJar.getAbsolutePath());
        commands.add("-cp");
        commands.add(uberJar.getAbsolutePath());
        commands.add(JAVAWS_MAIN);
        commands.addAll(javawsArgs);
        return commands;
    }

    private static boolean containsXmsArg(List<String> vmArgs) {
        if (vmArgs == null) {
            return false;
        }
        for (String arg : vmArgs) {
            if (arg != null && arg.startsWith("-Xms")) {
                return true;
            }
        }
        return false;
    }

    /**
     * On Windows, prefer {@code javaws.exe} / {@code javaws.cmd} over a bash script named
     * {@code javaws} — CreateProcess cannot run shell scripts (error 193).
     */
    private static File preferWindowsNativeLauncher(File binDirectory) {
        if (!JNLPRuntime.isWindows() || binDirectory == null) {
            return null;
        }
        File exe = new File(binDirectory, JAVAWS_NAME + ".exe");
        if (asExecutableFile(exe.getPath()) != null) {
            return exe;
        }
        File cmd = new File(binDirectory, JAVAWS_NAME + ".cmd");
        if (cmd.isFile()) {
            return cmd;
        }
        return null;
    }

    private static boolean isJavawsLauncherFile(File file) {
        return isJavawsLauncherName(file.getName());
    }

    private static File findSiblingJavaws(File binDirectory) {
        File windowsNative = preferWindowsNativeLauncher(binDirectory);
        if (windowsNative != null) {
            return windowsNative;
        }
        File[] candidates = JNLPRuntime.isWindows()
                ? new File[] {new File(binDirectory, JAVAWS_NAME)}
                : new File[] {new File(binDirectory, JAVAWS_NAME), new File(binDirectory, JAVAWS_NAME + ".exe")};
        for (File candidate : candidates) {
            if (asExecutableFile(candidate.getPath()) != null) {
                return candidate;
            }
        }
        return null;
    }

    private static File findJavawsOnPath() {
        String path = System.getenv("PATH");
        if (path == null || path.trim().isEmpty()) {
            path = System.getenv("Path");
        }
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        String[] names = JNLPRuntime.isWindows()
                ? new String[] {JAVAWS_NAME + ".exe", JAVAWS_NAME + ".cmd", JAVAWS_NAME}
                : new String[] {JAVAWS_NAME, JAVAWS_NAME + ".exe"};
        for (String dir : path.split(File.pathSeparator)) {
            if (dir == null || dir.trim().isEmpty()) {
                continue;
            }
            for (String name : names) {
                File candidate = new File(dir.trim(), name);
                if (name.endsWith(".cmd") && candidate.isFile()) {
                    return candidate;
                }
                File executable = asExecutableFile(candidate.getPath());
                if (executable != null) {
                    return executable;
                }
            }
        }
        return null;
    }

    private static File asExecutableFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File file = new File(path.trim());
        if (file.isFile() && file.canExecute()) {
            return file;
        }
        return null;
    }

    private static File asJarFile(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File file = new File(path.trim());
        if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return file;
        }
        return null;
    }

    private static File codeSourceJar(Class<?> type) {
        try {
            URL location = type.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            File file = urlToFile(location);
            return asJarFile(file == null ? null : file.getAbsolutePath());
        } catch (Exception ex) {
            return null;
        }
    }

    private static File urlToFile(URL url) {
        if (url == null) {
            return null;
        }
        try {
            if ("file".equalsIgnoreCase(url.getProtocol())) {
                return new File(url.toURI());
            }
        } catch (Exception ignored) {
            // Fall through to path decode.
        }
        String path = decodeCodeSourcePath(url.getPath());
        return path == null ? null : new File(path);
    }

    static String decodeCodeSourcePath(String path) {
        if (path == null) {
            return null;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(path, "UTF-8");
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            decoded = path;
        }
        if (File.separatorChar == '\\'
                && decoded.length() >= 3
                && decoded.charAt(0) == '/'
                && Character.isLetter(decoded.charAt(1))
                && decoded.charAt(2) == ':') {
            decoded = decoded.substring(1);
        }
        return decoded;
    }

    private static String stripExtension(String name) {
        if (name.endsWith(".exe") || name.endsWith(".cmd")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return "1".equals(trimmed)
                || "true".equalsIgnoreCase(trimmed)
                || "yes".equalsIgnoreCase(trimmed)
                || "on".equalsIgnoreCase(trimmed);
    }
}
