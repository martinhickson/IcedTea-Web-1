package net.sourceforge.jnlp.cache;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.util.JnlpAssignmentLauncher;
import net.sourceforge.jnlp.util.PropertiesFile;

/**
 * Discovers JNLP URLs recorded in the deployment cache ({@code jnlp-path} in .info files).
 */
public final class CachedJnlpUrlDiscovery {

    private CachedJnlpUrlDiscovery() {
    }

    public static List<String> discoverCachedJnlpUrls() {
        Set<String> urls = new LinkedHashSet<>();
        DirectoryNode root = new DirectoryNode("Root", PathsAndFiles.CACHE_DIR.getFile(), null);
        CacheDirectory.getDirStructure(root);
        for (DirectoryNode identifier : root.getChildren()) {
            for (DirectoryNode type : identifier.getChildren()) {
                for (DirectoryNode domain : type.getChildren()) {
                    for (DirectoryNode leaf : CacheDirectory.getLeafData(domain)) {
                        File cacheFile = leaf.getFile();
                        PropertiesFile info = new PropertiesFile(
                                new File(cacheFile.toString() + CacheDirectory.INFO_SUFFIX));
                        String jnlpPath = info.getProperty(CacheEntry.KEY_JNLP_PATH);
                        if (jnlpPath == null || jnlpPath.trim().isEmpty()) {
                            continue;
                        }
                        urls.add(JnlpAssignmentLauncher.canonicalizeJnlpUrl(jnlpPath.trim()));
                    }
                }
            }
        }
        List<String> sorted = new ArrayList<>(urls);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }
}
