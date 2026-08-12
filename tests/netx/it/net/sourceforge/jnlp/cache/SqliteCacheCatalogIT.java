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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    @Test(timeout = 120000)
    public void dualJvmProcessesInsertNoLostRows() throws Exception {
        File parentCache = tmp.newFolder("cache-parent");
        Process a = startWorker(parentCache, "insert", "1", String.valueOf(PER_WORKER));
        Process b = startWorker(parentCache, "insert", "2", String.valueOf(PER_WORKER));
        assertTrue(waitOk(a).contains("added=" + PER_WORKER));
        assertTrue(waitOk(b).contains("added=" + PER_WORKER));
        String count = waitOk(startWorker(parentCache, "count"));
        assertTrue(count, count.contains("count=" + (PER_WORKER * 2)));
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
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }

    private static String slimWorkerClasspath() {
        String sep = File.pathSeparator;
        String full = System.getProperty("java.class.path", "");
        List<String> kept = new ArrayList<>();
        for (String entry : full.split(Pattern.quote(sep))) {
            String lower = entry.replace('\\', '/').toLowerCase();
            if (lower.endsWith("/test-classes")
                    || lower.endsWith("/classes")
                    || lower.contains("sqlite-jdbc")
                    || lower.contains("/slf4j-api")
                    || lower.contains("hamcrest")) {
                kept.add(entry);
            }
        }
        File testClasses = new File("target/test-classes");
        File classes = new File("target/classes");
        if (testClasses.isDirectory()) {
            kept.add(0, testClasses.getAbsolutePath());
        }
        if (classes.isDirectory()) {
            kept.add(0, classes.getAbsolutePath());
        }
        assertFalse("slim worker classpath empty", kept.isEmpty());
        return String.join(sep, kept);
    }

    private static String waitOk(Process process) throws Exception {
        boolean finished = process.waitFor(90, TimeUnit.SECONDS);
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        assertTrue("worker timed out; output=" + output, finished);
        assertEquals("worker exit; output=" + output, 0, process.exitValue());
        assertTrue("worker output=" + output, output.contains("OK "));
        return output;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
