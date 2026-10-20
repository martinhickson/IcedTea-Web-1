package net.sourceforge.jnlp.config;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import net.sourceforge.jnlp.util.JvmSelector;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.Assert;
import org.junit.Test;

/**
 * Disable keeps a JDK in the list but out of automatic selection.
 * Remove keeps it out of autodetect. A disabled JDK is not turned back on
 * when discovered homes are merged.
 */
public class KnownJvmStoreDisableRemoveTest extends NoStdOutErrTest {

    @Test
    public void removedHomeIsNotRestoredByAutodetectMerge() throws Exception {
        DeploymentConfiguration config = loadEmpty();
        KnownJvmStore.setKnownJvmHomes(config, Collections.singletonList("/opt/jdk-kept"));
        KnownJvmStore.markJdkRemoved(config, "/opt/jdk-deleted");

        Assert.assertFalse(KnownJvmStore.mergeDiscoveredHomes(config,
                Arrays.asList("/opt/jdk-kept", "/opt/jdk-deleted")));
        Assert.assertEquals(Collections.singletonList("/opt/jdk-kept"),
                KnownJvmStore.getKnownJvmHomes(config));
        Assert.assertFalse(KnownJvmStore.ensureKnownJvmHome(config, "/opt/jdk-deleted"));
    }

    @Test
    public void autodetectMergeDoesNotEnableADisabledJdk() throws Exception {
        DeploymentConfiguration config = loadEmpty();
        KnownJvmStore.setKnownJvmHomes(config, Collections.singletonList("/opt/jdk-a"));
        KnownJvmStore.setJdkDisabled(config, "/opt/jdk-a", true);

        Assert.assertTrue(KnownJvmStore.mergeDiscoveredHomes(config,
                Arrays.asList("/opt/jdk-a", "/opt/jdk-new")));

        Assert.assertTrue(KnownJvmStore.isJdkDisabled(config, "/opt/jdk-a"));
        Assert.assertFalse(KnownJvmStore.isJdkDisabled(config, "/opt/jdk-new"));
        Assert.assertEquals(Collections.singletonList("/opt/jdk-new"),
                KnownJvmStore.getEnabledJvmHomes(config));
    }

    @Test
    public void deletingAJdkDropsItsAssignmentAndKeepsTheOther() throws Exception {
        DeploymentConfiguration config = loadEmpty();
        KnownJvmStore.setKnownJvmHomes(config, Arrays.asList("/opt/jdk-a", "/opt/jdk-b"));
        KnownJvmAssignmentStore.setAssignments(config, Collections.singletonList(
                new KnownJvmAssignmentStore.JvmAssignment(2, 1, "https://example.test/app.jnlp")));

        KnownJvmStore.markJdkRemoved(config, "/opt/jdk-a");
        KnownJvmStore.setKnownJvmHomes(config, Collections.singletonList("/opt/jdk-b"));

        Assert.assertEquals("/opt/jdk-b",
                KnownJvmAssignmentStore.findJvmHomeForJnlpUrl(config, "https://example.test/app.jnlp"));
        Assert.assertTrue(KnownJvmStore.isJdkRemoved(config, "/opt/jdk-a"));
    }

    @Test
    public void disabledJdkIsSkippedByAutomaticSelection() throws Exception {
        String home = System.getProperty("java.home");
        Assert.assertNotNull(home);
        DeploymentConfiguration config = loadEmpty();
        KnownJvmStore.setKnownJvmHomes(config, Collections.singletonList(home));
        KnownJvmStore.setJdkDisabled(config, home, true);

        Assert.assertNull(JvmSelector.selectBestJvmHome(config, null));

        KnownJvmStore.setJdkDisabled(config, home, false);
        Assert.assertNotNull(JvmSelector.selectBestJvmHome(config, null));
    }

    private static DeploymentConfiguration loadEmpty() throws Exception {
        File dir = Files.createTempDirectory("itw-jdk-disable").toFile();
        File userFile = new File(dir, "deployment.properties");
        Files.write(userFile.toPath(), "# empty\n".getBytes(StandardCharsets.UTF_8));
        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load((URL) null, userFile, false);
        return config;
    }
}
