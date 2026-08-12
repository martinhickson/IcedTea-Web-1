package net.sourceforge.jnlp.util;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmProbeSupportTest {

    @TempDir
    File temp;

    @Test
    void readMajorFromReleaseFileParsesJavaVersionWithoutSpawningProcess() throws Exception {
        File home = new File(temp, "jdk-17");
        assertTrue(home.mkdirs());
        File bin = new File(home, "bin");
        assertTrue(bin.mkdirs());
        File java = new File(bin, "java" + (isWindows() ? ".exe" : ""));
        assertTrue(java.createNewFile());
        try (FileWriter w = new FileWriter(new File(home, "release"), StandardCharsets.UTF_8)) {
            w.write("JAVA_VERSION=\"17.0.16\"\n");
            w.write("IMPLEMENTOR=\"Amazon.com Inc.\"\n");
        }

        assertEquals(17, JvmProbeSupport.readMajorFromReleaseFile(home.getAbsolutePath()));

        JvmDescriptor light = JvmDescriptor.describeLight(home.getAbsolutePath());
        assertTrue(light.isValid());
        assertEquals("17", light.getVersion());
    }

    @Test
    void describeLightRejectsMissingJavaBinaryWithoutProbing() {
        File home = new File(temp, "empty-home");
        assertTrue(home.mkdirs());
        JvmDescriptor light = JvmDescriptor.describeLight(home.getAbsolutePath());
        assertFalse(light.isValid());
    }

    @Test
    void describeLightEmptyAndNotDirWithoutProbing() {
        JvmDescriptor empty = JvmDescriptor.describeLight("  ");
        assertFalse(empty.isValid());
        assertEquals("", empty.getHomePath());

        JvmDescriptor missing = JvmDescriptor.describeLight(
                new File(temp, "no-such-home").getAbsolutePath());
        assertFalse(missing.isValid());
    }

    @Test
    void describeLightUsesPathFlavourAndVersionWhenReleaseMissing() throws Exception {
        File home = new File(temp, "Amazon Corretto/jdk21.0.3_9");
        assertTrue(new File(home, "bin").mkdirs());
        assertTrue(new File(home, "bin/java" + (isWindows() ? ".exe" : "")).createNewFile());

        JvmDescriptor light = JvmDescriptor.describeLight(home.getAbsolutePath());
        assertTrue(light.isValid());
        assertEquals("Amazon Corretto", light.getFlavour());
        assertTrue(light.getVersion().contains("21"), light.getVersion());
    }

    @Test
    void readMajorFromReleaseFileHandlesUnquotedAndLegacy18Style() throws Exception {
        File home8 = new File(temp, "jdk8");
        assertTrue(home8.mkdirs());
        try (FileWriter w = new FileWriter(new File(home8, "release"), StandardCharsets.UTF_8)) {
            w.write("JAVA_VERSION=1.8.0_412\n");
        }
        assertEquals(8, JvmProbeSupport.readMajorFromReleaseFile(home8.getAbsolutePath()));

        File home11 = new File(temp, "jdk11");
        assertTrue(home11.mkdirs());
        try (FileWriter w = new FileWriter(new File(home11, "release"), StandardCharsets.UTF_8)) {
            w.write("JAVA_VERSION=\"11.0.21\"\n");
        }
        assertEquals(11, JvmProbeSupport.readMajorFromReleaseFile(home11.getAbsolutePath()));
        assertEquals(0, JvmProbeSupport.readMajorFromReleaseFile(null));
        assertEquals(0, JvmProbeSupport.readMajorFromReleaseFile(temp.getAbsolutePath()));
    }

    @Test
    void majorVersionOfJvmHomePrefersReleaseFileOverProcessProbe() throws Exception {
        File home = new File(temp, "Amazon Corretto/jdk17.0.99_1");
        assertTrue(new File(home, "bin").mkdirs());
        assertTrue(new File(home, "bin/java" + (isWindows() ? ".exe" : "")).createNewFile());
        try (FileWriter w = new FileWriter(new File(home, "release"), StandardCharsets.UTF_8)) {
            w.write("JAVA_VERSION=\"17.0.99\"\n");
        }
        // Empty java.exe cannot be probed; release file must still win.
        assertEquals(17, JvmAutodetector.majorVersionOfJvmHome(home.getAbsolutePath()));
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }
}
