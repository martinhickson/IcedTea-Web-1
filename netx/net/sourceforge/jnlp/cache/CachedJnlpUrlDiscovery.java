package net.sourceforge.jnlp.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.LaunchedJnlpStore;
import net.sourceforge.jnlp.util.JnlpAssignmentLauncher;

/**
 * Discovers JNLP URLs recorded on catalog rows ({@code jnlp_path}), not sidecar
 * {@code .info} files.
 */
public final class CachedJnlpUrlDiscovery {

    private CachedJnlpUrlDiscovery() {
    }

    /**
     * Cache {@code jnlp_path} rows plus JNLP URLs recorded at launch. A cache hit
     * does not rewrite {@code jnlp_path}, so the launch list is what makes a
     * previously started application show up on the JDK assignment tab.
     */
    public static List<String> discoverAssignmentJnlpUrls(DeploymentConfiguration config) {
        Set<String> urls = new LinkedHashSet<>(discoverCachedJnlpUrls());
        if (config != null) {
            for (String launched : LaunchedJnlpStore.getUrls(config)) {
                if (launched != null && !launched.isEmpty()) {
                    urls.add(launched);
                }
            }
        }
        List<String> sorted = new ArrayList<>(urls);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    public static List<String> discoverCachedJnlpUrls() {
        Set<String> urls = new LinkedHashSet<>();
        CacheLRUWrapper lru = CacheLRUWrapper.getInstance();
        lru.lock();
        try {
            lru.load();
            collectAssignmentUrls(urls, lru.listAllMeta());
        } finally {
            lru.unlock();
        }
        List<String> sorted = new ArrayList<>(urls);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    /**
     * {@code jnlp_path} is the launch URL when a download recorded it.
     * A cached {@code .jnlp} resource is the same application when that field
     * was never written (cache hit, or a later store that left it blank).
     */
    static void collectAssignmentUrls(Set<String> urls, List<CacheEntryMeta> rows) {
        if (urls == null || rows == null) {
            return;
        }
        for (CacheEntryMeta row : rows) {
            if (row == null) {
                continue;
            }
            addUrl(urls, row.jnlpPath, false);
            addUrl(urls, row.resourceUrl, true);
        }
    }

    private static void addUrl(Set<String> urls, String value, boolean requireJnlpSuffix) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (requireJnlpSuffix && !looksLikeJnlp(trimmed)) {
            return;
        }
        urls.add(JnlpAssignmentLauncher.canonicalizeJnlpUrl(trimmed));
    }

    static boolean looksLikeJnlp(String value) {
        String path = value;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        return path.toLowerCase(java.util.Locale.ROOT).endsWith(".jnlp");
    }
}
