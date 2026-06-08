// Copyright (C) 2026 IcedTea-Web contributors
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
package net.sourceforge.jnlp.runtime;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.sourceforge.jnlp.Launcher;

/**
 * Entry point for the Maven shaded uber JAR. Delegates to {@link Boot} after
 * ensuring {@link Launcher#KEY_JAVAWS_LOCATION} is set for external relaunch.
 * <p>
 * When started via {@code java -jar} without the companion {@code javaws}
 * script, this class sets {@code icedtea-web.bin.location} to the uber JAR path
 * and re-invokes through the generated {@code javaws} wrapper when present so
 * {@code -J} VM arguments work on relaunch (heap settings, etc.).
 */
public final class JavawsUberLauncher {

    private JavawsUberLauncher() {
    }

    public static void main(String[] args) throws Exception {
        ensureLauncherLocation();
        Boot.main(args);
    }

    private static void ensureLauncherLocation() {
        String existing = System.getProperty(Launcher.KEY_JAVAWS_LOCATION);
        if (existing != null && !existing.trim().isEmpty()) {
            return;
        }

        File wrapper = findCompanionJavawsScript();
        if (wrapper != null && wrapper.canExecute()) {
            System.setProperty(Launcher.KEY_JAVAWS_LOCATION, wrapper.getAbsolutePath());
            if (System.getProperty("icedtea-web.bin.name") == null) {
                System.setProperty("icedtea-web.bin.name", "javaws");
            }
            return;
        }

        // Fallback: direct java -jar relaunch cannot pass -J flags; still set location for diagnostics.
        String jarPath = JavawsUberLauncher.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        System.setProperty(Launcher.KEY_JAVAWS_LOCATION, jarPath);
        if (System.getProperty("icedtea-web.bin.name") == null) {
            System.setProperty("icedtea-web.bin.name", "javaws");
        }
    }

    /**
     * Locate target/bin/javaws next to the uber JAR (Maven package layout).
     */
    private static File findCompanionJavawsScript() {
        String jarPath = JavawsUberLauncher.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        File jarFile = new File(jarPath);
        File targetDir = jarFile.getParentFile();
        if (targetDir == null) {
            return null;
        }
        List<File> candidates = new ArrayList<>();
        candidates.add(new File(targetDir, "bin/javaws"));
        candidates.add(new File(targetDir, "bin/javaws.exe"));
        candidates.add(new File(targetDir.getParentFile(), "bin/javaws"));
        candidates.add(new File(targetDir.getParentFile(), "bin/javaws.exe"));
        String itwBin = System.getenv("ITW_JAVAWS_BIN");
        if (itwBin != null) {
            candidates.add(new File(itwBin));
        }
        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }
}
