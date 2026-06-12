package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

class MultiBytecodeJnlpLaunchIT {

    @Test
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.MultiBytecodeJnlpLaunchIT#java17SampleAvailable")
    void java17AppJarUsesBytecodeMajor61() throws Exception {
        assertJarMajorVersion("java17-app", 17, 61);
    }

    @Test
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.MultiBytecodeJnlpLaunchIT#java21SampleAvailable")
    void java21AppJarUsesBytecodeMajor65() throws Exception {
        assertJarMajorVersion("java21-app", 21, 65);
    }

    @Test
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.MultiBytecodeJnlpLaunchIT#java25SampleAvailable")
    void java25AppJarUsesBytecodeMajor69() throws Exception {
        assertJarMajorVersion("java25-app", 25, 69);
    }

    @Test
    @Timeout(60)
    @EnabledIf("net.sourceforge.icedteaweb.it.JnlpLaunchTestSupport#javawsAvailable")
    void launchJava17AppPrintsIntegrationSuccess() throws Exception {
        Process process = JnlpLaunchTestSupport.launchHeldApp("java17-app", 3);
        String output = JnlpLaunchTestSupport.readProcessOutput(process, 45_000);
        assertThat(output).contains("ITW_INTEGRATION_SUCCESS");
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    private static void assertJarMajorVersion(String sampleName, int jdkMajor, int classMajor)
            throws Exception {
        assertThat(JnlpLaunchTestSupport.sampleBuilt(sampleName)).isTrue();
        File jar = JnlpLaunchTestSupport.jarFile(sampleName);
        assertThat(readClassMajorVersion(jar)).isEqualTo(classMajor);
    }

    private static int readClassMajorVersion(File jarFile) throws Exception {
        String entryName = "net/sourceforge/icedteaweb/it/apps/HeadlessHoldJnlpMain.class";
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(entryName);
            assertThat(entry).isNotNull();
            try (InputStream in = jar.getInputStream(entry)) {
                byte[] header = in.readNBytes(8);
                assertThat(header.length).isEqualTo(8);
                return ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
            }
        }
    }

    static boolean java17SampleAvailable() {
        return JnlpLaunchTestSupport.sampleBuilt("java17-app")
                && JnlpLaunchTestSupport.hasSampleJdk(17);
    }

    static boolean java21SampleAvailable() {
        return JnlpLaunchTestSupport.sampleBuilt("java21-app")
                && JnlpLaunchTestSupport.hasSampleJdk(21);
    }

    static boolean java25SampleAvailable() {
        return JnlpLaunchTestSupport.sampleBuilt("java25-app")
                && JnlpLaunchTestSupport.hasSampleJdk(25);
    }
}
