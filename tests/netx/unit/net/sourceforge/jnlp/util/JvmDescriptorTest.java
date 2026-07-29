package net.sourceforge.jnlp.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import org.junit.Test;

public class JvmDescriptorTest {

    @Test
    public void resolveToolsHomeUsesJdkRootWhenJavaHomeIsJre() throws IOException {
        File jdkRoot = createTempDir("jdk-tools-home");
        File jreHome = new File(jdkRoot, "jre");
        assertTrue(jreHome.mkdirs());
        assertTrue(new File(jdkRoot, "bin").mkdirs());
        File jcmd = new File(jdkRoot, "bin" + File.separator + "jcmd"
                + (net.sourceforge.jnlp.runtime.JNLPRuntime.isWindows() ? ".exe" : ""));
        assertTrue(jcmd.createNewFile());

        assertEquals(jdkRoot.getAbsolutePath(), JvmDescriptor.resolveToolsHome(jreHome.getAbsolutePath()));
    }

    @Test
    public void resolveToolsHomeKeepsHomeWhenJcmdIsPresent() throws IOException {
        File jdkRoot = createTempDir("jdk-direct-home");
        assertTrue(new File(jdkRoot, "bin").mkdirs());
        File jcmd = new File(jdkRoot, "bin" + File.separator + "jcmd"
                + (net.sourceforge.jnlp.runtime.JNLPRuntime.isWindows() ? ".exe" : ""));
        assertTrue(jcmd.createNewFile());

        assertEquals(jdkRoot.getAbsolutePath(), JvmDescriptor.resolveToolsHome(jdkRoot.getAbsolutePath()));
    }

    @Test
    public void describeCurrentRuntimeUsesToolsHome() {
        JvmDescriptor descriptor = JvmDescriptor.describeCurrentRuntime();
        assertNotNull(descriptor.getHomePath());
        assertTrue(!descriptor.getHomePath().trim().isEmpty());
        assertEquals(JvmDescriptor.resolveToolsHome(System.getProperty("java.home")),
                descriptor.getHomePath());
    }

    @Test
    public void detectFlavourFromPathRecognizesCorrettoAndTemurinInstallLayouts() {
        assertEquals("Amazon Corretto",
                JvmDescriptor.detectFlavourFromPath("C:\\Program Files\\Amazon Corretto\\jdk17.0.15_6"));
        assertEquals("Eclipse Temurin",
                JvmDescriptor.detectFlavourFromPath("C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.11.9-hotspot"));
        assertEquals("Eclipse Temurin",
                JvmDescriptor.detectFlavourFromPath("/usr/lib/jvm/temurin-17-jdk-amd64"));
        assertEquals("", JvmDescriptor.detectFlavourFromPath("C:\\Program Files\\Java\\jdk-17"));
    }

    @Test
    public void detectFlavourRecognizesAdoptiumTokenInVersionOutput() {
        assertEquals("Eclipse Temurin",
                JvmDescriptor.detectFlavour("openjdk runtime environment temurin-21.0.11+9"));
        assertEquals("Eclipse Temurin",
                JvmDescriptor.detectFlavour("eclipse adoptium openjdk"));
    }

    private static File createTempDir(String prefix) throws IOException {
        File dir = File.createTempFile(prefix, "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdir());
        dir.deleteOnExit();
        return dir;
    }
}
