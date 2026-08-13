package net.sourceforge.jnlp.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Probe-one-ChaCha then atomic per-host fallback to the full cipher list.
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
    public void probeOffersExactlyOneChaChaSuite() {
        String[] probe = ItwTls.probeCiphers();
        assertEquals(1, probe.length);
        assertTrue(probe[0].contains("CHACHA20"), "preferred suite should be ChaCha20-Poly1305: " + probe[0]);
        assertArrayEquals(probe, ItwTls.suitesFor("example.test"));
    }

    @Test
    public void fullModeOffersLegacyMultiSuiteList() {
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE, ItwTls.CIPHER_MODE_FULL);
        String[] full = ItwTls.fullCiphers();
        assertTrue(full.length > 1);
        assertArrayEquals(full, ItwTls.suitesFor("example.test"));
        assertFalse(ItwTls.isProbeMode());
    }

    @Test
    public void negotiatedPreferredCachesProbeForHost() {
        String preferred = ItwTls.probeCiphers()[0];
        ItwTls.noteNegotiated("cdn.example", preferred);
        assertEquals(ItwTls.OFFER_PREFERRED, ItwTls.offerState("cdn.example").get());
        assertArrayEquals(ItwTls.probeCiphers(), ItwTls.suitesFor("cdn.example"));
        assertArrayEquals(ItwTls.probeCiphers(), ItwTls.suitesFor("other.example"));
    }

    @Test
    public void negotiatedAesCachesFullListForThatHostOnly() {
        ItwTls.noteNegotiated("legacy.example", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        assertEquals(ItwTls.OFFER_FULL, ItwTls.offerState("legacy.example").get());
        assertArrayEquals(ItwTls.fullCiphers(), ItwTls.suitesFor("legacy.example"));
        assertArrayEquals(ItwTls.probeCiphers(), ItwTls.suitesFor("fresh.example"));
    }

    @Test
    public void handshakeFailureRetriesOnceThenSticksToFull() {
        SSLHandshakeException fail = new SSLHandshakeException("Received fatal alert: handshake_failure");
        assertTrue(ItwTls.shouldRetryWithFullCiphers("app.example", fail));
        assertEquals(ItwTls.OFFER_FULL, ItwTls.offerState("app.example").get());
        assertArrayEquals(ItwTls.fullCiphers(), ItwTls.suitesFor("app.example"));
        assertFalse(ItwTls.shouldRetryWithFullCiphers("app.example", fail));
    }

    @Test
    public void certFailuresDoNotTriggerCipherFallback() {
        assertFalse(ItwTls.shouldRetryWithFullCiphers("app.example",
                new SSLPeerUnverifiedException("peer not authenticated")));
        assertFalse(ItwTls.shouldRetryWithFullCiphers("app.example",
                new SSLHandshakeException("PKIX path building failed")));
        assertEquals(ItwTls.OFFER_UNKNOWN, ItwTls.offerState("app.example").get());
    }
}
