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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
 * when this module is verified there. Unit CI additionally covers dual-JVM via
 * {@code SqliteCacheCatalogTest}.
 */
@EnabledOnOs({OS.WINDOWS, OS.LINUX})
class SqliteCacheCatalogDualJvmIT {

    private static final int PER_WORKER = 80;
    private static final int TIMEOUT_SEC = 120;

    // NEVER: Windows may keep sqlite WAL/SHM briefly after close; do not fail the IT on cleanup.
    @TempDir(cleanup = CleanupMode.NEVER)
    Path tmp;

    private Path cacheParent;
    private final List<CacheLRUWrapper> openWrappers = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        cacheParent = tmp.resolve("cache");
        Files.createDirectories(cacheParent);
        // Plant legacy tree that must never be read when sqlite is on.
        Path legacyJar = cacheParent.resolve("0/http/evil.example/legacy.jar");
        Files.createDirectories(legacyJar.getParent());
        Files.writeString(legacyJar, "legacy", StandardCharsets.UTF_8);
        Files.writeString(cacheParent.resolve("recently_used"),
                "1,0=" + legacyJar.toAbsolutePath() + "\n", StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        for (CacheLRUWrapper w : openWrappers) {
            try {
                w.close();
            } catch (Exception ignored) {
                // best-effort so @TempDir can delete on Windows
            }
        }
        openWrappers.clear();
    }

    private CacheLRUWrapper openWrapper(boolean sqlite) {
        CacheLRUWrapper w = CacheLRUWrapper.createForTests(sqlite, cacheParent.toFile());
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
        assertThat(cacheParent.resolve("recently_used")).isRegularFile();
    }

    @Test
    @Timeout(60)
    void killSwitchFalseUsesLegacyRootNotDb() throws Exception {
        CacheLRUWrapper legacy = openWrapper(false);
        assertThat(legacy.isSqliteMode()).isFalse();
        assertThat(legacy.getCacheDir().getFile()).isEqualTo(cacheParent.toFile());
        assertThat(legacy.getSqliteCatalogFile()).isNull();

        File jar = cacheParent.resolve("9/http/legacy.mode/app.jar").toFile();
        assertThat(jar.getParentFile().mkdirs() || jar.getParentFile().isDirectory()).isTrue();
        assertThat(jar.createNewFile()).isTrue();

        legacy.lock();
        try {
            legacy.load();
            String key = legacy.generateKey(jar.getAbsolutePath());
            assertThat(legacy.addEntry(key, jar.getAbsolutePath())).isTrue();
            assertThat(legacy.store()).isTrue();
        } finally {
            legacy.unlock();
        }

        assertThat(cacheParent.resolve("db/cache_catalog.sqlite")).doesNotExist();
        assertThat(legacy.getRecentlyUsedFile().getFile()).isFile();
    }

    @Test
    @Timeout(60)
    void inProcessMultiGenerationNewestFirst() throws Exception {
        CacheLRUWrapper w = openWrapper(true);
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
        return pb.start();
    }

    private static String waitOk(Process process) throws Exception {
        boolean finished = process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        assertThat(finished).as("worker timed out; output=%s", output).isTrue();
        assertThat(process.exitValue()).as("worker exit; output=%s", output).isZero();
        assertThat(output).as("worker output").contains("OK ");
        return output;
    }

    private static String workerClasspath() throws Exception {
        // Failsafe CP already includes icedtea-web-uber + test-classes.
        String cp = System.getProperty("java.class.path");
        assertThat(cp).as("java.class.path").isNotBlank();
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
