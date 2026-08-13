package net.sourceforge.icedteaweb.autodetect.it.tls;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.util.Headers;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * HTTPS Undertow that only completes a handshake when the ClientHello offers
 * the configured cipher. Otherwise the SSLEngine sends {@code close_notify}.
 */
final class UndertowHttpsServer implements AutoCloseable {

    enum Mode {
        TLS12_AES_RSA(
                new String[] { "TLSv1.2" },
                new String[] { "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256" },
                new int[] { ClientHelloCiphers.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 },
                false),
        TLS13_CHACHA(
                new String[] { "TLSv1.3" },
                new String[] { "TLS_CHACHA20_POLY1305_SHA256" },
                new int[] { ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256 },
                false),
        TLS12_ECDSA_CHACHA(
                new String[] { "TLSv1.2" },
                new String[] { "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256" },
                new int[] { ClientHelloCiphers.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256 },
                true);

        final String[] protocols;
        final String[] ciphers;
        final int[] acceptedIds;
        final boolean ecdsa;

        Mode(String[] protocols, String[] ciphers, int[] acceptedIds, boolean ecdsa) {
            this.protocols = protocols;
            this.ciphers = ciphers;
            this.acceptedIds = acceptedIds;
            this.ecdsa = ecdsa;
        }
    }

    private final Undertow server;
    private final Path workDir;
    private final int port;
    private final AtomicInteger closeNotifyCount;
    private final AtomicInteger enginesCreated;

    private UndertowHttpsServer(Undertow server, Path workDir, int port,
            AtomicInteger closeNotifyCount, AtomicInteger enginesCreated) {
        this.server = server;
        this.workDir = workDir;
        this.port = port;
        this.closeNotifyCount = closeNotifyCount;
        this.enginesCreated = enginesCreated;
    }

    static UndertowHttpsServer start(Mode mode) throws Exception {
        Path workDir = Files.createTempDirectory("itw-tls-it");
        Path ksPath = mode.ecdsa ? SelfSignedKeyStore.ecdsa(workDir) : SelfSignedKeyStore.rsa(workDir);
        SSLContext inner = serverContext(ksPath);
        AtomicInteger closeNotifyCount = new AtomicInteger();
        AtomicInteger enginesCreated = new AtomicInteger();
        SSLContext wrapped = wrap(inner, mode, closeNotifyCount, enginesCreated);
        Undertow server = Undertow.builder()
                .setServerOption(UndertowOptions.ENABLE_HTTP2, false)
                .addHttpsListener(0, "127.0.0.1", wrapped)
                .setHandler(exchange -> {
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain; charset=UTF-8");
                    exchange.getResponseSender().send("tls-ok");
                })
                .build();
        server.start();
        int port = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
        return new UndertowHttpsServer(server, workDir, port, closeNotifyCount, enginesCreated);
    }

    String url() {
        return "https://127.0.0.1:" + port + "/probe";
    }

    int closeNotifyCount() {
        return closeNotifyCount.get();
    }

    int enginesCreated() {
        return enginesCreated.get();
    }

    private static SSLContext serverContext(Path ksPath) throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(ksPath)) {
            ks.load(in, SelfSignedKeyStore.PASSWORD.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, SelfSignedKeyStore.PASSWORD.toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return ctx;
    }

    private static SSLContext wrap(SSLContext inner, Mode mode,
            AtomicInteger closeNotifyCount, AtomicInteger enginesCreated) {
        Provider provider = new Provider("itw-tls-it", "1.0", "ITW TLS IT") {
            private static final long serialVersionUID = 1L;
        };
        SSLContextSpi spi = new SSLContextSpi() {
            @Override
            protected void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom random) {
                // inner is already initialized
            }

            @Override
            protected SSLSocketFactory engineGetSocketFactory() {
                return inner.getSocketFactory();
            }

            @Override
            protected SSLServerSocketFactory engineGetServerSocketFactory() {
                return inner.getServerSocketFactory();
            }

            @Override
            protected SSLEngine engineCreateSSLEngine() {
                return stamp(inner.createSSLEngine());
            }

            @Override
            protected SSLEngine engineCreateSSLEngine(String host, int port) {
                return stamp(inner.createSSLEngine(host, port));
            }

            private SSLEngine stamp(SSLEngine engine) {
                enginesCreated.incrementAndGet();
                engine.setUseClientMode(false);
                engine.setEnabledProtocols(mode.protocols);
                engine.setEnabledCipherSuites(mode.ciphers);
                return new CloseNotifySslEngine(engine, mode.acceptedIds, closeNotifyCount);
            }

            @Override
            protected SSLSessionContext engineGetServerSessionContext() {
                return inner.getServerSessionContext();
            }

            @Override
            protected SSLSessionContext engineGetClientSessionContext() {
                return inner.getClientSessionContext();
            }
        };
        return new SSLContext(spi, provider, "TLS") {
        };
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop();
        }
        if (workDir != null) {
            try {
                Files.walk(workDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception ignored) {
            }
        }
    }
}
