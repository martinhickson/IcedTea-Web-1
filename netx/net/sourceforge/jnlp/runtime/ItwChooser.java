package net.sourceforge.jnlp.runtime;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

public final class ItwChooser {
    private static final String[] UNIX_CANDIDATES = new String[] { "javaws", "javaws.sh" };
    private static final String[] WINDOWS_CANDIDATES = new String[] { "javaws.exe", "javaws.bat", "javaws.cmd" };

    private ItwChooser() {
    }

    public static void main(String[] args) {
        String[] launchArgs = resolveLaunchArgs(args);
        if (launchArgs == null) {
            return;
        }

        Path javaws = locateJavaws();
        if (javaws == null) {
            reportError("Could not locate javaws. Set ITW_HOME or ensure javaws is on PATH.");
            return;
        }

        List<String> command = buildCommand(javaws, launchArgs);
        int exitCode = runProcess(command);
        if (exitCode != 0) {
            reportError("javaws exited with code: " + exitCode);
        }
        System.exit(exitCode);
    }

    private static String[] resolveLaunchArgs(String[] args) {
        if (args.length == 0) {
            if (GraphicsEnvironment.isHeadless()) {
                reportError("No JNLP file provided and no GUI available.");
                return null;
            }
            File chosen = chooseJnlpFile();
            if (chosen == null) {
                return null;
            }
            return new String[] { chosen.getAbsolutePath() };
        }
        if (args.length == 1) {
            return new String[] { args[0] };
        }
        return args;
    }

    private static File chooseJnlpFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select JNLP file");
        chooser.setFileFilter(new FileNameExtensionFilter("JNLP files (*.jnlp)", "jnlp"));
        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            return chooser.getSelectedFile();
        }
        return null;
    }

    private static Path locateJavaws() {
        Path jarDir = resolveJarDirectory();
        Path itwHome = resolveItwHome(jarDir);

        List<Path> searchBases = new ArrayList<>();
        if (itwHome != null) {
            searchBases.add(itwHome.resolve("bin"));
        }
        if (jarDir != null) {
            searchBases.add(jarDir.resolve("bin"));
            searchBases.add(jarDir);
        }

        for (Path base : searchBases) {
            Path candidate = findJavawsIn(base);
            if (candidate != null) {
                return candidate;
            }
        }

        return findJavawsOnPath();
    }

    private static Path resolveJarDirectory() {
        try {
            URL location = ItwChooser.class.getProtectionDomain().getCodeSource().getLocation();
            URI uri = location.toURI();
            Path path = Paths.get(uri);
            if (Files.isDirectory(path)) {
                return path;
            }
            return path.getParent();
        } catch (Exception ex) {
            return Paths.get(".").toAbsolutePath().normalize();
        }
    }

    private static Path resolveItwHome(Path jarDir) {
        String envHome = System.getenv("ITW_HOME");
        if (envHome != null && !envHome.trim().isEmpty()) {
            Path envPath = Paths.get(envHome);
            if (Files.isDirectory(envPath)) {
                return envPath;
            }
        }

        if (jarDir == null) {
            return null;
        }

        Path name = jarDir.getFileName();
        if (name != null && "icedtea-web".equals(name.toString())) {
            Path shareDir = jarDir.getParent();
            if (shareDir != null && "share".equals(shareDir.getFileName().toString())) {
                return shareDir.getParent();
            }
        }

        if (name != null && "bin".equals(name.toString())) {
            return jarDir.getParent();
        }

        return jarDir;
    }

    private static Path findJavawsIn(Path baseDir) {
        if (baseDir == null || !Files.isDirectory(baseDir)) {
            return null;
        }

        for (String candidate : getCandidates()) {
            Path path = baseDir.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private static Path findJavawsOnPath() {
        String envPath = System.getenv("PATH");
        if (envPath == null || envPath.trim().isEmpty()) {
            return null;
        }

        String[] segments = envPath.split(File.pathSeparator);
        for (String segment : segments) {
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            Path candidate = findJavawsIn(Paths.get(segment));
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static String[] getCandidates() {
        if (isWindows()) {
            return WINDOWS_CANDIDATES;
        }
        return UNIX_CANDIDATES;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private static List<String> buildCommand(Path javaws, String[] args) {
        List<String> command = new ArrayList<>();
        String fileName = javaws.getFileName().toString().toLowerCase(Locale.ROOT);
        if (isWindows() && (fileName.endsWith(".bat") || fileName.endsWith(".cmd"))) {
            command.add("cmd.exe");
            command.add("/c");
            command.add(javaws.toString());
        } else if (fileName.endsWith(".sh")) {
            command.add("sh");
            command.add(javaws.toString());
        } else {
            command.add(javaws.toString());
        }
        command.addAll(Arrays.asList(args));
        return command;
    }

    private static int runProcess(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.inheritIO();
            Process process = builder.start();
            return process.waitFor();
        } catch (Exception ex) {
            reportError("Failed to start javaws: " + ex.getMessage());
            return 1;
        }
    }

    private static void reportError(String message) {
        System.err.println(message);
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, "IcedTea-Web", JOptionPane.ERROR_MESSAGE);
        }
    }
}

