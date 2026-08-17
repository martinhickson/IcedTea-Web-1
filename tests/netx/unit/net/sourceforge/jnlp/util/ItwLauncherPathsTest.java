package net.sourceforge.jnlp.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.Launcher;
import net.sourceforge.jnlp.runtime.JavawsUberLauncher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ItwLauncherPathsTest {

    private String previousLocation;
    private String previousNativeProp;

    @Before
    public void saveProperties() {
        previousLocation = System.getProperty(Launcher.KEY_JAVAWS_LOCATION);
        previousNativeProp = System.getProperty(ItwLauncherPaths.PROP_NATIVE_LAUNCHER);
    }

    @After
    public void restoreProperties() {
        if (previousLocation == null) {
            System.clearProperty(Launcher.KEY_JAVAWS_LOCATION);
        } else {
            System.setProperty(Launcher.KEY_JAVAWS_LOCATION, previousLocation);
        }
        if (previousNativeProp == null) {
            System.clearProperty(ItwLauncherPaths.PROP_NATIVE_LAUNCHER);
        } else {
            System.setProperty(ItwLauncherPaths.PROP_NATIVE_LAUNCHER, previousNativeProp);
        }
    }

    @Test
    public void resolveJavawsFromItwebSettingsSibling() throws IOException {
        System.setProperty(ItwLauncherPaths.PROP_NATIVE_LAUNCHER, "true");
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
        System.setProperty(ItwLauncherPaths.PROP_NATIVE_LAUNCHER, "true");
        File bin = Files.createTempDirectory("itw-bin-javaws").toFile();
        File javaws = new File(bin, "javaws");
        writeExecutable(javaws, "#!/bin/sh\necho javaws\n");

        System.setProperty(Launcher.KEY_JAVAWS_LOCATION, javaws.getAbsolutePath());
        assertEquals(javaws.getAbsolutePath(), ItwLauncherPaths.resolveJavawsBin());
    }

    @Test
    public void javaCpModeDoesNotRequireSiblingJavawsBinary() throws IOException {
        System.setProperty(ItwLauncherPaths.PROP_NATIVE_LAUNCHER, "false");
        File uberJar = File.createTempFile("icedtea-web-2.0.1-SNAPSHOT-uber", ".jar");
        uberJar.deleteOnExit();
        System.setProperty(Launcher.KEY_JAVAWS_LOCATION, uberJar.getAbsolutePath());
        assertFalse(ItwLauncherPaths.isNativeLauncherProcess());
        assertNull(ItwLauncherPaths.resolveJavawsBin());
        assertTrue(ItwLauncherPaths.canLaunchExternally());
        List<String> command = ItwLauncherPaths.buildExternalLaunchCommand(
                Collections.singletonList("-Djava.net.preferIPv4Stack=true"),
                Collections.singletonList("file:/tmp/app.jnlp"),
                null);
        assertTrue(command.get(0).toLowerCase().contains("java"));
        assertTrue(command.contains("-cp"));
        assertTrue(command.contains(uberJar.getAbsolutePath()));
        assertTrue(command.contains(JavawsUberLauncher.class.getName()));
        assertTrue(command.contains("file:/tmp/app.jnlp"));
        assertTrue(command.contains("-Djava.net.preferIPv4Stack=true"));
        assertTrue(command.contains("-Xms8m"));
        if (JavaVersionUtils.getRunningMajorVersion() >= 9) {
            assertTrue(command.contains("--add-exports"));
            assertTrue(command.contains("java.desktop/sun.awt=ALL-UNNAMED"));
        }
    }

    @Test
    public void nativeModeUsesJPrefixForVmArgs() throws IOException {
        System.setProperty(ItwLauncherPaths.PROP_NATIVE_LAUNCHER, "true");
        File bin = Files.createTempDirectory("itw-bin-native-cmd").toFile();
        File javaws = new File(bin, "javaws");
        writeExecutable(javaws, "#!/bin/sh\necho javaws\n");
        System.setProperty(Launcher.KEY_JAVAWS_LOCATION, javaws.getAbsolutePath());

        List<String> command = ItwLauncherPaths.buildExternalLaunchCommand(
                Arrays.asList("-Xmx256m"),
                Collections.singletonList("app.jnlp"),
                null);
        assertEquals(javaws.getAbsolutePath(), command.get(0));
        assertTrue(command.contains("-J-Xmx256m"));
        assertTrue(command.contains("app.jnlp"));
    }

    @Test
    public void javaCpPolicyEditorIncludesModularExportAndFile() throws IOException {
        System.setProperty(ItwLauncherPaths.PROP_NATIVE_LAUNCHER, "false");
        File uberJar = File.createTempFile("icedtea-web-2.0.1-SNAPSHOT-uber", ".jar");
        uberJar.deleteOnExit();
        System.setProperty(Launcher.KEY_JAVAWS_LOCATION, uberJar.getAbsolutePath());

        List<String> command = ItwLauncherPaths.buildPolicyEditorLaunchCommand("/tmp/java.policy");
        assertTrue(command.get(0).toLowerCase().contains("java"));
        assertTrue(command.contains("-cp"));
        assertTrue(command.contains(uberJar.getAbsolutePath()));
        assertTrue(command.contains("net.sourceforge.jnlp.security.policyeditor.PolicyEditor"));
        assertTrue(command.contains("-file"));
        assertTrue(command.contains("/tmp/java.policy"));
        if (JavaVersionUtils.getRunningMajorVersion() >= 9) {
            assertTrue(command.contains("--add-exports"));
            assertTrue(command.contains("java.base/sun.security.provider=ALL-UNNAMED"));
        }
    }

    @Test
    public void javaCpPolicyEditorPrefersBinNextToUberJar() throws IOException {
        System.setProperty(ItwLauncherPaths.PROP_NATIVE_LAUNCHER, "false");
        File root = Files.createTempDirectory("itw-extract").toFile();
        File lib = new File(root, "lib");
        File bin = new File(root, "bin");
        assertTrue(lib.mkdirs());
        assertTrue(bin.mkdirs());
        File uberJar = new File(lib, "icedtea-web-uber.jar");
        assertTrue(uberJar.createNewFile());
        File policyeditor = new File(bin, "policyeditor");
        writeExecutable(policyeditor, "#!/bin/sh\necho policyeditor\n");
        System.setProperty(Launcher.KEY_JAVAWS_LOCATION, uberJar.getAbsolutePath());

        List<String> command = ItwLauncherPaths.buildPolicyEditorLaunchCommand("/tmp/java.policy");
        assertEquals(policyeditor.getAbsolutePath(), command.get(0));
        assertTrue(command.contains("-file"));
        assertTrue(command.contains("/tmp/java.policy"));
    }

    @Test
    public void ensureSunSecurityProviderAccessWorksWithoutAddExports() throws Exception {
        if (JavaVersionUtils.getRunningMajorVersion() < 9) {
            return;
        }
        String javaName = JNLPRuntime.isWindows() ? "java.exe" : "java";
        File java = new File(new File(System.getProperty("java.home"), "bin"), javaName);
        assertTrue(java.isFile());
        ProcessBuilder pb = new ProcessBuilder(
                java.getAbsolutePath(),
                "-cp",
                System.getProperty("java.class.path"),
                SunSecurityProviderAccessProbe.class.getName());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        byte[] raw = process.getInputStream().readAllBytes();
        String output = new String(raw, StandardCharsets.UTF_8);
        assertTrue("probe timed out: " + output, process.waitFor(30, TimeUnit.SECONDS));
        assertEquals("probe failed: " + output, 0, process.exitValue());
        assertTrue(output.contains("OK"));
    }

    @Test
    public void nativePolicyEditorUsesSiblingBinary() throws IOException {
        System.setProperty(ItwLauncherPaths.PROP_NATIVE_LAUNCHER, "true");
        File bin = Files.createTempDirectory("itw-bin-policyeditor").toFile();
        File settings = new File(bin, "itweb-settings");
        File policyeditor = new File(bin, "policyeditor");
        writeExecutable(settings, "#!/bin/sh\necho settings\n");
        writeExecutable(policyeditor, "#!/bin/sh\necho policyeditor\n");
        System.setProperty(Launcher.KEY_JAVAWS_LOCATION, settings.getAbsolutePath());

        List<String> command = ItwLauncherPaths.buildPolicyEditorLaunchCommand("/tmp/java.policy");
        assertEquals(policyeditor.getAbsolutePath(), command.get(0));
        assertTrue(command.contains("-file"));
        assertTrue(command.contains("/tmp/java.policy"));
    }

    @Test
    public void canAccessSunSecurityProviderMatchesSurefireExports() {
        if (JavaVersionUtils.getRunningMajorVersion() >= 9) {
            assertTrue(ItwLauncherPaths.canAccessSunSecurityProvider());
        }
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
