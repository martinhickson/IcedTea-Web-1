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

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }
}
