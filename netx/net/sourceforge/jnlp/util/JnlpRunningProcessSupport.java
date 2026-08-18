package net.sourceforge.jnlp.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import net.sourceforge.jnlp.cache.CacheUtil;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.runtime.JNLPRuntime;

/**
 * Detects running IcedTea-Web JNLP JVMs from lock files, then correlates with {@code ps}
 * or {@code tasklist} for command-line details.
 */
public final class JnlpRunningProcessSupport {

    public static final class RunningProcess {
        private final int pid;
        private final String appTitle;
        private final String appVersion;
        private final String commandLine;
        private final String jnlpPath;
        private final String jvmHome;
        private final String jvmVendor;
        private final String jvmVersion;
        private String processStart;

        public RunningProcess(int pid, String appTitle, String commandLine) {
            this(pid, appTitle, null, commandLine, null, null, null, null);
        }

        public RunningProcess(int pid, String appTitle, String appVersion, String commandLine, String jnlpPath) {
            this(pid, appTitle, appVersion, commandLine, jnlpPath, null, null, null);
        }

        public RunningProcess(int pid, String appTitle, String appVersion, String commandLine, String jnlpPath,
                String jvmHome, String jvmVendor, String jvmVersion) {
            this.pid = pid;
            this.appTitle = appTitle;
            this.appVersion = appVersion;
            this.commandLine = commandLine == null ? "" : commandLine;
            this.jnlpPath = jnlpPath;
            this.jvmHome = jvmHome;
            this.jvmVendor = jvmVendor;
            this.jvmVersion = jvmVersion;
        }

        public int getPid() {
            return pid;
        }

        public String getAppTitle() {
            return appTitle;
        }

        public String getAppVersion() {
            return appVersion;
        }

        public String getCommandLine() {
            return commandLine;
        }

        public String getJnlpPath() {
            return jnlpPath;
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

        public String getProcessStart() {
            return processStart;
        }

        public void setProcessStart(String processStart) {
            this.processStart = processStart;
        }

        public String getDisplayName() {
            return JnlpLockMetadata.formatDisplayName(appTitle, appVersion, jnlpPath);
        }

        public String getShortName() {
            return getDisplayName();
        }

        public boolean matchesJnlpPath(String filter) {
            if (filter == null || filter.trim().isEmpty()) {
                return true;
            }
            if (jnlpPath != null && pathsMatch(jnlpPath, filter)) {
                return true;
            }
            return pathsMatch(commandLine, filter);
        }

        /**
         * True when {@code -Xclearcache cacheId} would remove this process's
         * resources. JAR hrefs from {@code -Xcacheids} never appear on the
         * command line; they still share the JNLP's cache directory.
         * Filename-only matching is intentionally not used ({@code app.jnlp}
         * would otherwise collide across unrelated apps).
         */
        public boolean blocksCacheClear(String cacheId) {
            if (cacheId == null || cacheId.trim().isEmpty()) {
                return true;
            }
            String id = cacheId.trim();
            String runningJnlp = jnlpPath;
            if (runningJnlp == null || runningJnlp.trim().isEmpty()) {
                runningJnlp = JnlpLockMetadata.extractJnlpPathFromCommandLine(commandLine);
            }
            if (runningJnlp != null && runningJnlp.trim().equalsIgnoreCase(id)) {
                return true;
            }
            if (CacheUtil.cacheIdSharesDirectoryWithJnlp(id, runningJnlp)) {
                return true;
            }
            return CacheUtil.cacheIdIsRunningJnlpHost(id, runningJnlp);
        }

        private static boolean pathsMatch(String haystackRaw, String needleRaw) {
            String haystack = haystackRaw.toLowerCase(Locale.ROOT);
            String needle = needleRaw.trim().toLowerCase(Locale.ROOT);
            if (haystack.contains(needle)) {
                return true;
            }
            int slash = Math.max(needle.lastIndexOf('/'), needle.lastIndexOf('\\'));
            if (slash >= 0 && slash < needle.length() - 1) {
                String fileName = needle.substring(slash + 1);
                if (!fileName.isEmpty() && haystack.contains(fileName)) {
                    return true;
                }
            }
            int scheme = needle.indexOf("://");
            if (scheme > 0) {
                String withoutScheme = needle.substring(scheme + 3);
                if (!withoutScheme.isEmpty() && haystack.contains(withoutScheme)) {
                    return true;
                }
            }
            return false;
        }
    }

    private JnlpRunningProcessSupport() {
    }

    public static int currentPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        if (at <= 0) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(0, at));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public static List<RunningProcess> listRunningJnlpProcesses() {
        return listRunningJnlpProcesses(null);
    }

    /**
     * {@code -Xclearcache} / cache UI must wait when a running JNLP app would
     * lose cache files. Pass {@code null} for a global clear (any app).
     * Do not match only on {@code cacheId} via {@link RunningProcess#matchesJnlpPath}:
     * {@code -Xcacheids} JAR hrefs never appear in lock metadata or the command
     * line, so that filter used to skip the busy guard.
     */
    public static boolean cacheClearBlockedByRunningApps(String cacheId) {
        List<RunningProcess> running = listRunningJnlpProcessesForCacheClear();
        if (cacheId == null || cacheId.trim().isEmpty()) {
            return !running.isEmpty();
        }
        for (RunningProcess process : running) {
            if (process.blocksCacheClear(cacheId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Same as {@link #listRunningJnlpProcesses()} but does not treat ancestor
     * PIDs as self. A JDK-relaunch child can otherwise hide the running app
     * when {@code -Xclearcache} is started under the same launcher tree.
     */
    public static List<RunningProcess> listRunningJnlpProcessesForCacheClear() {
        Map<Integer, RunningProcess> byPid = new LinkedHashMap<>();
        collectRunningJnlpProcesses(byPid, null, false);
        mergeCatalogRunningApps(byPid);
        return new ArrayList<>(byPid.values());
    }

    public static List<RunningProcess> listRunningJnlpProcesses(String jnlpPathFilter) {
        Map<Integer, RunningProcess> byPid = new LinkedHashMap<>();
        collectRunningJnlpProcesses(byPid, jnlpPathFilter, true);
        return new ArrayList<>(byPid.values());
    }

    private static void collectRunningJnlpProcesses(Map<Integer, RunningProcess> byPid,
            String jnlpPathFilter, boolean includeAncestorsInSelfTree) {
        int selfPid = currentPid();
        Set<Long> selfTree = selfTreePids(includeAncestorsInSelfTree);

        collectFromLockFiles(byPid, selfPid);
        collectFromProcessListing(byPid, selfPid);

        List<Integer> drop = new ArrayList<>();
        for (RunningProcess process : byPid.values()) {
            if (selfTree.contains((long) process.getPid())
                    || isInfrastructureProcess(process)
                    || (jnlpPathFilter != null && !jnlpPathFilter.trim().isEmpty()
                    && !process.matchesJnlpPath(jnlpPathFilter))) {
                drop.add(process.getPid());
            }
        }
        for (Integer pid : drop) {
            byPid.remove(pid);
        }
    }

    private static void mergeCatalogRunningApps(Map<Integer, RunningProcess> byPid) {
        int selfPid = currentPid();
        List<net.sourceforge.jnlp.cache.CacheRunningApp> leases;
        try {
            leases = net.sourceforge.jnlp.cache.CacheLRUWrapper.getInstance().listRunningApps();
        } catch (Exception e) {
            return;
        }
        for (net.sourceforge.jnlp.cache.CacheRunningApp lease : leases) {
            // Catalog leases are explicit. Do not drop them as "self tree"
            // descendants — a test (or a relaunch child) may be a child PID.
            if (lease.pid <= 0 || lease.pid == selfPid) {
                continue;
            }
            if (!isProcessAlive(lease.pid)) {
                try {
                    net.sourceforge.jnlp.cache.CacheLRUWrapper.getInstance().unregisterRunningApp(lease.pid);
                } catch (Exception ignored) {
                }
                continue;
            }
            RunningProcess existing = byPid.get(lease.pid);
            if (existing != null && existing.getJnlpPath() != null && !existing.getJnlpPath().trim().isEmpty()) {
                continue;
            }
            String commandLine = existing != null ? existing.getCommandLine() : resolveCommandLine(lease.pid);
            String title = existing != null ? existing.getAppTitle() : null;
            if (isInfrastructureProcess(commandLine, title, lease.jnlpPath)) {
                continue;
            }
            byPid.put(lease.pid, new RunningProcess(lease.pid, title,
                    existing != null ? existing.getAppVersion() : null, commandLine, lease.jnlpPath));
        }
    }

    static Set<Long> selfTreePids() {
        return selfTreePids(true);
    }

    static Set<Long> selfTreePids(boolean includeAncestors) {
        Set<Long> s = new HashSet<>();
        try {
            ProcessHandle current = ProcessHandle.current();
            s.add(current.pid());
            current.descendants().forEach(h -> s.add(h.pid()));
            if (includeAncestors) {
                ProcessHandle p = current.parent().orElse(null);
                while (p != null) {
                    s.add(p.pid());
                    p = p.parent().orElse(null);
                }
            }
        } catch (Exception ignored) {}
        return s;
    }

    public static boolean isInfrastructureProcess(RunningProcess process) {
        if (process == null) {
            return true;
        }
        return isInfrastructureProcess(process.getCommandLine(), process.getAppTitle(), process.getJnlpPath());
    }

    public static boolean isInfrastructureProcess(String commandLine, String appTitle, String jnlpPath) {
        if (isSettingsProcess(commandLine) || isSettingsProcess(appTitle) || isSettingsProcess(jnlpPath)) {
            return true;
        }
        if (appTitle != null) {
            String titleLower = appTitle.trim().toLowerCase(Locale.ROOT);
            if (titleLower.contains("icedtea-web control panel")
                    || titleLower.contains("icedtea control panel")
                    || titleLower.contains("policy editor")
                    || titleLower.equals("icedtea-web systemsteuerung")
                    || titleLower.equals("panel sterowania icedtea-web")) {
                return true;
            }
        }
        if (jnlpPath != null && !jnlpPath.trim().isEmpty()) {
            return false;
        }
        if (commandLine == null || commandLine.trim().isEmpty()) {
            return appTitle == null || appTitle.trim().isEmpty();
        }
        String lower = commandLine.toLowerCase(Locale.ROOT);
        if (lower.contains("-xclearcache") || lower.contains("-xlistcacheids")) {
            return true;
        }
        return isSettingsProcess(lower)
                || lower.contains("icedtea-web.bin.name=policyeditor")
                || (lower.contains("icedtea-web-uber")
                && !lower.contains(".jnlp")
                && (lower.contains("controlpanel") || lower.contains("policyeditor")));
    }

    public static boolean stopProcess(int pid, boolean force) {
        return stopProcess(pid, null, force);
    }

    public static boolean stopProcess(int pid, String recordedStart, boolean force) {
        if (pid <= 0) {
            return false;
        }
        if (recordedStart != null && !isSameProcess(pid, recordedStart)) {
            return false;
        }
        List<String> command = new ArrayList<>();
        if (JNLPRuntime.isWindows()) {
            command.add("taskkill");
            if (force) {
                command.add("/F");
            }
            command.add("/PID");
            command.add(Integer.toString(pid));
        } else {
            command.add("kill");
            if (force) {
                command.add("-9");
            }
            command.add(Integer.toString(pid));
        }
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static void collectFromLockFiles(Map<Integer, RunningProcess> byPid, int selfPid) {
        File locksDir = PathsAndFiles.LOCKS_DIR.getFile();
        if (locksDir.isDirectory()) {
            File[] files = locksDir.listFiles();
            if (files != null) {
                File mainLock = PathsAndFiles.MAIN_LOCK.getFile();
                File runningDetails = NetxRunningDetailsRegistry.getDetailsFile();
                for (File lockFile : files) {
                    if (!lockFile.isFile() || lockFile.equals(mainLock) || lockFile.equals(runningDetails)) {
                        continue;
                    }
                    RunningProcess running = processFromLockFile(lockFile, selfPid);
                    if (running != null && !isInfrastructureProcess(running)) {
                        byPid.put(running.getPid(), running);
                    }
                }
            }
        }

        collectFromRunningDetails(byPid, selfPid);
    }

    private static void collectFromRunningDetails(Map<Integer, RunningProcess> byPid, int selfPid) {
        for (JnlpLockMetadata.ProcessEntry entry : NetxRunningDetailsRegistry.listRegisteredProcesses()) {
            int pid = entry.getProcessId();
            if (pid <= 0 || pid == selfPid || byPid.containsKey(pid)) {
                continue;
            }
            if (!isProcessAlive(pid)) {
                NetxRunningDetailsRegistry.unregisterProcess(pid);
                continue;
            }
            if (entry.getProcessStart() != null && !isSameProcess(pid, entry.getProcessStart())) {
                NetxRunningDetailsRegistry.unregisterProcess(pid);
                continue;
            }
            String commandLine = resolveCommandLine(pid);
            if (!isLikelyJnlpProcess(commandLine)) {
                if (commandLine != null && !commandLine.trim().isEmpty()) {
                    NetxRunningDetailsRegistry.unregisterProcess(pid);
                    continue;
                }
                if (entry.getJnlpPath() == null || entry.getJnlpPath().trim().isEmpty()) {
                    NetxRunningDetailsRegistry.unregisterProcess(pid);
                    continue;
                }
            }
            RunningProcess running = toRunningProcess(pid, commandLine, entry);
            if (isInfrastructureProcess(running)) {
                NetxRunningDetailsRegistry.unregisterProcess(pid);
                continue;
            }
            byPid.put(pid, running);
        }
    }

    private static RunningProcess processFromLockFile(File lockFile, int selfPid) {
        JnlpLockMetadata metadata = JnlpLockMetadata.read(lockFile);
        int pid = metadata.getProcessId();

        if (pid <= 0 && metadata.getPort() != JnlpLockMetadata.INVALID_PORT) {
            pid = resolvePidFromPort(metadata.getPort());
        }
        if (pid <= 0 || pid == selfPid) {
            return null;
        }

        if (!isProcessAlive(pid)) {
            cleanupStaleLockFile(lockFile);
            return null;
        }

        if (metadata.getProcessStart() != null && !isSameProcess(pid, metadata.getProcessStart())) {
            cleanupStaleLockFile(lockFile);
            return null;
        }

        if (metadata.getPort() != JnlpLockMetadata.INVALID_PORT && isPortFree(metadata.getPort())) {
            cleanupStaleLockFile(lockFile);
            return null;
        }

        String commandLine = resolveCommandLine(pid);
        if (!isLikelyJnlpProcess(commandLine) && metadata.getJnlpPath() == null) {
            return null;
        }

        RunningProcess running = toRunningProcess(pid, commandLine, metadata);
        if (isInfrastructureProcess(running)) {
            return null;
        }
        return running;
    }

    private static RunningProcess toRunningProcess(int pid, String commandLine, JnlpLockMetadata metadata) {
        RunningProcess rp = toRunningProcess(pid, commandLine, metadata.getJnlpPath(), metadata.getAppTitle(),
                metadata.getAppVersion(), metadata.getJarVersion(),
                metadata.getJvmHome(), metadata.getJvmVendor(), metadata.getJvmVersion());
        rp.setProcessStart(metadata.getProcessStart());
        return rp;
    }

    private static RunningProcess toRunningProcess(int pid, String commandLine, JnlpLockMetadata.ProcessEntry entry) {
        RunningProcess rp = toRunningProcess(pid, commandLine, entry.getJnlpPath(), entry.getAppTitle(), entry.getAppVersion(),
                entry.getJarVersion(), entry.getJvmHome(), entry.getJvmVendor(), entry.getJvmVersion());
        rp.setProcessStart(entry.getProcessStart());
        return rp;
    }

    private static RunningProcess toRunningProcess(int pid, String commandLine, String jnlpPath,
            String appTitle, String appVersion, String jarVersion,
            String jvmHome, String jvmVendor, String jvmVersion) {
        String resolvedJnlpPath = jnlpPath;
        if (resolvedJnlpPath == null || resolvedJnlpPath.trim().isEmpty()) {
            resolvedJnlpPath = JnlpLockMetadata.extractJnlpPathFromCommandLine(commandLine);
        }
        String resolvedTitle = appTitle;
        String resolvedVersion = resolveDisplayVersion(appVersion, jarVersion);
        if (resolvedTitle == null || resolvedTitle.trim().isEmpty()
                || resolvedVersion == null || resolvedVersion.trim().isEmpty()) {
            JnlpLockMetadata.ProcessEntry resolved = JnlpLockMetadata.resolveApplicationInfo(resolvedJnlpPath);
            if (resolvedTitle == null || resolvedTitle.trim().isEmpty()) {
                resolvedTitle = resolved.getAppTitle();
            }
            if (resolvedVersion == null || resolvedVersion.trim().isEmpty()) {
                resolvedVersion = resolveDisplayVersion(resolved.getAppVersion(), resolved.getJarVersion());
            }
        }
        return new RunningProcess(pid, resolvedTitle, resolvedVersion, commandLine, resolvedJnlpPath,
                jvmHome, jvmVendor, jvmVersion);
    }

    private static String resolveDisplayVersion(String appVersion, String jarVersion) {
        String normalizedApp = JnlpLockMetadata.normalizeConcreteVersion(appVersion);
        if (normalizedApp != null) {
            return normalizedApp;
        }
        return JnlpLockMetadata.normalizeConcreteVersion(jarVersion);
    }

    private static void collectFromProcessListing(Map<Integer, RunningProcess> byPid, int selfPid) {
        if (JNLPRuntime.isWindows()) {
            collectFromTasklist(byPid, selfPid);
        } else {
            collectFromPs(byPid, selfPid);
        }
    }

    private static void collectFromPs(Map<Integer, RunningProcess> byPid, int selfPid) {
        Process process = null;
        try {
            process = new ProcessBuilder("ps", "-eo", "pid,args").redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    RunningProcess running = parsePsLine(line, selfPid);
                    if (running != null) {
                        putIfRicher(byPid, running);
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
    }

    private static void collectFromTasklist(Map<Integer, RunningProcess> byPid, int selfPid) {
        Process process = null;
        try {
            process = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH").redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    RunningProcess running = parseTasklistLine(line, selfPid);
                    if (running != null) {
                        putIfRicher(byPid, running);
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
    }

    private static RunningProcess parsePsLine(String line, int selfPid) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("PID")) {
            return null;
        }
        int space = trimmed.indexOf(' ');
        if (space <= 0) {
            return null;
        }
        int pid;
        try {
            pid = Integer.parseInt(trimmed.substring(0, space).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        if (pid == selfPid) {
            return null;
        }
        String commandLine = trimmed.substring(space + 1).trim();
        if (!isLikelyJnlpProcess(commandLine) || isInfrastructureProcess(commandLine, null, null)) {
            return null;
        }
        return new RunningProcess(pid, JnlpLockMetadata.shortNameFromCommandLine(commandLine),
                null, commandLine, JnlpLockMetadata.extractJnlpPathFromCommandLine(commandLine));
    }

    private static RunningProcess parseTasklistLine(String line, int selfPid) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        String[] fields = parseCsvLine(line);
        if (fields.length < 2) {
            return null;
        }
        int pid;
        try {
            pid = Integer.parseInt(fields[1].trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        if (pid == selfPid) {
            return null;
        }
        String imageName = fields[0].trim();
        if (!isLikelyJnlpImage(imageName)) {
            return null;
        }
        String commandLine = resolveCommandLine(pid);
        if (commandLine.isEmpty()) {
            commandLine = imageName;
        }
        if (isInfrastructureProcess(commandLine, null, JnlpLockMetadata.extractJnlpPathFromCommandLine(commandLine))) {
            return null;
        }
        return toRunningProcess(pid, commandLine,
                JnlpLockMetadata.extractJnlpPathFromCommandLine(commandLine), null, null, null, null, null, null);
    }

    private static void putIfRicher(Map<Integer, RunningProcess> byPid, RunningProcess running) {
        RunningProcess existing = byPid.get(running.getPid());
        if (existing == null) {
            byPid.put(running.getPid(), running);
            return;
        }
        boolean existingHasJnlp = existing.getJnlpPath() != null && !existing.getJnlpPath().trim().isEmpty();
        boolean incomingHasJnlp = running.getJnlpPath() != null && !running.getJnlpPath().trim().isEmpty();
        if (!existingHasJnlp && incomingHasJnlp) {
            byPid.put(running.getPid(), running);
        }
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[fields.size()]);
    }

    private static String resolveCommandLine(int pid) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isPresent()) {
            Optional<String> commandLine = handle.get().info().commandLine();
            if (commandLine.isPresent() && !commandLine.get().trim().isEmpty()) {
                return commandLine.get();
            }
        }
        if (JNLPRuntime.isWindows()) {
            return commandLineFromWmic(pid);
        }
        return commandLineFromPs(pid);
    }

    private static String commandLineFromPs(int pid) {
        Process process = null;
        try {
            process = new ProcessBuilder("ps", "-p", Integer.toString(pid), "-o", "args=")
                    .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                process.waitFor();
                return line == null ? "" : line.trim();
            }
        } catch (Exception ex) {
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String commandLineFromWmic(int pid) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "wmic", "process", "where", "ProcessId=" + pid, "get", "CommandLine", "/FORMAT:VALUE")
                    .redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("CommandLine=")) {
                        process.waitFor();
                        return line.substring("CommandLine=".length()).trim();
                    }
                }
            }
            process.waitFor();
        } catch (Exception ex) {
            // Fall through to tasklist image name.
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return "";
    }

    private static int resolvePidFromPort(int port) {
        if (port == JnlpLockMetadata.INVALID_PORT || port <= 0) {
            return -1;
        }
        if (JNLPRuntime.isWindows()) {
            return resolvePidFromPortWindows(port);
        }
        return resolvePidFromPortUnix(port);
    }

    private static int resolvePidFromPortUnix(int port) {
        Process process = null;
        try {
            process = new ProcessBuilder("ss", "-ltnp").redirectErrorStream(true).start();
            String portToken = ":" + port;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.contains(portToken) || !line.contains("pid=")) {
                        continue;
                    }
                    int pidIndex = line.indexOf("pid=");
                    int comma = line.indexOf(',', pidIndex);
                    String pidText = comma > pidIndex
                            ? line.substring(pidIndex + 4, comma)
                            : line.substring(pidIndex + 4);
                    return Integer.parseInt(pidText.trim());
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
        return -1;
    }

    private static int resolvePidFromPortWindows(int port) {
        Process process = null;
        try {
            process = new ProcessBuilder("netstat", "-ano").redirectErrorStream(true).start();
            String portToken = ":" + port;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.contains(portToken) || !line.toUpperCase(Locale.ROOT).contains("LISTENING")) {
                        continue;
                    }
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length == 0) {
                        continue;
                    }
                    return Integer.parseInt(parts[parts.length - 1]);
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
        return -1;
    }

    private static boolean isProcessAlive(int pid) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        return handle.isPresent() && handle.get().isAlive();
    }

    static boolean isSameProcess(int pid, String recordedStart) {
        if (recordedStart == null || recordedStart.trim().isEmpty()) {
            return false; // unverified — treat as not-same (don't show, don't kill)
        }
        Optional<java.time.Instant> currentStart = ProcessHandle.of(pid)
                .flatMap(h -> h.info().startInstant());
        return currentStart.isPresent() && currentStart.get().toString().equals(recordedStart.trim());
    }

    private static boolean isLockHeldByAnotherProcess(File lockFile) {
        try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw")) {
            FileChannel channel = raf.getChannel();
            FileLock lock = channel.tryLock();
            if (lock != null) {
                lock.release();
                return false;
            }
            return true;
        } catch (Exception ex) {
            return true;
        }
    }

    private static void cleanupStaleLockFile(File lockFile) {
        if (!lockFile.isFile()) {
            return;
        }
        if (isLockHeldByAnotherProcess(lockFile)) {
            return;
        }
        lockFile.delete();
    }

    private static boolean isPortFree(int port) {
        try {
            java.net.ServerSocket socket = new java.net.ServerSocket(port);
            socket.close();
            return true;
        } catch (java.net.BindException ex) {
            return false;
        } catch (Exception ex) {
            return true;
        }
    }

    private static boolean isLikelyJnlpImage(String imageName) {
        if (imageName == null) {
            return false;
        }
        String lower = imageName.toLowerCase(Locale.ROOT);
        if (isSettingsProcess(lower)) {
            return false;
        }
        return lower.contains("java") || lower.contains("javaws") || lower.contains("icedtea");
    }

    private static boolean isLikelyJnlpProcess(String commandLine) {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            return false;
        }
        String lower = commandLine.toLowerCase(Locale.ROOT);
        return lower.contains("icedtea-web-uber")
                || lower.contains("net.sourceforge.jnlp.runtime.")
                || lower.contains("net.sourceforge.jnlp.launcher")
                || lower.contains(".jnlp");
    }

    private static boolean isSettingsProcess(String commandLine) {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            return false;
        }
        String lower = commandLine.toLowerCase(Locale.ROOT);
        return lower.contains("controlpanel.commandline")
                || lower.contains("controlpanel.controlpanel")
                || lower.contains("policyeditor.policyeditor")
                || lower.contains("icedtea-web-settings")
                || lower.contains("icedtea_web_settings")
                || lower.contains("itweb-settings")
                || lower.contains("itwsettings")
                || lower.contains("icedtea-web.bin.name=icedtea-web-settings")
                || lower.contains("icedtea-web.bin.name=itweb-settings")
                || lower.contains("icedtea-web.bin.name=policyeditor");
    }
}
