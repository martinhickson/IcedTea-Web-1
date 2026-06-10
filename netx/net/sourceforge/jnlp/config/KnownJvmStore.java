package net.sourceforge.jnlp.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class KnownJvmStore {

    public static final String KEY_JRE_DIRS = "deployment.jre.dirs";
    public static final String PATH_SEPARATOR = "|";

    private KnownJvmStore() {
    }

    public static List<String> getKnownJvmHomes(DeploymentConfiguration config) {
        LinkedHashSet<String> homes = new LinkedHashSet<>();
        String listed = config.getProperty(KEY_JRE_DIRS);
        if (listed != null) {
            for (String entry : listed.split("\\|")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    homes.add(trimmed);
                }
            }
        }
        String legacy = config.getProperty(DeploymentConfiguration.KEY_JRE_DIR);
        if (legacy != null && !legacy.trim().isEmpty()) {
            homes.add(legacy.trim());
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
        String serialized = join(unique);
        config.setProperty(KEY_JRE_DIRS, serialized);
        if (unique.isEmpty()) {
            config.setProperty(DeploymentConfiguration.KEY_JRE_DIR, "");
        } else {
            config.setProperty(DeploymentConfiguration.KEY_JRE_DIR, unique.iterator().next());
        }
    }

    private static String join(LinkedHashSet<String> homes) {
        StringBuilder builder = new StringBuilder();
        for (String home : homes) {
            if (builder.length() > 0) {
                builder.append(PATH_SEPARATOR);
            }
            builder.append(home);
        }
        return builder.toString();
    }
}
