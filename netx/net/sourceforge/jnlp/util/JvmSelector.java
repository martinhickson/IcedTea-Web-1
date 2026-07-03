package net.sourceforge.jnlp.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    public static List<JvmDescriptor> describeKnownJvms(DeploymentConfiguration config) {
        List<JvmDescriptor> result = new ArrayList<>();
        for (String home : KnownJvmStore.getKnownJvmHomes(config)) {
            result.add(JvmDescriptor.describe(home));
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
                OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                        "Selected JVM from JDK assignment for JNLP [" + jnlpUrl + "]: " + assigned);
                return assigned;
            }
        }
        List<JvmDescriptor> candidates = describeKnownJvms(config);
        JdkMatchStrategy strategy = KnownJvmStore.getMatchStrategy(config);
        JvmDescriptor selected = selectBest(candidates, requestedVersion, strategy);
        if (selected == null) {
            return null;
        }
        OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                "Selected JVM for JNLP request [" + requestedVersion + "] using " + strategy.getConfigValue()
                        + " strategy: " + selected.getDisplayName() + " (" + selected.getHomePath() + ")");
        return selected.getHomePath();
    }

    public static JvmDescriptor selectBest(List<JvmDescriptor> candidates, String requestedVersion) {
        return selectBest(candidates, requestedVersion, JdkMatchStrategy.MAXIMUM);
    }

    public static JvmDescriptor selectBest(List<JvmDescriptor> candidates, String requestedVersion,
            JdkMatchStrategy strategy) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (strategy == null) {
            strategy = JdkMatchStrategy.MAXIMUM;
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
        List<JvmDescriptor> matching = new ArrayList<>();
        for (JvmDescriptor candidate : valid) {
            if (matchesStrategy(candidate, requestedVersion.trim(), strategy)) {
                matching.add(candidate);
            }
        }
        if (matching.isEmpty()) {
            return null;
        }
        Collections.sort(matching, comparatorFor(strategy));
        return matching.get(0);
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

    private static Comparator<JvmDescriptor> comparatorFor(JdkMatchStrategy strategy) {
        if (strategy == JdkMatchStrategy.MINIMUM) {
            return VERSION_ASCENDING;
        }
        return VERSION_DESCENDING;
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

    private static final Comparator<JvmDescriptor> VERSION_DESCENDING = new Comparator<JvmDescriptor>() {
        @Override
        public int compare(JvmDescriptor left, JvmDescriptor right) {
            return Integer.compare(parseMajor(right.getVersion()), parseMajor(left.getVersion()));
        }
    };

    private static final Comparator<JvmDescriptor> VERSION_ASCENDING = new Comparator<JvmDescriptor>() {
        @Override
        public int compare(JvmDescriptor left, JvmDescriptor right) {
            return Integer.compare(parseMajor(left.getVersion()), parseMajor(right.getVersion()));
        }
    };

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
