package net.sourceforge.jnlp.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.jnlp.util.JvmAutodetector;
import net.sourceforge.jnlp.util.JvmDescriptor;
import net.sourceforge.jnlp.util.JvmSelector;

public final class KnownJvmStore {

    public static final String KEY_JDK_PREFIX = "deployment.jdk.";
    public static final String KEY_MATCH_STRATEGY = "deployment.jdk.matchStrategy";

    private static final int MAX_JDK_ENTRIES = 64;
    private static final Pattern JDK_KEY = Pattern.compile("^deployment\\.jdk\\.(\\d+)$");
    private static final Pattern JDK_ASSIGNMENT_KEY = Pattern.compile("^deployment\\.jdk\\d+\\.assignment\\d+$");

    private KnownJvmStore() {
    }

    /**
     * User-managed JDK list / assignment keys are not in {@link Defaults} but are first-class
     * deployment.properties entries. Treat them as known so load/check/validator do not flag them.
     */
    public static boolean isKnownDynamicKey(String key) {
        if (key == null) {
            return false;
        }
        return JDK_KEY.matcher(key).matches()
                || JDK_ASSIGNMENT_KEY.matcher(key).matches()
                || KEY_MATCH_STRATEGY.equals(key);
    }

    /**
     * Preference-ordered JDK homes: legacy {@code deployment.jre.dir} first (upgrade default slot),
     * then numbered {@code deployment.jdk.N} in ascending N. Duplicates are collapsed.
     */
    public static List<String> getKnownJvmHomes(DeploymentConfiguration config) {
        LinkedHashSet<String> homes = new LinkedHashSet<>();
        String legacy = getLegacyDefaultHome(config);
        if (legacy != null) {
            homes.add(legacy);
        }
        for (String numbered : getNumberedJvmHomes(config)) {
            homes.add(numbered);
        }
        return new ArrayList<>(homes);
    }

    /**
     * Numbered {@code deployment.jdk.N} entries only (no legacy merge).
     */
    public static List<String> getNumberedJvmHomes(DeploymentConfiguration config) {
        List<String> homes = new ArrayList<>();
        for (int i = 1; i <= MAX_JDK_ENTRIES; i++) {
            String home = config.getProperty(jdkKey(i));
            if (home == null || home.trim().isEmpty()) {
                break;
            }
            homes.add(home.trim());
        }
        return homes;
    }

    public static String getLegacyDefaultHome(DeploymentConfiguration config) {
        if (config == null) {
            return null;
        }
        String legacy = config.getProperty(DeploymentConfiguration.KEY_JRE_DIR);
        if (legacy == null || legacy.trim().isEmpty()) {
            return null;
        }
        return legacy.trim();
    }

    /**
     * Persists preference order. Index 0 becomes {@code deployment.jre.dir} (legacy default slot)
     * and {@code deployment.jdk.1}; remaining entries become {@code deployment.jdk.2+} .
     */
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
     * Merges autodetected JDK homes after existing preference order. Newly discovered homes are
     * sorted Corretto → Temurin → others, then preferred majors 17 → 21 → 11 → others.
     *
     * @return true if any new JDK home was added
     */
    public static boolean applyAutodetectedJvms(DeploymentConfiguration config) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String home : getKnownJvmHomes(config)) {
            merged.add(home);
        }
        List<String> discovered = new ArrayList<>(JvmAutodetector.discoverValidJvmHomes());
        Collections.sort(discovered, autodetectionPreferenceComparator());
        boolean changed = false;
        for (String home : discovered) {
            if (merged.add(home)) {
                changed = true;
            }
        }
        if (changed) {
            setKnownJvmHomes(config, new ArrayList<>(merged));
        }
        return changed;
    }

    /**
     * Autodetect preference: Corretto, then Temurin, then others; within that, majors 17, 21, 11,
     * then remaining majors ascending. Used only when inserting newly discovered homes.
     */
    public static Comparator<String> autodetectionPreferenceComparator() {
        return new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                JvmDescriptor leftDesc = JvmDescriptor.describe(left);
                JvmDescriptor rightDesc = JvmDescriptor.describe(right);
                int vendor = Integer.compare(vendorRank(leftDesc), vendorRank(rightDesc));
                if (vendor != 0) {
                    return vendor;
                }
                int version = Integer.compare(versionRank(leftDesc), versionRank(rightDesc));
                if (version != 0) {
                    return version;
                }
                return normalizeHome(left).compareToIgnoreCase(normalizeHome(right));
            }
        };
    }

    static int vendorRank(JvmDescriptor descriptor) {
        if (descriptor == null) {
            return 2;
        }
        String flavour = descriptor.getFlavour() == null ? "" : descriptor.getFlavour();
        String home = descriptor.getHomePath() == null ? "" : descriptor.getHomePath();
        String lower = (flavour + " " + home).toLowerCase(Locale.ROOT);
        if (lower.contains("corretto")) {
            return 0;
        }
        if (lower.contains("temurin") || lower.contains("adoptium")) {
            return 1;
        }
        return 2;
    }

    static int versionRank(JvmDescriptor descriptor) {
        int major = descriptor == null ? 0 : JvmSelector.parseMajor(descriptor.getVersion());
        if (major == 17) {
            return 0;
        }
        if (major == 21) {
            return 1;
        }
        if (major == 11) {
            return 2;
        }
        if (major <= 0) {
            return 10_000;
        }
        // Keep other majors after the preferred set, ordered by major ascending.
        return 100 + major;
    }

    /**
     * Ensures {@code home} is stored as a numbered {@code deployment.jdk.N} entry.
     * <p>
     * {@link #getKnownJvmHomes} can return a path that exists only via the legacy
     * {@code deployment.jre.dir} fallback. Launch-time autodetect must still persist
     * a real numbered JDK entry so relaunch / control panel see it.
     *
     * @return true if the configuration was modified
     */
    public static boolean ensureKnownJvmHome(DeploymentConfiguration config, String home) {
        if (config == null || home == null || home.trim().isEmpty()) {
            return false;
        }
        String normalized = normalizeHome(home);
        if (normalized.isEmpty()) {
            return false;
        }
        if (isStoredAsNumberedJdk(config, normalized)) {
            return false;
        }
        List<String> homes = new ArrayList<>();
        for (String existing : getKnownJvmHomes(config)) {
            if (!sameHome(existing, normalized)) {
                homes.add(existing);
            }
        }
        homes.add(normalized);
        setKnownJvmHomes(config, homes);
        return true;
    }

    public static boolean isStoredAsNumberedJdk(DeploymentConfiguration config, String home) {
        if (config == null || home == null || home.trim().isEmpty()) {
            return false;
        }
        String normalized = normalizeHome(home);
        for (int i = 1; i <= MAX_JDK_ENTRIES; i++) {
            String value = config.getProperty(jdkKey(i));
            if (value == null || value.trim().isEmpty()) {
                break;
            }
            if (sameHome(value, normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeHome(String home) {
        String trimmed = home.trim();
        try {
            return new File(trimmed).getAbsoluteFile().getPath();
        } catch (Exception ex) {
            return trimmed;
        }
    }

    private static boolean sameHome(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String a = normalizeHome(left).replace('/', '\\');
        String b = normalizeHome(right).replace('/', '\\');
        if (a.equals(b)) {
            return true;
        }
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win") && a.equalsIgnoreCase(b);
    }
}
