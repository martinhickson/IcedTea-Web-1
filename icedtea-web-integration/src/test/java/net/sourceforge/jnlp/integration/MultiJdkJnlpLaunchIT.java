package net.sourceforge.jnlp.integration;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Launches a JNLP application via the shaded ITW {@code javaws} wrapper under
 * JDK 8, 11, 17, and 21.
 */
public class MultiJdkJnlpLaunchIT {

    private static final String JAVAWS_BIN = System.getProperty("itw.javaws.bin");
    private static final int TIMEOUT_SECONDS = Integer.getInteger("itw.launch.timeout.seconds", 180);

    private Undertow server;
    private int httpPort;
    private Path webRoot;

    static Stream<JdkCase> jdkCases() {
        return Stream.of(
                new JdkCase("8", System.getProperty("itw.jdk8.home")),
                new JdkCase("11", System.getProperty("itw.jdk11.home")),
                new JdkCase("17", System.getProperty("itw.jdk17.home")),
                new JdkCase("21", System.getProperty("itw.jdk21.home"))
        );
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(JAVAWS_BIN != null, "itw.javaws.bin must be set by Maven Failsafe");
        File javaws = new File(JAVAWS_BIN);
        assumeTrue(javaws.isFile() && javaws.canExecute(), "javaws launcher missing: " + JAVAWS_BIN);

        webRoot = Files.createTempDirectory("itw-jnlp-web");
        copyHeadlessAppJar();

        server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(new StaticFileHandler(webRoot))
                .build();
        server.start();
        httpPort = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.stop();
        }
        if (webRoot != null) {
            deleteRecursive(webRoot);
        }
    }

    @ParameterizedTest(name = "launch JNLP with JDK {0}")
    @MethodSource("jdkCases")
    void launchJnlpWithJdk(JdkCase jdk) throws Exception {
        assumeTrue(jdk.home != null && !jdk.home.isEmpty(), "JDK " + jdk.label + " home not configured");
        File javaBin = new File(jdk.home, "bin/java");
        assumeTrue(javaBin.isFile(), "java not found for JDK " + jdk.label + ": " + javaBin);

        Path marker = Files.createTempDirectory("itw-success-jdk" + jdk.label).resolve("success.marker");
        try {
            writeJnlp("headless-test.jnlp", "http://127.0.0.1:" + httpPort + "/", marker);
            String jnlpUrl = "http://127.0.0.1:" + httpPort + "/headless-test.jnlp";
            List<String> command = new ArrayList<>();
            command.add(JAVAWS_BIN);
            command.add("-headless");
            command.add("-verbose");
            command.add("-Xtrustall");
            command.add("-Xnofork");
            if (Integer.parseInt(jdk.label) >= 21) {
                command.add("-J-Djava.security.manager=allow");
            }
            command.add(jnlpUrl);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put("JAVA_HOME", jdk.home);
            pb.environment().put("ICEDTEA_WEB_SPLASH", "none");
            pb.redirectErrorStream(true);
            Path outputFile = Files.createTempFile("itw-javaws-jdk" + jdk.label, ".log");
            pb.redirectOutput(outputFile.toFile());

            Process process = pb.start();
            boolean launched = waitForLaunchSuccess(process, outputFile, marker);
            String output = readFile(outputFile);
            System.out.println("--- javaws output (JDK " + jdk.label + ") ---\n" + output);

            if (process.isAlive()) {
                process.destroy();
                process.waitFor(5, TimeUnit.SECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            } else if (!launched) {
                assertEquals(0, process.exitValue(),
                        "javaws exited with non-zero status on JDK " + jdk.label + "\n" + output);
            }
            assertTrue(launched, "launch did not report success on JDK " + jdk.label + ":\n" + output);
            assertTrue(output.contains("ITW_INTEGRATION_SUCCESS") || Files.exists(marker),
                    "launch did not report success on JDK " + jdk.label + ":\n" + output);
            if (output.contains("ITW_INTEGRATION_SUCCESS")) {
                assertTrue(output.contains("ok jdk="), "launch output on JDK " + jdk.label + ":\n" + output);
            } else if (Files.exists(marker)) {
                String markerContent = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8);
                assertTrue(markerContent.contains("ok jdk="),
                        "unexpected marker file on JDK " + jdk.label + ": " + markerContent);
            }
        } finally {
            if (marker != null && marker.getParent() != null) {
                deleteRecursive(marker.getParent());
            }
        }
    }

    private static boolean waitForLaunchSuccess(Process process, Path outputFile, Path marker)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + (TIMEOUT_SECONDS * 1000L);
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(marker)) {
                return true;
            }
            if (Files.exists(outputFile) && readFile(outputFile).contains("ITW_INTEGRATION_SUCCESS")) {
                return true;
            }
            if (!process.isAlive()) {
                return Files.exists(outputFile) && readFile(outputFile).contains("ITW_INTEGRATION_SUCCESS");
            }
            Thread.sleep(500);
        }
        return false;
    }

    private void copyHeadlessAppJar() throws IOException {
        Path source = Paths.get("target").resolve("icedtea-web-integration-1.0.1-SNAPSHOT-headless-app.jar");
        assumeTrue(Files.exists(source), "headless-app jar not built: " + source.toAbsolutePath());
        Files.copy(source, webRoot.resolve("headless-app.jar"));
    }

    private void writeJnlp(String fileName, String codebase, Path marker) throws IOException {
        String markerProperty = "";
        if (marker != null) {
            markerProperty = "    <property name=\"itw.test.success.marker\" value=\""
                    + marker.toAbsolutePath() + "\"/>\n";
        }
        String jnlp = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"" + codebase + "\" href=\"" + fileName + "\">\n"
                + "  <information><title>ITW headless integration</title><vendor>ITW</vendor></information>\n"
                + "  <security><all-permissions/></security>\n"
                + "  <resources>\n"
                + markerProperty
                + "    <j2se version=\"1.8+\"/>\n"
                + "    <jar href=\"headless-app.jar\" main=\"true\"/>\n"
                + "  </resources>\n"
                + "  <application-desc main-class=\"net.sourceforge.jnlp.integration.HeadlessJnlpMain\"/>\n"
                + "</jnlp>\n";
        Files.write(webRoot.resolve("headless-test.jnlp"), jnlp.getBytes(StandardCharsets.UTF_8));
    }

    private static String readFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            return "";
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // best effort cleanup
                    }
                });
    }

    private static final class JdkCase {
        final String label;
        final String home;

        JdkCase(String label, String home) {
            this.label = label;
            this.home = home;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static final class StaticFileHandler implements HttpHandler {
        private final Path webRoot;

        StaticFileHandler(Path webRoot) {
            this.webRoot = webRoot;
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
            byte[] body = Files.readAllBytes(file);
            String contentType = path.endsWith(".jnlp") ? "application/x-java-jnlp-file" : "application/java-archive";
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
            exchange.getResponseSender().send(java.nio.ByteBuffer.wrap(body));
        }
    }
}
