package net.sourceforge.icedteaweb.autodetect.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.sourceforge.jnlp.cache.CacheLRUWrapper;
import net.sourceforge.jnlp.cache.CacheUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dual-JVM coverage for the SQLite cache catalog under {@code {cachedir}/db/}.
 * Primary CI home: existing {@code autodetect-windows} job; also runs on Linux
 * when this module is verified there. Dual-JVM ProcessBuilder belongs in Failsafe
 * ({@code SqliteCacheCatalogIT} / this class), not Surefire.
 */
@EnabledOnOs({OS.WINDOWS, OS.LINUX})
class SqliteCacheCatalogDualJvmIT {

    private static final int PER_WORKER = 80;
    private static final int TIMEOUT_SEC = 120;

    // NEVER: Windows may keep sqlite WAL/SHM briefly after close; do not fail the IT on cleanup.
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tmp;

    private Path cacheParent;
    private String plantedLegacyIndex;
    private final List<CacheLRUWrapper> openWrappers = new ArrayList<>();
    private final java.util.Map<Process, StringBuilder> workerOutput = new ConcurrentHashMap<>();
    private final List<Process> workers = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        cacheParent = tmp.resolve("cache");
        Files.createDirectories(cacheParent);
        // Plant legacy tree that must never be read when sqlite is on.
        Path legacyJar = cacheParent.resolve("0/http/evil.example/legacy.jar");
        Files.createDirectories(legacyJar.getParent());
        Files.writeString(legacyJar, "legacy", StandardCharsets.UTF_8);
        plantedLegacyIndex = "1,0=" + legacyJar.toAbsolutePath() + "\n";
        Files.writeString(cacheParent.resolve("recently_used"), plantedLegacyIndex, StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        for (Process p : workers) {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
        workers.clear();
        workerOutput.clear();
        for (CacheLRUWrapper w : openWrappers) {
            try {
                w.close();
            } catch (Exception ignored) {
                // best-effort so @TempDir can delete on Windows
            }
        }
        openWrappers.clear();
    }

    private CacheLRUWrapper openWrapper() {
        CacheLRUWrapper w = CacheLRUWrapper.createForTests(true, cacheParent.toFile());
        openWrappers.add(w);
        return w;
    }

    @Test
    @Timeout(180)
    void twoProcessesInsertNoLostRowsAndIgnoreLegacyTree() throws Exception {
        Process a = startWorker("insert", "1", String.valueOf(PER_WORKER));
        Process b = startWorker("insert", "2", String.valueOf(PER_WORKER));
        assertThat(waitOk(a)).as("worker A stdout").contains("added=" + PER_WORKER);
        assertThat(waitOk(b)).as("worker B stdout").contains("added=" + PER_WORKER);

        String countOut = waitOk(startWorker("count"));
        assertThat(countOut).contains("count=" + (PER_WORKER * 2));

        // Indexed find for one of A's jars
        Path sample = cacheParent.resolve("db/1/http/dual.example/w1-0.jar");
        assertThat(sample).isRegularFile();
        String urlPath = CacheUtil.pathToURLPath(
                sample.toAbsolutePath().toString(),
                cacheParent.resolve("db").toAbsolutePath().toString());
        String findOut = waitOk(startWorker("find", urlPath));
        assertThat(findOut).contains(sample.toAbsolutePath().toString());

        // Legacy URL path must not appear
        String legacyUrl = CacheUtil.pathToURLPath(
                cacheParent.resolve("0/http/evil.example/legacy.jar").toAbsolutePath().toString(),
                cacheParent.toAbsolutePath().toString());
        String legacyFind = waitOk(startWorker("find", legacyUrl));
        assertThat(legacyFind).contains("matches=0");

        assertThat(cacheParent.resolve("db/cache_catalog.sqlite")).isRegularFile();
        assertThat(cacheParent.resolve("recently_used")).hasContent(plantedLegacyIndex);
        Path nativeDir = cacheParent.resolve("db/native");
        assertThat(nativeDir).as("xerial native extract under {cachedir}/db/native").isDirectory();
        try (java.util.stream.Stream<Path> natives = Files.list(nativeDir)) {
            assertThat(natives.map(p -> p.getFileName().toString().toLowerCase())
                    .anyMatch(n -> n.contains("sqlitejdbc")))
                    .as("sqlitejdbc native should be extracted under db/native, not %TEMP%")
                    .isTrue();
        }
    }

    @Test
    @Timeout(60)
    void inProcessMultiGenerationNewestFirst() throws Exception {
        CacheLRUWrapper w = openWrapper();
        File db = w.getCacheDir().getFile();
        File older = new File(db, "10/http/gen.example/app.jar");
        File newer = new File(db, "11/http/gen.example/app.jar");
        for (File jar : new File[] {older, newer}) {
            assertThat(jar.getParentFile().mkdirs() || jar.getParentFile().isDirectory()).isTrue();
            assertThat(jar.createNewFile()).isTrue();
        }
        w.lock();
        try {
            w.load();
            assertThat(w.addEntry("1000,10", older.getAbsolutePath())).isTrue();
            Thread.sleep(5);
            assertThat(w.addEntry("2000,11", newer.getAbsolutePath())).isTrue();
            w.store();
            String url = CacheUtil.pathToURLPath(newer.getAbsolutePath(), db.getAbsolutePath());
            assertThat(w.findEntriesByUrlPath(url))
                    .extracting(e -> e.getValue())
                    .containsExactly(newer.getAbsolutePath(), older.getAbsolutePath());
        } finally {
            w.unlock();
        }
        assertThat(w.getSqliteCatalogFile()).isFile();
    }

    private Process startWorker(String... commandAndArgs) throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        File javaExe = new File(java + (isWindows() ? ".exe" : ""));
        if (!javaExe.isFile()) {
            javaExe = new File(java);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.getAbsolutePath());
        cmd.add("-cp");
        cmd.add(workerClasspath());
        cmd.add(SqliteCatalogDualJvmWorker.class.getName());
        cmd.add(cacheParent.toAbsolutePath().toString());
        for (String a : commandAndArgs) {
            cmd.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        workers.add(process);
        StringBuilder buf = new StringBuilder();
        workerOutput.put(process, buf);
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (buf) {
                        buf.append(line).append('\n');
                    }
                }
            } catch (java.io.IOException ignored) {
                // process closed
            }
        }, "sqlite-dualjvm-stdout");
        reader.setDaemon(true);
        reader.start();
        return process;
    }

    private String waitOk(Process process) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SEC);
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
        String output = snapshotOutput(process);
        assertThat(output).as("worker output").contains("OK ");
        return output;
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

    private static String workerClasspath() throws Exception {
        String surefire = System.getProperty("surefire.test.class.path");
        String cp = (surefire != null && !surefire.trim().isEmpty())
                ? surefire
                : System.getProperty("java.class.path");
        assertThat(cp).as("worker classpath").isNotBlank();
        // Ensure worker class is present
        String worker = SqliteCatalogDualJvmWorker.class.getName().replace('.', '/') + ".class";
        assertThat(SqliteCatalogDualJvmWorker.class.getClassLoader().getResource(worker))
                .as("worker class on classpath")
                .isNotNull();
        return cp;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
