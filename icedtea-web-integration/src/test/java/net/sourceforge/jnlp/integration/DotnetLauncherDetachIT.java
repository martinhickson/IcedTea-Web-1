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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the .NET {@code javaws} launcher detaches from the JDK child process when
 * {@code deployment.keepJavawsProcess} is not enabled.
 */
public class DotnetLauncherDetachIT {

    private static final String DOTNET_JAVAWS_BIN = System.getProperty("itw.dotnet.javaws.bin");
    private static final String JDK_HOME = System.getProperty("itw.jdk11.home", "/usr/lib/jvm/java-11-amazon-corretto");
    private static final int TIMEOUT_SECONDS = Integer.getInteger("itw.launch.timeout.seconds", 180);

    private Undertow server;
    private int httpPort;
    private Path webRoot;
    private Path configRoot;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(DOTNET_JAVAWS_BIN != null, "itw.dotnet.javaws.bin must point at a .NET javaws binary");
        File javaws = new File(DOTNET_JAVAWS_BIN);
        assumeTrue(javaws.isFile() && javaws.canExecute(), "dotnet javaws missing: " + DOTNET_JAVAWS_BIN);

        configRoot = Files.createTempDirectory("itw-dotnet-config");
        writeDeploymentProperties(configRoot, false, false);

        webRoot = Files.createTempDirectory("itw-dotnet-web");
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
        deleteRecursive(webRoot);
        deleteRecursive(configRoot);
    }

    @Test
    void dotnetLauncherDetachesAndJavaChildCompletes() throws Exception {
        Path marker = Files.createTempDirectory("itw-dotnet-success").resolve("success.marker");
        try {
            writeJnlp("headless-test.jnlp", "http://127.0.0.1:" + httpPort + "/", marker);
            String jnlpUrl = "http://127.0.0.1:" + httpPort + "/headless-test.jnlp";

            List<String> command = new ArrayList<>();
            command.add(DOTNET_JAVAWS_BIN);
            command.add("-headless");
            command.add("-verbose");
            command.add("-Xtrustall");
            command.add("--auto-accept-https-certificate=true");
            command.add("-Xnofork");
            command.add(jnlpUrl);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put("JAVA_HOME", JDK_HOME);
            pb.environment().put("ICEDTEA_WEB_SPLASH", "none");
            pb.environment().put("XDG_CONFIG_HOME", configRoot.toString());
            pb.redirectErrorStream(true);
            Path launcherOutput = Files.createTempFile("itw-dotnet-launcher", ".log");
            pb.redirectOutput(launcherOutput.toFile());

            Process launcher = pb.start();
            boolean launcherFinished = launcher.waitFor(15, TimeUnit.SECONDS);
            assertTrue(launcherFinished, "dotnet javaws should exit quickly when detached");
            assertEquals(0, launcher.exitValue(), readFile(launcherOutput));

            Path launchLog = findLatestLaunchLog(configRoot);
            String launchRecord = Files.readString(launchLog, StandardCharsets.UTF_8);
            assertTrue(launchRecord.contains("Handoff status: SUCCESS"), launchRecord);
            assertTrue(launchRecord.contains("Parent process ID (handing off):"), launchRecord);
            assertTrue(launchRecord.contains("Child process ID (handed to):"), launchRecord);
            assertTrue(launchRecord.contains("Standard Output stream written to:"), launchRecord);
            assertTrue(launchRecord.contains("Standard Error stream written to:"), launchRecord);
            assertTrue(launchRecord.contains("no pipe buffer stall risk"), launchRecord);
            assertTrue(launchRecord.contains("Handoff complete: parent launcher exiting"), launchRecord);

            Path prelaunchLog = findPrelaunchHandoffLog(configRoot);
            String prelaunchRecord = Files.readString(prelaunchLog, StandardCharsets.UTF_8);
            assertTrue(prelaunchRecord.contains("Handoff step: prelaunch"), prelaunchRecord);
            assertTrue(prelaunchRecord.contains("Handoff status: SUCCESS"), prelaunchRecord);

            boolean launched = waitForMarker(marker, TIMEOUT_SECONDS);
            assertTrue(launched, "Java child should complete JNLP launch after launcher detach");
        } finally {
            deleteRecursive(marker.getParent());
        }
    }

    @Test
    void keepJavawsProcessPreservesBlockingBehavior() throws Exception {
        writeDeploymentProperties(configRoot, true, true);
        Path marker = Files.createTempDirectory("itw-dotnet-keep").resolve("success.marker");
        try {
            writeJnlp("headless-keep.jnlp", "http://127.0.0.1:" + httpPort + "/", marker);
            String jnlpUrl = "http://127.0.0.1:" + httpPort + "/headless-keep.jnlp";

            ProcessBuilder pb = new ProcessBuilder(
                    DOTNET_JAVAWS_BIN,
                    "-headless",
                    "-verbose",
                    "-Xtrustall",
                    "--auto-accept-https-certificate=true",
                    "-Xnofork",
                    jnlpUrl);
            pb.environment().put("JAVA_HOME", JDK_HOME);
            pb.environment().put("ICEDTEA_WEB_SPLASH", "none");
            pb.environment().put("XDG_CONFIG_HOME", configRoot.toString());
            pb.redirectErrorStream(true);
            Path launcherOutput = Files.createTempFile("itw-dotnet-keep", ".log");
            pb.redirectOutput(launcherOutput.toFile());

            Process launcher = pb.start();
            boolean launched = waitForMarker(marker, TIMEOUT_SECONDS);
            assertTrue(launcher.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "launcher should wait for Java child");
            assertTrue(launched, readFile(launcherOutput));
            assertEquals(0, launcher.exitValue(), readFile(launcherOutput));
        } finally {
            deleteRecursive(marker.getParent());
        }
    }

    private static void writeDeploymentProperties(Path configRoot, boolean keepJavaws, boolean keepPrelaunch)
            throws IOException {
        Path icedteaDir = configRoot.resolve("icedtea-web");
        Files.createDirectories(icedteaDir);
        StringBuilder sb = new StringBuilder();
        if (keepJavaws) {
            sb.append("deployment.keepJavawsProcess=true\n");
        }
        if (keepPrelaunch) {
            sb.append("deployment.keepjavaPrelaunchProcess=true\n");
        }
        Files.writeString(icedteaDir.resolve("deployment.properties"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void copyHeadlessAppJar() throws IOException {
        Path source = Paths.get("target", "icedtea-web-integration-2.0.1-SNAPSHOT-headless-app.jar");
        assumeTrue(Files.isRegularFile(source), "headless app jar missing: " + source.toAbsolutePath());
        Files.copy(source, webRoot.resolve("headless-app.jar"));
    }

    private void writeJnlp(String fileName, String codebase, Path marker) throws IOException {
        String markerProperty = "    <property name=\"itw.test.success.marker\" value=\""
                + marker.toAbsolutePath() + "\"/>\n";
        String jnlp = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"" + codebase + "\" href=\"" + fileName + "\">\n"
                + "  <information><title>Dotnet Detach IT</title><vendor>ITW</vendor></information>\n"
                + "  <security><all-permissions/></security>\n"
                + "  <resources>\n"
                + markerProperty
                + "    <j2se version=\"1.8+\"/>\n"
                + "    <jar href=\"headless-app.jar\" main=\"true\"/>\n"
                + "  </resources>\n"
                + "  <application-desc main-class=\"net.sourceforge.jnlp.integration.HeadlessJnlpMain\"/>\n"
                + "</jnlp>\n";
        Files.writeString(webRoot.resolve(fileName), jnlp, StandardCharsets.UTF_8);
    }

    private static boolean waitForMarker(Path marker, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(marker)) {
                return true;
            }
            Thread.sleep(250);
        }
        return Files.isRegularFile(marker);
    }

    private static Path findLatestLaunchLog(Path configRoot) throws IOException {
        Path logsDir = configRoot.resolve("icedtea-web").resolve("log");
        assumeTrue(Files.isDirectory(logsDir), "launcher logs directory missing: " + logsDir);
        try (Stream<Path> stream = Files.list(logsDir)) {
            return stream
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("itw-javantx-")
                                && name.endsWith(".log")
                                && !name.endsWith(".err.log")
                                && !name.contains("-prelaunch");
                    })
                    .max(Path::compareTo)
                    .orElseThrow(() -> new IOException("No main ITW javantx handoff logs in " + logsDir));
        }
    }

    private static Path findPrelaunchHandoffLog(Path configRoot) throws IOException {
        Path logsDir = configRoot.resolve("icedtea-web").resolve("log");
        assumeTrue(Files.isDirectory(logsDir), "launcher logs directory missing: " + logsDir);
        try (Stream<Path> stream = Files.list(logsDir)) {
            return stream
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("itw-javantx-")
                                && name.endsWith(".log")
                                && !name.endsWith(".err.log")
                                && name.contains("-prelaunch");
                    })
                    .max(Path::compareTo)
                    .orElseThrow(() -> new IOException("No prelaunch ITW javantx handoff logs in " + logsDir));
        }
    }

    private static String readFile(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class StaticFileHandler implements HttpHandler {
        private final Path webRoot;

        private StaticFileHandler(Path webRoot) {
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
            String contentType = path.endsWith(".jnlp")
                    ? "application/x-java-jnlp-file"
                    : "application/java-archive";
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
            exchange.getResponseSender().send(java.nio.ByteBuffer.wrap(body));
        }
    }
}
