package net.sourceforge.jnlp.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import net.sourceforge.jnlp.Launcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ItwLauncherPathsTest {

    private String previousLocation;
    private String previousEnv;

    @Before
    public void saveProperties() {
        previousLocation = System.getProperty(Launcher.KEY_JAVAWS_LOCATION);
        previousEnv = System.getenv(ItwLauncherPaths.ENV_JAVAWS_BIN);
    }

    @After
    public void restoreProperties() {
        if (previousLocation == null) {
            System.clearProperty(Launcher.KEY_JAVAWS_LOCATION);
        } else {
            System.setProperty(Launcher.KEY_JAVAWS_LOCATION, previousLocation);
        }
    }

    @Test
    public void resolveJavawsFromItwebSettingsSibling() throws IOException {
        File bin = Files.createTempDirectory("itw-bin").toFile();
        File settings = new File(bin, "itweb-settings");
        File javaws = new File(bin, "javaws");
        writeExecutable(settings, "#!/bin/sh\necho settings\n");
        writeExecutable(javaws, "#!/bin/sh\necho javaws\n");

        System.setProperty(Launcher.KEY_JAVAWS_LOCATION, settings.getAbsolutePath());
        File resolved = ItwLauncherPaths.resolveJavawsFromLauncherLocation(settings.getAbsolutePath());
        assertNotNull(resolved);
        assertEquals(javaws.getAbsolutePath(), resolved.getAbsolutePath());
        assertEquals(javaws.getAbsolutePath(), ItwLauncherPaths.resolveJavawsBin());
    }

    @Test
    public void resolveJavawsUsesConfiguredJavawsDirectly() throws IOException {
        File bin = Files.createTempDirectory("itw-bin-javaws").toFile();
        File javaws = new File(bin, "javaws");
        writeExecutable(javaws, "#!/bin/sh\necho javaws\n");

        System.setProperty(Launcher.KEY_JAVAWS_LOCATION, javaws.getAbsolutePath());
        assertEquals(javaws.getAbsolutePath(), ItwLauncherPaths.resolveJavawsBin());
    }

    @Test
    public void isJavawsLauncherNameRecognizesJavawsAndJavawsc() {
        assertTrue(ItwLauncherPaths.isJavawsLauncherName("javaws"));
        assertTrue(ItwLauncherPaths.isJavawsLauncherName("javaws.exe"));
        assertTrue(ItwLauncherPaths.isJavawsLauncherName("javawsc"));
    }

    private static void writeExecutable(File file, String contents) throws IOException {
        Files.write(file.toPath(), contents.getBytes("UTF-8"));
        assertTrue(file.setExecutable(true));
    }
}
