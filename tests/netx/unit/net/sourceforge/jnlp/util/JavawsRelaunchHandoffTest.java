package net.sourceforge.jnlp.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.Test;

public class JavawsRelaunchHandoffTest extends NoStdOutErrTest {

    @Test
    public void shouldHandoffByDefault() throws Exception {
        assertTrue(JavawsRelaunchHandoff.shouldHandoff(null));
        assertTrue(JavawsRelaunchHandoff.shouldHandoff(configWith(null)));
        assertTrue(JavawsRelaunchHandoff.shouldHandoff(configWith("false")));
    }

    @Test
    public void shouldNotHandoffWhenKeepRelaunchProcessEnabled() throws Exception {
        assertFalse(JavawsRelaunchHandoff.shouldHandoff(configWith("true")));
        assertFalse(JavawsRelaunchHandoff.shouldHandoff(configWith("YES")));
    }

    @Test
    public void handoffRecordDocumentsParentChildAndSafeStdio() throws Exception {
        File dir = Files.createTempDirectory("itw-relaunch-handoff").toFile();
        File stdout = new File(dir, "handoff.log");
        File stderr = new File(dir, "handoff.err.log");
        JavawsRelaunchHandoff.LogPaths logs = new JavawsRelaunchHandoff.LogPaths(stdout, stderr);
        List<String> command = Arrays.asList("javaws", "-Xnofork", "C:\\app with spaces\\app.jnlp");

        JavawsRelaunchHandoff.writeHandoffRecord(logs, "<pending>", "javaws", command);
        String text = new String(Files.readAllBytes(stdout.toPath()), StandardCharsets.UTF_8);

        assertTrue(text.contains("IcedTea-Web JDK relaunch handoff record"));
        assertTrue(text.contains("Handoff step: " + JavawsRelaunchHandoff.HANDOFF_STEP));
        assertTrue(text.contains("Handoff status: SUCCESS"));
        assertTrue(text.contains("Parent process ID (handing off):"));
        assertTrue(text.contains("Parent is exiting after handoff: true"));
        assertTrue(text.contains("Child process ID (handed to): <pending>"));
        assertTrue(text.contains("Child executable: javaws"));
        assertTrue(text.contains("no pipe buffer stall risk"));
        assertTrue(text.contains("NUL") || text.contains("/dev/null"));
        assertTrue(text.contains(stdout.getAbsolutePath()));
        assertTrue(text.contains(stderr.getAbsolutePath()));

        JavawsRelaunchHandoff.patchChildPid(stdout, 4242L);
        JavawsRelaunchHandoff.appendHandoffComplete(stdout, 4242L);
        String patched = new String(Files.readAllBytes(stdout.toPath()), StandardCharsets.UTF_8);
        assertTrue(patched.contains("Child process ID (handed to): 4242"));
        assertFalse(patched.contains("<pending>"));
        assertTrue(patched.contains("Handoff complete: parent launcher exiting without waiting for child process 4242"));
    }

    @Test
    public void detachedStdioUsesNullDeviceAndLogFilesNotInherit() throws Exception {
        File dir = Files.createTempDirectory("itw-relaunch-stdio").toFile();
        File stdout = new File(dir, "out.log");
        File stderr = new File(dir, "err.log");
        assertTrue(stdout.createNewFile());
        assertTrue(stderr.createNewFile());
        ProcessBuilder pb = new ProcessBuilder("java", "-version");
        JavawsRelaunchHandoff.applyDetachedStdio(pb, new JavawsRelaunchHandoff.LogPaths(stdout, stderr));

        assertEquals(ProcessBuilder.Redirect.Type.READ, pb.redirectInput().type());
        assertEquals(ProcessBuilder.Redirect.Type.APPEND, pb.redirectOutput().type());
        assertEquals(ProcessBuilder.Redirect.Type.APPEND, pb.redirectError().type());
        assertEquals(JavawsRelaunchHandoff.nullDevice().getPath(), pb.redirectInput().file().getPath());
        assertEquals(stdout.getAbsolutePath(), pb.redirectOutput().file().getAbsolutePath());
        assertEquals(stderr.getAbsolutePath(), pb.redirectError().file().getAbsolutePath());
    }

    private static DeploymentConfiguration configWith(String keepValue) throws Exception {
        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load();
        if (keepValue == null) {
            config.setProperty(DeploymentConfiguration.KEY_KEEP_JAVAWS_RELAUNCH_PROCESS, "false");
        } else {
            config.setProperty(DeploymentConfiguration.KEY_KEEP_JAVAWS_RELAUNCH_PROCESS, keepValue);
        }
        return config;
    }
}
