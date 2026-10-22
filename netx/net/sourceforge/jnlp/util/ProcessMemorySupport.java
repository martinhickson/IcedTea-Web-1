package net.sourceforge.jnlp.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
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
    private static final Pattern HEAP_REGION_USED_FIRST = Pattern.compile("used\\s+(\\d+)K,\\s+total\\s+(\\d+)K",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ZHEAP = Pattern.compile(
            "ZHeap\\s+used\\s+(\\d+)([KMG])(?:\\s*,\\s*capacity\\s+(\\d+)([KMG]))?(?:\\s*,\\s*max\\s+capacity\\s+(\\d+)([KMG]))?",
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
        ProcessJvmContext context;
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            context = contextFromLockfile(javaHome.trim(), vendor, version);
        } else {
            context = resolveJvmContext(process.getPid(), process.getCommandLine());
        }
        return enrichJvmContext(process.getPid(), context);
    }

    public static ProcessJvmContext resolveJvmContext(int pid, String commandLine) {
        String javaHome = resolveJavaHome(pid, commandLine);
        ProcessJvmContext context;
        if (javaHome == null || javaHome.trim().isEmpty()) {
            context = ProcessJvmContext.unknown();
        } else {
            context = JVM_CONTEXT_CACHE.computeIfAbsent(javaHome, ProcessMemorySupport::describeJvmContext);
        }
        return enrichJvmContext(pid, context);
    }

    private static ProcessJvmContext contextFromLockfile(String javaHome, String vendor, String version) {
        String normalizedHome = JvmDescriptor.resolveToolsHome(javaHome.trim());
        String jcmdPath = resolveJcmdPath(normalizedHome);
        String resolvedVendor = vendor == null ? "" : vendor.trim();
        String resolvedVersion = version == null ? "" : version.trim();
        return new ProcessJvmContext(normalizedHome, jcmdPath, resolvedVendor, resolvedVersion);
    }

    public static String resolveJavaHomeFromCommandLine(String commandLine) {
        return resolveJavaHome(-1, commandLine);
    }

    public static boolean trimHeap(int pid, ProcessJvmContext jvmContext) {
        if (pid <= 0) {
            return false;
        }
        String jcmd = resolveEffectiveJcmd(pid, jvmContext);
        boolean ok = true;
        for (int i = 0; i < 4; i++) {
            boolean ran = jcmd != null && runJcmd(jcmd, pid, "GC.run");
            if (!ran) {
                ran = captureViaAttach(pid, "GC.run") != null;
            }
            if (!ran) {
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
        if (pid > 0) {
            String jcmd = resolveEffectiveJcmd(pid, jvmContext);
            String heapInfo = captureDiagnostic(pid, "GC.heap_info", jcmd);
            if (!heapInfo.isEmpty()) {
                long[] heap = parseHeapFromHeapInfo(heapInfo);
                heapUsed = heap[0];
                heapMax = heap[1];
            }
            if (heapMax <= 0) {
                heapMax = parseMaxHeapFromFlags(captureDiagnostic(pid, "VM.flags", jcmd));
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

    private static ProcessJvmContext enrichJvmContext(int pid, ProcessJvmContext context) {
        ProcessJvmContext resolved = context == null ? ProcessJvmContext.unknown() : context;
        String jcmd = resolveEffectiveJcmd(pid, resolved);
        String javaHome = resolved.getJavaHome();
        String vendor = resolved.getVendor();
        String version = resolved.getJvmVersion();
        boolean homeMissing = isBlank(javaHome) || resolveJcmdPath(javaHome) == null;
        boolean genericName = isBlank(vendor) || "Java".equals(vendor);
        if (homeMissing || genericName || isBlank(version)) {
            ProcessJvmContext fromProcess = parseJcmdSystemProperties(
                    captureDiagnostic(pid, "VM.system_properties", jcmd));
            if (homeMissing && !isBlank(fromProcess.getJavaHome())) {
                javaHome = fromProcess.getJavaHome();
            }
            if (genericName && !isBlank(fromProcess.getVendor())) {
                vendor = fromProcess.getVendor();
            }
            if ((isBlank(version) || genericName) && !isBlank(fromProcess.getJvmVersion())) {
                version = fromProcess.getJvmVersion();
            }
        }
        return new ProcessJvmContext(javaHome, jcmd, vendor, version);
    }

    /**
     * Identity carried by {@code jcmd VM.system_properties}. The heap read already
     * attaches with jcmd; this is the same output, keeping vendor and version as
     * well as {@code java.home}.
     */
    static ProcessJvmContext parseJcmdSystemProperties(String output) {
        Map<String, String> properties = new HashMap<>();
        if (output != null) {
            for (String line : output.split("\n")) {
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = unescapeJcmdProperty(line.substring(separator + 1).trim());
                if (!key.isEmpty() && value != null && !value.isEmpty()) {
                    properties.put(key, value);
                }
            }
        }
        String home = properties.get("java.home");
        String flavour = "";
        if (properties.containsKey("java.runtime.name")
                || properties.containsKey("java.vm.name")
                || properties.containsKey("java.vendor")
                || properties.containsKey("java.vm.vendor")) {
            String combined = (blankToEmpty(properties.get("java.runtime.name")) + " "
                    + blankToEmpty(properties.get("java.runtime.version")) + " "
                    + blankToEmpty(properties.get("java.vm.name")) + " "
                    + blankToEmpty(properties.get("java.vm.vendor")) + " "
                    + blankToEmpty(properties.get("java.vendor")) + " "
                    + blankToEmpty(properties.get("java.vendor.version"))).toLowerCase(Locale.ROOT);
            flavour = JvmDescriptor.detectFlavour(combined);
        }
        String version = firstNonBlank(
                properties.get("java.version"),
                properties.get("java.vm.version"),
                properties.get("java.specification.version"));
        return new ProcessJvmContext(blankToNull(home), null, flavour, version);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String resolveEffectiveJcmd(int pid, ProcessJvmContext jvmContext) {
        if (jvmContext != null && jvmContext.hasJcmd()) {
            return jvmContext.getJcmdPath();
        }
        if (jvmContext != null && jvmContext.getJavaHome() != null && !jvmContext.getJavaHome().trim().isEmpty()) {
            String fromHome = resolveJcmdPath(jvmContext.getJavaHome());
            if (fromHome != null) {
                return fromHome;
            }
        }
        return discoverFallbackJcmd();
    }

    private static String discoverFallbackJcmd() {
        String fromRuntime = resolveJcmdPath(System.getProperty("java.home"));
        if (fromRuntime != null) {
            return fromRuntime;
        }
        for (String home : JvmAutodetector.discoverCandidateHomes()) {
            String jcmd = resolveJcmdPath(home);
            if (jcmd != null) {
                return jcmd;
            }
        }
        if (JNLPRuntime.isWindows()) {
            return discoverJcmdFromPath();
        }
        return null;
    }

    private static String discoverJcmdFromPath() {
        Process process = null;
        try {
            process = new ProcessBuilder("where.exe", "jcmd").redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    File candidate = new File(line);
                    if (candidate.isFile()) {
                        process.waitFor();
                        return candidate.getAbsolutePath();
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
        return null;
    }

    private static String unescapeJcmdProperty(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.replace("\\:", ":").replace("\\\\", "\\");
    }

    private static String resolveJcmdPath(String javaHome) {
        String toolsHome = JvmDescriptor.resolveToolsHome(javaHome);
        if (toolsHome == null || toolsHome.isEmpty()) {
            return null;
        }
        File jcmd = new File(toolsHome, "bin" + File.separator + "jcmd"
                + (JNLPRuntime.isWindows() ? ".exe" : ""));
        return jcmd.isFile() ? jcmd.getAbsolutePath() : null;
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
            if (process.exitValue() != 0) {
                return "";
            }
            return output.toString();
        } catch (Exception ex) {
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /**
     * Read a diagnostic command. {@code jcmd} is used when that binary exists.
     * A JRE image has no {@code jcmd}; the control panel JDK then attaches to the
     * target and runs the same command inside that process.
     */
    static String captureDiagnostic(int pid, String command, String jcmd) {
        if (pid <= 0 || command == null || command.isEmpty()) {
            return "";
        }
        if (jcmd != null && !jcmd.isEmpty()) {
            String fromTool = runJcmdCapture(jcmd, pid, command);
            if (!fromTool.isEmpty()) {
                return fromTool;
            }
        }
        String attached = captureViaAttach(pid, command);
        return attached == null ? "" : attached;
    }

    /**
     * @return command output, empty when the command succeeded with no text, or
     *         {@code null} when attach could not run
     */
    private static String captureViaAttach(int pid, String command) {
        Object vm = null;
        try {
            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            vm = vmClass.getMethod("attach", String.class).invoke(null, Integer.toString(pid));
            java.lang.reflect.Method execute = vm.getClass().getMethod("executeJCmd", String.class);
            try (InputStream in = (InputStream) execute.invoke(vm, command)) {
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    raw.write(buf, 0, n);
                }
                return new String(raw.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Throwable ex) {
            return null;
        } finally {
            if (vm != null) {
                try {
                    vm.getClass().getMethod("detach").invoke(vm);
                } catch (Throwable ignored) {
                    // The target keeps running if detach fails.
                }
            }
        }
    }

    static long[] parseHeapFromHeapInfo(String heapInfo) {
        if (heapInfo == null || heapInfo.isEmpty()) {
            return new long[] { 0L, 0L };
        }
        Matcher zheap = ZHEAP.matcher(heapInfo);
        if (zheap.find()) {
            long used = scaledHeapUnit(zheap.group(1), zheap.group(2));
            long max = 0L;
            if (zheap.group(5) != null) {
                max = scaledHeapUnit(zheap.group(5), zheap.group(6));
            } else if (zheap.group(3) != null) {
                max = scaledHeapUnit(zheap.group(3), zheap.group(4));
            }
            return new long[] { used, max };
        }
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
        if (bestTotal <= 0) {
            matcher = HEAP_REGION_USED_FIRST.matcher(heapInfo);
            while (matcher.find()) {
                long used = Long.parseLong(matcher.group(1)) * 1024L;
                long total = Long.parseLong(matcher.group(2)) * 1024L;
                if (total >= bestTotal) {
                    bestTotal = total;
                    bestUsed = used;
                }
            }
        }
        return new long[] { bestUsed, bestTotal };
    }

    private static long scaledHeapUnit(String amount, String unit) {
        long value = Long.parseLong(amount);
        if (unit == null) {
            return value;
        }
        switch (unit.toUpperCase(Locale.ROOT)) {
            case "G":
                return value * 1024L * 1024L * 1024L;
            case "M":
                return value * 1024L * 1024L;
            case "K":
                return value * 1024L;
            default:
                return value;
        }
    }

    static long parseMaxHeapFromFlags(String flags) {
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
        long fromWmic = readWmicLongProperty(
                new String[] {
                        "wmic", "process", "where", "ProcessId=" + pid, "get", "WorkingSetSize", "/VALUE" },
                "WorkingSetSize");
        if (fromWmic > 0) {
            return fromWmic;
        }
        return readWorkingSetFromPowerShell(pid);
    }

    private static long readWorkingSetFromPowerShell(int pid) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "(Get-Process -Id " + pid + " -ErrorAction SilentlyContinue).WorkingSet64")
                    .redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            process.waitFor();
            return parsePowerShellLong(output);
        } catch (Exception ex) {
            return 0;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
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
        long physical = readPhysicalMemoryFromRuntime();
        if (physical > 0) {
            return physical;
        }
        long kilobytes = readWmicLongProperty(
                new String[] { "wmic", "OS", "get", "TotalVisibleMemorySize", "/VALUE" },
                "TotalVisibleMemorySize");
        return kilobytes > 0 ? kilobytes * 1024L : 0;
    }

    static long readPhysicalMemoryFromRuntime() {
        try {
            Class<?> type = Class.forName("com.sun.management.OperatingSystemMXBean");
            Object bean = ManagementFactory.getOperatingSystemMXBean();
            if (!type.isInstance(bean)) {
                return 0;
            }
            Object value = type.getMethod("getTotalPhysicalMemorySize").invoke(bean);
            if (value instanceof Number) {
                return Math.max(0L, ((Number) value).longValue());
            }
        } catch (ReflectiveOperationException ex) {
            return 0;
        }
        return 0;
    }

    private static long readWmicLongProperty(String[] command, String property) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            process.waitFor();
            return parseWmicLongProperty(output, property);
        } catch (Exception ex) {
            return 0;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    static long parseWmicLongProperty(byte[] output, String property) {
        if (output == null || output.length == 0 || property == null || property.isEmpty()) {
            return 0;
        }
        String prefix = property + "=";
        for (String line : decodeWindowsToolOutput(output).split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(prefix)) {
                continue;
            }
            try {
                return Long.parseLong(trimmed.substring(prefix.length()).trim());
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
        return 0;
    }

    static long parsePowerShellLong(byte[] output) {
        String text = decodeWindowsToolOutput(output).trim();
        if (text.isEmpty()) {
            return 0;
        }
        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                return Long.parseLong(line);
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * {@code wmic} and Windows PowerShell 5 write UTF-16LE when stdout is a pipe.
     * Those bytes read as UTF-8 never contain {@code WorkingSetSize=}, so the
     * Memory bar reports the working set as unavailable.
     */
    static String decodeWindowsToolOutput(byte[] output) {
        if (output == null || output.length == 0) {
            return "";
        }
        int offset = 0;
        if (output.length >= 2 && (output[0] & 0xFF) == 0xFF && (output[1] & 0xFF) == 0xFE) {
            offset = 2;
        }
        int length = output.length - offset;
        if (length <= 0) {
            return "";
        }
        if (offset == 2 || looksLikeUtf16Le(output, offset, length)) {
            return new String(output, offset, length, StandardCharsets.UTF_16LE);
        }
        return new String(output, offset, length, StandardCharsets.UTF_8);
    }

    private static boolean looksLikeUtf16Le(byte[] output, int offset, int length) {
        int zeros = 0;
        int samples = 0;
        int end = offset + Math.min(length, 64);
        for (int i = offset + 1; i < end; i += 2) {
            samples++;
            if (output[i] == 0) {
                zeros++;
            }
        }
        return samples >= 4 && zeros * 4 >= samples * 3;
    }

    public static String formatMegabytes(long bytes) {
        if (bytes <= 0) {
            return "0 MB";
        }
        double mb = bytes / (1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }
}
