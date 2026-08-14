package net.sourceforge.jnlp.integration;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end integration tests for HTTP Range (RFC 7233) JAR-cache resume
 * (gitea2 mhickson/icedtea-web #13). Each test launches a real {@code javaws}
 * against an Undertow server that can honour / ignore / reject Range requests,
 * truncates the cached jar to simulate an interrupted download, then relaunches
 * and asserts ITW resumes (206 append) or falls back (200 / 416 / mismatch) and
 * the application still launches with an intact, verifiable jar.
 */
public class HttpRangeResumeIT {

    private static final String JAVAWS_BIN = System.getProperty("itw.javaws.bin");
    private static final String JDK_HOME = firstNonBlank(
            System.getProperty("itw.jdk11.home"),
            System.getProperty("itw.jdk17.home"),
            System.getProperty("itw.jdk8.home"));
    private static final int TIMEOUT_SECONDS = Integer.getInteger("itw.launch.timeout.seconds", 180);
    private static final String JAR_NAME = "headless-app.jar";

    private Undertow server;
    private int httpPort;
    private Path webRoot;
    private Path markerDir;
    private Path cacheHome;
    private Path configHome;
    private byte[] fullJar;

    /** Range-serving behaviour applied to every jar GET. Mutable so a test can change it. */
    private volatile RangeMode mode = RangeMode.HONOR;
    /** Last-Modified (epoch millis) advertised for the jar; bumped to simulate a changed resource. */
    private volatile long resourceLastModified = 0L;

    private final AtomicInteger jarGets = new AtomicInteger();
    private final AtomicReference<String> lastRangeHeader = new AtomicReference<>();
    private final AtomicReference<String> lastIfRangeHeader = new AtomicReference<>();
    private final AtomicInteger count206 = new AtomicInteger();
    private final AtomicInteger count200 = new AtomicInteger();
    private final AtomicInteger count416 = new AtomicInteger();

    enum RangeMode {
        /** Honour a byte Range with 206 + suffix. */
        HONOR,
        /** Ignore Range and always serve the full 200 body (old / range-unaware server). */
        IGNORE,
        /** Reject a Range with 416. */
        REJECT_416,
        /** Serve 206 with a Content-Range start of 0 (mismatches the requested suffix). */
        MISMATCH,
        /** Honour Range only when If-Range matches the current Last-Modified, else full 200. */
        IF_RANGE
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(JAVAWS_BIN != null && new File(JAVAWS_BIN).isFile(), "itw.javaws.bin missing");
        assumeTrue(JDK_HOME != null && javaExecutableForHome(JDK_HOME).isFile(), "JDK home missing");

        webRoot = Files.createTempDirectory("itw-range-web");
        markerDir = Files.createTempDirectory("itw-range-marker");
        cacheHome = Files.createTempDirectory("itw-range-cache");
        configHome = Files.createTempDirectory("itw-range-config");

        Path source = Paths.get("target", "icedtea-web-integration-2.0.1-SNAPSHOT-headless-app.jar");
        assumeTrue(Files.exists(source), "headless-app jar missing: " + source.toAbsolutePath());
        fullJar = Files.readAllBytes(source);
        Files.copy(source, webRoot.resolve(JAR_NAME));

        mode = RangeMode.HONOR;
        resourceLastModified = 0L;
        resetCaptures();

        server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(new RangeJarHandler())
                .build();
        server.start();
        httpPort = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        deleteRecursive(webRoot);
        deleteRecursive(markerDir);
        deleteRecursive(cacheHome);
        deleteRecursive(configHome);
    }

    private void resetCaptures() {
        jarGets.set(0);
        lastRangeHeader.set(null);
        lastIfRangeHeader.set(null);
        count206.set(0);
        count200.set(0);
        count416.set(0);
    }

    /**
     * Launch once (full download), truncate the cached jar to a prefix so the next launch
     * must resume, relaunch, and assert the server saw a 206-producing Range request and the
     * reassembled jar is whole and the app launched.
     */
    @Test
    void resumeTruncatedJarAppendsSuffixAndLaunches() throws Exception {
        mode = RangeMode.HONOR;

        Path marker1 = markerDir.resolve("success1.marker");
        writeJnlp(marker1);
        LaunchResult first = launch(marker1);
        assertTrue(first.success, "initial launch must populate cache:\n" + first.output);
        assertFalse(first.output.contains("ZipException"),
                "initial launch must not hit a ZipException:\n" + first.output);

        Path cachedJar = findCachedJar(cacheHome, JAR_NAME);
        assertNotNull(cachedJar, "expected cached " + JAR_NAME + " under " + cacheHome);
        assertEquals(fullJar.length, Files.size(cachedJar), "first launch caches the whole jar");

        int cut = Math.max(8, fullJar.length / 2);
        truncate(cachedJar, cut);
        assertFalse(isZipMagic(cachedJar) && Files.size(cachedJar) == fullJar.length,
                "precondition: cache jar is now a truncated prefix");

        resetCaptures();
        Path marker2 = markerDir.resolve("success2.marker");
        writeJnlp(marker2);
        LaunchResult second = launch(marker2);

        assertTrue(second.success, "relaunch after truncation must resume and succeed:\n" + second.output);
        assertEquals("bytes=" + cut + "-", lastRangeHeader.get(),
                "client must request the missing suffix by Range header");
        assertTrue(count206.get() > 0, "server must have served a 206 Partial Content");
        assertEquals(0, count416.get(), "no 416 expected when the range is satisfiable");

        Path reassembled = findCachedJar(cacheHome, JAR_NAME);
        assertNotNull(reassembled, "reassembled jar must be cached");
        assertEquals(fullJar.length, Files.size(reassembled), "appended suffix must restore full length");
        assertTrue(isZipMagic(reassembled), "reassembled jar must be a valid ZIP");
        assertArrayPrefix(fullJar, reassembled, cut);
    }

    /**
     * A range-unaware server (IGNORE) returns the full 200 body even when the client sends
     * Range. ITW must replace (truncate), not append, and the app must launch.
     */
    @Test
    void rangeUnawareServerReplacesAndLaunches() throws Exception {
        mode = RangeMode.IGNORE;

        Path marker1 = markerDir.resolve("success1.marker");
        writeJnlp(marker1);
        LaunchResult first = launch(marker1);
        assertTrue(first.success, "initial launch must populate cache:\n" + first.output);

        Path cachedJar = findCachedJar(cacheHome, JAR_NAME);
        assertNotNull(cachedJar, "expected cached " + JAR_NAME);
        int cut = Math.max(8, fullJar.length / 2);
        truncate(cachedJar, cut);

        resetCaptures();
        Path marker2 = markerDir.resolve("success2.marker");
        writeJnlp(marker2);
        LaunchResult second = launch(marker2);

        assertTrue(second.success, "relaunch must succeed via full GET:\n" + second.output);
        assertNotNull(lastRangeHeader.get(), "client still sends Range because a partial exists");
        assertTrue(count200.get() > 0, "range-unaware server serves a full 200");
        assertEquals(0, count206.get(), "no 206 from a range-unaware server");

        Path replaced = findCachedJar(cacheHome, JAR_NAME);
        assertNotNull(replaced);
        assertEquals(fullJar.length, Files.size(replaced),
                "server ignored Range: client must replace, not append (no length doubling)");
        assertArrayEquals(fullJar, Files.readAllBytes(replaced));
    }

    /**
     * A 416 (Range Not Satisfiable) must trigger a single full-GET fallback on the same URL,
     * not a download failure. Asserts both responses (416 then 200) occur and the app launches.
     */
    @Test
    void unsatisfiableRangeFallsBackToFullGet() throws Exception {
        mode = RangeMode.REJECT_416;

        Path marker1 = markerDir.resolve("success1.marker");
        writeJnlp(marker1);
        LaunchResult first = launch(marker1);
        assertTrue(first.success, "initial launch must populate cache:\n" + first.output);

        Path cachedJar = findCachedJar(cacheHome, JAR_NAME);
        assertNotNull(cachedJar, "expected cached " + JAR_NAME);
        int cut = Math.max(8, fullJar.length / 2);
        truncate(cachedJar, cut);

        resetCaptures();
        Path marker2 = markerDir.resolve("success2.marker");
        writeJnlp(marker2);
        LaunchResult second = launch(marker2);

        assertTrue(second.success, "relaunch must succeed after 416 fallback:\n" + second.output);
        assertEquals("bytes=" + cut + "-", lastRangeHeader.get(),
                "client must first attempt the Range resume");
        assertTrue(count416.get() >= 1, "server must reject the range with 416");
        assertTrue(count200.get() >= 1, "client must fall back to a full 200 GET after 416");
        assertEquals(0, count206.get(), "no 206 should be served when the range is rejected");

        Path replaced = findCachedJar(cacheHome, JAR_NAME);
        assertNotNull(replaced);
        assertEquals(fullJar.length, Files.size(replaced), "fallback full GET must restore full length");
        assertArrayEquals(fullJar, Files.readAllBytes(replaced));
    }

    /**
     * When the resource changed since the partial was cached, the client's If-Range must make
     * the server answer with a full 200 (not a 206 suffix), so the stale prefix is replaced
     * rather than appended to. Exercises the RFC 7233 If-Range safety path end-to-end.
     */
    @Test
    void ifRangeStaleForcesFullReplace() throws Exception {
        long t1 = 1_700_000_000_000L;
        resourceLastModified = t1;
        mode = RangeMode.IF_RANGE;

        Path marker1 = markerDir.resolve("success1.marker");
        writeJnlp(marker1);
        LaunchResult first = launch(marker1);
        assertTrue(first.success, "initial launch must populate cache:\n" + first.output);

        Path cachedJar = findCachedJar(cacheHome, JAR_NAME);
        assertNotNull(cachedJar, "expected cached " + JAR_NAME);
        int cut = Math.max(8, fullJar.length / 2);
        truncate(cachedJar, cut);

        // Simulate the resource changing on the server between the two launches.
        resourceLastModified = t1 + 70_000L;

        resetCaptures();
        Path marker2 = markerDir.resolve("success2.marker");
        writeJnlp(marker2);
        LaunchResult second = launch(marker2);

        assertTrue(second.success, "relaunch must succeed via full GET:\n" + second.output);
        assertNotNull(lastIfRangeHeader.get(),
                "client must send If-Range when it cached a Last-Modified");
        assertEquals(0, count206.get(), "stale If-Range must NOT yield a 206 suffix");
        assertTrue(count200.get() >= 1, "stale If-Range must yield a full 200 replacement");

        Path replaced = findCachedJar(cacheHome, JAR_NAME);
        assertNotNull(replaced);
        assertEquals(fullJar.length, Files.size(replaced),
                "stale prefix must be replaced, not appended");
        assertArrayEquals(fullJar, Files.readAllBytes(replaced));
    }

    // ----- helpers -----

    private void writeJnlp(Path marker) throws Exception {
        String jnlp = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"http://127.0.0.1:" + httpPort + "/\" href=\"range-resume.jnlp\">\n"
                + "  <information><title>ITW range resume</title><vendor>ITW</vendor></information>\n"
                + "  <security><all-permissions/></security>\n"
                + "  <resources>\n"
                + "    <property name=\"itw.test.success.marker\" value=\""
                + marker.toAbsolutePath().toString().replace("\\", "/") + "\"/>\n"
                + "    <j2se version=\"1.8+\"/>\n"
                + "    <jar href=\"" + JAR_NAME + "\" main=\"true\"/>\n"
                + "  </resources>\n"
                + "  <application-desc main-class=\"net.sourceforge.jnlp.integration.HeadlessJnlpMain\"/>\n"
                + "</jnlp>\n";
        Files.write(webRoot.resolve("range-resume.jnlp"), jnlp.getBytes(StandardCharsets.UTF_8));
    }

    private LaunchResult launch(Path marker) throws Exception {
        String jnlpUrl = "http://127.0.0.1:" + httpPort + "/range-resume.jnlp";
        List<String> command = new ArrayList<>();
        command.add(JAVAWS_BIN);
        command.add("-headless");
        command.add("-verbose");
        command.add("-Xtrustall");
        command.add("--auto-accept-https-certificate=true");
        command.add("-Xnofork");
        command.add(jnlpUrl);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("JAVA_HOME", JDK_HOME);
        pb.environment().put("ICEDTEA_WEB_SPLASH", "none");
        pb.environment().put("XDG_CACHE_HOME", cacheHome.toAbsolutePath().toString());
        pb.environment().put("XDG_CONFIG_HOME", configHome.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Path outputFile = Files.createTempFile("itw-range", ".log");
        pb.redirectOutput(outputFile.toFile());

        Process process = pb.start();
        boolean ok = waitForLaunchSuccess(process, outputFile, marker);
        String output = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);
        if (process.isAlive()) {
            process.destroy();
            process.waitFor(5, TimeUnit.SECONDS);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return new LaunchResult(ok, output);
    }

    private static void truncate(Path file, int cut) throws Exception {
        byte[] full = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(full, cut));
    }

    private static Path findCachedJar(Path cacheHome, String jarName) throws Exception {
        if (!Files.exists(cacheHome)) {
            return null;
        }
        try (Stream<Path> walk = Files.walk(cacheHome)) {
            List<Path> matches = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(jarName))
                    .collect(Collectors.toList());
            return matches.isEmpty() ? null : matches.get(matches.size() - 1);
        }
    }

    private static boolean isZipMagic(Path file) throws Exception {
        if (file == null || !Files.isRegularFile(file) || Files.size(file) < 4) {
            return false;
        }
        byte[] head = Files.readAllBytes(file);
        return head[0] == 'P' && head[1] == 'K'
                && ((head[2] == 3 && head[3] == 4) || (head[2] == 5 && head[3] == 6));
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        assertTrue(Arrays.equals(expected, actual),
                "byte content mismatch (expected " + expected.length + " bytes, got " + actual.length + ")");
    }

    private static void assertArrayPrefix(byte[] expectedFull, Path actualFile, int prefixLen) throws Exception {
        byte[] actual = Files.readAllBytes(actualFile);
        boolean prefixOk = prefixLen <= actual.length;
        for (int i = 0; prefixOk && i < prefixLen; i++) {
            prefixOk = expectedFull[i] == actual[i];
        }
        assertTrue(prefixOk, "resumed prefix bytes must equal the original jar prefix");
    }

    private static boolean waitForLaunchSuccess(Process process, Path outputFile, Path marker) throws Exception {
        long deadline = System.currentTimeMillis() + (TIMEOUT_SECONDS * 1000L);
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(marker)) {
                return true;
            }
            if (Files.exists(outputFile)) {
                String out = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);
                if (out.contains("ITW_INTEGRATION_SUCCESS")) {
                    return true;
                }
            }
            if (!process.isAlive()) {
                return Files.exists(marker)
                        || (Files.exists(outputFile)
                        && new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8)
                        .contains("ITW_INTEGRATION_SUCCESS"));
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static File javaExecutableForHome(String javaHome) {
        String name = System.getProperty("os.name", "").toLowerCase().contains("windows")
                ? "java.exe" : "java";
        return new File(javaHome, "bin/" + name);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static void deleteRecursive(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
    }

    private static final class LaunchResult {
        final boolean success;
        final String output;

        LaunchResult(boolean success, String output) {
            this.success = success;
            this.output = output;
        }
    }

    /** Undertow handler: serves the JNLP and a Range-aware (RFC 7233) jar download. */
    private final class RangeJarHandler implements HttpHandler {

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            String path = exchange.getRelativePath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                return;
            }
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            Path file = webRoot.resolve(path).normalize();
            if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                return;
            }

            byte[] body = Files.readAllBytes(file);
            if (path.endsWith(".jnlp")) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/x-java-jnlp-file");
                exchange.getResponseSender().send(ByteBuffer.wrap(body));
                return;
            }
            if (!path.endsWith(".jar")) {
                exchange.getResponseSender().send(ByteBuffer.wrap(body));
                return;
            }

            jarGets.incrementAndGet();
            String rangeHeader = exchange.getRequestHeaders().getFirst(Headers.RANGE);
            if (rangeHeader != null) {
                lastRangeHeader.set(rangeHeader);
            }
            String ifRange = exchange.getRequestHeaders().getFirst(Headers.IF_RANGE);
            if (ifRange != null) {
                lastIfRangeHeader.set(ifRange);
            }

            // Honour HEAD-style probes with no body but correct full length metadata.
            if (rangeHeader == null) {
                count200.incrementAndGet();
                serveFull(exchange, body);
                return;
            }

            switch (mode) {
                case IGNORE:
                    count200.incrementAndGet();
                    serveFull(exchange, body);
                    return;
                case REJECT_416:
                    count416.incrementAndGet();
                    rejectRange(exchange, body.length);
                    return;
                case IF_RANGE:
                    if (ifRangeStale(ifRange)) {
                        count200.incrementAndGet();
                        serveFull(exchange, body);
                        return;
                    }
                    // fall through to honour
                case HONOR:
                default:
                    break;
            }

            long start = parseRangeStart(rangeHeader, body.length);
            if (mode == RangeMode.MISMATCH) {
                // Claim the suffix starts at 0 so it cannot line up with the requested offset.
                count206.incrementAndGet();
                servePartial(exchange, body, 0, body.length - 1);
                return;
            }
            if (start < 0 || start >= body.length) {
                count416.incrementAndGet();
                rejectRange(exchange, body.length);
                return;
            }
            count206.incrementAndGet();
            servePartial(exchange, body, start, body.length - 1);
        }

        private void serveFull(HttpServerExchange exchange, byte[] body) {
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/java-archive");
            exchange.getResponseHeaders().put(Headers.ACCEPT_RANGES, "bytes");
            addLastModified(exchange);
            exchange.getResponseSender().send(ByteBuffer.wrap(body));
        }

        private void servePartial(HttpServerExchange exchange, byte[] body, long start, long end) {
            exchange.setStatusCode(StatusCodes.PARTIAL_CONTENT);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/java-archive");
            exchange.getResponseHeaders().put(Headers.ACCEPT_RANGES, "bytes");
            exchange.getResponseHeaders().put(Headers.CONTENT_RANGE,
                    "bytes " + start + "-" + end + "/" + body.length);
            addLastModified(exchange);
            int off = (int) start;
            int len = (int) (end - start + 1);
            exchange.getResponseSender().send(ByteBuffer.wrap(body, off, len));
        }

        private void rejectRange(HttpServerExchange exchange, int length) {
            exchange.setStatusCode(416 /* Range Not Satisfiable */);
            exchange.getResponseHeaders().put(Headers.ACCEPT_RANGES, "bytes");
            exchange.getResponseHeaders().put(Headers.CONTENT_RANGE, "bytes */" + length);
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, "0");
            exchange.endExchange();
        }

        private void addLastModified(HttpServerExchange exchange) {
            if (resourceLastModified > 0) {
                exchange.getResponseHeaders().put(Headers.LAST_MODIFIED,
                        Instant.ofEpochMilli(resourceLastModified).atZone(ZoneOffset.UTC)
                                .format(DateTimeFormatter.RFC_1123_DATE_TIME));
            }
        }

        /** RFC 7233 second-granularity If-Range comparison (mirrors the webstart servlet). */
        private boolean ifRangeStale(String ifRange) {
            if (resourceLastModified <= 0 || ifRange == null) {
                return false;
            }
            try {
                long ifRangeMillis = java.time.ZonedDateTime
                        .parse(ifRange.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli();
                return (ifRangeMillis / 1000L) < (resourceLastModified / 1000L);
            } catch (Exception e) {
                return false;
            }
        }

        private long parseRangeStart(String rangeHeader, int length) {
            String h = rangeHeader.trim();
            int eq = h.indexOf('=');
            if (eq == -1 || !h.substring(0, eq).trim().equalsIgnoreCase("bytes")) {
                return -1;
            }
            String spec = h.substring(eq + 1).trim();
            int comma = spec.indexOf(',');
            if (comma != -1) {
                spec = spec.substring(0, comma).trim();
            }
            int dash = spec.indexOf('-');
            if (dash == -1) {
                return -1;
            }
            String start = spec.substring(0, dash).trim();
            try {
                if (start.isEmpty()) {
                    return -1; // suffix form not used by ITW resume requests
                }
                return Long.parseLong(start);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }
}
