/*
 Copyright (C) 2026 IcedTea-Web contributors
*/
package net.sourceforge.jnlp.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Dual-JVM catalog IT (Failsafe). Same {@code db/} file, two processes, no lost rows.
 */
public class SqliteCacheCatalogIT {

    private static final int PER_WORKER = 40;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final java.util.Map<Process, StringBuilder> workerOutput =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.List<Process> workers =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @After
    public void destroyWorkers() {
        for (Process p : workers) {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
        for (Process p : workers) {
            try {
                p.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        workers.clear();
        workerOutput.clear();
    }

    @Test(timeout = 120000)
    public void dualJvmProcessesInsertNoLostRows() throws Exception {
        File parentCache = tmp.newFolder("cache-parent");
        File legacy = new File(parentCache, "recently_used");
        java.nio.file.Files.write(legacy.toPath(), "LEGACY-MARKER\n".getBytes(StandardCharsets.UTF_8));
        Process a = startWorker(parentCache, "insert", "1", String.valueOf(PER_WORKER));
        Process b = startWorker(parentCache, "insert", "2", String.valueOf(PER_WORKER));
        assertTrue(waitOk(a).contains("added=" + PER_WORKER));
        assertTrue(waitOk(b).contains("added=" + PER_WORKER));
        String count = waitOk(startWorker(parentCache, "count"));
        assertTrue(count, count.contains("count=" + (PER_WORKER * 2)));
        assertEquals("LEGACY-MARKER\n",
                new String(java.nio.file.Files.readAllBytes(legacy.toPath()), StandardCharsets.UTF_8));
        assertNoSplitCatalog(parentCache);
    }

    @Test(timeout = 120000)
    public void firstCreateTwoProcessesDoNotSplitCatalog() throws Exception {
        for (int round = 0; round < 5; round++) {
            File parentCache = tmp.newFolder("first-create-" + round);
            Process a = startWorker(parentCache, "insert", "1", "20");
            Process b = startWorker(parentCache, "insert", "2", "20");
            assertTrue(waitOk(a).contains("added=20"));
            assertTrue(waitOk(b).contains("added=20"));
            String count = waitOk(startWorker(parentCache, "count"));
            assertTrue("round " + round + " " + count, count.contains("count=40"));
            assertNoSplitCatalog(parentCache);
        }
    }

    @Test(timeout = 90000)
    public void killWorkerMidInsertCatalogStillOpens() throws Exception {
        File parentCache = tmp.newFolder("kill-cache");
        Process worker = startWorker(parentCache, "insert-until-killed", "1");
        long deadline = System.currentTimeMillis() + 15000L;
        boolean started = false;
        while (System.currentTimeMillis() < deadline) {
            File catalog = new File(CacheLRUWrapper.sqliteCacheRoot(parentCache),
                    SqliteCacheCatalog.DB_FILE_NAME);
            if (catalog.isFile()) {
                started = true;
                break;
            }
            Thread.sleep(50);
        }
        assertTrue("worker should create catalog before kill", started);
        Thread.sleep(200);
        worker.destroyForcibly();
        worker.waitFor(5, TimeUnit.SECONDS);
        Thread.sleep(400);

        String count = null;
        AssertionError last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Process counter = startWorker(parentCache, "count");
            try {
                count = waitOk(counter, 10);
                last = null;
                break;
            } catch (AssertionError e) {
                last = e;
                counter.destroyForcibly();
                Thread.sleep(400);
            }
        }
        if (last != null) {
            throw last;
        }
        assertTrue(count, count.contains("count="));
        int n = Integer.parseInt(count.replaceAll("(?s).*count=(\\d+).*", "$1"));
        assertTrue("catalog must open after kill; count=" + n, n >= 0);
    }

    @Test(timeout = 120000)
    public void dualJvmNextFolderIdMkdirClaimNoCollision() throws Exception {
        File parentCache = tmp.newFolder("alloc-cache");
        final int perWorker = 25;
        Process a = startWorker(parentCache, "alloc", String.valueOf(perWorker));
        Process b = startWorker(parentCache, "alloc", String.valueOf(perWorker));
        String outA = waitOk(a);
        String outB = waitOk(b);
        java.util.Set<String> ids = new java.util.HashSet<>();
        ids.addAll(parseAllocIds(outA));
        ids.addAll(parseAllocIds(outB));
        assertEquals("folder ids must be unique across JVMs", perWorker * 2, ids.size());
    }

    @Test(timeout = 60000)
    public void indexedLookupP95WellUnderPropertiesScanBudget() throws Exception {
        File parentCache = tmp.newFolder("soak-cache");
        CacheLRUWrapper w = CacheLRUWrapper.createForTests(true, parentCache);
        try {
            File db = w.getCacheDir().getFile();
            w.lock();
            try {
                w.load();
                for (int i = 0; i < 800; i++) {
                    File jar = new File(db, (i % 40) + "/http/soak.example/lib" + i + ".jar");
                    if (!jar.getParentFile().mkdirs() && !jar.getParentFile().isDirectory()) {
                        throw new IOException("mkdir " + jar.getParent());
                    }
                    if (!jar.exists() && !jar.createNewFile()) {
                        throw new IOException("create " + jar);
                    }
                    assertTrue(w.addEntry(System.nanoTime() + "," + (i % 40), jar.getAbsolutePath()));
                }
                w.store();
            } finally {
                w.unlock();
            }

            long[] samples = new long[400];
            w.lock();
            try {
                for (int i = 0; i < samples.length; i++) {
                    String url = CacheUtil.pathToURLPath(
                            new File(db, (i % 40) + "/http/soak.example/lib" + (i % 800) + ".jar").getAbsolutePath(),
                            db.getAbsolutePath());
                    long t0 = System.nanoTime();
                    w.findEntriesByUrlPath(url);
                    samples[i] = System.nanoTime() - t0;
                }
            } finally {
                w.unlock();
            }
            java.util.Arrays.sort(samples);
            long p50 = samples[samples.length / 2] / 1000L;
            long p95 = samples[(int) (samples.length * 0.95)] / 1000L;
            System.out.println("sqlite_catalog_lookup_p50_us=" + p50 + " p95_us=" + p95);
            assertTrue("p95=" + p95 + "us p50=" + p50 + "us (properties baseline ~5000us/lookup)",
                    p95 < 5_000L);
        } finally {
            w.close();
        }
    }

    @Test(timeout = 120000)
    public void altaScaleIndexedLookupP95UnderPropertiesBaseline() throws Exception {
        File parentCache = tmp.newFolder("alta-scale-cache");
        CacheLRUWrapper w = CacheLRUWrapper.createForTests(true, parentCache);
        try {
            File db = w.getCacheDir().getFile();
            w.lock();
            try {
                w.load();
                for (int i = 0; i < 5000; i++) {
                    File jar = new File(db, (i % 40) + "/http/alta.example/lib" + i + ".jar");
                    assertTrue(w.addEntry(System.nanoTime() + "," + (i % 40), jar.getAbsolutePath()));
                }
                w.store();
            } finally {
                w.unlock();
            }

            long[] samples = new long[500];
            w.lock();
            try {
                for (int i = 0; i < samples.length; i++) {
                    String url = CacheUtil.pathToURLPath(
                            new File(db, (i % 40) + "/http/alta.example/lib" + (i % 5000) + ".jar").getAbsolutePath(),
                            db.getAbsolutePath());
                    long t0 = System.nanoTime();
                    w.findEntriesByUrlPath(url);
                    samples[i] = System.nanoTime() - t0;
                }
            } finally {
                w.unlock();
            }
            java.util.Arrays.sort(samples);
            long p50 = samples[samples.length / 2] / 1000L;
            long p95 = samples[(int) (samples.length * 0.95)] / 1000L;
            System.out.println("sqlite_catalog_alta_n5000_p50_us=" + p50 + " p95_us=" + p95);
            assertTrue("alta-scale p95=" + p95 + "us p50=" + p50 + "us (properties spike ~5238us @ N=5000)",
                    p95 < 5_000L);
        } finally {
            w.close();
        }
    }

    private Process startWorker(File parentCache, String... commandAndArgs) throws IOException {
        String javaHome = System.getProperty("java.home");
        File java = new File(javaHome, "bin/java" + (isWindows() ? ".exe" : ""));
        List<String> cmd = new ArrayList<>();
        cmd.add(java.getAbsolutePath());
        cmd.add("-cp");
        cmd.add(slimWorkerClasspath());
        cmd.add(SqliteCatalogProcessWorker.class.getName());
        cmd.add(parentCache.getAbsolutePath());
        for (String a : commandAndArgs) {
            cmd.add(a);
        }
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        workers.add(process);
        // Drain immediately: waitOk used to attach the reader too late, so verbose
        // workers filled the pipe and blocked before waitFor.
        StringBuilder buf = new StringBuilder();
        workerOutput.put(process, buf);
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (buf) {
                            buf.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                    // process closed
                }
            }
        }, "sqlite-worker-stdout");
        reader.setDaemon(true);
        reader.start();
        return process;
    }

    private static String slimWorkerClasspath() {
        String sep = File.pathSeparator;
        java.util.LinkedHashSet<String> kept = new java.util.LinkedHashSet<>();
        // Short CP only: a full surefire.test.class.path can exceed Windows CreateProcess
        // limits and/or put a stale m2 jar ahead of target/classes.
        File workerDir = codeSourceFile(SqliteCatalogProcessWorker.class);
        if (workerDir != null && workerDir.isDirectory()) {
            File classes = new File(workerDir.getParentFile(), "classes");
            if (classes.isDirectory()) {
                kept.add(classes.getAbsolutePath());
            }
            kept.add(workerDir.getAbsolutePath());
        }
        File cwdClasses = new File("classes");
        File cwdTestClasses = new File("test-classes");
        if (cwdClasses.isDirectory()) {
            kept.add(cwdClasses.getAbsolutePath());
        }
        if (cwdTestClasses.isDirectory()) {
            kept.add(cwdTestClasses.getAbsolutePath());
        }
        try {
            File jdbc = codeSourceFile(Class.forName("org.sqlite.JDBC"));
            if (jdbc != null) {
                kept.add(jdbc.getAbsolutePath());
            }
        } catch (ClassNotFoundException ignored) {
            // sqlite-jdbc must be on the Failsafe CP
        }
        assertFalse("slim worker classpath empty", kept.isEmpty());
        return String.join(sep, kept);
    }

    private static File codeSourceFile(Class<?> cls) {
        try {
            java.security.CodeSource cs = cls.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) {
                return null;
            }
            File f = new File(cs.getLocation().toURI());
            return f.exists() ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String waitOk(Process process) throws Exception {
        return waitOk(process, 90);
    }

    private String waitOk(Process process, int timeoutSec) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSec);
        while (System.currentTimeMillis() < deadline && process.isAlive()) {
            String snap = snapshotOutput(process);
            if (snap.contains("OK ") || snap.contains("ERR ")) {
                process.waitFor(5, TimeUnit.SECONDS);
                break;
            }
            Thread.sleep(50);
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String text = snapshotOutput(process);
        assertTrue("worker output=" + text, text.contains("OK "));
        return text;
    }

    private String snapshotOutput(Process process) {
        StringBuilder buf = workerOutput.get(process);
        if (buf == null) {
            return "";
        }
        synchronized (buf) {
            return buf.toString();
        }
    }

    private static java.util.List<String> parseAllocIds(String workerOut) {
        int idx = workerOut.indexOf("ids=");
        assertTrue("missing ids= in " + workerOut, idx >= 0);
        String csv = workerOut.substring(idx + 4).trim().split("\\R")[0].trim();
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (!csv.isEmpty()) {
            for (String id : csv.split(",")) {
                ids.add(id.trim());
            }
        }
        return ids;
    }

    private static void assertNoSplitCatalog(File parentCache) {
        File dbDir = CacheLRUWrapper.sqliteCacheRoot(parentCache);
        File[] bad = dbDir.isDirectory()
                ? dbDir.listFiles((d, n) -> n.contains(".corrupt-")
                        || n.equals(SqliteCacheCatalog.FAILED_MARKER))
                : null;
        assertTrue("split/quarantine under " + dbDir + ": " + java.util.Arrays.toString(bad),
                bad == null || bad.length == 0);
        File catalog = new File(dbDir, SqliteCacheCatalog.DB_FILE_NAME);
        assertTrue("expected single catalog at " + catalog, catalog.isFile());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
