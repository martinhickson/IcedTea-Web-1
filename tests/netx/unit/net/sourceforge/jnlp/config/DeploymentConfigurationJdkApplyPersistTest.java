package net.sourceforge.jnlp.config;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Properties;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.Assert;
import org.junit.Test;

/**
 * Control Panel Apply must keep deployment.jdk.* on disk across subsequent Applies.
 * refreshPersistedBaseline must not poison the save() defaults baseline.
 */
public class DeploymentConfigurationJdkApplyPersistTest extends NoStdOutErrTest {

    @Test
    public void applyThenSecondApplyKeepsKnownJvmsOnDisk() throws Exception {
        File dir = Files.createTempDirectory("itw-jdk-apply").toFile();
        File userFile = new File(dir, "deployment.properties");
        Files.write(userFile.toPath(), "# empty\n".getBytes(StandardCharsets.UTF_8));

        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load((URL) null, userFile, false);
        config.beginEditorSession();

        KnownJvmStore.setKnownJvmHomes(config, Arrays.asList(
                "C:\\Program Files\\Amazon Corretto\\jdk21.0.7_6",
                "C:\\Program Files\\Amazon Corretto\\jdk17.0.15_6"));
        Assert.assertTrue(config.hasPendingChanges());

        config.applyPendingChanges();
        Assert.assertFalse(config.hasPendingChanges());

        Properties afterFirst = loadProps(userFile);
        Assert.assertEquals("C:\\Program Files\\Amazon Corretto\\jdk21.0.7_6",
                afterFirst.getProperty("deployment.jdk.1"));
        Assert.assertEquals("C:\\Program Files\\Amazon Corretto\\jdk17.0.15_6",
                afterFirst.getProperty("deployment.jdk.2"));

        // Second Apply with only an unrelated change must not wipe JDK keys.
        config.setProperty(DeploymentConfiguration.KEY_ENABLE_LOGGING_TOFILE, "true");
        config.applyPendingChanges();

        Properties afterSecond = loadProps(userFile);
        Assert.assertEquals("C:\\Program Files\\Amazon Corretto\\jdk21.0.7_6",
                afterSecond.getProperty("deployment.jdk.1"));
        Assert.assertEquals("C:\\Program Files\\Amazon Corretto\\jdk17.0.15_6",
                afterSecond.getProperty("deployment.jdk.2"));
        Assert.assertEquals("true",
                afterSecond.getProperty(DeploymentConfiguration.KEY_ENABLE_LOGGING_TOFILE));
    }

    private static Properties loadProps(File file) throws Exception {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        }
        return props;
    }
}
