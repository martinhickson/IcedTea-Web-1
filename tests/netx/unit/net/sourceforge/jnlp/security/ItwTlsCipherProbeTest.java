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
 * Probe TLS 1.3 ChaCha, then TLS 1.2 ECDHE-ECDSA ChaCha, then TLS 1.2
 * AES-256-GCM, then the full list. Cipher misses short-circuit inside open().
 */
public class ItwTlsCipherProbeTest {

    private String savedMode;
    private String savedFastest;

    @BeforeEach
    void saveAndReset() {
        savedMode = JNLPRuntime.getConfiguration()
                .getProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE);
        savedFastest = JNLPRuntime.getConfiguration()
                .getProperty(DeploymentConfiguration.KEY_USE_FASTEST_CIPHER);
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_USE_FASTEST_CIPHER, "true");
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
        if (savedFastest != null) {
            JNLPRuntime.getConfiguration()
                    .setProperty(DeploymentConfiguration.KEY_USE_FASTEST_CIPHER, savedFastest);
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
    public void fastestCipherDisabledUsesFullSetEvenWhenCipherModeIsProbe() {
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_USE_FASTEST_CIPHER, "false");
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE, ItwTls.CIPHER_MODE_PROBE);
        assertFalse(ItwTls.isUseFastestCipher());
        assertFalse(ItwTls.isProbeMode());
        assertArrayEquals(ItwTls.fullCiphers(), ItwTls.suitesFor("example.test"));
        assertArrayEquals(new String[] { "TLSv1.3", "TLSv1.2" }, ItwTls.protocolsFor("example.test"));
    }

    @Test
    public void fastestCipherEnabledWithProbeModeOffersSingleSuite() {
        assertTrue(ItwTls.isUseFastestCipher());
        assertTrue(ItwTls.isProbeMode());
        assertArrayEquals(ItwTls.tls13Ciphers(), ItwTls.suitesFor("example.test"));
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
    public void negotiatedAes256CachesAes256ProbeForHost() {
        assumeAes256Supported();
        ItwTls.noteNegotiated("cdn.example", ItwTls.TLS12_AES256, "TLSv1.2");
        assertEquals(ItwTls.OFFER_AES256, ItwTls.offerState("cdn.example").get());
        assertArrayEquals(ItwTls.aes256Ciphers(), ItwTls.suitesFor("cdn.example"));
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
    public void handshakeFailureAdvancesTls13ThenTls12ThenAes256ThenSticksToFull() {
        SSLHandshakeException fail = new SSLHandshakeException("Received fatal alert: handshake_failure");
        assertWalksToFull("app.example", fail);
        assertFalse(ItwTls.shortCircuitToNextOffer("app.example", fail));
        assertTrue(ItwTls.continueOpenAfterHandshakeMiss("app.example", fail, ItwTls.OFFER_TLS13),
                "open() must try the current offer once if this attempt started on a narrower stage");
        assertFalse(ItwTls.continueOpenAfterHandshakeMiss("app.example", fail, ItwTls.OFFER_FULL));
    }

    @Test
    public void closeNotifyDuringHandshakeAdvancesTls13ThenTls12ThenAes256ThenFull() {
        SSLProtocolException fail = new SSLProtocolException("Received close_notify during handshake");
        assertTrue(ItwTls.isCipherNegotiationFailure(fail));
        assertWalksToFull("app.example", fail);
        assertFalse(ItwTls.shortCircuitToNextOffer("app.example", fail));
        assertTrue(ItwTls.continueOpenAfterHandshakeMiss("app.example", fail, ItwTls.OFFER_TLS12),
                "parallel GET must continue this same URL after siblings already moved the host to full");
        assertEquals(ItwTls.OFFER_FULL, ItwTls.offerState("app.example").get());
        assertFalse(ItwTls.continueOpenAfterHandshakeMiss("app.example", fail, ItwTls.OFFER_FULL));
    }

    @Test
    public void handshakeMissReasonIsAShortPhraseNotAStack() {
        assertEquals("close_notify", ItwTls.handshakeMissReason(
                new SSLProtocolException("Received close_notify during handshake")));
        assertEquals("handshake_failure", ItwTls.handshakeMissReason(
                new SSLHandshakeException("Received fatal alert: handshake_failure")));
        assertEquals("no cipher suites in common", ItwTls.handshakeMissReason(
                new IOException("no cipher suites in common")));
        assertEquals("close_notify", ItwTls.handshakeMissReason(
                new IOException("I/O", new SSLProtocolException("Received close_notify during handshake"))));
    }

    @Test
    public void fullModeDoesNotContinueOpenOnHandshakeMiss() {
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE, ItwTls.CIPHER_MODE_FULL);
        SSLHandshakeException fail = new SSLHandshakeException("Received fatal alert: handshake_failure");
        assertFalse(ItwTls.continueOpenAfterHandshakeMiss("full.example", fail, ItwTls.OFFER_TLS13));
        assertFalse(ItwTls.shortCircuitToNextOffer("full.example", fail));
    }

    @Test
    public void wrappedCloseNotifyStillRetries() {
        IOException wrapped = new IOException("I/O",
                new SSLProtocolException("Received close_notify during handshake"));
        assertWalksToFull("nested.example", wrapped);
    }

    @Test
    public void certFailuresDoNotTriggerCipherFallback() {
        assertFalse(ItwTls.shouldRetryWithNextOffer("app.example",
                new SSLPeerUnverifiedException("peer not authenticated")));
        assertFalse(ItwTls.shouldRetryWithNextOffer("app.example",
                new SSLHandshakeException("PKIX path building failed")));
        assertFalse(ItwTls.continueOpenAfterHandshakeMiss("app.example",
                new SSLHandshakeException("PKIX path building failed"), ItwTls.OFFER_TLS13));
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
        assertWalksToFull("nested.example", wrapped);
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
        if (ItwTls.aes256Ciphers().length > 0) {
            assertEquals("aes256", ItwTls.stageName(ItwTls.OFFER_AES256));
        }
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

    @Test
    public void genericSslExceptionIsAProbeMiss() {
        javax.net.ssl.SSLException fail = new javax.net.ssl.SSLException("protocol error");
        assertTrue(ItwTls.isCipherNegotiationFailure(fail));
        assertTrue(ItwTls.shouldRetryWithNextOffer("app.example", fail));
        assertEquals(ItwTls.OFFER_TLS12, ItwTls.offerState("app.example").get());
    }

    @Test
    public void closeNotifyInIoExceptionMessageStillRetries() {
        assertTrue(ItwTls.shouldRetryWithNextOffer("app.example",
                new IOException("Received close_notify during handshake")));
        assertEquals(ItwTls.OFFER_TLS12, ItwTls.offerState("app.example").get());
    }

    @Test
    public void pkixCauseWinsOverOuterSslException() {
        javax.net.ssl.SSLException outer = new javax.net.ssl.SSLException("handshake failed");
        outer.initCause(new SSLHandshakeException("PKIX path building failed"));
        assertFalse(ItwTls.isCipherNegotiationFailure(outer));
        assertFalse(ItwTls.shouldRetryWithNextOffer("app.example", outer));
        assertEquals(ItwTls.OFFER_UNKNOWN, ItwTls.offerState("app.example").get());
    }

    @Test
    public void peerUnverifiedCauseDoesNotFallback() {
        javax.net.ssl.SSLException outer = new javax.net.ssl.SSLException("peer");
        outer.initCause(new SSLPeerUnverifiedException("peer not authenticated"));
        assertFalse(ItwTls.shouldRetryWithNextOffer("app.example", outer));
        assertEquals(ItwTls.OFFER_UNKNOWN, ItwTls.offerState("app.example").get());
    }

    @Test
    public void unableToFindValidCertificationDoesNotFallback() {
        assertFalse(ItwTls.shouldRetryWithNextOffer("app.example",
                new SSLHandshakeException("unable to find valid certification path to requested target")));
        assertEquals(ItwTls.OFFER_UNKNOWN, ItwTls.offerState("app.example").get());
    }

    @Test
    public void noteNegotiatedNullCipherIsANoOp() {
        ItwTls.noteNegotiated("app.example", null, "TLSv1.3");
        assertEquals(ItwTls.OFFER_UNKNOWN, ItwTls.offerState("app.example").get());
        assertArrayEquals(ItwTls.tls13Ciphers(), ItwTls.suitesFor("app.example"));
    }

    @Test
    public void emptyAndProbeModeAreCaseInsensitiveProbe() {
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE, "");
        assertTrue(ItwTls.isProbeMode());
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE, "  Probe  ");
        assertTrue(ItwTls.isProbeMode());
    }

    @Test
    public void fullModeNoteNegotiatedDoesNotHoldAProbeStage() {
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE, ItwTls.CIPHER_MODE_FULL);
        ItwTls.noteNegotiated("cdn.example", ItwTls.TLS13_CHACHA, "TLSv1.3");
        assertArrayEquals(ItwTls.fullCiphers(), ItwTls.suitesFor("cdn.example"));
        assertArrayEquals(new String[] { "TLSv1.3", "TLSv1.2" }, ItwTls.protocolsFor("cdn.example"));
    }

    @Test
    public void shouldRetryWithFullCiphersIsTheSameAdvance() {
        SSLHandshakeException fail = new SSLHandshakeException("handshake_failure");
        assertTrue(ItwTls.shouldRetryWithFullCiphers("app.example", fail));
        assertEquals(ItwTls.OFFER_TLS12, ItwTls.offerState("app.example").get());
    }

    @Test
    public void offeredCipherSummaryFollowsHostAdvance() {
        SSLHandshakeException fail = new SSLHandshakeException("handshake_failure");
        assertTrue(ItwTls.shouldRetryWithNextOffer("app.example", fail));
        assertEquals(ItwTls.TLS12_CHACHA, ItwTls.offeredCipherSummary("app.example"));
        if (ItwTls.aes256Ciphers().length > 0) {
            assertTrue(ItwTls.shouldRetryWithNextOffer("app.example", fail));
            assertEquals(ItwTls.TLS12_AES256, ItwTls.offeredCipherSummary("app.example"));
            assertArrayEquals(new String[] { "TLSv1.2" }, ItwTls.protocolsFor("app.example"));
        }
        assertTrue(ItwTls.shouldRetryWithNextOffer("app.example", fail));
        assertEquals(String.join(",", ItwTls.fullCiphers()), ItwTls.offeredCipherSummary("app.example"));
        assertArrayEquals(new String[] { "TLSv1.3", "TLSv1.2" }, ItwTls.protocolsFor("app.example"));
    }

    private static void assumeAes256Supported() {
        org.junit.jupiter.api.Assumptions.assumeTrue(ItwTls.aes256Ciphers().length > 0,
                "JVM does not support " + ItwTls.TLS12_AES256);
    }

    /** TLS 1.3 → TLS 1.2 ChaCha → AES-256-GCM (if supported) → full. */
    private static void assertWalksToFull(String host, Exception fail) {
        assertTrue(ItwTls.shouldRetryWithNextOffer(host, fail));
        assertEquals(ItwTls.OFFER_TLS12, ItwTls.offerState(host).get());
        assertArrayEquals(ItwTls.tls12Ciphers(), ItwTls.suitesFor(host));
        assertArrayEquals(new String[] { "TLSv1.2" }, ItwTls.protocolsFor(host));
        if (ItwTls.aes256Ciphers().length > 0) {
            assertTrue(ItwTls.shouldRetryWithNextOffer(host, fail));
            assertEquals(ItwTls.OFFER_AES256, ItwTls.offerState(host).get());
            assertArrayEquals(ItwTls.aes256Ciphers(), ItwTls.suitesFor(host));
            assertArrayEquals(new String[] { "TLSv1.2" }, ItwTls.protocolsFor(host));
        }
        assertTrue(ItwTls.shouldRetryWithNextOffer(host, fail));
        assertEquals(ItwTls.OFFER_FULL, ItwTls.offerState(host).get());
        assertArrayEquals(ItwTls.fullCiphers(), ItwTls.suitesFor(host));
        assertFalse(ItwTls.shouldRetryWithNextOffer(host, fail));
    }
}
