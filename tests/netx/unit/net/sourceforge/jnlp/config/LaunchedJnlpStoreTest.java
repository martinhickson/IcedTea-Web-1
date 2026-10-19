package net.sourceforge.jnlp.config;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import net.sourceforge.jnlp.cache.CachedJnlpUrlDiscovery;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.Assert;
import org.junit.Test;

/**
 * A launched JNLP URL must show up for the JDK assignment tab even when the
 * cache catalog has no jnlp_path row.
 */
public class LaunchedJnlpStoreTest extends NoStdOutErrTest {

    @Test
    public void recordedLaunchIsVisibleToTheAssignmentTab() throws Exception {
        DeploymentConfiguration config = loadEmpty();
        String url = "https://example.test/apps/console.jnlp";

        Assert.assertTrue(LaunchedJnlpStore.record(config, url));
        Assert.assertFalse("recording the same launch again is a no-op",
                LaunchedJnlpStore.record(config, url));

        List<String> shown = CachedJnlpUrlDiscovery.discoverAssignmentJnlpUrls(config);
        Assert.assertTrue(shown.contains(url));
    }

    private static DeploymentConfiguration loadEmpty() throws Exception {
        File dir = Files.createTempDirectory("itw-launched-jnlp").toFile();
        File userFile = new File(dir, "deployment.properties");
        Files.write(userFile.toPath(), "# empty\n".getBytes(StandardCharsets.UTF_8));
        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load((URL) null, userFile, false);
        return config;
    }
}
