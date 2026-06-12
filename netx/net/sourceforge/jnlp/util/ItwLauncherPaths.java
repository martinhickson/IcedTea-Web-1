package net.sourceforge.jnlp.util;

import java.io.File;
import java.util.Locale;
import net.sourceforge.jnlp.Launcher;
import net.sourceforge.jnlp.runtime.JNLPRuntime;

/**
 * Resolves the javaws launcher executable for external JNLP launches.
 * <p>
 * When itweb-settings (or policyeditor) is started via the .NET/native launcher,
 * {@code icedtea-web.bin.location} points at that binary. External launches must
 * use the {@code javaws} sibling in the same {@code bin/} directory.
 */
public final class ItwLauncherPaths {

    public static final String KEY_BIN_NAME = "icedtea-web.bin.name";
    public static final String ENV_JAVAWS_BIN = "ITW_JAVAWS_BIN";

    private static final String JAVAWS_NAME = "javaws";
    private static final String JAVAWSC_NAME = "javawsc";

    private ItwLauncherPaths() {
    }

    public static String resolveJavawsBin() {
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
            return null;
        }
        if (isJavawsLauncherFile(launcher)) {
            return launcher;
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

    private static boolean isJavawsLauncherFile(File file) {
        return isJavawsLauncherName(file.getName());
    }

    private static File findSiblingJavaws(File binDirectory) {
        File[] candidates = JNLPRuntime.isWindows()
                ? new File[] {new File(binDirectory, JAVAWS_NAME + ".exe"), new File(binDirectory, JAVAWS_NAME)}
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
                ? new String[] {JAVAWS_NAME + ".exe", JAVAWS_NAME}
                : new String[] {JAVAWS_NAME, JAVAWS_NAME + ".exe"};
        for (String dir : path.split(File.pathSeparator)) {
            if (dir == null || dir.trim().isEmpty()) {
                continue;
            }
            for (String name : names) {
                File candidate = asExecutableFile(new File(dir.trim(), name).getPath());
                if (candidate != null) {
                    return candidate;
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

    private static String stripExtension(String name) {
        if (name.endsWith(".exe")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }
}
