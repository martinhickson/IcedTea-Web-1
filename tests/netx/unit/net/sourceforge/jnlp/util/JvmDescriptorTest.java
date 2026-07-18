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

    private static File createTempDir(String prefix) throws IOException {
        File dir = File.createTempFile(prefix, "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdir());
        dir.deleteOnExit();
        return dir;
    }
}
