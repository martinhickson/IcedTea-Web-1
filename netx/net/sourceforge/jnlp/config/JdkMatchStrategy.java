package net.sourceforge.jnlp.config;

public enum JdkMatchStrategy {

    MAXIMUM("Maximum"),
    EXACT("Exact"),
    MINIMUM("Minimum");

    private final String configValue;

    JdkMatchStrategy(String configValue) {
        this.configValue = configValue;
    }

    public String getConfigValue() {
        return configValue;
    }

    public static JdkMatchStrategy fromConfig(String value) {
        if (value == null || value.trim().isEmpty()) {
            return MAXIMUM;
        }
        for (JdkMatchStrategy strategy : values()) {
            if (strategy.configValue.equalsIgnoreCase(value.trim())) {
                return strategy;
            }
        }
        return MAXIMUM;
    }
}
