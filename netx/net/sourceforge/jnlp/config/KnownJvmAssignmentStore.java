package net.sourceforge.jnlp.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KnownJvmAssignmentStore {

    public static final String KEY_PREFIX = "deployment.jdk";
    public static final String KEY_ASSIGNMENT_SUFFIX = ".assignment";

    private static final int MAX_JDK_ENTRIES = 64;
    private static final int MAX_ASSIGNMENTS_PER_JDK = 64;
    private static final Pattern ASSIGNMENT_KEY = Pattern.compile(
            "^deployment\\.jdk(\\d+)\\.assignment(\\d+)$");

    private KnownJvmAssignmentStore() {
    }

    public static final class JvmAssignment {
        private final int jdkIndex;
        private final int assignmentIndex;
        private final String jnlpUrl;

        public JvmAssignment(int jdkIndex, int assignmentIndex, String jnlpUrl) {
            this.jdkIndex = jdkIndex;
            this.assignmentIndex = assignmentIndex;
            this.jnlpUrl = jnlpUrl;
        }

        public int getJdkIndex() {
            return jdkIndex;
        }

        public int getAssignmentIndex() {
            return assignmentIndex;
        }

        public String getJnlpUrl() {
            return jnlpUrl;
        }
    }

    public static List<JvmAssignment> getAssignments(DeploymentConfiguration config) {
        List<JvmAssignment> assignments = new ArrayList<>();
        for (String name : config.getAllPropertyNames()) {
            Matcher matcher = ASSIGNMENT_KEY.matcher(name);
            if (!matcher.matches()) {
                continue;
            }
            String url = config.getProperty(name);
            if (url == null || url.trim().isEmpty()) {
                continue;
            }
            assignments.add(new JvmAssignment(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    url.trim()));
        }
        Collections.sort(assignments, new Comparator<JvmAssignment>() {
            @Override
            public int compare(JvmAssignment left, JvmAssignment right) {
                int byJdk = Integer.compare(left.jdkIndex, right.jdkIndex);
                if (byJdk != 0) {
                    return byJdk;
                }
                return Integer.compare(left.assignmentIndex, right.assignmentIndex);
            }
        });
        return assignments;
    }

    public static void setAssignments(DeploymentConfiguration config, List<JvmAssignment> assignments) {
        clearAllAssignmentKeys(config);
        if (assignments == null || assignments.isEmpty()) {
            return;
        }
        int[] nextAssignmentIndex = new int[MAX_JDK_ENTRIES + 1];
        for (JvmAssignment assignment : assignments) {
            if (assignment == null) {
                continue;
            }
            String url = assignment.jnlpUrl == null ? "" : assignment.jnlpUrl.trim();
            if (url.isEmpty() || assignment.jdkIndex < 1 || assignment.jdkIndex > MAX_JDK_ENTRIES) {
                continue;
            }
            int slot = ++nextAssignmentIndex[assignment.jdkIndex];
            if (slot > MAX_ASSIGNMENTS_PER_JDK) {
                continue;
            }
            config.setProperty(assignmentKey(assignment.jdkIndex, slot), url);
        }
    }

    public static String findJvmHomeForJnlpUrl(DeploymentConfiguration config, String jnlpUrl) {
        if (jnlpUrl == null || jnlpUrl.trim().isEmpty()) {
            return null;
        }
        String normalized = net.sourceforge.jnlp.util.JnlpAssignmentLauncher.canonicalizeJnlpUrl(jnlpUrl);
        List<String> homes = KnownJvmStore.getKnownJvmHomes(config);
        for (JvmAssignment assignment : getAssignments(config)) {
            String assignmentUrl = net.sourceforge.jnlp.util.JnlpAssignmentLauncher
                    .canonicalizeJnlpUrl(assignment.jnlpUrl);
            if (normalized.equals(assignmentUrl)) {
                int index = assignment.jdkIndex - 1;
                if (index >= 0 && index < homes.size()) {
                    return homes.get(index);
                }
            }
        }
        return null;
    }

    public static String assignmentKey(int jdkIndex, int assignmentIndex) {
        return KEY_PREFIX + jdkIndex + KEY_ASSIGNMENT_SUFFIX + assignmentIndex;
    }

    private static void clearAllAssignmentKeys(DeploymentConfiguration config) {
        for (String name : config.getAllPropertyNames()) {
            if (ASSIGNMENT_KEY.matcher(name).matches()) {
                config.setProperty(name, "");
            }
        }
    }
}
