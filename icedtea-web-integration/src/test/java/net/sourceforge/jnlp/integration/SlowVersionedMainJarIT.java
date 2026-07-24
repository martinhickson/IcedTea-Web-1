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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Production race (Sonata RDA nested launch):
 * <ul>
 *   <li>Server {@code __V} GETs eventually return HTTP 200</li>
 *   <li>Client checked main jar too early and/or against a bad cache entry</li>
 *   <li>Reported {@code Unknown Main-Class} / {@code JAR ... not found}</li>
 * </ul>
 * Launch must wait out a slow versioned main jar (and survive a first failed attempt)
 * instead of failing with Unknown Main-Class.
 */
public class SlowVersionedMainJarIT {

    private static final String JAVAWS_BIN = System.getProperty("itw.javaws.bin");
    private static final String JDK_HOME = firstNonBlank(
            System.getProperty("itw.jdk11.home"),
            System.getProperty("itw.jdk17.home"),
            System.getProperty("itw.jdk8.home"));
    private static final int TIMEOUT_SECONDS = Integer.getInteger("itw.launch.timeout.seconds", 180);
    private static final long MAIN_JAR_DELAY_MS = 2500L;

    private Undertow server;
    private int httpPort;
    private Path webRoot;
    private Path markerDir;
    private Path cacheHome;
    private Path configHome;
    private final AtomicInteger versionedJarHits = new AtomicInteger();
    private final AtomicInteger versionedJarFailures = new AtomicInteger();
    private final AtomicInteger versionedJarRejects = new AtomicInteger();
    /** When true, unversioned headless-app.jar 404s so the client must use __V. */
    private volatile boolean requireVersionedJar;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(JAVAWS_BIN != null && new File(JAVAWS_BIN).isFile(), "itw.javaws.bin missing");
        assumeTrue(JDK_HOME != null && javaExecutableForHome(JDK_HOME).isFile(), "JDK home missing");

        versionedJarHits.set(0);
        versionedJarFailures.set(0);
        versionedJarRejects.set(0);
        requireVersionedJar = false;

        webRoot = Files.createTempDirectory("itw-slow-main-web");
        markerDir = Files.createTempDirectory("itw-slow-main-marker");
        cacheHome = Files.createTempDirectory("itw-slow-main-cache");
        configHome = Files.createTempDirectory("itw-slow-main-config");

        Path source = Paths.get("target", "icedtea-web-integration-2.0.1-SNAPSHOT-headless-app.jar");
        assumeTrue(Files.exists(source), "headless-app jar missing: " + source.toAbsolutePath());
        Files.copy(source, webRoot.resolve("headless-app.jar"));
        Files.copy(source, webRoot.resolve("headless-app__V1.0.jar"));

        server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(new SlowVersionedJarHandler(webRoot, versionedJarHits, versionedJarFailures,
                        versionedJarRejects, () -> requireVersionedJar))
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

    @Test
    void slowVersionedMainJarMustNotCauseUnknownMainClass() throws Exception {
        Path marker = markerDir.resolve("success.marker");
        seedGhostMainJarCacheEntry();
        writeJnlp(marker);

        LaunchResult result = launch(marker);

        assertFalse(result.output.contains("Unknown Main-Class"),
                "PRODUCTION FAILURE MODE: Unknown Main-Class while versioned main jar in flight:\n"
                        + result.output);
        assertFalse(result.output.contains("JAR http://127.0.0.1:" + httpPort + "/headless-app.jar not found")
                        && !result.success,
                "PRODUCTION FAILURE MODE: main JAR not found before download finished:\n"
                        + result.output);
        assertTrue(result.success,
                "slow versioned main jar launch must succeed:\n" + result.output);
        assertTrue(versionedJarHits.get() >= 1,
                "expected versioned __V jar fetch; hits=" + versionedJarHits.get());
    }

    @Test
    void firstFailedVersionedFetchThenSlowSuccessMustNotUnknownMainClass() throws Exception {
        requireVersionedJar = true; // no plain-jar fallback — must recover on __V
        versionedJarFailures.set(1); // first __V GET → 503, then delayed 200
        Path marker = markerDir.resolve("success-retry.marker");
        seedGhostMainJarCacheEntry();
        writeJnlp(marker);

        LaunchResult result = launch(marker);

        assertFalse(result.output.contains("Unknown Main-Class"),
                "must recover from first failed versioned fetch without Unknown Main-Class:\n"
                        + result.output);
        assertTrue(result.success,
                "retry after failed versioned fetch must succeed:\n" + result.output);
        assertTrue(versionedJarRejects.get() >= 1,
                "expected at least one failed __V attempt; rejects=" + versionedJarRejects.get());
        assertTrue(versionedJarHits.get() >= 1,
                "expected successful __V GET after failure; hits=" + versionedJarHits.get()
                        + " rejects=" + versionedJarRejects.get());
    }

    /**
     * recently_used + .info for main jar URL, but no jar bytes — the ghost LRU shape
     * that made checkForMain see "JAR ... not found" against a bad cache entry.
     */
    private void seedGhostMainJarCacheEntry() throws Exception {
        Path cacheRoot = cacheHome.resolve("icedtea-web").resolve("cache");
        Path slot = cacheRoot.resolve("1").resolve("http").resolve("127.0.0.1")
                .resolve(String.valueOf(httpPort)).resolve("headless-app.jar");
        Files.createDirectories(slot.getParent());
        Path info = Paths.get(slot.toString() + ".info");
        String infoBody = "#automatically generated - do not edit\n"
                + "content-length=1\n"
                + "last-modified=0\n"
                + "last-updated=" + System.currentTimeMillis() + "\n"
                + "jnlp-path=slow-main.jnlp\n";
        Files.write(info, infoBody.getBytes(StandardCharsets.ISO_8859_1));
        // No headless-app.jar file — ghost .info-only slot.

        Path recentlyUsed = cacheRoot.resolve("recently_used");
        String path = slot.toAbsolutePath().toString().replace("\\", "\\\\").replace(":", "\\:");
        String entry = System.currentTimeMillis() + ",1=" + path + "\n";
        Files.write(recentlyUsed, entry.getBytes(StandardCharsets.ISO_8859_1));
    }

    private void writeJnlp(Path marker) throws Exception {
        String jnlp = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"http://127.0.0.1:" + httpPort + "/\" href=\"slow-main.jnlp\">\n"
                + "  <information><title>ITW slow versioned main</title><vendor>ITW</vendor></information>\n"
                + "  <security><all-permissions/></security>\n"
                + "  <resources>\n"
                + "    <property name=\"jnlp.versionEnabled\" value=\"true\"/>\n"
                + "    <property name=\"jnlp.packEnabled\" value=\"false\"/>\n"
                + "    <property name=\"itw.test.success.marker\" value=\""
                + marker.toAbsolutePath().toString().replace("\\", "/") + "\"/>\n"
                + "    <j2se version=\"1.8+\"/>\n"
                + "    <jar href=\"headless-app.jar\" version=\"1.0\" main=\"true\"/>\n"
                + "  </resources>\n"
                + "  <application-desc main-class=\"net.sourceforge.jnlp.integration.HeadlessJnlpMain\"/>\n"
                + "</jnlp>\n";
        Files.write(webRoot.resolve("slow-main.jnlp"), jnlp.getBytes(StandardCharsets.UTF_8));
    }

    private LaunchResult launch(Path marker) throws Exception {
        String jnlpUrl = "http://127.0.0.1:" + httpPort + "/slow-main.jnlp";
        List<String> command = new ArrayList<>();
        // scripts/javaws.sh is not a Win32 PE — ProcessBuilder must invoke bash on Windows.
        if (isWindows()) {
            command.add("bash");
        }
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
        Path outputFile = Files.createTempFile("itw-slow-main", ".log");
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

    private static boolean waitForLaunchSuccess(Process process, Path outputFile, Path marker)
            throws Exception {
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
                if (out.contains("Unknown Main-Class")) {
                    return false;
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

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private static File javaExecutableForHome(String javaHome) {
        String name = isWindows() ? "java.exe" : "java";
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
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private static final class LaunchResult {
        final boolean success;
        final String output;

        LaunchResult(boolean success, String output) {
            this.success = success;
            this.output = output;
        }
    }

    private static final class SlowVersionedJarHandler implements HttpHandler {
        private final Path webRoot;
        private final AtomicInteger versionedJarHits;
        private final AtomicInteger versionedJarFailures;
        private final AtomicInteger versionedJarRejects;
        private final java.util.function.BooleanSupplier requireVersionedJar;

        SlowVersionedJarHandler(Path webRoot, AtomicInteger versionedJarHits,
                AtomicInteger versionedJarFailures, AtomicInteger versionedJarRejects,
                java.util.function.BooleanSupplier requireVersionedJar) {
            this.webRoot = webRoot;
            this.versionedJarHits = versionedJarHits;
            this.versionedJarFailures = versionedJarFailures;
            this.versionedJarRejects = versionedJarRejects;
            this.requireVersionedJar = requireVersionedJar;
        }

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
            // Force versioned protocol when testing ERROR→retry (no plain-jar escape hatch).
            if (requireVersionedJar.getAsBoolean() && "headless-app.jar".equals(path)) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                return;
            }
            Path file = webRoot.resolve(path).normalize();
            if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                return;
            }

            boolean versionedJar = path.contains("__V") && path.endsWith(".jar");
            boolean isGet = exchange.getRequestMethod() != null
                    && "GET".equalsIgnoreCase(exchange.getRequestMethod().toString());
            if (versionedJar && isGet) {
                if (versionedJarFailures.getAndDecrement() > 0) {
                    versionedJarRejects.incrementAndGet();
                    exchange.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
                    exchange.getResponseSender().send("temporary version miss");
                    return;
                }
                // Hold the successful __V body so a premature checkForMain would lose the race.
                Thread.sleep(MAIN_JAR_DELAY_MS);
                versionedJarHits.incrementAndGet();
            }

            byte[] body = Files.readAllBytes(file);
            String contentType = path.endsWith(".jnlp")
                    ? "application/x-java-jnlp-file" : "application/java-archive";
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(body.length));
            exchange.getResponseSender().send(ByteBuffer.wrap(body));
        }
    }
}
