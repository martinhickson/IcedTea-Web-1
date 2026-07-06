package net.sourceforge.jnlp.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.jnlp.util.JvmAutodetector;

public final class KnownJvmStore {

    public static final String KEY_JDK_PREFIX = "deployment.jdk.";
    public static final String KEY_MATCH_STRATEGY = "deployment.jdk.matchStrategy";

    private static final int MAX_JDK_ENTRIES = 64;
    private static final Pattern JDK_KEY = Pattern.compile("^deployment\\.jdk\\.(\\d+)$");

    private KnownJvmStore() {
    }

    public static List<String> getKnownJvmHomes(DeploymentConfiguration config) {
        LinkedHashSet<String> homes = new LinkedHashSet<>();
        for (int i = 1; i <= MAX_JDK_ENTRIES; i++) {
            String home = config.getProperty(jdkKey(i));
            if (home == null || home.trim().isEmpty()) {
                break;
            }
            homes.add(home.trim());
        }
        if (homes.isEmpty()) {
            String legacy = config.getProperty(DeploymentConfiguration.KEY_JRE_DIR);
            if (legacy != null && !legacy.trim().isEmpty()) {
                homes.add(legacy.trim());
            }
        }
        return new ArrayList<>(homes);
    }

    public static void setKnownJvmHomes(DeploymentConfiguration config, List<String> homes) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (homes != null) {
            for (String home : homes) {
                if (home != null) {
                    String trimmed = home.trim();
                    if (!trimmed.isEmpty()) {
                        unique.add(trimmed);
                    }
                }
            }
        }
        clearNumberedJdkKeys(config);
        int index = 1;
        for (String home : unique) {
            config.setProperty(jdkKey(index++), home);
        }
        if (unique.isEmpty()) {
            config.setProperty(DeploymentConfiguration.KEY_JRE_DIR, "");
        } else {
            config.setProperty(DeploymentConfiguration.KEY_JRE_DIR, unique.iterator().next());
        }
    }

    private static void clearNumberedJdkKeys(DeploymentConfiguration config) {
        for (String name : config.getAllPropertyNames()) {
            Matcher matcher = JDK_KEY.matcher(name);
            if (matcher.matches()) {
                config.setProperty(name, "");
            }
        }
    }

    private static String jdkKey(int index) {
        return KEY_JDK_PREFIX + index;
    }

    public static JdkMatchStrategy getMatchStrategy(DeploymentConfiguration config) {
        return JdkMatchStrategy.fromConfig(config.getProperty(KEY_MATCH_STRATEGY));
    }

    public static void setMatchStrategy(DeploymentConfiguration config, JdkMatchStrategy strategy) {
        if (strategy == null || strategy == JdkMatchStrategy.EXACT) {
            config.setProperty(KEY_MATCH_STRATEGY, JdkMatchStrategy.EXACT.getConfigValue());
        } else {
            config.setProperty(KEY_MATCH_STRATEGY, strategy.getConfigValue());
        }
    }

    /**
     * Merges autodetected JDK homes into the configuration, matching control panel autodetect behaviour.
     *
     * @return true if any new JDK home was added
     */
    public static boolean applyAutodetectedJvms(DeploymentConfiguration config) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String home : getKnownJvmHomes(config)) {
            merged.add(home);
        }
        boolean changed = false;
        for (String home : JvmAutodetector.discoverValidJvmHomes()) {
            if (merged.add(home)) {
                changed = true;
            }
        }
        if (changed) {
            setKnownJvmHomes(config, new ArrayList<>(merged));
        }
        return changed;
    }
}
