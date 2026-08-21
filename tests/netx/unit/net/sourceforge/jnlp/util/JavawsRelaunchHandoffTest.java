package net.sourceforge.jnlp.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import net.sourceforge.jnlp.Launcher;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class JavawsRelaunchHandoffTest extends NoStdOutErrTest {

    private String previousBinName;
    private String previousLocation;

    @Before
    public void saveWrapperIdentity() {
        previousBinName = System.getProperty(ItwLauncherPaths.KEY_BIN_NAME);
        previousLocation = System.getProperty(Launcher.KEY_JAVAWS_LOCATION);
        System.clearProperty(ItwLauncherPaths.KEY_BIN_NAME);
        System.clearProperty(Launcher.KEY_JAVAWS_LOCATION);
    }

    @After
    public void restoreWrapperIdentity() {
        if (previousBinName == null) {
            System.clearProperty(ItwLauncherPaths.KEY_BIN_NAME);
        } else {
            System.setProperty(ItwLauncherPaths.KEY_BIN_NAME, previousBinName);
        }
        if (previousLocation == null) {
            System.clearProperty(Launcher.KEY_JAVAWS_LOCATION);
        } else {
            System.setProperty(Launcher.KEY_JAVAWS_LOCATION, previousLocation);
        }
    }

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
    public void shouldNotHandoffWhenKeepJavawsProcessEnabled() throws Exception {
        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load();
        config.setProperty(DeploymentConfiguration.KEY_KEEP_JAVAWS_RELAUNCH_PROCESS, "false");
        config.setProperty(DeploymentConfiguration.KEY_KEEP_JAVAWS_PROCESS, "true");
        assertFalse(JavawsRelaunchHandoff.shouldHandoff(config));
    }

    @Test
    public void shouldNotHandoffWhenParentIsJavawsc() throws Exception {
        System.setProperty(ItwLauncherPaths.KEY_BIN_NAME, "javawsc");
        assertFalse(JavawsRelaunchHandoff.shouldHandoff(null));
        assertFalse(JavawsRelaunchHandoff.shouldHandoff(configWith("false")));
    }

    @Test
    public void handoffRecordDocumentsParentChildAndSafeStdio() throws Exception {
        File dir = Files.createTempDirectory("itw-relaunch-handoff").toFile();
        File logFile = new File(dir, "handoff.log");
        List<String> command = Arrays.asList("javaws", "-Xnofork", "C:\\app with spaces\\app.jnlp");

        JavawsRelaunchHandoff.writeHandoffRecord(logFile, "<pending>", "javaws", command);
        String text = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);

        assertTrue(text.contains("IcedTea-Web JDK relaunch handoff record"));
        assertTrue(text.contains("Handoff step: " + JavawsRelaunchHandoff.HANDOFF_STEP));
        assertTrue(text.contains("Handoff status: SUCCESS"));
        assertTrue(text.contains("Parent process ID (handing off):"));
        assertTrue(text.contains("Parent is exiting after handoff: true"));
        assertTrue(text.contains("Child process ID (handed to): <pending>"));
        assertTrue(text.contains("Child executable: javaws"));
        assertTrue(text.contains("no pipe buffer stall risk"));
        assertTrue(text.contains("Standard Output and Standard Error written to:"));
        assertTrue(text.contains("NUL") || text.contains("/dev/null"));
        assertTrue(text.contains(logFile.getAbsolutePath()));

        JavawsRelaunchHandoff.patchChildPid(logFile, 4242L);
        JavawsRelaunchHandoff.appendHandoffComplete(logFile, 4242L);
        String patched = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(patched.contains("Child process ID (handed to): 4242"));
        assertFalse(patched.contains("<pending>"));
        assertTrue(patched.contains("Handoff complete: parent launcher exiting without waiting for child process 4242"));
    }

    @Test
    public void detachedStdioUsesNullDeviceAndCombinedLogFileNotInherit() throws Exception {
        File dir = Files.createTempDirectory("itw-relaunch-stdio").toFile();
        File logFile = new File(dir, "relaunch.log");
        assertTrue(logFile.createNewFile());
        ProcessBuilder pb = new ProcessBuilder("java", "-version");
        JavawsRelaunchHandoff.applyDetachedStdio(pb, logFile);

        assertTrue(pb.redirectErrorStream());
        assertEquals(ProcessBuilder.Redirect.Type.READ, pb.redirectInput().type());
        assertEquals(ProcessBuilder.Redirect.Type.APPEND, pb.redirectOutput().type());
        assertEquals(JavawsRelaunchHandoff.nullDevice().getPath(), pb.redirectInput().file().getPath());
        assertEquals(logFile.getAbsolutePath(), pb.redirectOutput().file().getAbsolutePath());
    }

    private static DeploymentConfiguration configWith(String keepValue) throws Exception {
        DeploymentConfiguration config = new DeploymentConfiguration();
        config.load();
        config.setProperty(DeploymentConfiguration.KEY_KEEP_JAVAWS_PROCESS, "false");
        if (keepValue == null) {
            config.setProperty(DeploymentConfiguration.KEY_KEEP_JAVAWS_RELAUNCH_PROCESS, "false");
        } else {
            config.setProperty(DeploymentConfiguration.KEY_KEEP_JAVAWS_RELAUNCH_PROCESS, keepValue);
        }
        return config;
    }
}
