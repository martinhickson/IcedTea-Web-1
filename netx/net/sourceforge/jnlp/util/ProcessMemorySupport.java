package net.sourceforge.jnlp.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.jnlp.runtime.JNLPRuntime;

public final class ProcessMemorySupport {

    private static final Pattern HEAP_REGION = Pattern.compile("total\\s+(\\d+)K,\\s+used\\s+(\\d+)K",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MAX_HEAP_FLAG = Pattern.compile("MaxHeapSize\\s*=\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JAVA_EXECUTABLE = Pattern.compile(
            "([A-Za-z]:[^\\s\"']+|/[^\\s\"']+)[/\\\\]bin[/\\\\]java(?:\\.exe)?",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, ProcessJvmContext> JVM_CONTEXT_CACHE = new ConcurrentHashMap<>();

    public static final class ProcessJvmContext {
        private final String javaHome;
        private final String jcmdPath;
        private final String vendor;
        private final String jvmVersion;

        public ProcessJvmContext(String javaHome, String jcmdPath, String vendor, String jvmVersion) {
            this.javaHome = javaHome;
            this.jcmdPath = jcmdPath;
            this.vendor = vendor == null ? "" : vendor;
            this.jvmVersion = jvmVersion == null ? "" : jvmVersion;
        }

        public String getJavaHome() {
            return javaHome;
        }

        public String getJcmdPath() {
            return jcmdPath;
        }

        public String getVendor() {
            return vendor;
        }

        public String getJvmVersion() {
            return jvmVersion;
        }

        public boolean hasJcmd() {
            return jcmdPath != null && !jcmdPath.isEmpty();
        }

        public static ProcessJvmContext unknown() {
            return new ProcessJvmContext(null, null, "", "");
        }
    }

    public static final class MemoryInfo {
        private final long heapUsedBytes;
        private final long heapMaxBytes;
        private final long rssBytes;
        private final long systemTotalBytes;

        public MemoryInfo(long heapUsedBytes, long heapMaxBytes, long rssBytes, long systemTotalBytes) {
            this.heapUsedBytes = Math.max(0, heapUsedBytes);
            this.heapMaxBytes = Math.max(0, heapMaxBytes);
            this.rssBytes = Math.max(0, rssBytes);
            this.systemTotalBytes = Math.max(0, systemTotalBytes);
        }

        public long getHeapUsedBytes() {
            return heapUsedBytes;
        }

        public long getHeapMaxBytes() {
            return heapMaxBytes;
        }

        public long getRssBytes() {
            return rssBytes;
        }

        public long getSystemTotalBytes() {
            return systemTotalBytes;
        }

        public boolean isAvailable() {
            return heapMaxBytes > 0 || heapUsedBytes > 0 || rssBytes > 0;
        }
    }

    private ProcessMemorySupport() {
    }

    public static ProcessJvmContext resolveJvmContext(JnlpRunningProcessSupport.RunningProcess process) {
        if (process == null) {
            return ProcessJvmContext.unknown();
        }
        String javaHome = process.getJvmHome();
        String vendor = process.getJvmVendor();
        String version = process.getJvmVersion();
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            return contextFromLockfile(javaHome.trim(), vendor, version);
        }
        return resolveJvmContext(process.getPid(), process.getCommandLine());
    }

    public static ProcessJvmContext resolveJvmContext(int pid, String commandLine) {
        String javaHome = resolveJavaHome(pid, commandLine);
        if (javaHome == null || javaHome.trim().isEmpty()) {
            return ProcessJvmContext.unknown();
        }
        return JVM_CONTEXT_CACHE.computeIfAbsent(javaHome, ProcessMemorySupport::describeJvmContext);
    }

    private static ProcessJvmContext contextFromLockfile(String javaHome, String vendor, String version) {
        String normalizedHome = javaHome.trim();
        String jcmdPath = resolveJcmdPath(normalizedHome);
        String resolvedVendor = vendor == null ? "" : vendor.trim();
        String resolvedVersion = version == null ? "" : version.trim();
        return new ProcessJvmContext(normalizedHome, jcmdPath, resolvedVendor, resolvedVersion);
    }

    public static String resolveJavaHomeFromCommandLine(String commandLine) {
        return resolveJavaHome(-1, commandLine);
    }

    public static boolean trimHeap(int pid, ProcessJvmContext jvmContext) {
        if (pid <= 0 || jvmContext == null || !jvmContext.hasJcmd()) {
            return false;
        }
        boolean ok = true;
        for (int i = 0; i < 4; i++) {
            if (!runJcmd(jvmContext.getJcmdPath(), pid, "GC.run")) {
                ok = false;
            }
        }
        return ok;
    }

    public static MemoryInfo readMemoryInfo(int pid, ProcessJvmContext jvmContext) {
        long rss = readRssBytes(pid);
        long systemTotal = readSystemMemoryBytes();
        long heapUsed = 0;
        long heapMax = 0;
        if (pid > 0 && jvmContext != null && jvmContext.hasJcmd()) {
            String jcmd = jvmContext.getJcmdPath();
            String heapInfo = runJcmdCapture(jcmd, pid, "GC.heap_info");
            if (heapInfo != null && !heapInfo.isEmpty()) {
                long[] heap = parseHeapFromHeapInfo(heapInfo);
                heapUsed = heap[0];
                heapMax = heap[1];
            }
            if (heapMax <= 0) {
                String flags = runJcmdCapture(jcmd, pid, "VM.flags");
                heapMax = parseMaxHeapFromFlags(flags);
            }
            if (heapMax <= 0 && heapUsed > 0) {
                heapMax = heapUsed;
            }
        }
        return new MemoryInfo(heapUsed, heapMax, rss, systemTotal);
    }

    private static ProcessJvmContext describeJvmContext(String javaHome) {
        JvmDescriptor descriptor = JvmDescriptor.describe(javaHome);
        String jcmdPath = resolveJcmdPath(javaHome);
        return new ProcessJvmContext(javaHome, jcmdPath, descriptor.getFlavour(), descriptor.getVersion());
    }

    private static String resolveJavaHome(int pid, String commandLine) {
        String javaExecutable = resolveJavaExecutable(pid, commandLine);
        return deriveJavaHomeFromExecutable(javaExecutable);
    }

    private static String resolveJavaExecutable(int pid, String commandLine) {
        if (commandLine != null && !commandLine.trim().isEmpty()) {
            Matcher matcher = JAVA_EXECUTABLE.matcher(commandLine);
            while (matcher.find()) {
                File candidate = new File(matcher.group(1));
                if (candidate.isFile()) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        String fromEnvironment = resolveJavaHomeFromProcessEnvironment(pid);
        if (fromEnvironment != null) {
            File javaBinary = new File(fromEnvironment + File.separator + "bin" + File.separator + "java"
                    + (JNLPRuntime.isWindows() ? ".exe" : ""));
            if (javaBinary.isFile()) {
                return javaBinary.getAbsolutePath();
            }
        }
        if (!JNLPRuntime.isWindows() && pid > 0) {
            return resolveLinuxExecutable(pid);
        }
        return null;
    }

    private static String deriveJavaHomeFromExecutable(String javaExecutable) {
        if (javaExecutable == null || javaExecutable.trim().isEmpty()) {
            return null;
        }
        File javaFile = new File(javaExecutable);
        File binDir = javaFile.getParentFile();
        if (binDir == null || !"bin".equalsIgnoreCase(binDir.getName())) {
            return null;
        }
        File home = binDir.getParentFile();
        return home == null ? null : home.getAbsolutePath();
    }

    private static String resolveLinuxExecutable(int pid) {
        try {
            Path exe = Paths.get("/proc", Integer.toString(pid), "exe");
            if (Files.exists(exe)) {
                Path target = Files.readSymbolicLink(exe);
                String path = target.toString();
                if (path.toLowerCase(Locale.ROOT).contains("java")) {
                    return path;
                }
            }
        } catch (Exception ex) {
            // Ignore.
        }
        return null;
    }

    private static String resolveJavaHomeFromProcessEnvironment(int pid) {
        if (JNLPRuntime.isWindows() || pid <= 0) {
            return null;
        }
        try {
            Path environ = Paths.get("/proc", Integer.toString(pid), "environ");
            if (!Files.isReadable(environ)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(environ);
            String data = new String(bytes, StandardCharsets.UTF_8);
            for (String entry : data.split("\u0000")) {
                if (entry.startsWith("JAVA_HOME=")) {
                    String home = entry.substring("JAVA_HOME=".length()).trim();
                    if (!home.isEmpty()) {
                        return home;
                    }
                }
            }
        } catch (Exception ex) {
            // Ignore.
        }
        return null;
    }

    private static String resolveJcmdPath(String javaHome) {
        if (javaHome == null || javaHome.trim().isEmpty()) {
            return null;
        }
        File jcmd = new File(javaHome.trim() + File.separator + "bin" + File.separator
                + "jcmd" + (JNLPRuntime.isWindows() ? ".exe" : ""));
        if (jcmd.isFile()) {
            return jcmd.getAbsolutePath();
        }
        return null;
    }

    private static boolean runJcmd(String jcmd, int pid, String command) {
        List<String> args = new ArrayList<>();
        args.add(jcmd);
        args.add(Integer.toString(pid));
        args.add(command);
        Process process = null;
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception ex) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String runJcmdCapture(String jcmd, int pid, String command) {
        List<String> args = new ArrayList<>();
        args.add(jcmd);
        args.add(Integer.toString(pid));
        args.add(command);
        Process process = null;
        try {
            process = new ProcessBuilder(args).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            process.waitFor();
            return output.toString();
        } catch (Exception ex) {
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static long[] parseHeapFromHeapInfo(String heapInfo) {
        long bestTotal = 0;
        long bestUsed = 0;
        Matcher matcher = HEAP_REGION.matcher(heapInfo);
        while (matcher.find()) {
            long total = Long.parseLong(matcher.group(1)) * 1024L;
            long used = Long.parseLong(matcher.group(2)) * 1024L;
            if (total >= bestTotal) {
                bestTotal = total;
                bestUsed = used;
            }
        }
        return new long[] { bestUsed, bestTotal };
    }

    private static long parseMaxHeapFromFlags(String flags) {
        if (flags == null || flags.isEmpty()) {
            return 0;
        }
        Matcher matcher = MAX_HEAP_FLAG.matcher(flags);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return 0;
    }

    private static long readRssBytes(int pid) {
        if (pid <= 0) {
            return 0;
        }
        if (JNLPRuntime.isWindows()) {
            return readRssBytesWindows(pid);
        }
        return readRssBytesUnix(pid);
    }

    private static long readRssBytesUnix(int pid) {
        Process process = null;
        try {
            process = new ProcessBuilder("ps", "-o", "rss=", "-p", Integer.toString(pid))
                    .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                process.waitFor();
                if (line == null || line.trim().isEmpty()) {
                    return 0;
                }
                return Long.parseLong(line.trim()) * 1024L;
            }
        } catch (Exception ex) {
            return 0;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static long readRssBytesWindows(int pid) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "wmic", "process", "where", "ProcessId=" + pid, "get", "WorkingSetSize", "/VALUE")
                    .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("WorkingSetSize=")) {
                        process.waitFor();
                        return Long.parseLong(line.substring("WorkingSetSize=".length()).trim());
                    }
                }
            }
            process.waitFor();
        } catch (Exception ex) {
            // Ignore.
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return 0;
    }

    private static long readSystemMemoryBytes() {
        if (JNLPRuntime.isWindows()) {
            return readSystemMemoryBytesWindows();
        }
        return readSystemMemoryBytesLinux();
    }

    private static long readSystemMemoryBytesLinux() {
        try {
            List<String> lines = Files.readAllLines(Paths.get("/proc/meminfo"), StandardCharsets.UTF_8);
            long memTotal = 0;
            long swapTotal = 0;
            for (String line : lines) {
                if (line.startsWith("MemTotal:")) {
                    memTotal = parseMeminfoKb(line);
                } else if (line.startsWith("SwapTotal:")) {
                    swapTotal = parseMeminfoKb(line);
                }
            }
            return (memTotal + swapTotal) * 1024L;
        } catch (Exception ex) {
            return 0;
        }
    }

    private static long parseMeminfoKb(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length >= 2) {
            return Long.parseLong(parts[1]);
        }
        return 0;
    }

    private static long readSystemMemoryBytesWindows() {
        long physical = readWmicValue("OS", "TotalVisibleMemorySize");
        long virtual = readWmicValue("OS", "TotalVirtualMemorySize");
        if (physical <= 0 && virtual <= 0) {
            return 0;
        }
        return (physical + virtual) * 1024L;
    }

    private static long readWmicValue(String alias, String property) {
        Process process = null;
        try {
            process = new ProcessBuilder("wmic", alias, "get", property, "/VALUE")
                    .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String prefix = property + "=";
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith(prefix)) {
                        process.waitFor();
                        return Long.parseLong(line.substring(prefix.length()).trim());
                    }
                }
            }
            process.waitFor();
        } catch (Exception ex) {
            // Ignore.
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return 0;
    }

    public static String formatMegabytes(long bytes) {
        if (bytes <= 0) {
            return "0 MB";
        }
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }
}
