package net.sourceforge.icedteaweb.autodetect.it.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import net.sourceforge.jnlp.security.ApacheHttpClient;
import net.sourceforge.jnlp.security.HttpResponse;
import net.sourceforge.jnlp.security.OracleHttpClient;
import net.sourceforge.jnlp.security.TlsProbeAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real TLS against Undertow: probe miss must advance (including Apache-style
 * {@code close_notify}), and a certificate failure must not.
 */
@Timeout(30)
class TlsProbeIT {

    private static final String HOST = "127.0.0.1";

    private SSLContext previousDefault;

    @BeforeEach
    void setUp() throws Exception {
        previousDefault = SSLContext.getDefault();
        TlsProbeAccess.forceProbeMode();
        SSLContext trustAll = SSLContext.getInstance("TLS");
        trustAll.init(null, new TrustManager[] { trustingManager() }, null);
        SSLContext.setDefault(trustAll);
    }

    @AfterEach
    void tearDown() {
        TlsProbeAccess.resetHostOffer();
        if (previousDefault != null) {
            SSLContext.setDefault(previousDefault);
        }
    }

    @Test
    void closeNotifyOnTls13ThenTls12FallsThroughToAes() throws Exception {
        assumeCipher("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        assumeCipher("TLS_CHACHA20_POLY1305_SHA256");
        assumeCipher("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256");
        assumeCipher("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
        try (UndertowHttpsServer server = UndertowHttpsServer.start(UndertowHttpsServer.Mode.TLS12_AES_RSA)) {
            assertEquals("tls-ok", getBody(server.url()));
            assertEquals(TlsProbeAccess.full(), TlsProbeAccess.offerStage(HOST));
            assertTrue(server.closeNotifyCount() >= 3,
                    "tls13, tls12, and aes256 probes must close_notify before AES-128; got "
                            + server.closeNotifyCount());
        }
    }

    @Test
    void tls13ChaChaSucceedsOnFirstProbe() throws Exception {
        assumeCipher("TLS_CHACHA20_POLY1305_SHA256");
        try (UndertowHttpsServer server = UndertowHttpsServer.start(UndertowHttpsServer.Mode.TLS13_CHACHA)) {
            assertEquals("tls-ok", getBody(server.url()));
            assertEquals(TlsProbeAccess.tls13(), TlsProbeAccess.offerStage(HOST));
            assertEquals(0, server.closeNotifyCount());
        }
    }

    @Test
    void closeNotifyOnTls13ThenEcdsaChaChaHoldsTls12() throws Exception {
        assumeCipher("TLS_CHACHA20_POLY1305_SHA256");
        assumeCipher("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256");
        try (UndertowHttpsServer server = UndertowHttpsServer.start(UndertowHttpsServer.Mode.TLS12_ECDSA_CHACHA)) {
            assertEquals("tls-ok", getBody(server.url()));
            assertEquals(TlsProbeAccess.tls12(), TlsProbeAccess.offerStage(HOST));
            assertEquals(1, server.closeNotifyCount());
        }
    }

    @Test
    void closeNotifyOnTls13ThenTls12HoldsAes256() throws Exception {
        assumeCipher("TLS_CHACHA20_POLY1305_SHA256");
        assumeCipher("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256");
        assumeCipher("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
        try (UndertowHttpsServer server = UndertowHttpsServer.start(UndertowHttpsServer.Mode.TLS12_AES256)) {
            assertEquals("tls-ok", getBody(server.url()));
            assertEquals(TlsProbeAccess.aes256(), TlsProbeAccess.offerStage(HOST));
            assertEquals(2, server.closeNotifyCount());
        }
    }

    @Test
    void pkixFailureDoesNotAdvanceProbe() throws Exception {
        assumeCipher("TLS_CHACHA20_POLY1305_SHA256");
        SSLContext.setDefault(previousDefault);
        TlsProbeAccess.resetHostOffer();
        try (UndertowHttpsServer server = UndertowHttpsServer.start(UndertowHttpsServer.Mode.TLS13_CHACHA)) {
            ApacheHttpClient client = new ApacheHttpClient();
            IOException failure = assertThrows(IOException.class,
                    () -> client.open(new URL(server.url()), "GET", null, null));
            assertTrue(isPkix(failure), "expected cert path failure, got: " + failure);
            assertEquals(TlsProbeAccess.tls13(), TlsProbeAccess.offerStage(HOST),
                    "PKIX must not walk the cipher list; got stage " + TlsProbeAccess.offerStage(HOST));
            assertEquals(0, server.closeNotifyCount());
        }
    }

    @Test
    void secondGetOnCachedFullDoesNotCloseNotifyAgain() throws Exception {
        assumeCipher("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        assumeCipher("TLS_CHACHA20_POLY1305_SHA256");
        assumeCipher("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256");
        assumeCipher("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
        try (UndertowHttpsServer server = UndertowHttpsServer.start(UndertowHttpsServer.Mode.TLS12_AES_RSA)) {
            assertEquals("tls-ok", getBody(server.url()));
            int afterProbe = server.closeNotifyCount();
            assertTrue(afterProbe >= 3);
            assertEquals(TlsProbeAccess.full(), TlsProbeAccess.offerStage(HOST));
            assertEquals("tls-ok", getBody(server.url()));
            assertEquals(afterProbe, server.closeNotifyCount());
        }
    }

    @Test
    void headAlsoFallsThroughToAes() throws Exception {
        assumeCipher("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        assumeCipher("TLS_CHACHA20_POLY1305_SHA256");
        assumeCipher("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256");
        assumeCipher("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
        try (UndertowHttpsServer server = UndertowHttpsServer.start(UndertowHttpsServer.Mode.TLS12_AES_RSA)) {
            ApacheHttpClient client = new ApacheHttpClient();
            try (HttpResponse response = client.open(new URL(server.url()), "HEAD", null, null)) {
                assertEquals(200, response.getStatusCode());
            }
            assertEquals(TlsProbeAccess.full(), TlsProbeAccess.offerStage(HOST));
            assertTrue(server.closeNotifyCount() >= 3);
        }
    }

    @Test
    void oracleClientAlsoFallsThroughToAes() throws Exception {
        assumeCipher("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        assumeCipher("TLS_CHACHA20_POLY1305_SHA256");
        assumeCipher("TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256");
        assumeCipher("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
        try (UndertowHttpsServer server = UndertowHttpsServer.start(UndertowHttpsServer.Mode.TLS12_AES_RSA)) {
            OracleHttpClient client = new OracleHttpClient();
            try (HttpResponse response = client.open(new URL(server.url()), "GET", null, null)) {
                assertEquals(200, response.getStatusCode());
                try (InputStream in = response.getBody()) {
                    assertEquals("tls-ok", new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertEquals(TlsProbeAccess.full(), TlsProbeAccess.offerStage(HOST));
            assertTrue(server.closeNotifyCount() >= 3);
        }
    }

    private static String getBody(String url) throws Exception {
        ApacheHttpClient client = new ApacheHttpClient();
        try (HttpResponse response = client.open(new URL(url), "GET", null, null)) {
            assertEquals(200, response.getStatusCode());
            try (InputStream in = response.getBody()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static void assumeCipher(String suite) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, null, null);
        String[] supported = ctx.createSSLEngine().getSupportedCipherSuites();
        boolean found = false;
        for (String s : supported) {
            if (suite.equals(s)) {
                found = true;
                break;
            }
        }
        assumeTrue(found, "JVM does not support " + suite);
    }

    private static boolean isPkix(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null) {
                String l = m.toLowerCase();
                if (l.contains("pkix")
                        || l.contains("unable to find valid certification")
                        || l.contains("certificate_unknown")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static X509TrustManager trustingManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }
}
