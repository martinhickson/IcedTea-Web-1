package net.sourceforge.jnlp.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Probe TLS 1.3 ChaCha, then TLS 1.2 ECDHE-ECDSA ChaCha, then the full list.
 */
public class ItwTlsCipherProbeTest {

    private String savedMode;

    @BeforeEach
    void saveAndReset() {
        savedMode = JNLPRuntime.getConfiguration()
                .getProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE);
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE, ItwTls.CIPHER_MODE_PROBE);
        ItwTls.resetHostOfferForTest();
    }

    @AfterEach
    void restore() {
        if (savedMode != null) {
            JNLPRuntime.getConfiguration()
                    .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE, savedMode);
        }
        ItwTls.resetHostOfferForTest();
    }

    @Test
    public void probeStartsWithTls13ChaChaOnly() {
        String[] tls13 = ItwTls.tls13Ciphers();
        assertEquals(1, tls13.length);
        assertEquals(ItwTls.TLS13_CHACHA, tls13[0]);
        assertArrayEquals(tls13, ItwTls.suitesFor("example.test"));
        assertArrayEquals(new String[] { "TLSv1.3" }, ItwTls.protocolsFor("example.test"));
    }

    @Test
    public void tls12ProbeIsEcdheEcdsaChaChaOnly() {
        String[] tls12 = ItwTls.tls12Ciphers();
        assertEquals(1, tls12.length);
        assertEquals(ItwTls.TLS12_CHACHA, tls12[0]);
    }

    @Test
    public void fullModeOffersLegacyMultiSuiteList() {
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE, ItwTls.CIPHER_MODE_FULL);
        String[] full = ItwTls.fullCiphers();
        assertTrue(full.length > 1);
        assertArrayEquals(full, ItwTls.suitesFor("example.test"));
        assertArrayEquals(new String[] { "TLSv1.3", "TLSv1.2" }, ItwTls.protocolsFor("example.test"));
        assertFalse(ItwTls.isProbeMode());
    }

    @Test
    public void negotiatedTls13CachesTls13ProbeForHost() {
        ItwTls.noteNegotiated("cdn.example", ItwTls.TLS13_CHACHA, "TLSv1.3");
        assertEquals(ItwTls.OFFER_TLS13, ItwTls.offerState("cdn.example").get());
        assertArrayEquals(ItwTls.tls13Ciphers(), ItwTls.suitesFor("cdn.example"));
        assertArrayEquals(ItwTls.tls13Ciphers(), ItwTls.suitesFor("other.example"));
    }

    @Test
    public void negotiatedTls12EcdsaCachesTls12ProbeForHost() {
        ItwTls.noteNegotiated("cdn.example", ItwTls.TLS12_CHACHA, "TLSv1.2");
        assertEquals(ItwTls.OFFER_TLS12, ItwTls.offerState("cdn.example").get());
        assertArrayEquals(ItwTls.tls12Ciphers(), ItwTls.suitesFor("cdn.example"));
        assertArrayEquals(new String[] { "TLSv1.2" }, ItwTls.protocolsFor("cdn.example"));
        assertArrayEquals(ItwTls.tls13Ciphers(), ItwTls.suitesFor("fresh.example"));
    }

    @Test
    public void negotiatedAesCachesFullListForThatHostOnly() {
        ItwTls.noteNegotiated("legacy.example", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLSv1.2");
        assertEquals(ItwTls.OFFER_FULL, ItwTls.offerState("legacy.example").get());
        assertArrayEquals(ItwTls.fullCiphers(), ItwTls.suitesFor("legacy.example"));
        assertArrayEquals(ItwTls.tls13Ciphers(), ItwTls.suitesFor("fresh.example"));
    }

    @Test
    public void handshakeFailureAdvancesTls13ThenTls12ThenSticksToFull() {
        SSLHandshakeException fail = new SSLHandshakeException("Received fatal alert: handshake_failure");
        assertTrue(ItwTls.shouldRetryWithNextOffer("app.example", fail));
        assertEquals(ItwTls.OFFER_TLS12, ItwTls.offerState("app.example").get());
        assertArrayEquals(ItwTls.tls12Ciphers(), ItwTls.suitesFor("app.example"));
        assertArrayEquals(new String[] { "TLSv1.2" }, ItwTls.protocolsFor("app.example"));

        assertTrue(ItwTls.shouldRetryWithNextOffer("app.example", fail));
        assertEquals(ItwTls.OFFER_FULL, ItwTls.offerState("app.example").get());
        assertArrayEquals(ItwTls.fullCiphers(), ItwTls.suitesFor("app.example"));
        assertFalse(ItwTls.shouldRetryWithNextOffer("app.example", fail));
    }

    @Test
    public void closeNotifyDuringHandshakeAdvancesTls13ThenTls12ThenFull() {
        SSLProtocolException fail = new SSLProtocolException("Received close_notify during handshake");
        assertTrue(ItwTls.isCipherNegotiationFailure(fail));
        assertTrue(ItwTls.shouldRetryWithNextOffer("app.example", fail));
        assertEquals(ItwTls.OFFER_TLS12, ItwTls.offerState("app.example").get());
        assertArrayEquals(ItwTls.tls12Ciphers(), ItwTls.suitesFor("app.example"));
        assertArrayEquals(new String[] { "TLSv1.2" }, ItwTls.protocolsFor("app.example"));

        assertTrue(ItwTls.shouldRetryWithNextOffer("app.example", fail));
        assertEquals(ItwTls.OFFER_FULL, ItwTls.offerState("app.example").get());
        assertArrayEquals(ItwTls.fullCiphers(), ItwTls.suitesFor("app.example"));
        assertFalse(ItwTls.shouldRetryWithNextOffer("app.example", fail));
    }

    @Test
    public void wrappedCloseNotifyStillRetries() {
        IOException wrapped = new IOException("I/O",
                new SSLProtocolException("Received close_notify during handshake"));
        assertTrue(ItwTls.shouldRetryWithNextOffer("nested.example", wrapped));
        assertEquals(ItwTls.OFFER_TLS12, ItwTls.offerState("nested.example").get());
        assertTrue(ItwTls.shouldRetryWithNextOffer("nested.example", wrapped));
        assertFalse(ItwTls.shouldRetryWithNextOffer("nested.example", wrapped));
    }

    @Test
    public void certFailuresDoNotTriggerCipherFallback() {
        assertFalse(ItwTls.shouldRetryWithNextOffer("app.example",
                new SSLPeerUnverifiedException("peer not authenticated")));
        assertFalse(ItwTls.shouldRetryWithNextOffer("app.example",
                new SSLHandshakeException("PKIX path building failed")));
        assertEquals(ItwTls.OFFER_UNKNOWN, ItwTls.offerState("app.example").get());
    }

    @Test
    public void hostKeyIsCaseInsensitive() {
        ItwTls.noteNegotiated("CDN.Example", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLSv1.2");
        assertEquals(ItwTls.OFFER_FULL, ItwTls.offerState("cdn.example").get());
        assertArrayEquals(ItwTls.fullCiphers(), ItwTls.suitesFor("CdN.eXaMpLe"));
    }

    @Test
    public void nestedHandshakeFailureStillRetries() {
        IOException wrapped = new IOException("I/O",
                new SSLHandshakeException("Received fatal alert: handshake_failure"));
        assertTrue(ItwTls.shouldRetryWithNextOffer("nested.example", wrapped));
        assertEquals(ItwTls.OFFER_TLS12, ItwTls.offerState("nested.example").get());
        assertTrue(ItwTls.shouldRetryWithNextOffer("nested.example", wrapped));
        assertFalse(ItwTls.shouldRetryWithNextOffer("nested.example", wrapped));
    }

    @Test
    public void noCipherSuitesInCommonMessageRetries() {
        assertTrue(ItwTls.shouldRetryWithNextOffer("plain.example",
                new IOException("no cipher suites in common")));
        assertEquals(ItwTls.OFFER_TLS12, ItwTls.offerState("plain.example").get());
    }

    @Test
    public void fullModeNeverRetriesProbe() {
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE, ItwTls.CIPHER_MODE_FULL);
        assertFalse(ItwTls.shouldRetryWithNextOffer("full.example",
                new SSLHandshakeException("Received fatal alert: handshake_failure")));
        assertEquals(ItwTls.OFFER_UNKNOWN, ItwTls.offerState("full.example").get());
    }

    @Test
    public void cipherSuitesOverrideDisablesProbe() {
        String saved = JNLPRuntime.getConfiguration()
                .getProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_SUITES);
        try {
            JNLPRuntime.getConfiguration().setProperty(
                    DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_SUITES,
                    "TLS_AES_128_GCM_SHA256");
            assertFalse(ItwTls.isProbeMode());
        } finally {
            if (saved == null) {
                JNLPRuntime.getConfiguration().setProperty(
                        DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_SUITES, "");
            } else {
                JNLPRuntime.getConfiguration()
                        .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_SUITES, saved);
            }
        }
    }

    @Test
    public void parametersForProbeHostOfferTls13AndSni() {
        SSLParameters p = ItwTls.parametersFor("cdn.example");
        assertArrayEquals(ItwTls.tls13Ciphers(), p.getCipherSuites());
        List<String> protocols = Arrays.asList(p.getProtocols());
        assertEquals(List.of("TLSv1.3"), protocols);
        assertEquals(1, p.getServerNames().size());
        assertEquals("cdn.example", ((SNIHostName) p.getServerNames().get(0)).getAsciiName());
    }

    @Test
    public void parametersForLiteralIpDoesNotThrow() {
        SSLParameters p = ItwTls.parametersFor("127.0.0.1");
        assertArrayEquals(ItwTls.tls13Ciphers(), p.getCipherSuites());
    }

    @Test
    public void isPreferredCipherMatchesBothProbeSuites() {
        assertTrue(ItwTls.isPreferredCipher(ItwTls.TLS13_CHACHA));
        assertTrue(ItwTls.isPreferredCipher(ItwTls.TLS12_CHACHA));
        assertFalse(ItwTls.isPreferredCipher("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"));
        assertFalse(ItwTls.isPreferredCipher("TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"));
        assertFalse(ItwTls.isPreferredCipher(null));
    }

    @Test
    public void rsaChaChaIsNotAProbeHold() {
        ItwTls.noteNegotiated("rsa.example", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256", "TLSv1.2");
        assertEquals(ItwTls.OFFER_FULL, ItwTls.offerState("rsa.example").get());
        assertArrayEquals(ItwTls.fullCiphers(), ItwTls.suitesFor("rsa.example"));
        assertEquals(ItwTls.OFFER_FULL, ItwTls.stageForNegotiatedCipher(
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"));
    }

    @Test
    public void noteNegotiatedWithoutProtocolStillCachesTls13() {
        ItwTls.noteNegotiated("cdn.example", ItwTls.TLS13_CHACHA);
        assertEquals(ItwTls.OFFER_TLS13, ItwTls.offerState("cdn.example").get());
        assertArrayEquals(ItwTls.tls13Ciphers(), ItwTls.suitesFor("cdn.example"));
    }

    @Test
    public void parametersForAfterTls12AdvanceOffersTls12Only() {
        SSLHandshakeException fail = new SSLHandshakeException("handshake_failure");
        assertTrue(ItwTls.shouldRetryWithNextOffer("app.example", fail));
        SSLParameters p = ItwTls.parametersFor("app.example");
        assertArrayEquals(ItwTls.tls12Ciphers(), p.getCipherSuites());
        assertArrayEquals(new String[] { "TLSv1.2" }, p.getProtocols());
    }

    @Test
    public void parametersAndCiphersAndSummaryFollowUnknownHostProbe() {
        ItwTls.warm();
        assertArrayEquals(ItwTls.tls13Ciphers(), ItwTls.parameters().getCipherSuites());
        assertArrayEquals(ItwTls.tls13Ciphers(), ItwTls.ciphers());
        assertEquals(ItwTls.TLS13_CHACHA, ItwTls.offeredCipherSummary());
        assertEquals(ItwTls.TLS13_CHACHA, ItwTls.offeredCipherSummary("fresh.example"));
        assertEquals("tls13", ItwTls.stageName(ItwTls.OFFER_TLS13));
        assertEquals("tls12", ItwTls.stageName(ItwTls.OFFER_TLS12));
        assertEquals("full", ItwTls.stageName(ItwTls.OFFER_FULL));
        assertNotNull(ItwTls.context());
    }

    @Test
    public void emptyAndNullHostShareTheUnknownOffer() {
        ItwTls.noteNegotiated("", ItwTls.TLS12_CHACHA, "TLSv1.2");
        assertEquals(ItwTls.OFFER_TLS12, ItwTls.offerState(null).get());
        assertArrayEquals(ItwTls.tls12Ciphers(), ItwTls.suitesFor(null));
        assertArrayEquals(ItwTls.tls12Ciphers(), ItwTls.suitesFor(""));
    }

    @Test
    public void certAlertMessagesDoNotTriggerCipherFallback() {
        assertFalse(ItwTls.shouldRetryWithNextOffer("app.example",
                new SSLHandshakeException("Received fatal alert: certificate_unknown")));
        assertFalse(ItwTls.shouldRetryWithNextOffer("app.example",
                new SSLHandshakeException("certificate_expired")));
        assertEquals(ItwTls.OFFER_UNKNOWN, ItwTls.offerState("app.example").get());
    }

    @Test
    public void nextStageFromFullDoesNotRetry() {
        ItwTls.offerState("done.example").set(ItwTls.OFFER_FULL);
        assertEquals(ItwTls.OFFER_FULL, ItwTls.nextStage(ItwTls.OFFER_FULL));
        assertFalse(ItwTls.shouldRetryWithNextOffer("done.example",
                new SSLHandshakeException("handshake_failure")));
    }

    @Test
    public void parametersForIpv6DoesNotThrow() {
        SSLParameters p = ItwTls.parametersFor("::1");
        assertArrayEquals(ItwTls.tls13Ciphers(), p.getCipherSuites());
    }

    @Test
    public void probeCiphersIsTheTls13Suite() {
        assertArrayEquals(ItwTls.tls13Ciphers(), ItwTls.probeCiphers());
    }
}
