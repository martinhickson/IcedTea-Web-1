package net.sourceforge.jnlp.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;

import net.sourceforge.jnlp.JNLPFile;
import net.sourceforge.jnlp.config.PathsAndFiles;

/**
 * Optional companion file for {@link PathsAndFiles#MAIN_LOCK}. The existing
 * {@code netx_running} file is left unchanged for backwards compatibility; PID
 * metadata is stored in {@code netx_running_details} in the same directory.
 */
public final class NetxRunningDetailsRegistry {

    public static final String DETAILS_FILE_NAME = "netx_running_details";

    private static final String HEADER = "# Optional metadata for running IcedTea-Web instances."
            + "\n# The netx_running lock file remains authoritative.";

    private NetxRunningDetailsRegistry() {
    }

    public static File getDetailsFile() {
        File mainLock = PathsAndFiles.MAIN_LOCK.getFile();
        File parent = mainLock.getParentFile();
        if (parent == null) {
            return new File(DETAILS_FILE_NAME);
        }
        return new File(parent, DETAILS_FILE_NAME);
    }

    public static void registerProcess(int processId) {
        registerProcess(processId, null);
    }

    public static void registerProcess(int processId, String jnlpPath) {
        registerProcess(JnlpLockMetadata.entryFromCurrentRuntime(processId, jnlpPath, null, null));
    }

    public static void registerProcess(JNLPFile jnlpFile) {
        int processId = JnlpRunningProcessSupport.currentPid();
        if (processId <= 0) {
            return;
        }
        registerProcess(JnlpLockMetadata.entryFromJnlpFile(jnlpFile, processId));
    }

    public static void registerProcess(JnlpLockMetadata.ProcessEntry entry) {
        if (entry == null || entry.getProcessId() <= 0) {
            return;
        }
        try {
            updateRegistry(entry, true);
        } catch (IOException ex) {
            // Best effort; older builds and lock-byte checks still work.
        }
    }

    public static void unregisterProcess(int processId) {
        if (processId <= 0) {
            return;
        }
        try {
            updateRegistry(new JnlpLockMetadata.ProcessEntry(processId, null), false);
        } catch (IOException ex) {
            // Best effort.
        }
    }

    public static List<JnlpLockMetadata.ProcessEntry> listRegisteredProcesses() {
        File detailsFile = getDetailsFile();
        if (!detailsFile.isFile()) {
            return new ArrayList<>();
        }
        return JnlpLockMetadata.readAllProcessEntries(detailsFile);
    }

    private static void updateRegistry(JnlpLockMetadata.ProcessEntry entry, boolean register) throws IOException {
        File detailsFile = getDetailsFile();
        FileUtils.createParentDir(detailsFile);
        if (!detailsFile.exists()) {
            FileUtils.createRestrictedFile(detailsFile, true);
        }

        try (RandomAccessFile raf = new RandomAccessFile(detailsFile, "rw")) {
            FileChannel channel = raf.getChannel();
            FileLock fileLock = channel.tryLock();
            if (fileLock == null) {
                fileLock = channel.lock();
            }
            try {
                List<JnlpLockMetadata.ProcessEntry> entries = JnlpLockMetadata.readAllProcessEntries(detailsFile);
                if (register) {
                    entries = upsertEntry(entries, entry);
                } else {
                    entries = removeEntry(entries, entry.getProcessId());
                }
                JnlpLockMetadata.writeProcessEntries(detailsFile, HEADER, entries);
            } finally {
                fileLock.release();
            }
        }
    }

    private static List<JnlpLockMetadata.ProcessEntry> upsertEntry(
            List<JnlpLockMetadata.ProcessEntry> entries, JnlpLockMetadata.ProcessEntry entry) {
        List<JnlpLockMetadata.ProcessEntry> updated = new ArrayList<>();
        boolean replaced = false;
        for (JnlpLockMetadata.ProcessEntry existing : entries) {
            if (existing.getProcessId() == entry.getProcessId()) {
                updated.add(mergeEntry(existing, entry));
                replaced = true;
            } else {
                updated.add(existing);
            }
        }
        if (!replaced) {
            updated.add(entry);
        }
        return updated;
    }

    private static JnlpLockMetadata.ProcessEntry mergeEntry(
            JnlpLockMetadata.ProcessEntry existing, JnlpLockMetadata.ProcessEntry incoming) {
        return new JnlpLockMetadata.ProcessEntry(
                incoming.getProcessId(),
                prefer(incoming.getJnlpPath(), existing.getJnlpPath()),
                prefer(incoming.getAppTitle(), existing.getAppTitle()),
                prefer(incoming.getAppVersion(), existing.getAppVersion()),
                prefer(incoming.getJvmHome(), existing.getJvmHome()),
                prefer(incoming.getJvmVendor(), existing.getJvmVendor()),
                prefer(incoming.getJvmVersion(), existing.getJvmVersion()));
    }

    private static String prefer(String primary, String fallback) {
        if (primary != null && !primary.trim().isEmpty()) {
            return primary.trim();
        }
        return fallback;
    }

    private static List<JnlpLockMetadata.ProcessEntry> removeEntry(
            List<JnlpLockMetadata.ProcessEntry> entries, int processId) {
        List<JnlpLockMetadata.ProcessEntry> updated = new ArrayList<>();
        for (JnlpLockMetadata.ProcessEntry entry : entries) {
            if (entry.getProcessId() != processId) {
                updated.add(entry);
            }
        }
        return updated;
    }
}
