package net.sourceforge.jnlp.it.localhost;

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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A trusted JNLP app must be able to connect using the hostname
 * {@code localhost}. {@code JNLPSecurityManager} must not deny
 * {@code SocketPermission} resolve (the old PTR skip did).
 */
public class LocalhostConnectIT {

    private static final String JAVAWS_BIN = System.getProperty("itw.javaws.bin");
    private static final String JDK_HOME = System.getProperty("itw.jdk11.home");
    private static final String SIGNED_APP_JAR = System.getProperty("itw.signed.app.jar");
    private static final int TIMEOUT_SECONDS = Integer.getInteger("itw.launch.timeout.seconds", 180);

    private Undertow server;
    private int httpPort;
    private Path webRoot;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(JAVAWS_BIN != null && new File(JAVAWS_BIN).isFile(),
                "itw.javaws.bin must be set by Maven Failsafe");
        assumeTrue(JDK_HOME != null && new File(JDK_HOME, "bin/java").isFile(),
                "JDK 11 home missing: " + JDK_HOME);

        Path signedJar = Paths.get(SIGNED_APP_JAR != null ? SIGNED_APP_JAR : "");
        assumeTrue(Files.isRegularFile(signedJar),
                "webstart-signed app jar missing: " + signedJar.toAbsolutePath()
                        + " (webstart-maven-plugin jnlp-single must run at package)");

        webRoot = Files.createTempDirectory("itw-localhost-web");
        Files.copy(signedJar, webRoot.resolve("localhost-app.jar"));
        Files.write(webRoot.resolve("ping.txt"), "pong\n".getBytes(StandardCharsets.UTF_8));

        server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(new StaticFileHandler(webRoot))
                .build();
        server.start();
        httpPort = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();

        String codebase = "http://localhost:" + httpPort + "/";
        String pingUrl = codebase + "ping.txt";
        String jnlp = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"" + codebase + "\" href=\"localhost-connect.jnlp\">\n"
                + "  <information><title>ITW localhost connect</title><vendor>ITW</vendor></information>\n"
                + "  <security><all-permissions/></security>\n"
                + "  <resources>\n"
                + "    <property name=\"itw.test.localhost.url\" value=\"" + pingUrl + "\"/>\n"
                + "    <j2se version=\"11+\"/>\n"
                + "    <jar href=\"localhost-app.jar\" main=\"true\"/>\n"
                + "  </resources>\n"
                + "  <application-desc main-class=\"net.sourceforge.jnlp.it.localhost.LocalhostConnectApp\"/>\n"
                + "</jnlp>\n";
        Files.write(webRoot.resolve("localhost-connect.jnlp"), jnlp.getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (webRoot != null) {
            Files.walk(webRoot).sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Test
    void trustedAppCanConnectUsingLocalhostHostname() throws Exception {
        String jnlpUrl = "http://localhost:" + httpPort + "/localhost-connect.jnlp";
        ProcessBuilder processBuilder = new ProcessBuilder(
                JAVAWS_BIN,
                "-headless",
                "-verbose",
                "-Xtrustall",
                "--auto-accept-https-certificate=true",
                "-Xnofork",
                jnlpUrl
        );
        processBuilder.environment().put("JAVA_HOME", JDK_HOME);
        processBuilder.environment().put("ICEDTEA_WEB_SPLASH", "none");
        processBuilder.redirectErrorStream(true);
        Path outputFile = Files.createTempFile("itw-localhost-javaws", ".log");
        processBuilder.redirectOutput(outputFile.toFile());

        Process process = processBuilder.start();
        String output = waitForConnectResult(process, outputFile);

        if (process.isAlive()) {
            process.destroy();
            process.waitFor(5, TimeUnit.SECONDS);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        assertFalse(isLocalhostSecurityException(output),
                "SecurityException blocked localhost connect:\n" + output);
        assertTrue(output.contains("ITW_LOCALHOST_CONNECT_OK"),
                "trusted app did not connect via localhost:\n" + output);
    }

    private String waitForConnectResult(Process process, Path outputFile) throws Exception {
        long deadline = System.currentTimeMillis() + (TIMEOUT_SECONDS * 1000L);
        while (System.currentTimeMillis() < deadline) {
            String snap = readFile(outputFile);
            if (isLocalhostSecurityException(snap)
                    || snap.contains("ITW_LOCALHOST_CONNECT_OK")
                    || snap.contains("ITW_LOCALHOST_CONNECT_FAIL")) {
                process.waitFor(2, TimeUnit.SECONDS);
                return readFile(outputFile);
            }
            if (!process.isAlive()) {
                return readFile(outputFile);
            }
            Thread.sleep(200);
        }
        return readFile(outputFile);
    }

    static boolean isLocalhostSecurityException(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        if (output.contains("SecurityException")
                && (output.contains("resolve localhost") || output.contains("resolve 127.0.0.1"))) {
            return true;
        }
        return output.contains("ITW_LOCALHOST_CONNECT_FAIL")
                && output.contains("SecurityException");
    }

    private static String readFile(Path path) throws Exception {
        if (!Files.exists(path)) {
            return "";
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
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
            String contentType = "application/octet-stream";
            if (path.endsWith(".jnlp")) {
                contentType = "application/x-java-jnlp-file";
            } else if (path.endsWith(".jar")) {
                contentType = "application/java-archive";
            } else if (path.endsWith(".txt")) {
                contentType = "text/plain";
            }
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
            exchange.getResponseSender().send(ByteBuffer.wrap(body));
        }
    }
}
