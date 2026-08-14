package net.sourceforge.jnlp.cache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.sourceforge.jnlp.util.JnlpAssignmentLauncher;

/**
 * Discovers JNLP URLs recorded on catalog rows ({@code jnlp_path}), not sidecar
 * {@code .info} files.
 */
public final class CachedJnlpUrlDiscovery {

    private CachedJnlpUrlDiscovery() {
    }

    public static List<String> discoverCachedJnlpUrls() {
        Set<String> urls = new LinkedHashSet<>();
        CacheLRUWrapper lru = CacheLRUWrapper.getInstance();
        lru.lock();
        try {
            lru.load();
            for (CacheEntryMeta row : lru.listAllMeta()) {
                if (row.jnlpPath == null || row.jnlpPath.trim().isEmpty()) {
                    continue;
                }
                urls.add(JnlpAssignmentLauncher.canonicalizeJnlpUrl(row.jnlpPath.trim()));
            }
        } finally {
            lru.unlock();
        }
        List<String> sorted = new ArrayList<>(urls);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }
}
