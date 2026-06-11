package net.sourceforge.jnlp.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.sourceforge.jnlp.JARDesc;
import net.sourceforge.jnlp.JNLPFile;
import net.sourceforge.jnlp.ResourcesDesc;
import net.sourceforge.jnlp.Version;

/**
 * Key/value metadata stored in IcedTea-Web lock files.
 */
public final class JnlpLockMetadata {

    public static final String KEY_PROCESS_ID = "processID";
    public static final String KEY_PORT = "port";
    public static final String KEY_JNLP_PATH = "jnlpPath";
    public static final String KEY_APP_TITLE = "appTitle";
    public static final String KEY_APP_VERSION = "appVersion";
    public static final String KEY_JVM_HOME = "jvmHome";
    public static final String KEY_JVM_VENDOR = "jvmVendor";
    public static final String KEY_JVM_VERSION = "jvmVersion";

    public static final int INVALID_PORT = Integer.MIN_VALUE;

    public static final class ProcessEntry {
        private final int processId;
        private final String jnlpPath;
        private final String appTitle;
        private final String appVersion;
        private final String jvmHome;
        private final String jvmVendor;
        private final String jvmVersion;

        public ProcessEntry(int processId, String jnlpPath) {
            this(processId, jnlpPath, null, null, null, null, null);
        }

        public ProcessEntry(int processId, String jnlpPath, String appTitle, String appVersion) {
            this(processId, jnlpPath, appTitle, appVersion, null, null, null);
        }

        public ProcessEntry(int processId, String jnlpPath, String appTitle, String appVersion,
                String jvmHome, String jvmVendor, String jvmVersion) {
            this.processId = processId;
            this.jnlpPath = jnlpPath;
            this.appTitle = appTitle;
            this.appVersion = appVersion;
            this.jvmHome = jvmHome;
            this.jvmVendor = jvmVendor;
            this.jvmVersion = jvmVersion;
        }

        public int getProcessId() {
            return processId;
        }

        public String getJnlpPath() {
            return jnlpPath;
        }

        public String getAppTitle() {
            return appTitle;
        }

        public String getAppVersion() {
            return appVersion;
        }

        public String getJvmHome() {
            return jvmHome;
        }

        public String getJvmVendor() {
            return jvmVendor;
        }

        public String getJvmVersion() {
            return jvmVersion;
        }
    }

    private int processId = -1;
    private int port = INVALID_PORT;
    private String jnlpPath;
    private String appTitle;
    private String appVersion;
    private String jvmHome;
    private String jvmVendor;
    private String jvmVersion;

    public int getProcessId() {
        return processId;
    }

    public int getPort() {
        return port;
    }

    public String getJnlpPath() {
        return jnlpPath;
    }

    public String getAppTitle() {
        return appTitle;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public String getJvmHome() {
        return jvmHome;
    }

    public String getJvmVendor() {
        return jvmVendor;
    }

    public String getJvmVersion() {
        return jvmVersion;
    }

    public static JnlpLockMetadata read(File lockFile) {
        JnlpLockMetadata metadata = new JnlpLockMetadata();
        if (lockFile == null || !lockFile.isFile()) {
            return metadata;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(lockFile))) {
            String line;
            boolean parsedLegacyPort = false;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals > 0) {
                    String key = line.substring(0, equals).trim();
                    String value = line.substring(equals + 1).trim();
                    apply(metadata, key, value);
                } else if (!parsedLegacyPort) {
                    try {
                        metadata.port = Integer.parseInt(line);
                        parsedLegacyPort = true;
                    } catch (NumberFormatException ex) {
                        // Ignore non-numeric lines in legacy files.
                    }
                }
            }
        } catch (IOException ex) {
            // Return partial metadata.
        }
        return metadata;
    }

    public static void write(File lockFile, int port, int processId, String jnlpPath) throws IOException {
        write(lockFile, port, processId, jnlpPath, null, null);
    }

    public static void write(File lockFile, int port, int processId, String jnlpPath,
            String appTitle, String appVersion) throws IOException {
        write(lockFile, port, new ProcessEntry(processId, jnlpPath, appTitle, appVersion));
    }

    public static void write(File lockFile, int port, ProcessEntry entry) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(lockFile, false))) {
            if (port != INVALID_PORT) {
                writer.write(KEY_PORT + "=" + port);
                writer.newLine();
            }
            writeEntryFields(writer, entry);
            writer.flush();
        }
    }

    private static void writeEntryFields(BufferedWriter writer, ProcessEntry entry) throws IOException {
        if (entry == null) {
            return;
        }
        if (entry.getProcessId() > 0) {
            writer.write(KEY_PROCESS_ID + "=" + entry.getProcessId());
            writer.newLine();
        }
        if (entry.getJnlpPath() != null && !entry.getJnlpPath().trim().isEmpty()) {
            writer.write(KEY_JNLP_PATH + "=" + entry.getJnlpPath().trim());
            writer.newLine();
        }
        if (entry.getAppTitle() != null && !entry.getAppTitle().trim().isEmpty()) {
            writer.write(KEY_APP_TITLE + "=" + entry.getAppTitle().trim());
            writer.newLine();
        }
        if (entry.getAppVersion() != null && !entry.getAppVersion().trim().isEmpty()) {
            writer.write(KEY_APP_VERSION + "=" + entry.getAppVersion().trim());
            writer.newLine();
        }
        if (entry.getJvmHome() != null && !entry.getJvmHome().trim().isEmpty()) {
            writer.write(KEY_JVM_HOME + "=" + entry.getJvmHome().trim());
            writer.newLine();
        }
        if (entry.getJvmVendor() != null && !entry.getJvmVendor().trim().isEmpty()) {
            writer.write(KEY_JVM_VENDOR + "=" + entry.getJvmVendor().trim());
            writer.newLine();
        }
        if (entry.getJvmVersion() != null && !entry.getJvmVersion().trim().isEmpty()) {
            writer.write(KEY_JVM_VERSION + "=" + entry.getJvmVersion().trim());
            writer.newLine();
        }
    }

    public static List<ProcessEntry> readAllProcessEntries(File lockFile) {
        List<ProcessEntry> entries = new ArrayList<>();
        if (lockFile == null || !lockFile.isFile()) {
            return entries;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(lockFile))) {
            int pendingPid = -1;
            String pendingJnlpPath = null;
            String pendingAppTitle = null;
            String pendingAppVersion = null;
            String pendingJvmHome = null;
            String pendingJvmVendor = null;
            String pendingJvmVersion = null;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String key = line.substring(0, equals).trim();
                String value = line.substring(equals + 1).trim();
                if (KEY_PROCESS_ID.equalsIgnoreCase(key)) {
                    if (pendingPid > 0) {
                        entries.add(new ProcessEntry(pendingPid, pendingJnlpPath, pendingAppTitle, pendingAppVersion,
                                pendingJvmHome, pendingJvmVendor, pendingJvmVersion));
                    }
                    try {
                        pendingPid = Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        pendingPid = -1;
                    }
                    pendingJnlpPath = null;
                    pendingAppTitle = null;
                    pendingAppVersion = null;
                    pendingJvmHome = null;
                    pendingJvmVendor = null;
                    pendingJvmVersion = null;
                } else if (KEY_JNLP_PATH.equalsIgnoreCase(key)) {
                    pendingJnlpPath = value;
                } else if (KEY_APP_TITLE.equalsIgnoreCase(key)) {
                    pendingAppTitle = value;
                } else if (KEY_APP_VERSION.equalsIgnoreCase(key)) {
                    pendingAppVersion = value;
                } else if (KEY_JVM_HOME.equalsIgnoreCase(key)) {
                    pendingJvmHome = value;
                } else if (KEY_JVM_VENDOR.equalsIgnoreCase(key)) {
                    pendingJvmVendor = value;
                } else if (KEY_JVM_VERSION.equalsIgnoreCase(key)) {
                    pendingJvmVersion = value;
                }
            }
            if (pendingPid > 0) {
                entries.add(new ProcessEntry(pendingPid, pendingJnlpPath, pendingAppTitle, pendingAppVersion,
                        pendingJvmHome, pendingJvmVendor, pendingJvmVersion));
            }
        } catch (IOException ex) {
            // Return partial list.
        }
        return entries;
    }

    public static void writeProcessEntries(File lockFile, String header, List<ProcessEntry> entries) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(lockFile, false))) {
            if (header != null && !header.isEmpty()) {
                for (String line : header.split("\\R")) {
                    writer.write(line);
                    writer.newLine();
                }
            }
            for (ProcessEntry entry : entries) {
                writeEntryFields(writer, entry);
            }
            writer.flush();
        }
    }

    private static void apply(JnlpLockMetadata metadata, String key, String value) {
        if (KEY_PROCESS_ID.equalsIgnoreCase(key)) {
            try {
                metadata.processId = Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                metadata.processId = -1;
            }
        } else if (KEY_PORT.equalsIgnoreCase(key)) {
            try {
                metadata.port = Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                metadata.port = INVALID_PORT;
            }
        } else if (KEY_JNLP_PATH.equalsIgnoreCase(key)) {
            metadata.jnlpPath = value;
        } else if (KEY_APP_TITLE.equalsIgnoreCase(key)) {
            metadata.appTitle = value;
        } else if (KEY_APP_VERSION.equalsIgnoreCase(key)) {
            metadata.appVersion = value;
        } else if (KEY_JVM_HOME.equalsIgnoreCase(key)) {
            metadata.jvmHome = value;
        } else if (KEY_JVM_VENDOR.equalsIgnoreCase(key)) {
            metadata.jvmVendor = value;
        } else if (KEY_JVM_VERSION.equalsIgnoreCase(key)) {
            metadata.jvmVersion = value;
        }
    }

    public static ProcessEntry entryFromJnlpFile(JNLPFile jnlpFile, int processId) {
        JvmDescriptor jvm = JvmDescriptor.describeCurrentRuntime();
        return new ProcessEntry(
                processId,
                extractJnlpPath(jnlpFile),
                extractAppTitle(jnlpFile),
                extractAppVersion(jnlpFile),
                jvm.getHomePath(),
                jvm.getFlavour(),
                jvm.getVersion());
    }

    public static ProcessEntry entryFromCurrentRuntime(int processId, String jnlpPath,
            String appTitle, String appVersion) {
        JvmDescriptor jvm = JvmDescriptor.describeCurrentRuntime();
        return new ProcessEntry(processId, jnlpPath, appTitle, appVersion,
                jvm.getHomePath(), jvm.getFlavour(), jvm.getVersion());
    }

    public static String extractAppTitle(JNLPFile jnlpFile) {
        if (jnlpFile == null) {
            return null;
        }
        try {
            String title = jnlpFile.getInformation().getTitle();
            if (title != null && !title.trim().isEmpty()) {
                return title.trim();
            }
        } catch (Exception ex) {
            // Fall through.
        }
        try {
            String title = jnlpFile.getTitleFromJnlp();
            if (title != null && !title.trim().isEmpty()) {
                return title.trim();
            }
        } catch (Exception ex) {
            // Fall through.
        }
        return null;
    }

    public static String extractAppVersion(JNLPFile jnlpFile) {
        if (jnlpFile == null) {
            return null;
        }
        Version version = jnlpFile.getFileVersion();
        if (version != null && version.toString() != null && !version.toString().trim().isEmpty()) {
            return version.toString().trim();
        }
        version = jnlpFile.getSpecVersion();
        if (version != null && version.toString() != null && !version.toString().trim().isEmpty()) {
            return version.toString().trim();
        }
        return extractVersionFromMainJarHref(jnlpFile);
    }

    private static String extractVersionFromMainJarHref(JNLPFile jnlpFile) {
        try {
            ResourcesDesc resources = jnlpFile.getResources();
            if (resources == null) {
                return null;
            }
            JARDesc[] jars = resources.getJARs();
            if (jars == null || jars.length == 0) {
                return null;
            }
            JARDesc mainJar = jars[0];
            for (JARDesc jar : jars) {
                if (jar.isMain()) {
                    mainJar = jar;
                    break;
                }
            }
            if (mainJar.getLocation() == null) {
                return null;
            }
            return versionFromJarFileName(mainJar.getLocation().toString());
        } catch (Exception ex) {
            return null;
        }
    }

    private static String versionFromJarFileName(String href) {
        if (href == null || href.trim().isEmpty()) {
            return null;
        }
        String name = href.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.endsWith(".jar") || name.endsWith(".JAR")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name.endsWith("-app")) {
            name = name.substring(0, name.length() - 4);
        }
        int dash = name.lastIndexOf('-');
        if (dash > 0 && dash < name.length() - 1) {
            String version = name.substring(dash + 1);
            if (version.matches(".*\\d.*")) {
                return version;
            }
        }
        return null;
    }

    public static String formatDisplayName(String appTitle, String appVersion, String jnlpPath) {
        String title = appTitle;
        if (title == null || title.trim().isEmpty()) {
            title = shortNameFromJnlpPath(jnlpPath);
        }
        if (appVersion != null && !appVersion.trim().isEmpty()) {
            return title.trim() + " (" + appVersion.trim() + ")";
        }
        return title.trim();
    }

    public static ProcessEntry resolveApplicationInfo(String jnlpPath) {
        if (jnlpPath == null || jnlpPath.trim().isEmpty()) {
            return new ProcessEntry(-1, null);
        }
        try {
            URL location = new URL(jnlpPath.trim());
            JNLPFile file = new JNLPFile(location);
            return new ProcessEntry(-1, jnlpPath.trim(), extractAppTitle(file), extractAppVersion(file));
        } catch (Exception ex) {
            try {
                File localFile = new File(jnlpPath.trim());
                if (localFile.isFile()) {
                    JNLPFile file = new JNLPFile(localFile.toURI().toURL());
                    return new ProcessEntry(-1, jnlpPath.trim(), extractAppTitle(file), extractAppVersion(file));
                }
            } catch (Exception ignored) {
                // Fall through.
            }
        }
        return new ProcessEntry(-1, jnlpPath.trim());
    }

    public static String extractJnlpPath(JNLPFile jnlpFile) {
        if (jnlpFile == null) {
            return null;
        }
        if (jnlpFile.getSourceLocation() != null) {
            return jnlpFile.getSourceLocation().toString();
        }
        if (jnlpFile.getFileLocation() != null) {
            return jnlpFile.getFileLocation().toString();
        }
        return null;
    }

    public static String shortNameFromJnlpPath(String jnlpPath) {
        if (jnlpPath == null || jnlpPath.trim().isEmpty()) {
            return "IcedTea-Web";
        }
        String trimmed = jnlpPath.trim();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String fileName = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        if (fileName.endsWith(".jnlp") || fileName.endsWith(".JNLP")) {
            fileName = fileName.substring(0, fileName.length() - 5);
        }
        return fileName.isEmpty() ? "IcedTea-Web" : fileName;
    }

    public static String shortNameFromCommandLine(String commandLine) {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            return "IcedTea-Web";
        }
        String lower = commandLine.toLowerCase(Locale.ROOT);
        int jnlpIndex = lower.indexOf(".jnlp");
        if (jnlpIndex > 0) {
            int start = Math.max(lower.lastIndexOf(' ', jnlpIndex - 1), lower.lastIndexOf('=', jnlpIndex - 1));
            String candidate = commandLine.substring(start + 1, jnlpIndex + 5).trim();
            return shortNameFromJnlpPath(candidate);
        }
        return "IcedTea-Web";
    }

    public static String extractJnlpPathFromCommandLine(String commandLine) {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            return null;
        }
        String[] tokens = commandLine.split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            if ("-jnlp".equals(tokens[i]) && i + 1 < tokens.length) {
                return tokens[i + 1];
            }
        }
        for (String token : tokens) {
            if (token.toLowerCase(Locale.ROOT).endsWith(".jnlp")) {
                return token;
            }
        }
        return null;
    }
}
