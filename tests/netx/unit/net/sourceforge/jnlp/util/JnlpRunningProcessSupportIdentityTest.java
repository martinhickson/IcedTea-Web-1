package net.sourceforge.jnlp.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.sourceforge.jnlp.cache.CacheRunningApp;

public class JnlpRunningProcessSupportIdentityTest {

    @Test
    public void matchesCurrentProcessWithCorrectStart() {
        long pid = java.lang.ProcessHandle.current().pid();
        String start = java.lang.ProcessHandle.current().info().startInstant()
                .map(java.time.Instant::toString).orElse(null);
        assertTrue(JnlpRunningProcessSupport.isSameProcess((int) pid, start));
    }

    @Test
    public void rejectsWrongStartInstant() {
        long pid = java.lang.ProcessHandle.current().pid();
        assertFalse(JnlpRunningProcessSupport.isSameProcess((int) pid, "2000-01-01T00:00:00Z"));
    }

    @Test
    public void rejectsNullRecordedStart() {
        long pid = java.lang.ProcessHandle.current().pid();
        assertFalse(JnlpRunningProcessSupport.isSameProcess((int) pid, null));
        assertFalse(JnlpRunningProcessSupport.isSameProcess((int) pid, "  "));
    }

    @Test
    public void rejectsNonexistentPid() {
        // a PID far beyond typical ranges is not a live process → startInstant absent → false
        assertFalse(JnlpRunningProcessSupport.isSameProcess(9999999, "2000-01-01T00:00:00Z"));
    }

    @Test
    public void matchesJnlpPathDoesNotTreatJarHrefAsRunningJnlp() {
        JnlpRunningProcessSupport.RunningProcess running = new JnlpRunningProcessSupport.RunningProcess(42, "console", "1.0",
                "java -jar icedtea-web-uber.jar http://127.0.0.1:4200/jnlp/console/app.jnlp",
                "http://127.0.0.1:4200/jnlp/console/app.jnlp");
        assertTrue(running.matchesJnlpPath("http://127.0.0.1:4200/jnlp/console/app.jnlp"));
        // -Xcacheids lists this JAR href; it is a valid -Xclearcache target but is
        // not on the command line / lock metadata. Filtering the busy list by it
        // used to skip the guard.
        assertFalse(running.matchesJnlpPath("http://127.0.0.1:4200/jnlp/console/app.jar"));
        assertFalse(running.matchesJnlpPath("http://127.0.0.1:4200/jnlp/swing-gui/app.jar"));
        assertTrue(running.blocksCacheClear("http://127.0.0.1:4200/jnlp/console/app.jnlp"));
        assertTrue(running.blocksCacheClear("http://127.0.0.1:4200/jnlp/console/app.jar"));
        assertTrue(running.blocksCacheClear("127.0.0.1"));
        assertFalse(running.blocksCacheClear("example.com"));
        assertFalse(running.blocksCacheClear("com"));
        assertFalse(running.blocksCacheClear("http://127.0.0.1:4200/jnlp/swing-gui/app.jar"));
        assertFalse(running.blocksCacheClear("http://127.0.0.1:4200/jnlp/swing-gui/app.jnlp"));

        JnlpRunningProcessSupport.RunningProcess siblingHost = new JnlpRunningProcessSupport.RunningProcess(
                44, "evil", "1.0",
                "java -jar icedtea-web-uber.jar http://evil.example.com/app.jnlp",
                "http://evil.example.com/app.jnlp");
        assertTrue(siblingHost.blocksCacheClear("evil.example.com"));
        assertFalse(siblingHost.blocksCacheClear("example.com"));

        JnlpRunningProcessSupport.RunningProcess fromCommandLine = new JnlpRunningProcessSupport.RunningProcess(
                43, "console",
                "java -jar icedtea-web-uber.jar -jnlp http://127.0.0.1:4200/jnlp/console/app.jnlp");
        assertTrue(fromCommandLine.blocksCacheClear("http://127.0.0.1:4200/jnlp/console/app.jar"));
    }

    @Test
    public void clearcacheCliIsInfrastructure() {
        assertTrue(JnlpRunningProcessSupport.isInfrastructureProcess(
                "java -jar icedtea-web-uber.jar -Xclearcache http://127.0.0.1:4350/jnlp/c401/app.jnlp",
                null, null));
        assertTrue(JnlpRunningProcessSupport.isInfrastructureProcess(
                "java -jar icedtea-web-uber.jar -Xlistcacheids",
                null, null));
        assertFalse(JnlpRunningProcessSupport.isInfrastructureProcess(
                "java -jar icedtea-web-uber.jar -jnlp http://127.0.0.1:4350/jnlp/c401/app.jnlp",
                "c401", "http://127.0.0.1:4350/jnlp/c401/app.jnlp"));
    }

    @Test
    public void settingsLaunchersAreInfrastructure() {
        assertTrue(JnlpRunningProcessSupport.isInfrastructureProcess(
                "C:\\Program Files\\IcedTeaWeb\\WebStart\\bin\\itweb-settings.exe",
                null, null));
        assertTrue(JnlpRunningProcessSupport.isInfrastructureProcess(
                "icedtea-web-settings.exe", null, null));
        assertTrue(JnlpRunningProcessSupport.isInfrastructureProcess(
                "java -Dicedtea-web.bin.name=itweb-settings -jar icedtea-web-uber.jar "
                        + "net.sourceforge.jnlp.controlpanel.CommandLine",
                null, null));
        assertTrue(JnlpRunningProcessSupport.isInfrastructureProcess(
                "java -Dicedtea-web.bin.name=icedtea-web-settings -cp icedtea-web-uber.jar "
                        + "net.sourceforge.jnlp.controlpanel.CommandLine",
                "IcedTea-Web Control Panel", null));
        // A leftover JNLP path must not promote the Control Panel into the cache-clear list.
        assertTrue(JnlpRunningProcessSupport.isInfrastructureProcess(
                "java -Dicedtea-web.bin.name=itweb-settings -jar icedtea-web-uber.jar "
                        + "net.sourceforge.jnlp.controlpanel.CommandLine",
                null, "https://example.com/app.jnlp"));
        assertFalse(JnlpRunningProcessSupport.isInfrastructureProcess(
                "java -Dicedtea-web.bin.name=javaws -jar icedtea-web-uber.jar "
                        + "-jnlp https://example.com/app.jnlp",
                "App", "https://example.com/app.jnlp"));
    }

    @Test
    public void catalogUnavailableBlocksCacheClear() {
        assertTrue(JnlpRunningProcessSupport.cacheClearBlockedByRunningApps(
                null, java.util.Collections.emptyList(), true));
        assertTrue(JnlpRunningProcessSupport.cacheClearBlockedByRunningApps(
                "https://example.com/app.jnlp", java.util.Collections.emptyList(), true));
        assertFalse(JnlpRunningProcessSupport.cacheClearBlockedByRunningApps(
                null, java.util.Collections.emptyList(), false));
    }

    @Test
    public void listedPidsAreSubsetOfCatalogLeases() {
        int selfPid = JnlpRunningProcessSupport.currentPid();
        String selfStart = java.lang.ProcessHandle.current().info().startInstant()
                .map(java.time.Instant::toString).orElse("2000-01-01T00:00:00Z");
        List<CacheRunningApp> leases = Arrays.asList(
                new CacheRunningApp(9_999_999, "https://example.com/dead.jnlp", "2000-01-01T00:00:00Z"),
                new CacheRunningApp(selfPid, "https://example.com/self.jnlp", selfStart),
                new CacheRunningApp(-1, "https://example.com/bad.jnlp", null),
                new CacheRunningApp(selfPid, "https://example.com/reused.jnlp", "2000-01-01T00:00:00Z"));
        Set<Integer> catalogPids = new HashSet<Integer>();
        for (CacheRunningApp lease : leases) {
            catalogPids.add(lease.pid);
        }
        List<JnlpRunningProcessSupport.RunningProcess> listed =
                JnlpRunningProcessSupport.runningProcessesFromCatalogLeases(leases);
        for (JnlpRunningProcessSupport.RunningProcess process : listed) {
            assertTrue(catalogPids.contains(process.getPid()),
                    "list must not invent a PID outside running_app");
        }
        for (JnlpRunningProcessSupport.RunningProcess process : listed) {
            assertFalse(process.getPid() == 9_999_999);
            assertFalse(process.getPid() == selfPid);
        }
    }
}
