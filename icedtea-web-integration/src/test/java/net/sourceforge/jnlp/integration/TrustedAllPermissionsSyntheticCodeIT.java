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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression for Sonata CXF login failure:
 * {@code AccessControlException: RuntimePermission getClassLoader} when a JDK
 * proxy / ByteBuddy-generated frame (no CodeSigners) is on the ACC stack under
 * ITW SecurityManager, even though the JNLP has {@code <all-permissions/>} and
 * jars are signed.
 */
public class TrustedAllPermissionsSyntheticCodeIT {

    private static final String JAVAWS_BIN = System.getProperty("itw.javaws.bin");
    private static final String JDK_HOME = firstNonBlank(
            System.getProperty("itw.jdk11.home"),
            System.getProperty("itw.jdk17.home"),
            System.getProperty("itw.jdk8.home"));
    private static final int TIMEOUT_SECONDS = Integer.getInteger("itw.launch.timeout.seconds", 180);

    private Undertow server;
    private int httpPort;
    private Path webRoot;
    private Path markerDir;
    private Path cacheHome;
    private Path configHome;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(JAVAWS_BIN != null && new File(JAVAWS_BIN).isFile(), "itw.javaws.bin missing");
        assumeTrue(JDK_HOME != null && javaExecutableForHome(JDK_HOME).isFile(), "JDK home missing");

        webRoot = Files.createTempDirectory("itw-synthetic-web");
        markerDir = Files.createTempDirectory("itw-synthetic-marker");
        cacheHome = Files.createTempDirectory("itw-synthetic-cache");
        configHome = Files.createTempDirectory("itw-synthetic-config");

        Path appJar = Paths.get("target",
                "icedtea-web-integration-2.0.1-SNAPSHOT-synthetic-permissions-app.jar");
        Path byteBuddyJar = Paths.get("target", "byte-buddy-signed.jar");
        assumeTrue(Files.exists(appJar), "synthetic app jar missing: " + appJar.toAbsolutePath());
        assumeTrue(Files.exists(byteBuddyJar), "signed byte-buddy jar missing: " + byteBuddyJar.toAbsolutePath());

        Files.copy(appJar, webRoot.resolve("synthetic-permissions-app.jar"));
        Files.copy(byteBuddyJar, webRoot.resolve("byte-buddy.jar"));

        server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(new StaticFileHandler(webRoot))
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
    void proxyAndByteBuddyGetClassLoaderMustSucceedUnderAllPermissions() throws Exception {
        Path marker = markerDir.resolve("success.marker");
        writeJnlp(marker);

        LaunchResult result = launch(marker);

        assertFalse(result.output.contains("AccessControlException")
                        && result.output.contains("getClassLoader"),
                "PRODUCTION FAILURE MODE: getClassLoader denied on synthetic stack:\n"
                        + result.output);
        assertTrue(result.output.contains("ITW_PROXY_GETCLASSLOADER_OK"),
                "JDK proxy getClassLoader path did not succeed:\n" + result.output);
        assertTrue(result.output.contains("ITW_BYTEBUDDY_GETCLASSLOADER_OK"),
                "ByteBuddy-generated getClassLoader path did not succeed:\n" + result.output);
        assertTrue(result.output.contains("ITW_JARFILE_BYTEBUDDY_PATH_OK"),
                "JarFile path under ITW ByteBuddy protection did not succeed:\n" + result.output);
        assertTrue(result.success,
                "synthetic-code all-permissions launch must succeed:\n" + result.output);
    }

    private void writeJnlp(Path marker) throws Exception {
        String jnlp = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"http://127.0.0.1:" + httpPort
                + "/\" href=\"synthetic-permissions.jnlp\">\n"
                + "  <information><title>ITW synthetic permissions</title><vendor>ITW</vendor></information>\n"
                + "  <security><all-permissions/></security>\n"
                + "  <resources>\n"
                + "    <property name=\"itw.test.success.marker\" value=\""
                + marker.toAbsolutePath().toString().replace("\\", "/") + "\"/>\n"
                + "    <j2se version=\"1.8+\"/>\n"
                + "    <jar href=\"synthetic-permissions-app.jar\" main=\"true\"/>\n"
                + "    <jar href=\"byte-buddy.jar\"/>\n"
                + "  </resources>\n"
                + "  <application-desc main-class=\"net.sourceforge.jnlp.integration.SyntheticCodePermissionsMain\"/>\n"
                + "</jnlp>\n";
        Files.write(webRoot.resolve("synthetic-permissions.jnlp"), jnlp.getBytes(StandardCharsets.UTF_8));
    }

    private LaunchResult launch(Path marker) throws Exception {
        String jnlpUrl = "http://127.0.0.1:" + httpPort + "/synthetic-permissions.jnlp";
        List<String> command = new ArrayList<>();
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
        Path outputFile = Files.createTempFile("itw-synthetic", ".log");
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
                if (out.contains("ITW_INTEGRATION_SUCCESS")
                        && out.contains("ITW_PROXY_GETCLASSLOADER_OK")
                        && out.contains("ITW_BYTEBUDDY_GETCLASSLOADER_OK")) {
                    return true;
                }
                if (out.contains("AccessControlException") && out.contains("getClassLoader")) {
                    return false;
                }
            }
            if (!process.isAlive()) {
                if (!Files.exists(outputFile)) {
                    return false;
                }
                String out = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);
                return out.contains("ITW_INTEGRATION_SUCCESS")
                        && out.contains("ITW_PROXY_GETCLASSLOADER_OK")
                        && out.contains("ITW_BYTEBUDDY_GETCLASSLOADER_OK")
                        && out.contains("ITW_JARFILE_BYTEBUDDY_PATH_OK");
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

    private static final class StaticFileHandler implements HttpHandler {
        private final Path webRoot;

        StaticFileHandler(Path webRoot) {
            this.webRoot = webRoot;
        }

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            String relative = exchange.getRequestPath();
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            if (relative.isEmpty() || relative.contains("..")) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.endExchange();
                return;
            }
            Path file = webRoot.resolve(relative).normalize();
            if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.endExchange();
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (relative.endsWith(".jnlp")) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/x-java-jnlp-file");
            } else if (relative.endsWith(".jar")) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/java-archive");
            }
            exchange.setStatusCode(StatusCodes.OK);
            exchange.getResponseSender().send(ByteBuffer.wrap(bytes));
        }
    }
}
