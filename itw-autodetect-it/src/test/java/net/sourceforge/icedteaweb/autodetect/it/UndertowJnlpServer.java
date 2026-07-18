package net.sourceforge.icedteaweb.autodetect.it;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Serves a JNLP + JAR from a temporary web root over HTTP (Undertow).
 */
final class UndertowJnlpServer implements AutoCloseable {

    private final Undertow server;
    private final int httpPort;
    private final Path webRoot;

    private UndertowJnlpServer(Undertow server, int httpPort, Path webRoot) {
        this.server = server;
        this.httpPort = httpPort;
        this.webRoot = webRoot;
    }

    static UndertowJnlpServer start(Path sampleJar, Path marker, String jnlpFileName) throws IOException {
        Path webRoot = Files.createTempDirectory("itw-autodetect-web");
        Files.copy(sampleJar, webRoot.resolve("app.jar"), StandardCopyOption.REPLACE_EXISTING);

        Undertow server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(new StaticFileHandler(webRoot))
                .build();
        server.start();
        int port = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
        String codebase = "http://127.0.0.1:" + port + "/";
        writeJnlp(webRoot.resolve(jnlpFileName), codebase, jnlpFileName, marker);
        writeIndexHtml(webRoot.resolve("index.html"), jnlpFileName);
        return new UndertowJnlpServer(server, port, webRoot);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + httpPort + "/";
    }

    String jnlpUrl(String jnlpFileName) {
        return baseUrl() + jnlpFileName;
    }

    private static void writeIndexHtml(Path indexPath, String jnlpFileName) throws IOException {
        String html = "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head><meta charset=\"utf-8\"/><title>ITW sample app</title></head>\n"
                + "<body>\n"
                + "  <h1>IcedTea-Web sample app</h1>\n"
                + "  <p>Click to launch the JDK&nbsp;17 JNLP (opens with the registered "
                + "<code>javaws</code> / Web Start handler):</p>\n"
                + "  <p><a href=\"" + jnlpFileName + "\">" + jnlpFileName + "</a></p>\n"
                + "</body>\n"
                + "</html>\n";
        Files.writeString(indexPath, html, StandardCharsets.UTF_8);
    }

    private static void writeJnlp(Path jnlpPath, String codebase, String href, Path marker)
            throws IOException {
        int holdSeconds = Integer.getInteger("itw.test.hold.seconds", 120);
        String markerProperty = "    <property name=\"itw.test.success.marker\" value=\""
                + marker.toAbsolutePath().toString().replace("\\", "/") + "\"/>\n";
        String holdProperty = "    <property name=\"itw.test.hold.seconds\" value=\""
                + holdSeconds + "\"/>\n";
        String jnlp = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"" + codebase + "\" href=\"" + href + "\">\n"
                + "  <information>\n"
                + "    <title>ITW Java 17 Autodetect Sample</title>\n"
                + "    <vendor>IcedTea-Web IT</vendor>\n"
                + "  </information>\n"
                + "  <security><all-permissions/></security>\n"
                + "  <resources>\n"
                + markerProperty
                + holdProperty
                // Exact "17" (not "17+") so resolveMissingSuitableJre shows Apply
                // instead of silently auto-applying a discovered minimum match.
                + "    <j2se version=\"17\"/>\n"
                + "    <jar href=\"app.jar\" main=\"true\"/>\n"
                + "  </resources>\n"
                + "  <application-desc main-class=\"net.sourceforge.icedteaweb.autodetect.it.apps.Java17HoldJnlpMain\"/>\n"
                + "</jnlp>\n";
        Files.writeString(jnlpPath, jnlp, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        if (server != null) {
            server.stop();
        }
        if (webRoot != null) {
            deleteRecursive(webRoot);
        }
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // best-effort cleanup
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
                path = "/index.html";
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
            String contentType;
            if (path.endsWith(".jnlp")) {
                contentType = "application/x-java-jnlp-file";
            } else if (path.endsWith(".html") || path.endsWith(".htm")) {
                contentType = "text/html; charset=UTF-8";
            } else {
                contentType = "application/java-archive";
            }
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(body.length));
            exchange.getResponseHeaders().put(Headers.LAST_MODIFIED,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                            java.time.Instant.ofEpochMilli(lastModified)
                                    .atZone(java.time.ZoneOffset.UTC)));
            exchange.getResponseSender().send(ByteBuffer.wrap(body));
        }
    }
}
