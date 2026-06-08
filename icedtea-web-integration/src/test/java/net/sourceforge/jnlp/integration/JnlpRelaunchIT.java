package net.sourceforge.jnlp.integration;

import io.undertow.Undertow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies JVM relaunch (fork) via {@code javaws} when JNLP declares heap settings.
 */
public class JnlpRelaunchIT {

    private static final String JAVAWS_BIN = System.getProperty("itw.javaws.bin");
    private static final String JDK_HOME = System.getProperty("itw.jdk8.home");
    private static final int TIMEOUT_SECONDS = Integer.getInteger("itw.launch.timeout.seconds", 180);

    private Undertow server;
    private int httpPort;
    private Path webRoot;
    private Path marker;
    private Path markerDir;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(JAVAWS_BIN != null);
        assumeTrue(JDK_HOME != null && new File(JDK_HOME, "bin/java").isFile());

        webRoot = Files.createTempDirectory("itw-relaunch-web");
        markerDir = Files.createTempDirectory("itw-relaunch");
        marker = markerDir.resolve("success.marker");

        Path source = Paths.get("target", "icedtea-web-integration-1.0.1-SNAPSHOT-headless-app.jar");
        assumeTrue(Files.exists(source));
        Files.copy(source, webRoot.resolve("headless-app.jar"));

        server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(new MultiJdkJnlpLaunchIT.StaticFileHandler(webRoot))
                .build();
        server.start();
        httpPort = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();

        String jnlp = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"http://127.0.0.1:" + httpPort + "/\" href=\"relaunch-test.jnlp\">\n"
                + "  <information><title>ITW relaunch test</title><vendor>ITW</vendor></information>\n"
                + "  <security><all-permissions/></security>\n"
                + "  <resources>\n"
                + "    <property name=\"itw.test.success.marker\" value=\"" + marker.toAbsolutePath() + "\"/>\n"
                + "    <j2se version=\"1.8+\" max-heap-size=\"256m\" initial-heap-size=\"64m\"/>\n"
                + "    <jar href=\"headless-app.jar\" main=\"true\"/>\n"
                + "  </resources>\n"
                + "  <application-desc main-class=\"net.sourceforge.jnlp.integration.HeadlessJnlpMain\"/>\n"
                + "</jnlp>\n";
        Files.write(webRoot.resolve("relaunch-test.jnlp"), jnlp.getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (webRoot != null) {
            Files.walk(webRoot).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
        if (markerDir != null) {
            Files.walk(markerDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }

    @Test
    void relaunchesWithHeapSettingsFromJnlp() throws Exception {
        String jnlpUrl = "http://127.0.0.1:" + httpPort + "/relaunch-test.jnlp";
        ProcessBuilder pb = new ProcessBuilder(
                JAVAWS_BIN,
                "-headless",
                "-verbose",
                "-Xtrustall",
                jnlpUrl
        );
        pb.environment().put("JAVA_HOME", JDK_HOME);
        pb.environment().put("ICEDTEA_WEB_SPLASH", "none");
        pb.redirectErrorStream(true);
        Path outputFile = Files.createTempFile("itw-relaunch-javaws", ".log");
        pb.redirectOutput(outputFile.toFile());

        Process process = pb.start();
        boolean launched = waitForLaunchSuccess(process, outputFile);
        String output = readFile(outputFile);

        if (process.isAlive()) {
            process.destroy();
            process.waitFor(5, TimeUnit.SECONDS);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        assertTrue(launched, "relaunch did not run application:\n" + output);
        assertTrue(output.contains("ITW_INTEGRATION_SUCCESS") || Files.exists(marker),
                "relaunch did not run application:\n" + output);
        if (output.contains("ITW_INTEGRATION_SUCCESS")) {
            assertTrue(output.contains("ok jdk="), "relaunch output: " + output);
        } else {
            String markerContent = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8);
            assertTrue(markerContent.contains("ok jdk="), "marker: " + markerContent);
        }
    }

    private boolean waitForLaunchSuccess(Process process, Path outputFile) throws Exception {
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

    private static String readFile(Path path) throws Exception {
        if (!Files.exists(path)) {
            return "";
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
