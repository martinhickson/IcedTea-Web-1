package net.sourceforge.jnlp.util;

import java.io.File;

import net.sourceforge.jnlp.JNLPFile;
import net.sourceforge.jnlp.config.PathsAndFiles;

/**
 * Shared lock and tuning file naming for per-application JNLP instances.
 */
public final class JnlpLockFileNames {

    public static final String TUNING_SUFFIX = ".tuning";

    private JnlpLockFileNames() {
    }

    public static File lockFileFor(JNLPFile jnlpFile) {
        return new File(locksDirectory(), lockFileNameFor(jnlpFile));
    }

    public static File tuningFileFor(JNLPFile jnlpFile) {
        return tuningFileForLockName(lockFileNameFor(jnlpFile));
    }

    public static File tuningFileFor(String jnlpPath, String appVersion) {
        if (jnlpPath == null || jnlpPath.trim().isEmpty()) {
            return null;
        }
        StringBuilder initialName = new StringBuilder(jnlpPath.trim());
        if (appVersion != null && !appVersion.trim().isEmpty()) {
            initialName.append(appVersion.trim());
        }
        initialName.append(currentDisplay());
        return tuningFileForLockName(FileUtils.sanitizeFileName(initialName.toString()));
    }

    public static File tuningFileForLockFile(File lockFile) {
        if (lockFile == null) {
            return null;
        }
        return new File(lockFile.getParentFile(), lockFile.getName() + TUNING_SUFFIX);
    }

    public static File tuningFileForLockName(String lockFileName) {
        if (lockFileName == null || lockFileName.trim().isEmpty()) {
            return null;
        }
        return new File(locksDirectory(), lockFileName.trim() + TUNING_SUFFIX);
    }

    public static String lockFileNameFor(JNLPFile jnlpFile) {
        StringBuilder initialName = new StringBuilder();
        if (jnlpFile.getSourceLocation() != null) {
            initialName.append(jnlpFile.getSourceLocation());
        } else if (jnlpFile.getFileLocation() != null) {
            initialName.append(jnlpFile.getFileLocation());
        }
        if (jnlpFile.getFileVersion() != null) {
            initialName.append(jnlpFile.getFileVersion().toString());
        }
        initialName.append(currentDisplay());
        return FileUtils.sanitizeFileName(initialName.toString());
    }

    private static File locksDirectory() {
        return PathsAndFiles.LOCKS_DIR.getFile();
    }

    private static String currentDisplay() {
        String display = System.getenv("DISPLAY");
        return display == null ? "" : display;
    }
}
