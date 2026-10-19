package net.sourceforge.jnlp.integration.packgz;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * HTTP Range (RFC 7233) together with explicit {@code .pack.gz} jars
 * ({@code jnlp.packEnabled}).
 *
 * <p>A pack200-gzip body is not appended onto the unpacked jar. A {@code .pack.gz}
 * larger than the range slot is split with the same multipart Range pattern as a
 * plain jar: each chunk is a slice of the pack.gz file, the slices are
 * concatenated, then the pack is unpacked. These tests serve only the
 * {@code .pack.gz} file (the plain jar 404s).
 */
public class HttpRangePackGzIT {

    private static final String JAVAWS_BIN = System.getProperty("itw.javaws.bin");
    private static final String JDK_HOME = firstNonBlank(
            System.getProperty("itw.jdk11.home"),
            System.getProperty("itw.jdk17.home"),
            System.getProperty("itw.jdk8.home"));
    private static final int TIMEOUT_SECONDS = Integer.getInteger("itw.launch.timeout.seconds", 180);
    private static final String JAR_NAME = "headless-app.jar";
    private static final String CLASS_ENTRY = "net/sourceforge/jnlp/integration/HeadlessJnlpMain.class";
    private static final String PACK_FIXTURE =
            "icedtea-web-integration-2.0.1-SNAPSHOT-headless-app.jar.pack.gz";

    private Undertow server;
    private int httpPort;
    private Path webRoot;
    private Path markerDir;
    private Path cacheHome;
    private Path configHome;
    private byte[] packGz;

    /** How {@code .pack.gz} GETs answer a {@code Range} header. */
    private volatile RangeMode mode = RangeMode.HONOR;

    private final CopyOnWriteArrayList<PackHit> packHits = new CopyOnWriteArrayList<>();

    enum RangeMode {
        /** Honour a byte range with 206. */
        HONOR,
        /** Ignore Range and return the full pack.gz as 200. */
        IGNORE
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(JAVAWS_BIN != null && new File(JAVAWS_BIN).isFile(), "itw.javaws.bin missing");
        assumeTrue(JDK_HOME != null && javaExecutableForHome(JDK_HOME).isFile(), "JDK home missing");

        Path fixture = Paths.get("target", PACK_FIXTURE);
        assumeTrue(Files.isRegularFile(fixture), "pack.gz fixture missing: " + fixture.toAbsolutePath());
        packGz = Files.readAllBytes(fixture);
        assertTrue(packGz.length > 128, "pack.gz fixture must be larger than the small range slot");
        assertEquals(0x1f, packGz[0] & 0xff, "fixture must be gzip");
        assertEquals(0x8b, packGz[1] & 0xff, "fixture must be gzip");

        webRoot = Files.createTempDirectory("itw-packgz-web");
        markerDir = Files.createTempDirectory("itw-packgz-marker");
        cacheHome = Files.createTempDirectory("itw-packgz-cache");
        configHome = Files.createTempDirectory("itw-packgz-config");
        mode = RangeMode.HONOR;
        packHits.clear();

        server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(new PackGzHandler())
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

    /**
     * Slot covers the whole pack. The server answers the probe with 206 of every
     * pack.gz byte. That complete partial response must unpack and launch.
     * The jar href is versioned, so the file is {@code headless-app__V1.0.jar.pack.gz}.
     */
    @Test
    void versionedPackGzComplete206UnpacksAndLaunches() throws Exception {
        String packName = "headless-app__V1.0.jar.pack.gz";
        installPack(packName);
        writeUserDeploymentProperty("deployment.http.range.enabled", "true");
        writeUserDeploymentProperty("deployment.http.range.maxSlotBytes", Integer.toString(packGz.length));

        Path marker = markerDir.resolve("success.marker");
        writeJnlp(marker, true);
        LaunchResult launch = launch(marker);

        assertTrue(launch.success, "versioned pack.gz 206 must unpack and launch:\n" + launch.output);
        List<PackHit> gets = packGets();
        assertEquals(1, gets.size(), "one GET of the versioned pack.gz, saw " + gets);
        PackHit hit = gets.get(0);
        assertEquals(packName, hit.path, "must fetch the versioned .pack.gz, saw " + gets);
        assertTrue(hit.range != null && hit.range.startsWith("bytes=0-"),
                "client must send a Range probe, saw " + hit.range);
        assertEquals(206, hit.status, "server honoured Range with 206: " + hit);
        assertEquals(packGz.length, hit.bodyLength, "206 body must be the entire pack.gz: " + hit);
        assertCachedJarLoadsMainClass();
    }

    /**
     * Slot is smaller than the pack. The client must split the pack.gz the same way
     * as a plain jar (one bounded Range per slot) and unpack the concatenation.
     * A single full-body GET is not this path.
     */
    @Test
    void packGzLargerThanRangeSlotIsSplitReassembledAndLaunches() throws Exception {
        String packName = JAR_NAME + ".pack.gz";
        installPack(packName);
        int slot = 64;
        writeUserDeploymentProperty("deployment.http.range.enabled", "true");
        writeUserDeploymentProperty("deployment.http.range.maxSlotBytes", Integer.toString(slot));
        mode = RangeMode.HONOR;

        Path marker = markerDir.resolve("success.marker");
        writeJnlp(marker, false);
        LaunchResult launch = launch(marker);

        assertTrue(launch.success, "split pack.gz must unpack and launch:\n" + launch.output);
        int chunks = (packGz.length + slot - 1) / slot;
        List<PackHit> gets = packGets();
        assertEquals(chunks, gets.size(), "one Range GET per slot, saw " + gets);
        boolean[] covered = new boolean[packGz.length];
        int coveredBytes = 0;
        for (PackHit hit : gets) {
            assertEquals(206, hit.status, "each chunk must be a 206: " + hit);
            assertNotNull(hit.range, "each chunk must send Range: " + hit);
            long[] range = requestedRange(hit.range, packGz.length);
            assertNotNull(range, "unparsed range " + hit.range);
            assertTrue(hit.bodyLength <= slot, "chunk must not exceed the slot: " + hit);
            assertEquals(range[1] - range[0] + 1, hit.bodyLength, "body must be the requested slice: " + hit);
            for (int i = (int) range[0]; i <= range[1]; i++) {
                assertFalse(covered[i], "overlapping range at " + i + " from " + gets);
                covered[i] = true;
                coveredBytes++;
            }
        }
        assertEquals(packGz.length, coveredBytes, "chunks must cover the pack.gz exactly");
        assertCachedJarLoadsMainClass();
    }

    /**
     * Range-unaware host returns the full pack.gz with 200 even when the client
     * sends Range. The unpack must use that body, not append it to anything.
     */
    @Test
    void rangeUnawarePackGzServerReturnsFull200AndLaunches() throws Exception {
        String packName = JAR_NAME + ".pack.gz";
        installPack(packName);
        writeUserDeploymentProperty("deployment.http.range.enabled", "true");
        writeUserDeploymentProperty("deployment.http.range.maxSlotBytes", "64");
        mode = RangeMode.IGNORE;

        Path marker = markerDir.resolve("success.marker");
        writeJnlp(marker, false);
        LaunchResult launch = launch(marker);

        assertTrue(launch.success, "range-unaware pack.gz server must still launch:\n" + launch.output);
        List<PackHit> full = packGets().stream()
                .filter(h -> h.status == 200 && h.bodyLength == packGz.length)
                .collect(Collectors.toList());
        assertFalse(full.isEmpty(), "expected a full 200 pack.gz body, saw " + packGets());
        assertCachedJarLoadsMainClass();
    }

    /**
     * After a successful pack.gz unpack, truncate the cached jar (ZIP magic kept).
     * Relaunch must fetch the pack again and replace the cache with the same
     * unpacked entries. The pack.gz suffix must not be appended to the jar.
     */
    @Test
    void truncatedCachedJarRefetchesWholePackGz() throws Exception {
        String packName = JAR_NAME + ".pack.gz";
        installPack(packName);
        writeUserDeploymentProperty("deployment.http.range.enabled", "true");
        writeUserDeploymentProperty("deployment.http.range.maxSlotBytes", Integer.toString(packGz.length));
        mode = RangeMode.HONOR;

        Path marker1 = markerDir.resolve("success1.marker");
        writeJnlp(marker1, false);
        LaunchResult first = launch(marker1);
        assertTrue(first.success, "initial pack.gz launch must succeed:\n" + first.output);

        Path cached = findCachedJar(cacheHome, JAR_NAME);
        assertNotNull(cached, "unpacked jar must be cached");
        Map<String, byte[]> before = zipEntries(cached);
        assertNotNull(before.get(CLASS_ENTRY), "unpacked jar must contain the main class");
        long unpackedLength = Files.size(cached);

        int cut = Math.max(8, (int) unpackedLength / 2);
        byte[] prefix = Arrays.copyOf(Files.readAllBytes(cached), cut);
        Files.write(cached, prefix);
        assertEquals(cut, Files.size(cached), "precondition: cache jar is truncated");
        assertTrue(prefix[0] == 'P' && prefix[1] == 'K', "precondition: ZIP magic remains so resume is eligible");

        packHits.clear();
        Path marker2 = markerDir.resolve("success2.marker");
        writeJnlp(marker2, false);
        LaunchResult second = launch(marker2);

        assertTrue(second.success, "relaunch after truncating a pack.gz unpack must succeed:\n" + second.output);
        assertTrue(packGets().stream().anyMatch(h -> packName.equals(h.path) && h.bodyLength == packGz.length),
                "relaunch must transfer the whole pack.gz again, saw " + packGets());

        Path restored = findCachedJar(cacheHome, JAR_NAME);
        assertNotNull(restored, "restored jar must be cached");
        assertEquals(unpackedLength, Files.size(restored),
                "restored jar must match the original unpack length (no appended pack bytes)");
        assertArrayEquals(before.get(CLASS_ENTRY), zipEntries(restored).get(CLASS_ENTRY),
                "main class bytes must match the first unpack");
    }

    private void assertCachedJarLoadsMainClass() throws Exception {
        Path cached = findCachedJar(cacheHome, JAR_NAME);
        assertNotNull(cached, "unpacked " + JAR_NAME + " must be cached under " + cacheHome);
        byte[] main = zipEntries(cached).get(CLASS_ENTRY);
        assertNotNull(main, "cached jar must contain " + CLASS_ENTRY);
        assertTrue(main.length > 8 && main[0] == (byte) 0xCA && main[1] == (byte) 0xFE
                        && main[2] == (byte) 0xBA && main[3] == (byte) 0xBE,
                "cached main class must be a class file");
    }

    private void installPack(String fileName) throws Exception {
        Files.write(webRoot.resolve(fileName), packGz);
    }

    private List<PackHit> packGets() {
        List<PackHit> gets = new ArrayList<>();
        for (PackHit hit : packHits) {
            if ("GET".equals(hit.method) && hit.path.endsWith(".pack.gz")) {
                gets.add(hit);
            }
        }
        return gets;
    }

    /** {@code bytes=start-end} or {@code bytes=start-} against a body of {@code length}. */
    private static long[] requestedRange(String rangeHeader, int length) {
        String h = rangeHeader.trim();
        int eq = h.indexOf('=');
        if (eq < 0 || !h.substring(0, eq).trim().equalsIgnoreCase("bytes")) {
            return null;
        }
        String spec = h.substring(eq + 1).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        try {
            long start = Long.parseLong(spec.substring(0, dash).trim());
            String endText = spec.substring(dash + 1).trim();
            long end = endText.isEmpty() ? length - 1L : Long.parseLong(endText);
            if (start < 0 || end >= length || start > end) {
                return null;
            }
            return new long[]{start, end};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void writeJnlp(Path marker, boolean versioned) throws Exception {
        String versionAttr = versioned ? " version=\"1.0\"" : "";
        String versionProp = versioned
                ? "    <property name=\"jnlp.versionEnabled\" value=\"true\"/>\n"
                : "";
        String jnlp = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"http://127.0.0.1:" + httpPort + "/\" href=\"packgz-range.jnlp\">\n"
                + "  <information><title>ITW pack.gz range</title><vendor>ITW</vendor></information>\n"
                + "  <security><all-permissions/></security>\n"
                + "  <resources>\n"
                + versionProp
                + "    <property name=\"jnlp.packEnabled\" value=\"true\"/>\n"
                + "    <property name=\"itw.test.success.marker\" value=\""
                + marker.toAbsolutePath().toString().replace("\\", "/") + "\"/>\n"
                + "    <j2se version=\"1.8+\"/>\n"
                + "    <jar href=\"" + JAR_NAME + "\"" + versionAttr + " main=\"true\"/>\n"
                + "  </resources>\n"
                + "  <application-desc main-class=\"net.sourceforge.jnlp.integration.HeadlessJnlpMain\"/>\n"
                + "</jnlp>\n";
        Files.write(webRoot.resolve("packgz-range.jnlp"), jnlp.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUserDeploymentProperty(String key, String value) throws Exception {
        Path dir = configHome.resolve("icedtea-web");
        Files.createDirectories(dir);
        Path file = dir.resolve("deployment.properties");
        String existing = Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
        if (!existing.isEmpty() && !existing.endsWith("\n")) {
            existing += "\n";
        }
        existing += key + "=" + value + "\n";
        Files.write(file, existing.getBytes(StandardCharsets.UTF_8));
    }

    private LaunchResult launch(Path marker) throws Exception {
        String jnlpUrl = "http://127.0.0.1:" + httpPort + "/packgz-range.jnlp";
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
        Path outputFile = Files.createTempFile("itw-packgz", ".log");
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

    private static Map<String, byte[]> zipEntries(Path jar) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile jf = new JarFile(jar.toFile(), false)) {
            for (JarEntry entry : Collections.list(jf.entries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream in = jf.getInputStream(entry)) {
                    entries.put(entry.getName(), readAll(in));
                }
            }
        }
        return entries;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
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
            if (matches.isEmpty()) {
                return null;
            }
            Path largest = matches.get(0);
            for (Path candidate : matches) {
                if (Files.size(candidate) > Files.size(largest)) {
                    largest = candidate;
                }
            }
            return largest;
        }
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

    private static final class PackHit {
        final String method;
        final String path;
        final String range;
        final int status;
        final int bodyLength;

        PackHit(String method, String path, String range, int status, int bodyLength) {
            this.method = method;
            this.path = path;
            this.range = range;
            this.status = status;
            this.bodyLength = bodyLength;
        }

        @Override
        public String toString() {
            return method + " " + path + " range=" + range + " status=" + status + " body=" + bodyLength;
        }
    }

    /** Serves the JNLP and a Range-aware {@code .pack.gz}. Any other path is 404. */
    private final class PackGzHandler implements HttpHandler {

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            String path = exchange.getRelativePath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.endExchange();
                return;
            }
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            Path file = webRoot.resolve(path).normalize();
            if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.endExchange();
                return;
            }
            byte[] body = Files.readAllBytes(file);
            if (path.endsWith(".jnlp")) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/x-java-jnlp-file");
                exchange.getResponseSender().send(ByteBuffer.wrap(body));
                return;
            }
            if (!path.endsWith(".pack.gz")) {
                exchange.getResponseSender().send(ByteBuffer.wrap(body));
                return;
            }

            String method = exchange.getRequestMethod().toString();
            String rangeHeader = exchange.getRequestHeaders().getFirst(Headers.RANGE);
            boolean head = "HEAD".equalsIgnoreCase(method);
            if (head || rangeHeader == null || mode == RangeMode.IGNORE) {
                int status = StatusCodes.OK;
                record(method, path, rangeHeader, status, head ? 0 : body.length);
                serveFull(exchange, body, head);
                return;
            }

            long[] range = parseRange(rangeHeader, body.length);
            if (range == null) {
                record(method, path, rangeHeader, 416, 0);
                rejectRange(exchange, body.length);
                return;
            }
            int length = (int) (range[1] - range[0] + 1);
            record(method, path, rangeHeader, StatusCodes.PARTIAL_CONTENT, length);
            servePartial(exchange, body, range[0], range[1], length);
        }

        private void record(String method, String path, String range, int status, int bodyLength) {
            packHits.add(new PackHit(method, path, range, status, bodyLength));
        }

        private void serveFull(HttpServerExchange exchange, byte[] body, boolean head) {
            exchange.setStatusCode(StatusCodes.OK);
            packHeaders(exchange, body.length);
            if (head) {
                exchange.endExchange();
                return;
            }
            exchange.getResponseSender().send(ByteBuffer.wrap(body));
        }

        private void servePartial(HttpServerExchange exchange, byte[] body, long start, long end, int sliceLength) {
            exchange.setStatusCode(StatusCodes.PARTIAL_CONTENT);
            // Content-Length is the slice. The complete pack.gz size is only in Content-Range.
            packHeaders(exchange, sliceLength);
            exchange.getResponseHeaders().put(Headers.CONTENT_RANGE,
                    "bytes " + start + "-" + end + "/" + body.length);
            exchange.getResponseSender().send(ByteBuffer.wrap(body, (int) start, sliceLength));
        }

        private void rejectRange(HttpServerExchange exchange, int length) {
            exchange.setStatusCode(416);
            exchange.getResponseHeaders().put(Headers.ACCEPT_RANGES, "bytes");
            exchange.getResponseHeaders().put(Headers.CONTENT_ENCODING, "pack200-gzip");
            exchange.getResponseHeaders().put(Headers.CONTENT_RANGE, "bytes */" + length);
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, "0");
            exchange.endExchange();
        }

        private void packHeaders(HttpServerExchange exchange, int length) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/x-java-pack200");
            exchange.getResponseHeaders().put(Headers.CONTENT_ENCODING, "pack200-gzip");
            exchange.getResponseHeaders().put(Headers.ACCEPT_RANGES, "bytes");
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, Integer.toString(length));
        }

        /**
         * Single byte range {@code bytes=start-end} or {@code bytes=start-}.
         * Unsatisfiable ranges return null so the caller sends 416.
         */
        private long[] parseRange(String rangeHeader, int length) {
            String h = rangeHeader.trim();
            int eq = h.indexOf('=');
            if (eq == -1 || !h.substring(0, eq).trim().equalsIgnoreCase("bytes")) {
                return null;
            }
            String spec = h.substring(eq + 1).trim();
            int comma = spec.indexOf(',');
            if (comma != -1) {
                spec = spec.substring(0, comma).trim();
            }
            int dash = spec.indexOf('-');
            if (dash == -1) {
                return null;
            }
            String s = spec.substring(0, dash).trim();
            String e = spec.substring(dash + 1).trim();
            try {
                if (s.isEmpty()) {
                    return null;
                }
                long start = Long.parseLong(s);
                long end = e.isEmpty() ? length - 1L : Long.parseLong(e);
                if (length <= 0 || start >= length || start > end) {
                    return null;
                }
                if (end >= length) {
                    end = length - 1L;
                }
                return new long[]{start, end};
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }
}
