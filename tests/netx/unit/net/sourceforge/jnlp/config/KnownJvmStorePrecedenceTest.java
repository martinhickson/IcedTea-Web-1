package net.sourceforge.jnlp.config;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import net.sourceforge.jnlp.util.JvmDescriptor;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.Assert;
import org.junit.Test;

public class KnownJvmStorePrecedenceTest extends NoStdOutErrTest {

    @Test
    public void legacyJreDirAppearsFirstEvenWhenNumberedJdksExist() throws Exception {
        File dir = Files.createTempDirectory("itw-jdk-prec").toFile();
        File userFile = new File(dir, "deployment.properties");
        String legacy = new File(dir, "legacy-jdk").getAbsolutePath();
        String numbered = new File(dir, "autodetect-jdk").getAbsolutePath();
        Files.write(userFile.toPath(), (
                "deployment.jre.dir=" + escapeProp(legacy) + "\n"
                + "deployment.jdk.1=" + escapeProp(numbered) + "\n"
                ).getBytes(StandardCharsets.UTF_8));

        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load((URL) null, userFile, false);

        List<String> homes = KnownJvmStore.getKnownJvmHomes(config);
        Assert.assertEquals(2, homes.size());
        Assert.assertEquals(legacy, homes.get(0));
        Assert.assertEquals(numbered, homes.get(1));
    }

    @Test
    public void legacyDuplicateOfNumberedJdkDoesNotDuplicateListEntry() throws Exception {
        File dir = Files.createTempDirectory("itw-jdk-dup").toFile();
        File userFile = new File(dir, "deployment.properties");
        String only = new File(dir, "only-jdk").getAbsolutePath();
        Files.write(userFile.toPath(), (
                "deployment.jre.dir=" + escapeProp(only) + "\n"
                + "deployment.jdk.1=" + escapeProp(only) + "\n"
                ).getBytes(StandardCharsets.UTF_8));

        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load((URL) null, userFile, false);

        Assert.assertEquals(Arrays.asList(only), KnownJvmStore.getKnownJvmHomes(config));
    }

    @Test
    public void setKnownJvmHomesWritesTopEntryAsLegacyDefaultSlot() throws Exception {
        File dir = Files.createTempDirectory("itw-jdk-set").toFile();
        File userFile = new File(dir, "deployment.properties");
        Files.write(userFile.toPath(), "# empty\n".getBytes(StandardCharsets.UTF_8));

        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load((URL) null, userFile, false);

        String first = new File(dir, "first").getAbsolutePath();
        String second = new File(dir, "second").getAbsolutePath();
        KnownJvmStore.setKnownJvmHomes(config, Arrays.asList(first, second));

        Assert.assertEquals(first, config.getProperty(DeploymentConfiguration.KEY_JRE_DIR));
        Assert.assertEquals(first, config.getProperty("deployment.jdk.1"));
        Assert.assertEquals(second, config.getProperty("deployment.jdk.2"));
    }

    @Test
    public void autodetectionPreferenceSortsCorretto17ThenTemurin21ThenOpenJdk() {
        JvmDescriptor corretto17 = new JvmDescriptor("C:\\corretto-17", "Amazon Corretto", "17", true, null);
        JvmDescriptor corretto21 = new JvmDescriptor("C:\\corretto-21", "Amazon Corretto", "21", true, null);
        JvmDescriptor temurin21 = new JvmDescriptor("C:\\temurin-21", "Eclipse Temurin", "21", true, null);
        JvmDescriptor openJdk11 = new JvmDescriptor("C:\\openjdk-11", "OpenJDK", "11", true, null);
        JvmDescriptor openJdk21 = new JvmDescriptor("C:\\openjdk-21", "OpenJDK", "21", true, null);

        List<JvmDescriptor> sorted = new ArrayList<>(Arrays.asList(
                openJdk11, temurin21, openJdk21, corretto21, corretto17));
        sorted.sort(Comparator
                .comparingInt(KnownJvmStore::vendorRank)
                .thenComparingInt(KnownJvmStore::versionRank)
                .thenComparing(JvmDescriptor::getHomePath, String.CASE_INSENSITIVE_ORDER));

        Assert.assertEquals(Arrays.asList(
                corretto17.getHomePath(),
                corretto21.getHomePath(),
                temurin21.getHomePath(),
                openJdk21.getHomePath(),
                openJdk11.getHomePath()),
                pathsOf(sorted));
    }

    @Test
    public void vendorRankUsesInstallPathWhenFlavourIsGenericJava() {
        // Matches probe fast-path: synthetic version output yields flavour "Java".
        JvmDescriptor corretto = new JvmDescriptor(
                "C:\\Program Files\\Amazon Corretto\\jdk17.0.15_6", "Java", "17", true, null);
        JvmDescriptor temurin = new JvmDescriptor(
                "C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.11.9-hotspot", "Java", "21", true, null);
        JvmDescriptor other = new JvmDescriptor("C:\\Program Files\\Java\\jdk-17", "Java", "17", true, null);

        Assert.assertEquals(0, KnownJvmStore.vendorRank(corretto));
        Assert.assertEquals(1, KnownJvmStore.vendorRank(temurin));
        Assert.assertEquals(2, KnownJvmStore.vendorRank(other));
    }

    @Test
    public void autodetectionPreferenceComparatorUsesDescribeLightOnly() throws Exception {
        File dir = Files.createTempDirectory("itw-jdk-light-sort").toFile();
        String open11 = fakeLightHome(dir, "Java", "jdk-11.0.1");
        String temurin21 = fakeLightHome(dir, "Eclipse Adoptium", "jdk-21.0.1");
        String corretto17 = fakeLightHome(dir, "Amazon Corretto", "jdk17.0.1");

        // Fake homes have java.exe but cannot be process-probed — comparator must still rank.
        Assert.assertTrue(JvmDescriptor.describeLight(corretto17).isValid());
        List<String> homes = new ArrayList<>(Arrays.asList(open11, temurin21, corretto17));
        homes.sort(KnownJvmStore.autodetectionPreferenceComparator());

        Assert.assertEquals(Arrays.asList(corretto17, temurin21, open11), homes);
    }

    private static String fakeLightHome(File root, String vendorDir, String leaf) throws Exception {
        File home = new File(root, vendorDir + File.separator + leaf);
        Assert.assertTrue(new File(home, "bin").mkdirs());
        String javaName = "java" + (File.separatorChar == '\\' ? ".exe" : "");
        Assert.assertTrue(new File(home, "bin" + File.separator + javaName).createNewFile());
        return home.getAbsolutePath();
    }

    private static List<String> pathsOf(List<JvmDescriptor> descriptors) {
        List<String> paths = new ArrayList<>();
        for (JvmDescriptor descriptor : descriptors) {
            paths.add(descriptor.getHomePath());
        }
        return paths;
    }

    private static String escapeProp(String value) {
        return value.replace("\\", "\\\\");
    }
}
