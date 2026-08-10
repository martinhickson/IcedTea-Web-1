package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import net.sourceforge.jnlp.util.JnlpAssignmentLauncher;
import net.sourceforge.jnlp.util.JnlpAssignmentLauncher.LaunchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class JnlpAssignmentLauncherOutputIT {

    @Test
    @EnabledIf("net.sourceforge.icedteaweb.it.JnlpAssignmentLauncherOutputIT#javawsAvailable")
    void launchWithCapturedOutputReturnsCommandAndOutputText() throws Exception {
        LaunchResult result = JnlpAssignmentLauncher.launchWithCapturedOutput(
                "file:///does-not-exist.jnlp", null, 3000L);

        assertThat(result.getCommandLine()).contains("does-not-exist.jnlp");
        assertThat(result.getCommandLine()).isNotBlank();
    }

    @Test
    void formatLaunchMetricsIncludesItwVersionAndJnlpUrl() {
        String metrics = JnlpAssignmentLauncher.formatLaunchMetrics("file:///sample.jnlp", "/usr/lib/jvm/java-17");

        assertThat(metrics).contains("file:///sample.jnlp");
        assertThat(metrics).contains("/usr/lib/jvm/java-17");
        assertThat(metrics).containsPattern("(?i)icedtea-web version:");
    }

    @Test
    void buildLaunchCommandAddsSecurityManagerAllowForJdk21Home() {
        assumeTrue(JnlpLaunchTestSupport.javawsAvailable());
        List<String> command = JnlpAssignmentLauncher.buildLaunchCommand(
                "/tmp/javaws", "file:///sample.jnlp", "/usr/lib/jvm/java-21-openjdk-amd64");

        assertThat(command).contains("-J-Djava.security.manager=allow");
    }

    @Test
    void resolveJavawsBinFindsSiblingWhenBinLocationIsItwebSettings() throws Exception {
        java.io.File bin = java.nio.file.Files.createTempDirectory("itw-it-bin").toFile();
        java.io.File settings = new java.io.File(bin, "icedtea-web-settings");
        java.io.File javaws = new java.io.File(bin, "javaws");
        java.nio.file.Files.write(settings.toPath(), "#!/bin/sh\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(javaws.toPath(), "#!/bin/sh\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        settings.setExecutable(true);
        javaws.setExecutable(true);

        String previous = System.getProperty(net.sourceforge.jnlp.Launcher.KEY_JAVAWS_LOCATION);
        try {
            System.setProperty(net.sourceforge.jnlp.Launcher.KEY_JAVAWS_LOCATION, settings.getAbsolutePath());
            assertThat(JnlpAssignmentLauncher.resolveJavawsBin()).isEqualTo(javaws.getAbsolutePath());
        } finally {
            if (previous == null) {
                System.clearProperty(net.sourceforge.jnlp.Launcher.KEY_JAVAWS_LOCATION);
            } else {
                System.setProperty(net.sourceforge.jnlp.Launcher.KEY_JAVAWS_LOCATION, previous);
            }
        }
    }

    static boolean javawsAvailable() {
        return JnlpLaunchTestSupport.javawsAvailable();
    }
}
