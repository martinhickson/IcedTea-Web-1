package net.sourceforge.jnlp.integration;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Failsafe launch of the sample applications under {@code src/it}. Each sample
 * is its own Maven project, compiled against a published library build that is
 * not the build inside the IcedTea-Web uber jar. javaws must run that sample
 * to the same result the sample's own library produces.
 */
public class LibraryVersionSampleIT {

    private static final String OK = "ITW_LIBRARY_SAMPLE_OK";
    private static final String JAVAWS_BIN = System.getProperty("itw.javaws.bin");
    private static final int TIMEOUT_SECONDS = Integer.getInteger("itw.launch.timeout.seconds", 180);

    private Undertow server;
    private int httpPort;
    private Path webRoot;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(JAVAWS_BIN != null && new File(JAVAWS_BIN).canExecute(),
                "javaws launcher missing: " + JAVAWS_BIN);
        webRoot = Files.createTempDirectory("itw-library-sample");
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "byte-buddy-1.18.15",
            "rhino-1.8.0",
            "sqlite-jdbc-3.53.0.0",
            "httpclient-5.5.2"
    })
    void sampleRunsAgainstItsOwnLibrary(String sampleName) throws Exception {
        Path sampleDir = Paths.get("src", "it", sampleName);
        Path sampleJar = sampleDir.resolve("target").resolve("sample.jar");
        Path libDir = sampleDir.resolve("target").resolve("jnlp-lib");
        assumeTrue(Files.isRegularFile(sampleJar), "sample jar missing: " + sampleJar.toAbsolutePath());
        assumeTrue(Files.isDirectory(libDir), "sample libraries missing: " + libDir.toAbsolutePath());

        String mainClass;
        try (JarFile jar = new JarFile(sampleJar.toFile())) {
            mainClass = jar.getManifest().getMainAttributes().getValue("Main-Class");
        }
        assertTrue(mainClass != null && !mainClass.isEmpty(), "sample jar has no Main-Class");

        Files.copy(sampleJar, webRoot.resolve("sample.jar"));
        List<String> hrefs = new ArrayList<>();
        hrefs.add("sample.jar");
        try (Stream<Path> libs = Files.list(libDir)) {
            for (Path lib : libs.filter(Files::isRegularFile).collect(Collectors.toList())) {
                Files.copy(lib, webRoot.resolve(lib.getFileName().toString()));
                hrefs.add(lib.getFileName().toString());
            }
        }
        String jnlpName = sampleName + ".jnlp";
        String codebase = "http://127.0.0.1:" + httpPort + "/";
        Files.write(webRoot.resolve(jnlpName),
                jnlp(codebase, jnlpName, mainClass, hrefs).getBytes(StandardCharsets.UTF_8));

        String jdkHome = System.getProperty("itw.jdk11.home");
        assumeTrue(jdkHome != null && new File(jdkHome, "bin/java").isFile(), "JDK 11 home missing");

        List<String> command = new ArrayList<>();
        command.add(JAVAWS_BIN);
        command.add("-headless");
        command.add("-verbose");
        command.add("-nosecurity");
        command.add("-Xtrustall");
        command.add("-Xnofork");
        command.add("http://127.0.0.1:" + httpPort + "/" + jnlpName);

        Path outputFile = Files.createTempFile("itw-library-sample-", ".log");
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put("JAVA_HOME", jdkHome);
            pb.environment().put("ICEDTEA_WEB_SPLASH", "none");
            pb.redirectErrorStream(true);
            pb.redirectOutput(outputFile.toFile());
            Process process = pb.start();
            String output = waitForSample(process, outputFile);
            if (process.isAlive()) {
                process.destroy();
                process.waitFor(5, TimeUnit.SECONDS);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            assertTrue(output.contains(OK), sampleName + " did not finish on its own library:\n" + output);
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private static String waitForSample(Process process, Path outputFile) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + (TIMEOUT_SECONDS * 1000L);
        String output = "";
        while (System.currentTimeMillis() < deadline) {
            output = read(outputFile);
            if (output.contains(OK) || !process.isAlive()) {
                return read(outputFile);
            }
            Thread.sleep(250);
        }
        return output;
    }

    private static String jnlp(String codebase, String fileName, String mainClass, List<String> hrefs) {
        StringBuilder jars = new StringBuilder();
        for (int i = 0; i < hrefs.size(); i++) {
            jars.append("    <jar href=\"").append(hrefs.get(i)).append("\"");
            if (i == 0) {
                jars.append(" main=\"true\"");
            }
            jars.append("/>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"" + codebase + "\" href=\"" + fileName + "\">\n"
                + "  <information><title>" + fileName + "</title><vendor>ITW</vendor></information>\n"
                + "  <security><all-permissions/></security>\n"
                + "  <resources>\n"
                + "    <j2se version=\"11+\"/>\n"
                + jars
                + "  </resources>\n"
                + "  <application-desc main-class=\"" + mainClass + "\"/>\n"
                + "</jnlp>\n";
    }

    private static String read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return "";
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walk(root).sorted((a, b) -> b.compareTo(a)).forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // best effort cleanup
            }
        });
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
            exchange.getResponseSender().send(ByteBuffer.wrap(body));
        }
    }
}
