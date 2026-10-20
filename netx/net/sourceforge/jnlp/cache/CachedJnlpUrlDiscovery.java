package net.sourceforge.jnlp.cache;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
     * {@code jnlp_path} is the launch URL when a download recorded one.
     * A catalog {@code resource_url} is a cache key such as
     * {@code /http/host/app/app.jnlp}, not a URL javaws can open. Use it only
     * when {@code jnlp_path} is blank, and only after turning that key back
     * into an http, https, or file URL.
     */
    static void collectAssignmentUrls(Set<String> urls, List<CacheEntryMeta> rows) {
        if (urls == null || rows == null) {
            return;
        }
        for (CacheEntryMeta row : rows) {
            if (row == null) {
                continue;
            }
            if (isLaunchableUrl(row.jnlpPath)) {
                addUrl(urls, row.jnlpPath, false);
                continue;
            }
            addUrl(urls, launchUrlFromResource(row.resourceUrl), true);
        }
    }

    /**
     * A resource value that already has an http, https, or file scheme is used
     * as-is. A cache-relative key is reconstructed; anything else is dropped.
     */
    private static String launchUrlFromResource(String resourceUrl) {
        if (resourceUrl == null) {
            return null;
        }
        String trimmed = resourceUrl.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (isLaunchableUrl(trimmed)) {
            return trimmed;
        }
        return CacheUtil.hrefFromCacheRelativePath(trimmed);
    }

    static boolean isLaunchableUrl(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            String scheme = new URI(trimmed).getScheme();
            return "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme)
                    || "file".equalsIgnoreCase(scheme);
        } catch (Exception ex) {
            return false;
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
        return path.toLowerCase(Locale.ROOT).endsWith(".jnlp");
    }
}
