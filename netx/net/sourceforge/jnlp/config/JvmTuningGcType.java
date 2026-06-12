package net.sourceforge.jnlp.config;

public enum JvmTuningGcType {

    DEFAULT(""),
    G1GC("G1GC"),
    ZGC("ZGC");

    private final String configValue;

    JvmTuningGcType(String configValue) {
        this.configValue = configValue;
    }

    public String getConfigValue() {
        return configValue;
    }

    public static JvmTuningGcType fromConfig(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT;
        }
        for (JvmTuningGcType type : values()) {
            if (type.configValue.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return DEFAULT;
    }
}
