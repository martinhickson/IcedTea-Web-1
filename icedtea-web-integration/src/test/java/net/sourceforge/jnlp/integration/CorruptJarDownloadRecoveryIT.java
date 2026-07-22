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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Replicates the production Windows failure mode end-to-end:
 * <ol>
 *   <li>Launch once so a real jar is cached</li>
 *   <li>Overwrite that cache entry with the JNLP version-servlet body
 *       {@code 11 Could not locate requested version} and {@code last-modified=0}
 *       (exactly what stuck in {@code sonata-rda-launcher.jar})</li>
 *   <li>Launch again — must re-download and succeed; must NOT die in
 *       {@code JarCertVerifier} with {@code ZipException: zip END header not found}</li>
 * </ol>
 */
public class CorruptJarDownloadRecoveryIT {

    private static final String JAVAWS_BIN = System.getProperty("itw.javaws.bin");
    private static final String JDK_HOME = firstNonBlank(
            System.getProperty("itw.jdk11.home"),
            System.getProperty("itw.jdk17.home"),
            System.getProperty("itw.jdk8.home"));
    private static final int TIMEOUT_SECONDS = Integer.getInteger("itw.launch.timeout.seconds", 180);
    private static final byte[] VERSION_MISS =
            "11 Could not locate requested version\r\n".getBytes(StandardCharsets.US_ASCII);

    private Undertow server;
    private int httpPort;
    private Path webRoot;
    private Path markerDir;
    private Path cacheHome;
    private Path configHome;
    private final AtomicInteger jarHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(JAVAWS_BIN != null && new File(JAVAWS_BIN).isFile(), "itw.javaws.bin missing");
        assumeTrue(JDK_HOME != null && javaExecutableForHome(JDK_HOME).isFile(), "JDK home missing");

        webRoot = Files.createTempDirectory("itw-sticky-poison-web");
        markerDir = Files.createTempDirectory("itw-sticky-poison-marker");
        cacheHome = Files.createTempDirectory("itw-sticky-poison-cache");
        configHome = Files.createTempDirectory("itw-sticky-poison-config");

        Path source = Paths.get("target", "icedtea-web-integration-2.0.1-SNAPSHOT-headless-app.jar");
        assumeTrue(Files.exists(source), "headless-app jar missing: " + source.toAbsolutePath());
        Files.copy(source, webRoot.resolve("headless-app.jar"));

        server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(new CountingJarHandler(webRoot, jarHits))
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
    void stickyVersionMissPoisonInCacheMustNotCauseZipExceptionOnRelaunch() throws Exception {
        Path marker1 = markerDir.resolve("success1.marker");
        writeJnlp(marker1);

        LaunchResult first = launch(marker1);
        assertTrue(first.success, "initial launch must populate cache:\n" + first.output);
        assertTrue(jarHits.get() >= 1, "jar must have been fetched");

        Path cachedJar = findCachedJar(cacheHome, "headless-app.jar");
        assertTrue(cachedJar != null && Files.isRegularFile(cachedJar),
                "expected cached headless-app.jar under " + cacheHome);
        Path info = cachedJar.resolveSibling(cachedJar.getFileName().toString() + ".info");
        assertTrue(Files.isRegularFile(info), "missing cache .info for " + cachedJar);

        // Production sticky state: version-miss text + last-modified=0 + matching content-length.
        Files.write(cachedJar, VERSION_MISS);
        Files.write(info, buildPoisonInfo(VERSION_MISS.length).getBytes(StandardCharsets.ISO_8859_1));
        assertFalse(isZipMagic(cachedJar), "precondition: cache is poisoned");

        int hitsAfterPoison = jarHits.get();
        Path marker2 = markerDir.resolve("success2.marker");
        writeJnlp(marker2);
        LaunchResult second = launch(marker2);

        assertFalse(second.output.contains("zip END header not found"),
                "PRODUCTION FAILURE MODE still present (JarCertVerifier ZipException):\n" + second.output);
        assertTrue(second.success,
                "relaunch with sticky version-miss poison must recover by re-download:\n" + second.output);
        assertTrue(jarHits.get() > hitsAfterPoison,
                "expected re-download after poison; hits before=" + hitsAfterPoison
                        + " after=" + jarHits.get());
        assertTrue(isZipMagic(findCachedJar(cacheHome, "headless-app.jar")),
                "cache must hold a real jar again");
    }

    private void writeJnlp(Path marker) throws Exception {
        String jnlp = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"http://127.0.0.1:" + httpPort + "/\" href=\"sticky-poison.jnlp\">\n"
                + "  <information><title>ITW sticky poison</title><vendor>ITW</vendor></information>\n"
                + "  <security><all-permissions/></security>\n"
                + "  <resources>\n"
                + "    <property name=\"jnlp.versionEnabled\" value=\"true\"/>\n"
                + "    <property name=\"itw.test.success.marker\" value=\""
                + marker.toAbsolutePath().toString().replace("\\", "/") + "\"/>\n"
                + "    <j2se version=\"1.8+\"/>\n"
                + "    <jar href=\"headless-app.jar\" version=\"1.0\" main=\"true\"/>\n"
                + "  </resources>\n"
                + "  <application-desc main-class=\"net.sourceforge.jnlp.integration.HeadlessJnlpMain\"/>\n"
                + "</jnlp>\n";
        Files.write(webRoot.resolve("sticky-poison.jnlp"), jnlp.getBytes(StandardCharsets.UTF_8));
        // Version-encoded name some servers use; also keep plain jar for fallback URL creators.
        Files.copy(webRoot.resolve("headless-app.jar"),
                webRoot.resolve("headless-app__V1.0.jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private LaunchResult launch(Path marker) throws Exception {
        String jnlpUrl = "http://127.0.0.1:" + httpPort + "/sticky-poison.jnlp";
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
        Path outputFile = Files.createTempFile("itw-sticky-poison", ".log");
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

    private static String buildPoisonInfo(int length) {
        return "#automatically generated - do not edit\n"
                + "#Wed Jul 22 11:31:24 NZST 2026\n"
                + "content-length=" + length + "\n"
                + "last-modified=0\n"
                + "last-updated=" + System.currentTimeMillis() + "\n"
                + "jnlp-path=sticky-poison.jnlp\n";
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

    private static final class CountingJarHandler implements HttpHandler {
        private final Path webRoot;
        private final AtomicInteger jarHits;

        CountingJarHandler(Path webRoot, AtomicInteger jarHits) {
            this.webRoot = webRoot;
            this.jarHits = jarHits;
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
            Path file = webRoot.resolve(path).normalize();
            if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                return;
            }
            if (path.endsWith(".jar")) {
                jarHits.incrementAndGet();
            }
            byte[] body = Files.readAllBytes(file);
            String contentType = path.endsWith(".jnlp")
                    ? "application/x-java-jnlp-file" : "application/java-archive";
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
            exchange.getResponseSender().send(ByteBuffer.wrap(body));
        }
    }
}
