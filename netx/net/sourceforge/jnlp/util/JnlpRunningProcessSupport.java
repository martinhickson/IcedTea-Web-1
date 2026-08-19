package net.sourceforge.jnlp.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.sourceforge.jnlp.cache.CacheRunningApp;
import net.sourceforge.jnlp.cache.CacheUtil;
import net.sourceforge.jnlp.runtime.JNLPRuntime;

/**
 * Running JNLP JVMs from the cache catalog {@code running_app} table only.
 * Command lines are resolved for a known catalog PID; the OS process list is
 * not scanned for extra PIDs.
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
        try {
            return cacheClearBlockedByRunningApps(cacheId, listCatalogRunningApps(null), false);
        } catch (CatalogUnavailableException e) {
            return true;
        }
    }

    /**
     * {@code catalogUnavailable} means {@code running_app} could not be read.
     * Cache clear must not proceed — an empty list is not "no apps running".
     */
    static boolean cacheClearBlockedByRunningApps(String cacheId, List<RunningProcess> running,
            boolean catalogUnavailable) {
        if (catalogUnavailable) {
            return true;
        }
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
     * Catalog {@code running_app} only — the JVMs this runtime registered.
     * No {@code tasklist}/{@code ps} scrape (those are not a subset of what
     * ITW started). Dead PIDs are dropped from the catalog.
     */
    public static List<RunningProcess> listRunningJnlpProcessesForCacheClear() {
        try {
            return listCatalogRunningApps(null);
        } catch (CatalogUnavailableException e) {
            return new ArrayList<>();
        }
    }

    public static List<RunningProcess> listRunningJnlpProcesses(String jnlpPathFilter) {
        try {
            return listCatalogRunningApps(jnlpPathFilter);
        } catch (CatalogUnavailableException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Upper bound is {@link net.sourceforge.jnlp.cache.CacheLRUWrapper#listRunningApps()}.
     * Throws {@link CatalogUnavailableException} when the catalog cannot be read.
     */
    private static List<RunningProcess> listCatalogRunningApps(String jnlpPathFilter) {
        Map<Integer, RunningProcess> byPid = new LinkedHashMap<>();
        collectFromCatalogRunningApps(byPid);
        if (jnlpPathFilter != null && !jnlpPathFilter.trim().isEmpty()) {
            List<Integer> drop = new ArrayList<>();
            for (RunningProcess process : byPid.values()) {
                if (!process.matchesJnlpPath(jnlpPathFilter)) {
                    drop.add(process.getPid());
                }
            }
            for (Integer pid : drop) {
                byPid.remove(pid);
            }
        }
        return new ArrayList<>(byPid.values());
    }

    private static void collectFromCatalogRunningApps(Map<Integer, RunningProcess> byPid) {
        List<CacheRunningApp> leases;
        try {
            leases = net.sourceforge.jnlp.cache.CacheLRUWrapper.getInstance().listRunningApps();
        } catch (CatalogUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new CatalogUnavailableException(e);
        }
        addLiveCatalogLeases(byPid, leases, true);
    }

    /**
     * Keeps only catalog PIDs that are still the same live process. Listed
     * PIDs are always a subset of {@code leases}.
     */
    static List<RunningProcess> runningProcessesFromCatalogLeases(List<CacheRunningApp> leases) {
        Map<Integer, RunningProcess> byPid = new LinkedHashMap<>();
        addLiveCatalogLeases(byPid, leases, false);
        return new ArrayList<>(byPid.values());
    }

    private static void addLiveCatalogLeases(Map<Integer, RunningProcess> byPid,
            List<CacheRunningApp> leases, boolean unregisterStale) {
        if (leases == null) {
            return;
        }
        int selfPid = currentPid();
        for (CacheRunningApp lease : leases) {
            if (lease == null || lease.pid <= 0 || lease.pid == selfPid) {
                continue;
            }
            if (!isProcessAlive(lease.pid) || !isSameProcess(lease.pid, lease.processStart)) {
                if (unregisterStale) {
                    try {
                        net.sourceforge.jnlp.cache.CacheLRUWrapper.getInstance().unregisterRunningApp(lease.pid);
                    } catch (Exception ignored) {
                    }
                }
                continue;
            }
            String commandLine = resolveCommandLine(lease.pid);
            if (isInfrastructureProcess(commandLine, null, lease.jnlpPath)) {
                continue;
            }
            RunningProcess running = toRunningProcess(lease.pid, commandLine, lease.jnlpPath,
                    null, null, null, null, null, null);
            running.setProcessStart(lease.processStart);
            byPid.put(lease.pid, running);
        }
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
        if (!isSameProcess(pid, recordedStart)) {
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

    /**
     * Command line of a known catalog PID. {@code ProcessHandle} first (cheap);
     * if that Optional is empty, {@code wmic} on Windows or {@code ps} on Unix.
     * Either non-empty result is enough — this is display / infrastructure
     * filter, not process identity.
     */
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
            // Ignore — ProcessHandle already tried.
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return "";
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

    static final class CatalogUnavailableException extends RuntimeException {
        CatalogUnavailableException(Throwable cause) {
            super("running_app catalog unavailable", cause);
        }
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
