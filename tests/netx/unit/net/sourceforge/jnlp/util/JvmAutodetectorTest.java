package net.sourceforge.jnlp.util;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmAutodetectorTest {

    @Test
    void parseRegQueryOutputExtractsJavaHomes() {
        String sample = ""
                + "HKEY_LOCAL_MACHINE\\SOFTWARE\\JavaSoft\\JDK\\17.0.16\r\n"
                + "    JavaHome    REG_SZ    C:\\Program Files\\Amazon Corretto\\jdk17.0.16_8\r\n"
                + "\r\n"
                + "HKEY_LOCAL_MACHINE\\SOFTWARE\\JavaSoft\\JDK\\21.0.8\r\n"
                + "    JavaHome    REG_SZ    C:\\Program Files\\Amazon Corretto\\jdk21.0.8_9\r\n";

        List<String> homes = WindowsJvmRegistry.parseRegQueryOutput(sample);

        assertEquals(2, homes.size());
        assertTrue(homes.contains("C:\\Program Files\\Amazon Corretto\\jdk17.0.16_8"));
        assertTrue(homes.contains("C:\\Program Files\\Amazon Corretto\\jdk21.0.8_9"));
    }

    @Test
    void parseMajorVersionFromJavaVersionOutputSupportsModernAndLegacyFormats() {
        assertEquals(17, JvmProbeSupport.parseMajorVersionFromJavaVersionOutput(
                "openjdk version \"17.0.16\" 2025-07-15 LTS"));
        assertEquals(8, JvmProbeSupport.parseMajorVersionFromJavaVersionOutput(
                "java version \"1.8.0_402\""));
        assertEquals(11, JvmProbeSupport.parseMajorVersionFromJavaVersionOutput(
                "openjdk version \"11.0.25\" 2024-10-15 LTS"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void discoverCandidateHomesIncludesRegistryJavaHomesOnWindows() {
        List<String> candidates = JvmAutodetector.discoverCandidateHomes();
        assertFalse(candidates.isEmpty(), "expected at least one JDK candidate on this Windows host");

        boolean hasRegistryBackedHome = candidates.stream().anyMatch(
                home -> home.toLowerCase().contains("corretto") || home.toLowerCase().contains("jdk"));
        assertTrue(hasRegistryBackedHome,
                "expected Program Files or registry-backed JDK paths among: " + candidates);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void discoverValidJvmHomesFindsInstalledJdk17OnWindows() {
        String jdk17Home = System.getenv("ITW_JDK17_HOME");
        if (jdk17Home == null || jdk17Home.isBlank()) {
            jdk17Home = System.getenv("JDK17_HOME");
        }
        if (jdk17Home == null || jdk17Home.isBlank()) {
            jdk17Home = "C:\\Program Files\\Amazon Corretto\\jdk17.0.16_8";
        }

        File javaBinary = new File(jdk17Home, "bin\\java.exe");
        if (!javaBinary.isFile()) {
            List<String> candidates = JvmAutodetector.discoverCandidateHomes();
            boolean anyJdk17Candidate = false;
            for (String candidate : candidates) {
                if (JvmAutodetector.majorVersionOfJvmHome(candidate) == 17) {
                    anyJdk17Candidate = true;
                    break;
                }
            }
            assertTrue(anyJdk17Candidate,
                    "no JDK 17 discovered among candidates: " + candidates);
            return;
        }

        int major = JvmProbeSupport.probeMajorVersionWithJavaVersion(javaBinary);
        assertEquals(17, major, "java -version fallback probe failed for " + jdk17Home);

        List<String> validHomes = JvmAutodetector.discoverValidJvmHomes();
        boolean foundJdk17 = false;
        for (String home : validHomes) {
            if (JvmAutodetector.majorVersionOfJvmHome(home) == 17) {
                foundJdk17 = true;
                break;
            }
        }
        assertTrue(foundJdk17,
                "discoverValidJvmHomes() did not include JDK 17; found: " + validHomes);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void probeMajorVersionUsesJavaVersionFallbackWithoutUberJar() {
        String jdk17Home = "C:\\Program Files\\Amazon Corretto\\jdk17.0.16_8";
        File javaBinary = new File(jdk17Home, "bin\\java.exe");
        if (!javaBinary.isFile()) {
            return;
        }

        int major = JvmProbeSupport.probeMajorVersionWithJavaVersion(javaBinary);
        assertEquals(17, major);
    }
}
