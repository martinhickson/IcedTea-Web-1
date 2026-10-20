package net.sourceforge.jnlp.cache;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.Assert;
import org.junit.Test;

/**
 * The JDK assignment tab must list a launched application from the cache even
 * when the catalog row never stored {@code jnlp_path}.
 */
public class CachedJnlpUrlDiscoveryTest extends NoStdOutErrTest {

    @Test
    public void cachedJnlpResourceShowsUpWithoutJnlpPath() {
        CacheEntryMeta jnlp = new CacheEntryMeta();
        jnlp.resourceUrl = "https://example.test/apps/console.jnlp";
        jnlp.jnlpPath = null;

        CacheEntryMeta jar = new CacheEntryMeta();
        jar.resourceUrl = "https://example.test/apps/console.jar";
        jar.jnlpPath = "";

        Set<String> urls = new LinkedHashSet<>();
        List<CacheEntryMeta> rows = new ArrayList<>();
        rows.add(jnlp);
        rows.add(jar);
        CachedJnlpUrlDiscovery.collectAssignmentUrls(urls, rows);

        Assert.assertTrue(urls.contains("https://example.test/apps/console.jnlp"));
        Assert.assertFalse(urls.contains("https://example.test/apps/console.jar"));
    }

    @Test
    public void storedJnlpPathStillShowsUp() {
        CacheEntryMeta row = new CacheEntryMeta();
        row.jnlpPath = "https://example.test/apps/other.jnlp?token=1";
        row.resourceUrl = "https://example.test/apps/other.jar";

        Set<String> urls = new LinkedHashSet<>();
        List<CacheEntryMeta> rows = new ArrayList<>();
        rows.add(row);
        CachedJnlpUrlDiscovery.collectAssignmentUrls(urls, rows);

        Assert.assertTrue(urls.contains("https://example.test/apps/other.jnlp?token=1"));
        Assert.assertEquals(1, urls.size());
    }
}
