package net.sourceforge.jnlp.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Key/value metadata stored in IcedTea-Web lock files.
 */
public final class JnlpLockMetadata {

    public static final String KEY_PROCESS_ID = "processID";
    public static final String KEY_PORT = "port";
    public static final String KEY_JNLP_PATH = "jnlpPath";

    public static final int INVALID_PORT = Integer.MIN_VALUE;

    public static final class ProcessEntry {
        private final int processId;
        private final String jnlpPath;

        public ProcessEntry(int processId, String jnlpPath) {
            this.processId = processId;
            this.jnlpPath = jnlpPath;
        }

        public int getProcessId() {
            return processId;
        }

        public String getJnlpPath() {
            return jnlpPath;
        }
    }

    private int processId = -1;
    private int port = INVALID_PORT;
    private String jnlpPath;

    public int getProcessId() {
        return processId;
    }

    public int getPort() {
        return port;
    }

    public String getJnlpPath() {
        return jnlpPath;
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
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(lockFile, false))) {
            if (port != INVALID_PORT) {
                writer.write(KEY_PORT + "=" + port);
                writer.newLine();
            }
            if (processId > 0) {
                writer.write(KEY_PROCESS_ID + "=" + processId);
                writer.newLine();
            }
            if (jnlpPath != null && !jnlpPath.trim().isEmpty()) {
                writer.write(KEY_JNLP_PATH + "=" + jnlpPath.trim());
                writer.newLine();
            }
            writer.flush();
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
                        entries.add(new ProcessEntry(pendingPid, pendingJnlpPath));
                    }
                    try {
                        pendingPid = Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        pendingPid = -1;
                    }
                    pendingJnlpPath = null;
                } else if (KEY_JNLP_PATH.equalsIgnoreCase(key)) {
                    pendingJnlpPath = value;
                }
            }
            if (pendingPid > 0) {
                entries.add(new ProcessEntry(pendingPid, pendingJnlpPath));
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
                writer.write(KEY_PROCESS_ID + "=" + entry.getProcessId());
                writer.newLine();
                if (entry.getJnlpPath() != null && !entry.getJnlpPath().trim().isEmpty()) {
                    writer.write(KEY_JNLP_PATH + "=" + entry.getJnlpPath().trim());
                    writer.newLine();
                }
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
        }
    }

    public static String extractJnlpPath(net.sourceforge.jnlp.JNLPFile jnlpFile) {
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
}
