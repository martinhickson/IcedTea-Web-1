package net.sourceforge.jnlp.util;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.JdkMatchStrategy;
import net.sourceforge.jnlp.config.KnownJvmStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selection must light-describe candidates and only fully validate the chosen JVM.
 * Fake homes (java.exe present, cannot probe) fail full validate and are skipped.
 */
class JvmSelectorValidateOnceTest {

    @TempDir
    File temp;

    @Test
    void selectBestJvmHomeSkipsInvalidCandidateAndReturnsNullWhenNoneValidate() throws Exception {
        File fake17 = fakeJdkHome("Amazon Corretto", "jdk17.0.1", "17.0.1");
        DeploymentConfiguration config = new DeploymentConfiguration();
        KnownJvmStore.setKnownJvmHomes(config, Arrays.asList(fake17.getAbsolutePath()));
        KnownJvmStore.setMatchStrategy(config, JdkMatchStrategy.EXACT);

        // Light describe says valid; full validateJvm cannot probe → skip → null
        assertTrue(JvmDescriptor.describeLight(fake17.getAbsolutePath()).isValid());
        assertNull(JvmSelector.selectBestJvmHome(config, "17"));
    }

    @Test
    void selectBestJvmHomePrefersCurrentRuntimeWhenListedAndRequested() throws Exception {
        String realHome = JvmDescriptor.describeCurrentRuntime().getHomePath();
        int major = JavaVersionUtils.getRunningMajorVersion();
        File fakeOther = fakeJdkHome("Amazon Corretto", "jdk99.0.0", "99.0.0");

        DeploymentConfiguration config = new DeploymentConfiguration();
        // Fake first in preference order — must be skipped after failed validation
        KnownJvmStore.setKnownJvmHomes(config,
                Arrays.asList(fakeOther.getAbsolutePath(), realHome));
        KnownJvmStore.setMatchStrategy(config, JdkMatchStrategy.EXACT);

        String selected = JvmSelector.selectBestJvmHome(config, Integer.toString(major));
        assertEquals(realHome, selected);
    }

    @Test
    void describeKnownJvmsUsesLightDescribeWithoutRequiringProbe() throws Exception {
        File fake = fakeJdkHome("Eclipse Adoptium", "jdk-21.0.1", "21.0.1");
        DeploymentConfiguration config = new DeploymentConfiguration();
        KnownJvmStore.setKnownJvmHomes(config, Arrays.asList(fake.getAbsolutePath()));

        assertEquals(1, JvmSelector.describeKnownJvms(config).size());
        assertTrue(JvmSelector.describeKnownJvms(config).get(0).isValid());
        assertEquals("21", JvmSelector.describeKnownJvms(config).get(0).getVersion());
    }

    private File fakeJdkHome(String vendorDir, String leaf, String javaVersion) throws Exception {
        File home = new File(temp, vendorDir + "/" + leaf);
        assertTrue(new File(home, "bin").mkdirs());
        File java = new File(home, "bin/java" + (isWindows() ? ".exe" : ""));
        assertTrue(java.createNewFile());
        try (FileWriter w = new FileWriter(new File(home, "release"), StandardCharsets.UTF_8)) {
            w.write("JAVA_VERSION=\"" + javaVersion + "\"\n");
        }
        return home;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }
}
