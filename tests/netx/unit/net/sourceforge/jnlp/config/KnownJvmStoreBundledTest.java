package net.sourceforge.jnlp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class KnownJvmStoreBundledTest {

    @TempDir
    Path tmp;

    @Test
    public void discoversBundledJreHomesPreferred21First() throws Exception {
        // install layout: bin/<launcher exe>, runtime/temurin-<ver>/<jdk>/bin/java
        Path install = tmp.resolve("install");
        Files.createDirectories(install.resolve("bin"));
        Files.createDirectories(install.resolve("runtime/temurin-25/jdk25/bin"));
        Files.createDirectories(install.resolve("runtime/temurin-21/jdk21/bin"));
        Files.createDirectories(install.resolve("runtime/temurin-17/jdk17/bin"));
        Files.createDirectories(install.resolve("runtime/temurin-11/jdk11/bin"));
        Files.createFile(install.resolve("bin/javaws.exe"));
        Files.createFile(install.resolve("runtime/temurin-25/jdk25/bin/java.exe"));
        Files.createFile(install.resolve("runtime/temurin-21/jdk21/bin/java.exe"));
        Files.createFile(install.resolve("runtime/temurin-17/jdk17/bin/java.exe"));
        Files.createFile(install.resolve("runtime/temurin-11/jdk11/bin/java.exe"));

        String old = System.getProperty("icedtea-web.bin.location");
        System.setProperty("icedtea-web.bin.location", install.resolve("bin/javaws.exe").toString());
        try {
            assertEquals(install.toFile(), KnownJvmStore.findInstallRoot());
            List<String> homes = KnownJvmStore.discoverBundledJvmHomes();
            assertEquals(4, homes.size());
            // preferred order 21, 17, 11, 25 (25 ranked last: JDK 25 lacks jdk.internal.util.jar)
            assertTrue(homes.get(0).replace('\\', '/').contains("temurin-21/jdk21"));
            assertTrue(homes.get(1).replace('\\', '/').contains("temurin-17/jdk17"));
            assertTrue(homes.get(2).replace('\\', '/').contains("temurin-11/jdk11"));
            assertTrue(homes.get(3).replace('\\', '/').contains("temurin-25/jdk25"));
        } finally {
            if (old == null) {
                System.clearProperty("icedtea-web.bin.location");
            } else {
                System.setProperty("icedtea-web.bin.location", old);
            }
        }
    }

    @Test
    public void discoversMacOsContentsHomeLayout() throws Exception {
        Path install = tmp.resolve("mac-install");
        Files.createDirectories(install.resolve("runtime/temurin-21/jdk21/Contents/Home/bin"));
        Files.createFile(install.resolve("runtime/temurin-21/jdk21/Contents/Home/bin/java"));

        String old = System.getProperty("icedtea-web.bin.location");
        System.setProperty("icedtea-web.bin.location", install.resolve("bin/javaws").toString());
        try {
            List<String> homes = KnownJvmStore.discoverBundledJvmHomes();
            assertEquals(1, homes.size());
            assertTrue(homes.get(0).replace('\\', '/').endsWith("temurin-21/jdk21/Contents/Home"),
                    "macOS JRE home must resolve to Contents/Home: " + homes.get(0));
        } finally {
            if (old == null) {
                System.clearProperty("icedtea-web.bin.location");
            } else {
                System.setProperty("icedtea-web.bin.location", old);
            }
        }
    }

    @Test
    public void noOpWithoutBundle() throws Exception {
        Path install = tmp.resolve("no-bundle");
        Files.createDirectories(install.resolve("bin"));
        Files.createFile(install.resolve("bin/javaws"));

        String old = System.getProperty("icedtea-web.bin.location");
        System.setProperty("icedtea-web.bin.location", install.resolve("bin/javaws").toString());
        try {
            assertTrue(KnownJvmStore.discoverBundledJvmHomes().isEmpty());
        } finally {
            if (old == null) {
                System.clearProperty("icedtea-web.bin.location");
            } else {
                System.setProperty("icedtea-web.bin.location", old);
            }
        }
    }

    @Test
    public void applyBundledJvmsAppendsMissingHomesAndKeepsJdk1First() throws Exception {
        Path install = tmp.resolve("seed-order");
        Path preferred = install.resolve("preferred-21");
        Files.createDirectories(preferred.resolve("bin"));
        Files.createFile(preferred.resolve("bin/java"));
        Files.createDirectories(install.resolve("bin"));
        Files.createDirectories(install.resolve("runtime/temurin-21/jdk21/bin"));
        Files.createDirectories(install.resolve("runtime/temurin-17/jdk17/bin"));
        Files.createFile(install.resolve("bin/javaws"));
        Files.createFile(install.resolve("runtime/temurin-21/jdk21/bin/java"));
        Files.createFile(install.resolve("runtime/temurin-17/jdk17/bin/java"));

        String preferredHome = preferred.toFile().getAbsolutePath();
        File userFile = tmp.resolve("deployment.properties").toFile();
        Files.write(userFile.toPath(), (
                "deployment.jdk.1=" + escapeProp(preferredHome) + "\n"
                + "deployment.jre.dir=" + escapeProp(preferredHome) + "\n"
                ).getBytes(StandardCharsets.UTF_8));

        String old = System.getProperty("icedtea-web.bin.location");
        System.setProperty("icedtea-web.bin.location", install.resolve("bin/javaws").toString());
        try {
            DeploymentConfiguration config = new DeploymentConfiguration();
            config.load((java.net.URL) null, userFile, false);

            List<String> homes = KnownJvmStore.getKnownJvmHomes(config);
            assertEquals(preferredHome, homes.get(0));
            assertTrue(homes.size() >= 3, "missing bundled homes should be appended: " + homes);
            assertTrue(homes.get(1).replace('\\', '/').contains("temurin-21/jdk21"));
            assertTrue(homes.get(2).replace('\\', '/').contains("temurin-17/jdk17"));

            Properties savedAfterFirstLoad = loadSaved(userFile);
            assertEquals(preferredHome, savedAfterFirstLoad.getProperty("deployment.jdk.1"));
            assertEquals(preferredHome, savedAfterFirstLoad.getProperty("deployment.jre.dir"));
            assertTrue(savedAfterFirstLoad.getProperty("deployment.jdk.2")
                    .replace('\\', '/').contains("temurin-21/jdk21"));
            assertTrue(savedAfterFirstLoad.getProperty("deployment.jdk.3")
                    .replace('\\', '/').contains("temurin-17/jdk17"));

            assertFalse(KnownJvmStore.applyBundledJvms(config), "second apply must not reorder");
            assertEquals(homes, KnownJvmStore.getKnownJvmHomes(config));

            DeploymentConfiguration reload = new DeploymentConfiguration();
            reload.load((java.net.URL) null, userFile, false);
            assertEquals(homes, KnownJvmStore.getKnownJvmHomes(reload));

            Properties savedAfterReload = loadSaved(userFile);
            assertEquals(preferredHome, savedAfterReload.getProperty("deployment.jdk.1"));
            assertEquals(savedAfterFirstLoad.getProperty("deployment.jdk.2"),
                    savedAfterReload.getProperty("deployment.jdk.2"));
            assertEquals(savedAfterFirstLoad.getProperty("deployment.jdk.3"),
                    savedAfterReload.getProperty("deployment.jdk.3"));
        } finally {
            if (old == null) {
                System.clearProperty("icedtea-web.bin.location");
            } else {
                System.setProperty("icedtea-web.bin.location", old);
            }
        }
    }

    @Test
    public void applyBundledJvmsDoesNotPrependWhenBundledAlreadyListed() throws Exception {
        Path install = tmp.resolve("already-listed");
        Path preferred = install.resolve("openjdk-21");
        Files.createDirectories(preferred.resolve("bin"));
        Files.createFile(preferred.resolve("bin/java"));
        Files.createDirectories(install.resolve("bin"));
        Files.createDirectories(install.resolve("runtime/temurin-21/jdk21/bin"));
        Files.createFile(install.resolve("bin/javaws"));
        Files.createFile(install.resolve("runtime/temurin-21/jdk21/bin/java"));

        String preferredHome = preferred.toFile().getAbsolutePath();
        String bundledHome = install.resolve("runtime/temurin-21/jdk21").toFile().getAbsolutePath();
        File userFile = tmp.resolve("already.properties").toFile();
        Files.write(userFile.toPath(), (
                "deployment.jdk.1=" + escapeProp(preferredHome) + "\n"
                + "deployment.jdk.2=" + escapeProp(bundledHome) + "\n"
                + "deployment.jre.dir=" + escapeProp(preferredHome) + "\n"
                ).getBytes(StandardCharsets.UTF_8));

        String old = System.getProperty("icedtea-web.bin.location");
        System.setProperty("icedtea-web.bin.location", install.resolve("bin/javaws").toString());
        try {
            DeploymentConfiguration config = new DeploymentConfiguration();
            config.load((java.net.URL) null, userFile, false);

            assertEquals(Arrays.asList(preferredHome, bundledHome),
                    KnownJvmStore.getKnownJvmHomes(config));
            assertFalse(KnownJvmStore.applyBundledJvms(config));
            assertEquals(preferredHome, config.getProperty("deployment.jdk.1"));
            assertEquals(bundledHome, config.getProperty("deployment.jdk.2"));

            Properties saved = loadSaved(userFile);
            assertEquals(preferredHome, saved.getProperty("deployment.jdk.1"));
            assertEquals(bundledHome, saved.getProperty("deployment.jdk.2"));
            assertEquals(preferredHome, saved.getProperty("deployment.jre.dir"));

            DeploymentConfiguration reload = new DeploymentConfiguration();
            reload.load((java.net.URL) null, userFile, false);
            Properties savedAfterReload = loadSaved(userFile);
            assertEquals(preferredHome, savedAfterReload.getProperty("deployment.jdk.1"));
            assertEquals(bundledHome, savedAfterReload.getProperty("deployment.jdk.2"));
            assertEquals(Arrays.asList(preferredHome, bundledHome),
                    KnownJvmStore.getKnownJvmHomes(reload));
        } finally {
            if (old == null) {
                System.clearProperty("icedtea-web.bin.location");
            } else {
                System.setProperty("icedtea-web.bin.location", old);
            }
        }
    }

    private static Properties loadSaved(File file) throws Exception {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        }
        return props;
    }

    private static String escapeProp(String value) {
        return value.replace("\\", "\\\\");
    }
}
