package net.sourceforge.jnlp.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sourceforge.jnlp.util.JnlpAssignmentLauncher;

/**
 * JNLP URLs the user has actually launched. The JDK assignment tab reads this
 * list. Cache {@code jnlp_path} rows are not enough: a cache hit never rewrites
 * them, so a launch can leave the assignment tab empty.
 */
public final class LaunchedJnlpStore {

    public static final String KEY_PREFIX = "deployment.launchedJnlp.";

    private static final int MAX_ENTRIES = 64;
    private static final Pattern KEY = Pattern.compile("^deployment\\.launchedJnlp\\.(\\d+)$");

    private LaunchedJnlpStore() {
    }

    public static boolean isLaunchedJnlpKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /**
     * @return true when {@code url} was not already recorded
     */
    public static boolean record(DeploymentConfiguration config, String url) {
        if (config == null || url == null) {
            return false;
        }
        String canonical = JnlpAssignmentLauncher.canonicalizeJnlpUrl(url);
        if (canonical.isEmpty()) {
            return false;
        }
        LinkedHashSet<String> urls = new LinkedHashSet<>(getUrls(config));
        if (!urls.add(canonical)) {
            return false;
        }
        write(config, new ArrayList<>(urls));
        return true;
    }

    public static List<String> getUrls(DeploymentConfiguration config) {
        List<String> urls = new ArrayList<>();
        if (config == null) {
            return urls;
        }
        for (int i = 1; i <= MAX_ENTRIES; i++) {
            String value = config.getProperty(KEY_PREFIX + i);
            if (value == null || value.trim().isEmpty()) {
                break;
            }
            urls.add(JnlpAssignmentLauncher.canonicalizeJnlpUrl(value));
        }
        return urls;
    }

    private static void write(DeploymentConfiguration config, List<String> urls) {
        for (String name : config.getAllPropertyNames()) {
            Matcher matcher = KEY.matcher(name);
            if (matcher.matches()) {
                config.setProperty(name, "");
            }
        }
        int index = 1;
        for (String url : urls) {
            if (index > MAX_ENTRIES) {
                break;
            }
            config.setProperty(KEY_PREFIX + index, url);
            index++;
        }
    }
}
