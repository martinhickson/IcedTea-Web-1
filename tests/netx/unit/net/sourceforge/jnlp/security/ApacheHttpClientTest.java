package net.sourceforge.jnlp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the non-HTTP scheme fallback: Apache HttpClient only understands http(s),
 * so file:/jar: URLs must route through java.net.URLConnection (as the classic
 * client did). Regression: "javaws /path/to/app.jnlp" failed with
 * "Target host is not specified" under the apache default.
 */
public class ApacheHttpClientTest {

    @TempDir
    Path tmp;

    @Test
    public void fileSchemeFallsBackToUrlConnection() throws Exception {
        Path file = tmp.resolve("hello.txt");
        Files.write(file, "hello file-url\n".getBytes(StandardCharsets.UTF_8));

        ApacheHttpClient client = new ApacheHttpClient();
        try (HttpResponse response = client.open(file.toUri().toURL(), "GET", null, null)) {
            assertEquals(200, response.getStatusCode());
            String body;
            try (InputStream in = response.getBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertEquals("hello file-url\n", body);
        }
    }

    @Test
    public void postIsRejectedBeforeConnect() throws Exception {
        ApacheHttpClient client = new ApacheHttpClient();
        assertThrows(ProtocolException.class,
                () -> client.open(new URL("http://127.0.0.1/x"), "POST", null, null));
    }

    @Test
    public void headOnFileSchemeStillFallsBack() throws Exception {
        Path file = tmp.resolve("head.txt");
        Files.write(file, "head-body\n".getBytes(StandardCharsets.UTF_8));
        ApacheHttpClient client = new ApacheHttpClient();
        try (HttpResponse response = client.open(file.toUri().toURL(), "HEAD", null, null)) {
            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    public void putIsRejectedBeforeConnect() throws Exception {
        ApacheHttpClient client = new ApacheHttpClient();
        assertThrows(ProtocolException.class,
                () -> client.open(new URL("http://127.0.0.1/x"), "PUT", null, null));
    }

    /**
     * Regression: Apache HttpClient was built with no RoutePlanner, so
     * {@code deployment.proxy.*} / {@link ProxySelector#getDefault()} was never
     * consulted and downloads went direct. The client is constructed before
     * {@code JNLPRuntime} installs the selector — the planner must re-read
     * the default on each request.
     */
    @Test
    public void httpGetUsesDefaultProxySelectorInstalledAfterConstruction() throws Exception {
        int originPort;
        try (ServerSocket closed = new ServerSocket(0)) {
            originPort = closed.getLocalPort();
        }
        URL origin = new URL("http://127.0.0.1:" + originPort + "/jnlp/app.jnlp");

        ProxySelector previous = ProxySelector.getDefault();
        ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        AtomicReference<String> firstLine = new AtomicReference<>();
        CountDownLatch accepted = new CountDownLatch(1);
        Thread acceptor = new Thread(() -> {
            try (Socket s = proxy.accept()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                firstLine.set(reader.readLine());
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // drain headers
                }
                s.getOutputStream().write((
                        "HTTP/1.1 502 Bad Gateway\r\n"
                                + "Content-Length: 0\r\n"
                                + "Connection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
            } catch (IOException ignored) {
            } finally {
                accepted.countDown();
            }
        }, "itw-proxy-capture");
        acceptor.setDaemon(true);
        acceptor.start();

        ApacheHttpClient client = new ApacheHttpClient();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return Collections.singletonList(new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress("127.0.0.1", proxy.getLocalPort())));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        });
        try (HttpResponse response = client.open(origin, "GET", null, null)) {
            assertEquals(502, response.getStatusCode());
        } finally {
            ProxySelector.setDefault(previous);
            proxy.close();
            acceptor.join(2000);
        }
        assertTrue(accepted.await(5, TimeUnit.SECONDS), "proxy must receive the GET");
        assertEquals("GET " + origin + " HTTP/1.1", firstLine.get());
    }

    @Test
    public void httpGetFailsWhenDefaultSelectorReturnsClosedProxy() throws Exception {
        int originPort;
        int deadProxyPort;
        try (ServerSocket origin = new ServerSocket(0);
                ServerSocket dead = new ServerSocket(0)) {
            originPort = origin.getLocalPort();
            deadProxyPort = dead.getLocalPort();
        }
        URL origin = new URL("http://127.0.0.1:" + originPort + "/jnlp/app.jnlp");
        int proxyPort = deadProxyPort;

        ProxySelector previous = ProxySelector.getDefault();
        ApacheHttpClient client = new ApacheHttpClient();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return Collections.singletonList(new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress("127.0.0.1", proxyPort)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        });
        try {
            assertThrows(IOException.class, () -> client.open(origin, "GET", null, null));
        } finally {
            ProxySelector.setDefault(previous);
        }
    }
}
