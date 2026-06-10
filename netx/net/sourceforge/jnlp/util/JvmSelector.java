package net.sourceforge.jnlp.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import net.sourceforge.jnlp.Version;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
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
        List<JvmDescriptor> candidates = describeKnownJvms(config);
        JvmDescriptor selected = selectBest(candidates, requestedVersion);
        if (selected == null) {
            return null;
        }
        OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                "Selected JVM for JNLP request [" + requestedVersion + "]: " + selected.getDisplayName()
                        + " (" + selected.getHomePath() + ")");
        return selected.getHomePath();
    }

    public static JvmDescriptor selectBest(List<JvmDescriptor> candidates, String requestedVersion) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
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
        Version requirement = new Version(requestedVersion.trim());
        List<JvmDescriptor> matching = new ArrayList<>();
        for (JvmDescriptor candidate : valid) {
            String version = candidate.getVersion();
            if (version.isEmpty()) {
                continue;
            }
            if (requirement.matchesAny(version) || requirement.matchesAny("1." + version + ".0")) {
                matching.add(candidate);
            }
        }
        if (matching.isEmpty()) {
            return valid.get(0);
        }
        Collections.sort(matching, VERSION_DESCENDING);
        return matching.get(0);
    }

    private static final Comparator<JvmDescriptor> VERSION_DESCENDING = new Comparator<JvmDescriptor>() {
        @Override
        public int compare(JvmDescriptor left, JvmDescriptor right) {
            return Integer.compare(parseMajor(right.getVersion()), parseMajor(left.getVersion()));
        }
    };

    private static int parseMajor(String version) {
        if (version == null || version.isEmpty()) {
            return 0;
        }
        String token = version.split("[.\\-_+]")[0];
        try {
            if (token.startsWith("1") && version.contains(".")) {
                String[] parts = version.split("[.\\-_+]");
                if (parts.length > 1) {
                    return Integer.parseInt(parts[1]);
                }
            }
            return Integer.parseInt(token);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
