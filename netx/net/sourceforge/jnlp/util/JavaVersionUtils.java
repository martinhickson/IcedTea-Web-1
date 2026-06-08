package net.sourceforge.jnlp.util;

/**
 * Helpers for comparing the running JRE feature release.
 */
public final class JavaVersionUtils {

    /** SecurityManager was removed in JDK 24 (JEP 486). */
    public static final int SECURITY_MANAGER_REMOVED_MAJOR = 24;

    private JavaVersionUtils() {
    }

    public static int getRunningMajorVersion() {
        String spec = System.getProperty("java.specification.version");
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

    public static boolean isSecurityManagerSupported() {
        return getRunningMajorVersion() < SECURITY_MANAGER_REMOVED_MAJOR;
    }
}
