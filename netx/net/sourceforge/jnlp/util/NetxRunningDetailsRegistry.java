package net.sourceforge.jnlp.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;

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
        if (processId <= 0) {
            return;
        }
        try {
            updateRegistry(processId, true, jnlpPath);
        } catch (IOException ex) {
            // Best effort; older builds and lock-byte checks still work.
        }
    }

    public static void unregisterProcess(int processId) {
        if (processId <= 0) {
            return;
        }
        try {
            updateRegistry(processId, false, null);
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

    private static void updateRegistry(int processId, boolean register, String jnlpPath) throws IOException {
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
                    entries = upsertEntry(entries, processId, jnlpPath);
                } else {
                    entries = removeEntry(entries, processId);
                }
                JnlpLockMetadata.writeProcessEntries(detailsFile, HEADER, entries);
            } finally {
                fileLock.release();
            }
        }
    }

    private static List<JnlpLockMetadata.ProcessEntry> upsertEntry(
            List<JnlpLockMetadata.ProcessEntry> entries, int processId, String jnlpPath) {
        List<JnlpLockMetadata.ProcessEntry> updated = new ArrayList<>();
        boolean replaced = false;
        for (JnlpLockMetadata.ProcessEntry entry : entries) {
            if (entry.getProcessId() == processId) {
                updated.add(new JnlpLockMetadata.ProcessEntry(processId,
                        jnlpPath != null ? jnlpPath : entry.getJnlpPath()));
                replaced = true;
            } else {
                updated.add(entry);
            }
        }
        if (!replaced) {
            updated.add(new JnlpLockMetadata.ProcessEntry(processId, jnlpPath));
        }
        return updated;
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
