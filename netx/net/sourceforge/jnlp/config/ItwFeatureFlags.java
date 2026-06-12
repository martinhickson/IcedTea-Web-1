package net.sourceforge.jnlp.config;

public final class ItwFeatureFlags {

    private ItwFeatureFlags() {
    }

    public static boolean isJvmTuningTabEnabled() {
        String value = System.getenv("ITW_TUNING");
        if (value == null) {
            return false;
        }
        value = value.trim();
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }
}
