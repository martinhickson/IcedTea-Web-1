package net.sourceforge.jnlp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class KnownJvmStoreBundledTest {

    @TempDir
    Path tmp;

    @Test
    public void discoversBundledJreHomesHighestMajorFirst() throws Exception {
        // install layout: bin/<launcher exe>, runtime/temurin-<ver>/<jdk>/bin/java
        Path install = tmp.resolve("install");
        Files.createDirectories(install.resolve("bin"));
        Files.createDirectories(install.resolve("runtime/temurin-25/jdk25/bin"));
        Files.createDirectories(install.resolve("runtime/temurin-17/jdk17/bin"));
        Files.createDirectories(install.resolve("runtime/temurin-11/jdk11/bin"));
        Files.createFile(install.resolve("bin/javaws.exe"));
        Files.createFile(install.resolve("runtime/temurin-25/jdk25/bin/java.exe"));
        Files.createFile(install.resolve("runtime/temurin-17/jdk17/bin/java.exe"));
        Files.createFile(install.resolve("runtime/temurin-11/jdk11/bin/java.exe"));

        String old = System.getProperty("icedtea-web.bin.location");
        System.setProperty("icedtea-web.bin.location", install.resolve("bin/javaws.exe").toString());
        try {
            assertEquals(install.toFile(), KnownJvmStore.findInstallRoot());
            List<String> homes = KnownJvmStore.discoverBundledJvmHomes();
            assertEquals(3, homes.size());
            // highest major first: temurin-25, temurin-17, temurin-11
            assertTrue(homes.get(0).replace('\\', '/').contains("temurin-25/jdk25"));
            assertTrue(homes.get(1).replace('\\', '/').contains("temurin-17/jdk17"));
            assertTrue(homes.get(2).replace('\\', '/').contains("temurin-11/jdk11"));
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
}
