package net.sourceforge.jnlp.config;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.Assert;
import org.junit.Test;

/**
 * Launch-time autodetect must persist a numbered deployment.jdk.N even when the
 * path is already visible only through deployment.jre.dir fallback.
 */
public class KnownJvmStoreEnsureHomeTest extends NoStdOutErrTest {

    @Test
    public void ensureKnownJvmHomePersistsNumberedKeyWhenOnlyJreDirFallbackExists() throws Exception {
        File dir = Files.createTempDirectory("itw-ensure-jdk").toFile();
        File userFile = new File(dir, "deployment.properties");
        String jdkHome = new File(dir, "fake-jdk-21").getAbsolutePath();
        Files.write(userFile.toPath(), (
                "deployment.jre.dir=" + jdkHome.replace("\\", "\\\\") + "\n"
                ).getBytes(StandardCharsets.UTF_8));

        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load((URL) null, userFile, false);

        Assert.assertFalse("precondition: no numbered jdk key yet",
                KnownJvmStore.isStoredAsNumberedJdk(config, jdkHome));
        Assert.assertEquals(jdkHome, KnownJvmStore.getKnownJvmHomes(config).get(0));

        Assert.assertTrue(KnownJvmStore.ensureKnownJvmHome(config, jdkHome));
        config.save();

        Properties saved = new Properties();
        try (FileInputStream in = new FileInputStream(userFile)) {
            saved.load(in);
        }
        Assert.assertEquals(jdkHome, saved.getProperty("deployment.jdk.1"));
        Assert.assertTrue(KnownJvmStore.isStoredAsNumberedJdk(config, jdkHome));
        Assert.assertFalse("second ensure is a no-op",
                KnownJvmStore.ensureKnownJvmHome(config, jdkHome));
    }
}
