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
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileFilter;
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
        if (Boot.isJavaVersionProbe(args)) {
            Boot.printJavaMajorVersionAndExit();
        }

        ensureLauncherLocation();
        args = chooseJnlpFileWhenNoArguments(args);
        if (args == null) {
            return;
        }
        Boot.main(args);
    }

    private static String[] chooseJnlpFileWhenNoArguments(String[] args) throws Exception {
        if (args.length > 0 || !isJavawsLauncherName(System.getProperty("icedtea-web.bin.name"))) {
            return args;
        }
        File selected = chooseJnlpFile();
        return selected == null ? null : new String[] { selected.getAbsolutePath() };
    }

    /** GUI ({@code javaws}) and console ({@code javawsc}) launchers share the no-args file chooser. */
    private static boolean isJavawsLauncherName(String binName) {
        return "javaws".equals(binName) || "javawsc".equals(binName);
    }

    private static File chooseJnlpFile() throws Exception {
        final File[] selected = new File[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                    // Fall back to Swing's default look and feel if the platform one is unavailable.
                }
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Choose JNLP Application to Launch");
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                chooser.setAcceptAllFileFilterUsed(true);
                chooser.setFileFilter(new FileFilter() {
                    @Override
                    public boolean accept(File file) {
                        return file.isDirectory() || file.getName().toLowerCase().endsWith(".jnlp");
                    }

                    @Override
                    public String getDescription() {
                        return "JNLP applications (*.jnlp)";
                    }
                });
                int result = chooser.showOpenDialog(null);
                if (result == JFileChooser.APPROVE_OPTION) {
                    selected[0] = chooser.getSelectedFile();
                }
            }
        });
        return selected[0];
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
