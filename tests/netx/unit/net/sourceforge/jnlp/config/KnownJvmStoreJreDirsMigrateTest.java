package net.sourceforge.jnlp.config;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.Assert;
import org.junit.Test;

public class KnownJvmStoreJreDirsMigrateTest extends NoStdOutErrTest {

    @Test
    public void parseJreDirsSplitsOnPipeAndSkipsBlanks() {
        Assert.assertEquals(Arrays.asList("/opt/jdk-21", "/opt/jdk-17"),
                KnownJvmStore.parseJreDirs("/opt/jdk-21|/opt/jdk-17"));
        Assert.assertEquals(Arrays.asList("/opt/jdk-21"),
                KnownJvmStore.parseJreDirs("  /opt/jdk-21 | | "));
        Assert.assertEquals(Collections.emptyList(), KnownJvmStore.parseJreDirs(""));
        Assert.assertEquals(Collections.emptyList(), KnownJvmStore.parseJreDirs(null));
    }

    @Test
    public void isMigratableJvmHomeRequiresValidBinaryAndRejectsJava8() throws Exception {
        File dir = Files.createTempDirectory("itw-jre-dirs-valid").toFile();
        String jdk21 = fakeJvmHome(dir, "jdk-21.0.1", "21.0.1");
        String jdk11 = fakeJvmHome(dir, "jdk-11.0.2", "11.0.2");
        String jdk18 = fakeJvmHome(dir, "jdk1.8.0_392", "1.8.0_392");
        String missingJava = new File(dir, "empty-home").getAbsolutePath();
        Assert.assertTrue(new File(missingJava).mkdirs());

        Assert.assertTrue(KnownJvmStore.isMigratableJvmHome(jdk21));
        Assert.assertTrue(KnownJvmStore.isMigratableJvmHome(jdk11));
        Assert.assertFalse(KnownJvmStore.isMigratableJvmHome(jdk18));
        Assert.assertFalse(KnownJvmStore.isMigratableJvmHome(missingJava));
        Assert.assertFalse(KnownJvmStore.isMigratableJvmHome(""));
        Assert.assertFalse(KnownJvmStore.isMigratableJvmHome(null));
    }

    @Test
    public void dirsOnlyFileBecomesKnownJvmListAndDropsLegacyKey() throws Exception {
        File dir = Files.createTempDirectory("itw-jre-dirs").toFile();
        File userFile = new File(dir, "deployment.properties");
        String first = fakeJvmHome(dir, "jdk-21.0.1", "21.0.1");
        String second = fakeJvmHome(dir, "jdk-17.0.1", "17.0.1");
        Files.write(userFile.toPath(), (
                migrateEnabled()
                + "deployment.jre.dirs=" + escapeProp(first) + "|" + escapeProp(second) + "\n"
                ).getBytes(StandardCharsets.UTF_8));

        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load((URL) null, userFile, true);

        Assert.assertEquals(Arrays.asList(first, second), KnownJvmStore.getKnownJvmHomes(config));
        Assert.assertEquals(first, config.getProperty(DeploymentConfiguration.KEY_JRE_DIR));
        Assert.assertEquals(first, config.getProperty("deployment.jdk.1"));
        Assert.assertEquals(second, config.getProperty("deployment.jdk.2"));
        Assert.assertNull(config.getProperty(DeploymentConfiguration.KEY_JRE_DIRS));

        Properties saved = loadProps(userFile);
        Assert.assertNull(saved.getProperty(DeploymentConfiguration.KEY_JRE_DIRS));
        Assert.assertEquals(first, saved.getProperty("deployment.jdk.1"));
        Assert.assertEquals(second, saved.getProperty("deployment.jdk.2"));
    }

    @Test
    public void existingKnownHomesKeepOrderAndAppendNewDirsEntries() throws Exception {
        File dir = Files.createTempDirectory("itw-jre-dirs-merge").toFile();
        File userFile = new File(dir, "deployment.properties");
        String existing = fakeJvmHome(dir, "jdk-17.0.1", "17.0.1");
        String extra = fakeJvmHome(dir, "jdk-21.0.1", "21.0.1");
        Files.write(userFile.toPath(), (
                migrateEnabled()
                + "deployment.jre.dir=" + escapeProp(existing) + "\n"
                + "deployment.jdk.1=" + escapeProp(existing) + "\n"
                + "deployment.jre.dirs=" + escapeProp(existing) + "|" + escapeProp(extra) + "\n"
                ).getBytes(StandardCharsets.UTF_8));

        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load((URL) null, userFile, true);

        Assert.assertEquals(Arrays.asList(existing, extra), KnownJvmStore.getKnownJvmHomes(config));
        Assert.assertNull(config.getProperty(DeploymentConfiguration.KEY_JRE_DIRS));
    }

    @Test
    public void migrateSkipsJava8AndHomesWithoutJavaBinary() throws Exception {
        File dir = Files.createTempDirectory("itw-jre-dirs-skip").toFile();
        File userFile = new File(dir, "deployment.properties");
        String jdk21 = fakeJvmHome(dir, "jdk-21.0.1", "21.0.1");
        String jdk18 = fakeJvmHome(dir, "jdk1.8.0_392", "1.8.0_392");
        String missingJava = new File(dir, "not-a-jdk").getAbsolutePath();
        Assert.assertTrue(new File(missingJava).mkdirs());
        Files.write(userFile.toPath(), (
                migrateEnabled()
                + "deployment.jre.dirs=" + escapeProp(jdk18) + "|" + escapeProp(missingJava)
                        + "|" + escapeProp(jdk21) + "\n"
                ).getBytes(StandardCharsets.UTF_8));

        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load((URL) null, userFile, true);

        Assert.assertEquals(Arrays.asList(jdk21), KnownJvmStore.getKnownJvmHomes(config));
        Assert.assertEquals(jdk21, config.getProperty(DeploymentConfiguration.KEY_JRE_DIR));
        Assert.assertNull(config.getProperty(DeploymentConfiguration.KEY_JRE_DIRS));
        Assert.assertNull(config.getProperty("deployment.jdk.2"));
    }

    @Test
    public void migrateDropsLegacyKeyWhenEveryDirsEntryIsInvalid() throws Exception {
        File dir = Files.createTempDirectory("itw-jre-dirs-all-bad").toFile();
        File userFile = new File(dir, "deployment.properties");
        String jdk18 = fakeJvmHome(dir, "jdk1.8.0_392", "1.8.0_392");
        String missingJava = new File(dir, "not-a-jdk").getAbsolutePath();
        Assert.assertTrue(new File(missingJava).mkdirs());
        Files.write(userFile.toPath(), (
                migrateEnabled()
                + "deployment.jre.dirs=" + escapeProp(jdk18) + "|" + escapeProp(missingJava) + "\n"
                ).getBytes(StandardCharsets.UTF_8));

        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load((URL) null, userFile, true);

        Assert.assertEquals(Collections.emptyList(), KnownJvmStore.getKnownJvmHomes(config));
        Assert.assertNull(config.getProperty(DeploymentConfiguration.KEY_JRE_DIRS));
        Assert.assertTrue(config.getProperty(DeploymentConfiguration.KEY_JRE_DIR) == null
                || config.getProperty(DeploymentConfiguration.KEY_JRE_DIR).trim().isEmpty());
    }

    @Test
    public void defaultLeavesJreDirsAsUnknownAndDoesNotMigrate() throws Exception {
        File dir = Files.createTempDirectory("itw-jre-dirs-default").toFile();
        File userFile = new File(dir, "deployment.properties");
        String first = fakeJvmHome(dir, "jdk-21.0.1", "21.0.1");
        String second = fakeJvmHome(dir, "jdk-17.0.1", "17.0.1");
        String dirsValue = first + "|" + second;
        Files.write(userFile.toPath(), (
                "deployment.jre.dirs=" + escapeProp(dirsValue) + "\n"
                ).getBytes(StandardCharsets.UTF_8));

        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load((URL) null, userFile, true);

        Assert.assertEquals("false", config.getProperty(DeploymentConfiguration.KEY_JRE_DIRS_MIGRATE));
        Assert.assertFalse(KnownJvmStore.isJreDirsMigrateEnabled(config));
        Assert.assertEquals(dirsValue, config.getProperty(DeploymentConfiguration.KEY_JRE_DIRS));
        Assert.assertEquals(Collections.emptyList(), KnownJvmStore.getKnownJvmHomes(config));
        Assert.assertNull(config.getProperty("deployment.jdk.1"));

        Properties saved = loadProps(userFile);
        Assert.assertEquals(dirsValue, saved.getProperty(DeploymentConfiguration.KEY_JRE_DIRS));
        Assert.assertNull(saved.getProperty("deployment.jdk.1"));
    }

    private static String migrateEnabled() {
        return DeploymentConfiguration.KEY_JRE_DIRS_MIGRATE + "=true\n";
    }

    private static String fakeJvmHome(File root, String leaf, String javaVersion) throws Exception {
        File home = new File(root, leaf);
        Assert.assertTrue(new File(home, "bin").mkdirs());
        String javaName = JNLPRuntime.isWindows() ? "java.exe" : "java";
        Assert.assertTrue(new File(home, "bin" + File.separator + javaName).createNewFile());
        Files.write(new File(home, "release").toPath(),
                ("JAVA_VERSION=\"" + javaVersion + "\"\n").getBytes(StandardCharsets.UTF_8));
        return home.getAbsolutePath();
    }

    private static Properties loadProps(File file) throws Exception {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        }
        return props;
    }

    private static String escapeProp(String value) {
        return value.replace("\\", "\\\\");
    }
}
