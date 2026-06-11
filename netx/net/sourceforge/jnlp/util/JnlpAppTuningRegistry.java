package net.sourceforge.jnlp.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import net.sourceforge.jnlp.JNLPFile;
import net.sourceforge.jnlp.Launcher;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.runtime.Translator;
import net.sourceforge.jnlp.util.logging.OutputController;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport.RunningProcess;
import net.sourceforge.jnlp.util.ProcessMemorySupport.LiveJvmSettings;
import net.sourceforge.jnlp.util.ProcessMemorySupport.ProcessJvmContext;

/**
 * Per-application JVM tuning persisted beside lock files. Tuning overrides JNLP java-vm-args.
 */
public final class JnlpAppTuningRegistry {

    public static final String KEY_JNLP_PATH = "jnlpPath";
    public static final String KEY_DEFAULT_MAX_HEAP_BYTES = "defaultMaxHeapBytes";
    public static final String KEY_DEFAULT_GC_TYPE = "defaultGcType";
    public static final String KEY_DEFAULT_SOFT_MAX_HEAP_BYTES = "defaultSoftMaxHeapBytes";
    public static final String KEY_MAX_HEAP_BYTES = "maxHeapBytes";
    public static final String KEY_GC_TYPE = "gcType";
    public static final String KEY_SOFT_MAX_HEAP_BYTES = "softMaxHeapBytes";

    public static final String GC_G1 = "G1";
    public static final String GC_ZGC = "ZGC";

    public static final class AppTuning {
        private String jnlpPath;
        private long defaultMaxHeapBytes;
        private String defaultGcType = GC_G1;
        private long defaultSoftMaxHeapBytes;
        private long maxHeapBytes;
        private String gcType = GC_G1;
        private long softMaxHeapBytes;

        public String getJnlpPath() {
            return jnlpPath;
        }

        public long getDefaultMaxHeapBytes() {
            return defaultMaxHeapBytes;
        }

        public String getDefaultGcType() {
            return defaultGcType;
        }

        public long getDefaultSoftMaxHeapBytes() {
            return defaultSoftMaxHeapBytes;
        }

        public long getMaxHeapBytes() {
            return maxHeapBytes;
        }

        public String getGcType() {
            return gcType;
        }

        public long getSoftMaxHeapBytes() {
            return softMaxHeapBytes;
        }

        public boolean hasStoredTuning() {
            return maxHeapBytes > 0;
        }
    }

    private JnlpAppTuningRegistry() {
    }

    public static long softMaxDefaultForMaxHeap(long maxHeapBytes) {
        if (maxHeapBytes <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(maxHeapBytes * 0.20));
    }

    public static long softMaxMinimumForMaxHeap(long maxHeapBytes) {
        return softMaxDefaultForMaxHeap(maxHeapBytes);
    }

    public static long softMaxMaximumForMaxHeap(long maxHeapBytes) {
        return softMaxDefaultForMaxHeap(maxHeapBytes);
    }

    public static long minimumAllowedMaxHeap(long defaultMaxHeapBytes) {
        if (defaultMaxHeapBytes <= 0) {
            return 1;
        }
        return Math.max(1, Math.round(defaultMaxHeapBytes * 0.50));
    }

    public static File tuningFileFor(JNLPFile file) {
        return JnlpLockFileNames.tuningFileFor(file);
    }

    public static File tuningFileFor(RunningProcess process) {
        if (process == null) {
            return null;
        }
        File locksDir = PathsAndFiles.LOCKS_DIR.getFile();
        if (!locksDir.isDirectory()) {
            return JnlpLockFileNames.tuningFileFor(process.getJnlpPath(), process.getAppVersion());
        }
        File mainLock = PathsAndFiles.MAIN_LOCK.getFile();
        File runningDetails = NetxRunningDetailsRegistry.getDetailsFile();
        for (File lockFile : locksDir.listFiles()) {
            if (!lockFile.isFile() || lockFile.equals(mainLock) || lockFile.equals(runningDetails)
                    || lockFile.getName().endsWith(JnlpLockFileNames.TUNING_SUFFIX)) {
                continue;
            }
            JnlpLockMetadata metadata = JnlpLockMetadata.read(lockFile);
            if (metadata.getProcessId() == process.getPid()
                    || pathsEqual(metadata.getJnlpPath(), process.getJnlpPath())) {
                return JnlpLockFileNames.tuningFileForLockFile(lockFile);
            }
        }
        String jnlpPath = resolveJnlpPath(process);
        return JnlpLockFileNames.tuningFileFor(jnlpPath, process.getAppVersion());
    }

    public static AppTuning read(File tuningFile) {
        AppTuning tuning = new AppTuning();
        if (tuningFile == null || !tuningFile.isFile()) {
            return tuning;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(tuningFile))) {
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
                apply(tuning, key, value);
            }
        } catch (IOException ex) {
            // Return partial tuning.
        }
        return tuning;
    }

    public static AppTuning loadForProcess(RunningProcess process, ProcessJvmContext jvmContext) {
        File tuningFile = tuningFileFor(process);
        AppTuning stored = read(tuningFile);
        boolean hasStored = tuningFile != null && tuningFile.isFile();
        LiveJvmSettings live = ProcessMemorySupport.readLiveJvmSettings(process.getPid(), jvmContext);
        AppTuning result = new AppTuning();
        result.jnlpPath = resolveJnlpPath(process);

        long liveMax = live.getMaxHeapBytes();
        if (liveMax <= 0) {
            liveMax = 512L * 1024L * 1024L;
        }
        if (hasStored && stored.defaultMaxHeapBytes > 0) {
            result.defaultMaxHeapBytes = stored.defaultMaxHeapBytes;
            result.defaultGcType = normalizeGc(stored.defaultGcType);
            result.defaultSoftMaxHeapBytes = stored.defaultSoftMaxHeapBytes > 0
                    ? stored.defaultSoftMaxHeapBytes
                    : softMaxDefaultForMaxHeap(stored.defaultMaxHeapBytes);
        } else {
            result.defaultMaxHeapBytes = liveMax;
            result.defaultGcType = normalizeGc(live.getGcType());
            result.defaultSoftMaxHeapBytes = live.getSoftMaxHeapBytes() > 0
                    ? live.getSoftMaxHeapBytes()
                    : softMaxDefaultForMaxHeap(liveMax);
        }

        if (hasStored && stored.hasStoredTuning()) {
            result.maxHeapBytes = stored.maxHeapBytes;
            result.gcType = normalizeGc(stored.gcType);
            result.softMaxHeapBytes = stored.softMaxHeapBytes > 0
                    ? stored.softMaxHeapBytes
                    : softMaxDefaultForMaxHeap(stored.maxHeapBytes);
        } else {
            result.maxHeapBytes = live.getMaxHeapBytes() > 0 ? live.getMaxHeapBytes() : result.defaultMaxHeapBytes;
            result.gcType = normalizeGc(live.getGcType() != null && !live.getGcType().isEmpty()
                    ? live.getGcType() : result.defaultGcType);
            result.softMaxHeapBytes = live.getSoftMaxHeapBytes() > 0
                    ? live.getSoftMaxHeapBytes()
                    : softMaxDefaultForMaxHeap(result.maxHeapBytes);
        }
        return result;
    }

    public static void saveTunedValues(File tuningFile, AppTuning baseline, long maxHeapBytes,
            String gcType, long softMaxHeapBytes) throws IOException {
        if (baseline == null) {
            throw new IOException("Tuning values are not loaded yet.");
        }
        AppTuning tuning = new AppTuning();
        tuning.jnlpPath = baseline.jnlpPath;
        tuning.defaultMaxHeapBytes = baseline.defaultMaxHeapBytes;
        tuning.defaultGcType = baseline.defaultGcType;
        tuning.defaultSoftMaxHeapBytes = baseline.defaultSoftMaxHeapBytes;
        tuning.maxHeapBytes = maxHeapBytes;
        tuning.gcType = normalizeGc(gcType);
        tuning.softMaxHeapBytes = softMaxHeapBytes;
        write(tuningFile, tuning);
    }

    public static void write(File tuningFile, AppTuning tuning) throws IOException {
        if (tuningFile == null || tuning == null) {
            throw new IOException("Tuning file path is unknown for this application.");
        }
        File parent = tuningFile.getParentFile();
        if (parent != null) {
            if (!parent.isDirectory()) {
                if (!parent.mkdirs() && !parent.isDirectory()) {
                    FileUtils.createRestrictedDirectory(parent);
                }
            }
        }
        File staleTemp = new File(tuningFile.getCanonicalPath() + ".temp");
        if (staleTemp.exists() && !staleTemp.delete()) {
            throw new IOException("Cannot remove stale tuning temp file: " + staleTemp);
        }
        if (!tuningFile.isFile()) {
            FileUtils.createRestrictedFile(tuningFile, true);
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tuningFile, false))) {
            if (tuning.jnlpPath != null && !tuning.jnlpPath.trim().isEmpty()) {
                writer.write(KEY_JNLP_PATH + "=" + tuning.jnlpPath.trim());
                writer.newLine();
            }
            writer.write(KEY_DEFAULT_MAX_HEAP_BYTES + "=" + tuning.defaultMaxHeapBytes);
            writer.newLine();
            writer.write(KEY_DEFAULT_GC_TYPE + "=" + normalizeGc(tuning.defaultGcType));
            writer.newLine();
            writer.write(KEY_DEFAULT_SOFT_MAX_HEAP_BYTES + "=" + tuning.defaultSoftMaxHeapBytes);
            writer.newLine();
            writer.write(KEY_MAX_HEAP_BYTES + "=" + tuning.maxHeapBytes);
            writer.newLine();
            writer.write(KEY_GC_TYPE + "=" + normalizeGc(tuning.gcType));
            writer.newLine();
            writer.write(KEY_SOFT_MAX_HEAP_BYTES + "=" + tuning.softMaxHeapBytes);
            writer.newLine();
            writer.flush();
        }
    }

    public static void delete(File tuningFile) {
        if (tuningFile != null && tuningFile.isFile() && !tuningFile.delete()) {
            tuningFile.deleteOnExit();
        }
    }

    public static void deleteAllTuningFiles() {
        File locksDir = PathsAndFiles.LOCKS_DIR.getFile();
        if (!locksDir.isDirectory()) {
            return;
        }
        File[] files = locksDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(JnlpLockFileNames.TUNING_SUFFIX)) {
                delete(file);
            }
        }
    }

    public static void deleteTuningForApplication(String application) {
        if (application == null || application.trim().isEmpty()) {
            return;
        }
        File locksDir = PathsAndFiles.LOCKS_DIR.getFile();
        if (!locksDir.isDirectory()) {
            return;
        }
        String needle = application.trim();
        File[] files = locksDir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(JnlpLockFileNames.TUNING_SUFFIX)) {
                continue;
            }
            AppTuning tuning = read(file);
            if (pathsEqual(tuning.jnlpPath, needle)
                    || CacheUtilDomainMatch.domainMatches(tuning.jnlpPath, needle)) {
                delete(file);
            }
        }
    }

    public static void applyTuningOverrides(JNLPFile file, List<String> vmArgs) {
        if (file == null || vmArgs == null) {
            return;
        }
        File tuningFile = tuningFileFor(file);
        AppTuning tuning = read(tuningFile);
        if (tuning.maxHeapBytes <= 0 && tuningFile != null && !tuningFile.isFile()) {
            return;
        }
        if (tuning.maxHeapBytes <= 0) {
            return;
        }
        stripTunableVmArgs(vmArgs);
        vmArgs.add("-Xmx" + formatHeapArg(tuning.maxHeapBytes));
        appendGcArgs(vmArgs, normalizeGc(tuning.gcType));
        if (tuning.softMaxHeapBytes > 0) {
            vmArgs.add("-XX:SoftMaxHeapSize=" + tuning.softMaxHeapBytes);
        }
    }

    public static String getRelaunchFailureMessage(RunningProcess process) {
        if (process == null) {
            return Translator.R("CPRunningAppsTuneRelaunchFailed");
        }
        if (resolveJnlpPath(process) == null) {
            return Translator.R("CPRunningAppsTuneRelaunchNoJnlp");
        }
        if (resolveJavawsLauncherPath() == null) {
            return Translator.R("CPRunningAppsTuneRelaunchNoLauncher");
        }
        return Translator.R("CPRunningAppsTuneRelaunchFailed");
    }

    public static boolean relaunchApplication(RunningProcess process) {
        if (process == null) {
            return false;
        }
        String jnlpPath = resolveJnlpPath(process);
        String javaws = resolveJavawsLauncherPath();
        if (jnlpPath == null || javaws == null) {
            OutputController.getLogger().log(OutputController.Level.WARNING_ALL,
                    "Relaunch preflight failed for pid " + process.getPid()
                            + " jnlpPath=" + jnlpPath + " javaws=" + javaws);
            return false;
        }
        List<String> vmArgs;
        try {
            vmArgs = openJnlpFile(jnlpPath).getNewVMArgs();
        } catch (Exception ex) {
            OutputController.getLogger().log(ex);
            return false;
        }
        int pid = process.getPid();
        JnlpRunningProcessSupport.stopProcess(pid, false);
        waitForProcessExit(pid, 15000);
        if (JnlpRunningProcessSupport.isProcessAlive(pid)) {
            JnlpRunningProcessSupport.stopProcess(pid, true);
            waitForProcessExit(pid, 5000);
        }
        return launchJnlp(jnlpPath, javaws, vmArgs);
    }

    /**
     * Control panel runs as {@code itweb-settings}, so {@link Launcher#KEY_JAVAWS_LOCATION} points at
     * the settings binary. Relaunch must use the sibling {@code javaws} launcher instead.
     */
    static String resolveJavawsLauncherPath() {
        String location = System.getProperty(Launcher.KEY_JAVAWS_LOCATION, "").trim();
        if (!location.isEmpty()) {
            File launcher = new File(location);
            if (isJavawsLauncher(launcher)) {
                return launcher.getAbsolutePath();
            }
            File binDir = launcher.getParentFile();
            if (binDir != null && binDir.isDirectory()) {
                String[] names = JNLPRuntime.isWindows()
                        ? new String[] { "javaws.exe", "javawsc.exe" }
                        : new String[] { "javaws", "javawsc" };
                for (String name : names) {
                    File candidate = new File(binDir, name);
                    if (candidate.isFile()) {
                        return candidate.getAbsolutePath();
                    }
                }
            }
        }
        String path = System.getenv("PATH");
        if (path != null && !path.trim().isEmpty()) {
            for (String entry : path.split(File.pathSeparator)) {
                if (entry == null || entry.trim().isEmpty()) {
                    continue;
                }
                File candidate = new File(entry.trim(), JNLPRuntime.isWindows() ? "javaws.exe" : "javaws");
                if (candidate.isFile()) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        File installed = new File("/opt/icedtea-web/bin/javaws");
        if (installed.isFile()) {
            return installed.getAbsolutePath();
        }
        return null;
    }

    private static boolean isJavawsLauncher(File launcher) {
        if (launcher == null || !launcher.isFile()) {
            return false;
        }
        String lower = launcher.getName().toLowerCase(Locale.ROOT);
        return lower.startsWith("javaws") && !lower.contains("settings") && !lower.contains("policy");
    }

    static String resolveJnlpPath(RunningProcess process) {
        String jnlpPath = process.getJnlpPath();
        if (jnlpPath != null && !jnlpPath.trim().isEmpty()) {
            return jnlpPath.trim();
        }
        jnlpPath = JnlpLockMetadata.extractJnlpPathFromCommandLine(process.getCommandLine());
        if (jnlpPath != null && !jnlpPath.trim().isEmpty()) {
            return jnlpPath.trim();
        }
        return null;
    }

    private static boolean launchJnlp(String jnlpPath, String javaws, List<String> vmArgs) {
        try {
            List<String> commands = new ArrayList<>();
            commands.add(javaws);
            for (String arg : vmArgs) {
                commands.add("-J" + arg);
            }
            commands.add(jnlpPath);
            ProcessBuilder builder = new ProcessBuilder(commands);
            builder.environment().put("ICEDTEA_WEB_SPLASH", "none");
            builder.start();
            return true;
        } catch (Exception ex) {
            OutputController.getLogger().log(ex);
            return false;
        }
    }

    private static JNLPFile openJnlpFile(String jnlpPath) throws Exception {
        if (jnlpPath.contains("://")) {
            return new JNLPFile(new URL(jnlpPath));
        }
        return new JNLPFile(new File(jnlpPath).toURI().toURL());
    }

    private static void waitForProcessExit(int pid, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!JnlpRunningProcessSupport.isProcessAlive(pid)) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void stripTunableVmArgs(List<String> vmArgs) {
        Iterator<String> it = vmArgs.iterator();
        while (it.hasNext()) {
            String arg = it.next();
            if (arg == null) {
                it.remove();
                continue;
            }
            if (arg.startsWith("-Xmx") || arg.startsWith("-XX:SoftMaxHeapSize=")
                    || arg.equals("-XX:+UseG1GC") || arg.equals("-XX:-UseG1GC")
                    || arg.equals("-XX:+UseZGC") || arg.equals("-XX:-UseZGC")) {
                it.remove();
            }
        }
    }

    private static void appendGcArgs(List<String> vmArgs, String gcType) {
        if (GC_ZGC.equals(gcType)) {
            vmArgs.add("-XX:+UseZGC");
            vmArgs.add("-XX:-UseG1GC");
        } else {
            vmArgs.add("-XX:+UseG1GC");
            vmArgs.add("-XX:-UseZGC");
        }
    }

    private static String formatHeapArg(long bytes) {
        if (bytes % (1024L * 1024L) == 0) {
            return (bytes / (1024L * 1024L)) + "m";
        }
        return Long.toString(bytes);
    }

    private static String normalizeGc(String gcType) {
        if (gcType == null) {
            return GC_G1;
        }
        if (GC_ZGC.equalsIgnoreCase(gcType.trim())) {
            return GC_ZGC;
        }
        return GC_G1;
    }

    private static void apply(AppTuning tuning, String key, String value) {
        if (KEY_JNLP_PATH.equalsIgnoreCase(key)) {
            tuning.jnlpPath = value;
        } else if (KEY_DEFAULT_MAX_HEAP_BYTES.equalsIgnoreCase(key)) {
            tuning.defaultMaxHeapBytes = parseLong(value);
        } else if (KEY_DEFAULT_GC_TYPE.equalsIgnoreCase(key)) {
            tuning.defaultGcType = normalizeGc(value);
        } else if (KEY_DEFAULT_SOFT_MAX_HEAP_BYTES.equalsIgnoreCase(key)) {
            tuning.defaultSoftMaxHeapBytes = parseLong(value);
        } else if (KEY_MAX_HEAP_BYTES.equalsIgnoreCase(key)) {
            tuning.maxHeapBytes = parseLong(value);
        } else if (KEY_GC_TYPE.equalsIgnoreCase(key)) {
            tuning.gcType = normalizeGc(value);
        } else if (KEY_SOFT_MAX_HEAP_BYTES.equalsIgnoreCase(key)) {
            tuning.softMaxHeapBytes = parseLong(value);
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean pathsEqual(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    /**
     * Domain matching helper kept local to avoid pulling cache UI into tuning registry.
     */
    private static final class CacheUtilDomainMatch {
        private static boolean domainMatches(String jnlpPath, String domain) {
            if (jnlpPath == null || domain == null) {
                return false;
            }
            String lowerPath = jnlpPath.toLowerCase(Locale.ROOT);
            String lowerDomain = domain.toLowerCase(Locale.ROOT);
            if (lowerPath.contains(lowerDomain)) {
                return true;
            }
            int scheme = lowerDomain.indexOf("://");
            if (scheme > 0) {
                String hostPart = lowerDomain.substring(scheme + 3);
                return lowerPath.contains(hostPart);
            }
            return false;
        }
    }
}
