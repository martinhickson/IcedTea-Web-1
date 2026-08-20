package net.sourceforge.jnlp.config;

import java.io.File;
import java.net.URI;
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
import net.sourceforge.jnlp.util.logging.OutputController;

public final class KnownJvmStore {

    public static final String KEY_JDK_PREFIX = "deployment.jdk.";
    public static final String KEY_MATCH_STRATEGY = "deployment.jdk.matchStrategy";

    private static final int MAX_JDK_ENTRIES = 64;
    /** Java 8 (1.8) and other pre-11 homes are not migrated; ITW requires JDK 11+. */
    static final int MIN_MIGRATED_JDK_MAJOR = 11;
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
     * Splits a legacy {@code deployment.jre.dirs} value (pipe-separated JVM homes).
     */
    static List<String> parseJreDirs(String value) {
        List<String> homes = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) {
            return homes;
        }
        for (String part : value.split("\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                homes.add(trimmed);
            }
        }
        return homes;
    }

    /**
     * Migrates {@code deployment.jre.dirs} into {@code deployment.jre.dir} / {@code deployment.jdk.N}
     * and drops the legacy key. No-op unless {@link DeploymentConfiguration#KEY_JRE_DIRS_MIGRATE}
     * is {@code true}. Only homes that pass {@link #isMigratableJvmHome} are written;
     * Java 8 (1.8) and missing {@code bin/java} are skipped. Failures are debug-only so
     * configuration load still succeeds.
     *
     * @return true if the configuration was modified (caller should persist)
     */
    public static boolean migrateLegacyJreDirs(DeploymentConfiguration config) {
        try {
            if (!isJreDirsMigrateEnabled(config)) {
                return false;
            }
            return migrateLegacyJreDirsUnchecked(config);
        } catch (Throwable t) {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "Skipping deployment.jre.dirs migration: " + t);
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, t);
            return false;
        }
    }

    static boolean isJreDirsMigrateEnabled(DeploymentConfiguration config) {
        if (config == null) {
            return false;
        }
        String flag = config.getProperty(DeploymentConfiguration.KEY_JRE_DIRS_MIGRATE);
        return flag != null && Boolean.parseBoolean(flag.trim());
    }

    private static boolean migrateLegacyJreDirsUnchecked(DeploymentConfiguration config) {
        if (config == null) {
            return false;
        }
        String raw = config.getProperty(DeploymentConfiguration.KEY_JRE_DIRS);
        if (raw == null) {
            return false;
        }
        List<String> fromDirs = filterMigratableJvmHomes(parseJreDirs(raw));
        List<String> existingRaw = getKnownJvmHomes(config);
        List<String> existing = filterMigratableJvmHomes(existingRaw);
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existing.isEmpty()) {
            merged.addAll(fromDirs);
        } else {
            merged.addAll(existing);
            merged.addAll(fromDirs);
        }
        List<String> next = new ArrayList<>(merged);
        if (!next.equals(existingRaw)) {
            setKnownJvmHomes(config, next);
        }
        config.removeProperty(DeploymentConfiguration.KEY_JRE_DIRS);
        return true;
    }

    private static List<String> filterMigratableJvmHomes(List<String> homes) {
        List<String> valid = new ArrayList<>();
        for (String home : homes) {
            if (isMigratableJvmHome(home)) {
                valid.add(home);
            }
        }
        return valid;
    }

    /**
     * Light check used when copying a path into the known-JVM list: {@code bin/java} must
     * exist and the detected major must be {@link #MIN_MIGRATED_JDK_MAJOR} or higher
     * (Java 8 / {@code 1.8} is not accepted). Does not spawn a JVM process.
     */
    static boolean isMigratableJvmHome(String home) {
        if (home == null || home.trim().isEmpty()) {
            return false;
        }
        try {
            JvmDescriptor descriptor = JvmDescriptor.describeLight(home.trim());
            if (!descriptor.isValid()) {
                OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                        "Skipping invalid JVM home during deployment.jre.dirs migration: " + home);
                return false;
            }
            int major = JvmSelector.parseMajor(descriptor.getVersion());
            if (major < MIN_MIGRATED_JDK_MAJOR) {
                OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                        "Skipping JVM home (major " + major + " < " + MIN_MIGRATED_JDK_MAJOR
                                + ", Java 8/1.8 is not valid) during deployment.jre.dirs migration: "
                                + home);
                return false;
            }
            return true;
        } catch (Throwable t) {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "Skipping JVM home during deployment.jre.dirs migration: " + home + " (" + t + ")");
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, t);
            return false;
        }
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
     * Seeds bundled Temurin JREs that are not already in the known-JVM list.
     * The MSI/deb ships JREs under {@code [installDir]/runtime/temurin-*}
     * (linux/windows: {@code temurin-<ver>/<jdk>/bin/java}; macOS:
     * {@code temurin-<ver>/<jdk>/Contents/Home/bin/java}). Missing homes
     * are appended so a persisted {@code deployment.jdk.1} order is kept.
     * On an empty list they are added in preferred order 21 → 17 → 11 → 25.
     * Temurin 25 is last: ITW's JarFileCloseProtection depends on
     * jdk.internal.util.jar which JDK 25 removed. No-op when no bundle is
     * present (e.g. a source/IDE run). The launcher separately resolves the
     * download JVM (temurin-21); this only feeds the app-JVM selection list.
     *
     * @return true if any bundled home was added (caller should persist)
     */
    public static boolean applyBundledJvms(DeploymentConfiguration config) {
        List<String> bundled = discoverBundledJvmHomes();
        if (bundled.isEmpty()) {
            return false;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String home : getKnownJvmHomes(config)) {
            merged.add(home);
        }
        boolean changed = false;
        for (String home : bundled) {
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
     * Scans {@code [installDir]/runtime/temurin-*} for JRE homes, highest major first.
     */
    static List<String> discoverBundledJvmHomes() {
        File installRoot = findInstallRoot();
        if (installRoot == null) {
            return Collections.emptyList();
        }
        File runtime = new File(installRoot, "runtime");
        if (!runtime.isDirectory()) {
            return Collections.emptyList();
        }
        File[] dirs = runtime.listFiles();
        if (dirs == null) {
            return Collections.emptyList();
        }
        List<File> temurin = new ArrayList<>();
        for (File d : dirs) {
            if (d.isDirectory() && d.getName().toLowerCase(Locale.ROOT).startsWith("temurin-")) {
                temurin.add(d);
            }
        }
        // Preferred order 21 → 17 → 11 → 25, unknown temurin majors last.
        temurin.sort(Comparator.comparingInt(KnownJvmStore::bundledMajorPreference)
                .thenComparing(a -> a.getName()));
        List<String> homes = new ArrayList<>();
        for (File dir : temurin) {
            File home = findJreHome(dir);
            if (home != null) {
                homes.add(home.getAbsolutePath());
            }
        }
        return homes;
    }

    /**
     * Preference rank for a bundled temurin-&lt;major&gt; directory: 21 &lt; 17 &lt; 11 &lt; 25,
     * anything else last. Lower ranks sort first.
     */
    static int bundledMajorPreference(File temurinDir) {
        String name = temurinDir.getName();
        int major;
        try {
            major = Integer.parseInt(name.substring("temurin-".length()));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return Integer.MAX_VALUE;
        }
        switch (major) {
            case 21:
                return 0;
            case 17:
                return 1;
            case 11:
                return 2;
            case 25:
                return 3;
            default:
                return 4;
        }
    }

    /** JRE home is the child of a temurin-* dir that contains bin/java (or Contents/Home on macOS). */
    private static File findJreHome(File temurinDir) {
        File[] children = temurinDir.listFiles();
        if (children == null) {
            return null;
        }
        for (File c : children) {
            if (!c.isDirectory()) {
                continue;
            }
            File bin = new File(c, "bin");
            if (new File(bin, "java").isFile() || new File(bin, "java.exe").isFile()) {
                return c;                       // linux / windows layout
            }
            File contentsHome = new File(c, "Contents" + File.separator + "Home");
            if (new File(contentsHome, "bin" + File.separator + "java").isFile()) {
                return contentsHome;            // mac layout
            }
        }
        return null;
    }

    /** Install root = parent of the launcher's bin dir (icedtea-web.bin.location) or the uber-jar's lib dir. */
    static File findInstallRoot() {
        String binLocation = System.getProperty("icedtea-web.bin.location");
        if (binLocation != null && !binLocation.trim().isEmpty()) {
            File exe = new File(binLocation);
            File exeDir = exe.getParentFile();
            if (exeDir != null && exeDir.getParentFile() != null) {
                return exeDir.getParentFile();  // [installRoot]/bin/.. -> [installRoot]
            }
        }
        try {
            URI loc = KnownJvmStore.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            File lib = new File(loc).getParentFile();   // [installRoot]/lib
            if (lib != null && lib.getParentFile() != null) {
                return lib.getParentFile();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Autodetect preference: Corretto, then Temurin, then others; within that, majors 17, 21, 11,
     * then remaining majors ascending. Used only when inserting newly discovered homes.
     */
    public static Comparator<String> autodetectionPreferenceComparator() {
        return new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                // Light describe only — sorting must not process-probe every JDK.
                JvmDescriptor leftDesc = JvmDescriptor.describeLight(left);
                JvmDescriptor rightDesc = JvmDescriptor.describeLight(right);
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
