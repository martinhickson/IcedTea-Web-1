package net.sourceforge.jnlp.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KnownJvmTuningStore {

    public static final String KEY_PREFIX = "deployment.jdk";
    public static final String KEY_TUNING_SUFFIX = ".tuning";

    private static final int MAX_JDK_ENTRIES = 64;
    private static final int MAX_TUNING_ENTRIES_PER_JDK = 64;
    private static final Pattern TUNING_KEY = Pattern.compile(
            "^deployment\\.jdk(\\d+)\\.tuning(\\d+)\\.(url|gc|maxHeap|softMax)$");

    private KnownJvmTuningStore() {
    }

    public static final class JvmTuningEntry {
        private final int jdkIndex;
        private final int tuningIndex;
        private final String jnlpUrl;
        private final JvmTuningGcType gcType;
        private final String maxHeapMb;
        private final String softMaxPercent;

        public JvmTuningEntry(int jdkIndex, int tuningIndex, String jnlpUrl, JvmTuningGcType gcType,
                String maxHeapMb, String softMaxPercent) {
            this.jdkIndex = jdkIndex;
            this.tuningIndex = tuningIndex;
            this.jnlpUrl = jnlpUrl;
            this.gcType = gcType == null ? JvmTuningGcType.DEFAULT : gcType;
            this.maxHeapMb = maxHeapMb == null ? "" : maxHeapMb.trim();
            this.softMaxPercent = softMaxPercent == null ? "" : softMaxPercent.trim();
        }

        public int getJdkIndex() {
            return jdkIndex;
        }

        public int getTuningIndex() {
            return tuningIndex;
        }

        public String getJnlpUrl() {
            return jnlpUrl;
        }

        public JvmTuningGcType getGcType() {
            return gcType;
        }

        public String getMaxHeapMb() {
            return maxHeapMb;
        }

        public String getSoftMaxPercent() {
            return softMaxPercent;
        }
    }

    public static List<JvmTuningEntry> getTuningEntries(DeploymentConfiguration config) {
        List<JvmTuningEntry> entries = new ArrayList<>();
        for (int jdkIndex = 1; jdkIndex <= MAX_JDK_ENTRIES; jdkIndex++) {
            for (int tuningIndex = 1; tuningIndex <= MAX_TUNING_ENTRIES_PER_JDK; tuningIndex++) {
                String url = config.getProperty(tuningKey(jdkIndex, tuningIndex, "url"));
                if (url == null || url.trim().isEmpty()) {
                    break;
                }
                entries.add(new JvmTuningEntry(
                        jdkIndex,
                        tuningIndex,
                        url.trim(),
                        JvmTuningGcType.fromConfig(config.getProperty(tuningKey(jdkIndex, tuningIndex, "gc"))),
                        config.getProperty(tuningKey(jdkIndex, tuningIndex, "maxHeap")),
                        config.getProperty(tuningKey(jdkIndex, tuningIndex, "softMax"))));
            }
        }
        Collections.sort(entries, new Comparator<JvmTuningEntry>() {
            @Override
            public int compare(JvmTuningEntry left, JvmTuningEntry right) {
                int byJdk = Integer.compare(left.jdkIndex, right.jdkIndex);
                if (byJdk != 0) {
                    return byJdk;
                }
                return Integer.compare(left.tuningIndex, right.tuningIndex);
            }
        });
        return entries;
    }

    public static void setTuningEntries(DeploymentConfiguration config, List<JvmTuningEntry> entries) {
        clearAllTuningKeys(config);
        if (entries == null || entries.isEmpty()) {
            return;
        }
        int[] nextIndex = new int[MAX_JDK_ENTRIES + 1];
        for (JvmTuningEntry entry : entries) {
            if (entry == null || entry.jnlpUrl == null || entry.jnlpUrl.trim().isEmpty()) {
                continue;
            }
            if (entry.jdkIndex < 1 || entry.jdkIndex > MAX_JDK_ENTRIES) {
                continue;
            }
            int slot = ++nextIndex[entry.jdkIndex];
            if (slot > MAX_TUNING_ENTRIES_PER_JDK) {
                continue;
            }
            config.setProperty(tuningKey(entry.jdkIndex, slot, "url"), entry.jnlpUrl.trim());
            if (entry.gcType != null && entry.gcType != JvmTuningGcType.DEFAULT) {
                config.setProperty(tuningKey(entry.jdkIndex, slot, "gc"), entry.gcType.getConfigValue());
            }
            if (entry.maxHeapMb != null && !entry.maxHeapMb.trim().isEmpty()) {
                config.setProperty(tuningKey(entry.jdkIndex, slot, "maxHeap"), entry.maxHeapMb.trim());
            }
            if (entry.softMaxPercent != null && !entry.softMaxPercent.trim().isEmpty()) {
                config.setProperty(tuningKey(entry.jdkIndex, slot, "softMax"), entry.softMaxPercent.trim());
            }
        }
    }

    public static JvmTuningEntry findTuningForJnlpUrl(DeploymentConfiguration config, String jnlpUrl) {
        if (jnlpUrl == null || jnlpUrl.trim().isEmpty()) {
            return null;
        }
        String normalized = jnlpUrl.trim();
        for (JvmTuningEntry entry : getTuningEntries(config)) {
            if (normalized.equals(entry.jnlpUrl)) {
                return entry;
            }
        }
        return null;
    }

    public static String tuningKey(int jdkIndex, int tuningIndex, String field) {
        return KEY_PREFIX + jdkIndex + KEY_TUNING_SUFFIX + tuningIndex + "." + field;
    }

    private static void clearAllTuningKeys(DeploymentConfiguration config) {
        for (String name : config.getAllPropertyNames()) {
            if (TUNING_KEY.matcher(name).matches()) {
                config.setProperty(name, "");
            }
        }
    }
}
