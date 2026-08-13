package net.sourceforge.jnlp.util;

import java.util.ArrayList;
import java.util.List;
import net.sourceforge.jnlp.Version;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.JdkMatchStrategy;
import net.sourceforge.jnlp.config.KnownJvmAssignmentStore;
import net.sourceforge.jnlp.config.KnownJvmStore;
import net.sourceforge.jnlp.util.logging.OutputController;

public final class JvmSelector {

    private JvmSelector() {
    }

    /**
     * Light descriptors for known homes (no process probe per install).
     * Full {@link JvmDescriptor#describe(String)} is reserved for the chosen JVM.
     */
    public static List<JvmDescriptor> describeKnownJvms(DeploymentConfiguration config) {
        List<JvmDescriptor> result = new ArrayList<>();
        for (String home : KnownJvmStore.getKnownJvmHomes(config)) {
            result.add(JvmDescriptor.describeLight(home));
        }
        return result;
    }

    public static String selectBestJvmHome(DeploymentConfiguration config, String requestedVersion) {
        return selectBestJvmHome(config, requestedVersion, null);
    }

    public static String selectBestJvmHome(DeploymentConfiguration config, String requestedVersion, String jnlpUrl) {
        if (jnlpUrl != null && !jnlpUrl.trim().isEmpty()) {
            String assigned = KnownJvmAssignmentStore.findJvmHomeForJnlpUrl(config, jnlpUrl.trim());
            if (assigned != null) {
                JvmDescriptor validated = JvmDescriptor.describe(assigned);
                if (validated.isValid()) {
                    OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                            "Selected JVM from JDK assignment for JNLP [" + jnlpUrl + "]: " + assigned
                                    + " (validated)");
                    return assigned;
                }
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                        "Assigned JVM failed validation, ignoring: " + assigned);
            }
        }
        List<JvmDescriptor> candidates = describeKnownJvms(config);
        JdkMatchStrategy strategy = KnownJvmStore.getMatchStrategy(config);
        // Try best light match, then next, validating only the candidate we would use.
        List<JvmDescriptor> remaining = new ArrayList<>(candidates);
        while (!remaining.isEmpty()) {
            JvmDescriptor selected = selectBest(remaining, requestedVersion, strategy);
            if (selected == null) {
                return null;
            }
            JvmDescriptor validated = JvmDescriptor.describe(selected.getHomePath());
            if (validated.isValid()) {
                OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                        "Selected JVM for JNLP request [" + requestedVersion + "] using "
                                + strategy.getConfigValue() + " strategy: " + validated.getDisplayName()
                                + " (" + validated.getHomePath() + ") — validated this JVM only");
                return validated.getHomePath();
            }
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                    "Candidate JVM failed validation, trying next: " + selected.getHomePath());
            remaining.removeIf(c -> selected.getHomePath().equals(c.getHomePath()));
        }
        return null;
    }

    public static JvmDescriptor selectBest(List<JvmDescriptor> candidates, String requestedVersion) {
        return selectBest(candidates, requestedVersion, JdkMatchStrategy.EXACT);
    }

    /**
     * Choose among ordered JNLP {@code <j2se>} version alternatives.
     * Prefer: (1) an alternative matching the current runtime, (2) the first alternative
     * that has a known configured JVM, else (3) the first listed version (JNLP preference).
     */
    public static String selectVersionAmongAlternatives(List<String> orderedVersions,
            List<JvmDescriptor> knownCandidates, JdkMatchStrategy strategy, int runningMajor) {
        if (orderedVersions == null || orderedVersions.isEmpty()) {
            return null;
        }
        if (strategy == null) {
            strategy = JdkMatchStrategy.EXACT;
        }
        if (runningMajor > 0) {
            JvmDescriptor running = new JvmDescriptor("running", "Running JVM",
                    Integer.toString(runningMajor), true, null);
            for (String version : orderedVersions) {
                if (version != null && matchesStrategy(running, version.trim(), strategy)) {
                    return version.trim();
                }
            }
        }
        if (knownCandidates != null && !knownCandidates.isEmpty()) {
            for (String version : orderedVersions) {
                if (version == null || version.trim().isEmpty()) {
                    continue;
                }
                if (selectBest(knownCandidates, version.trim(), strategy) != null) {
                    return version.trim();
                }
            }
        }
        String first = orderedVersions.get(0);
        return first == null ? null : first.trim();
    }

    public static JvmDescriptor selectBest(List<JvmDescriptor> candidates, String requestedVersion,
            JdkMatchStrategy strategy) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (strategy == null) {
            strategy = JdkMatchStrategy.EXACT;
        }
        List<JvmDescriptor> valid = new ArrayList<>();
        for (JvmDescriptor candidate : candidates) {
            if (candidate.isValid()) {
                valid.add(candidate);
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        if (requestedVersion == null || requestedVersion.trim().isEmpty()) {
            return valid.get(0);
        }
        // Version-level match first (Exact / Minimum / Maximum). Among multiple matches,
        // configured preference order wins — do not re-sort by version.
        for (JvmDescriptor candidate : valid) {
            if (matchesStrategy(candidate, requestedVersion.trim(), strategy)) {
                return candidate;
            }
        }
        return null;
    }

    public static boolean matchesStrategy(JvmDescriptor candidate, String requestedVersion, JdkMatchStrategy strategy) {
        String jvmVersion = candidate.getVersion();
        if (jvmVersion == null || jvmVersion.isEmpty()) {
            int probedMajor = JvmAutodetector.majorVersionOfJvmHome(candidate.getHomePath());
            if (probedMajor <= 0) {
                return false;
            }
            jvmVersion = Integer.toString(probedMajor);
        }
        if (strategy == JdkMatchStrategy.EXACT) {
            return parseMajor(jvmVersion) == parseMajor(stripPlusModifier(requestedVersion));
        }
        Version requirement = new Version(requestedVersion);
        if (requirement.matchesAny(jvmVersion) || requirement.matchesAny("1." + jvmVersion + ".0")) {
            return true;
        }
        int requestedMajor = parseMajor(requestedVersion);
        return requestedMajor > 0 && parseMajor(jvmVersion) == requestedMajor;
    }

    static String stripPlusModifier(String requestedVersion) {
        if (requestedVersion == null) {
            return "";
        }
        String trimmed = requestedVersion.trim();
        if (trimmed.endsWith("+")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static int parseMajor(String version) {
        if (version == null || version.isEmpty()) {
            return 0;
        }
        String trimmed = version.trim();
        String[] parts = trimmed.split("[.\\-_+]");
        try {
            if (parts.length > 0 && "1".equals(parts[0]) && parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
