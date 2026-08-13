package net.sourceforge.jnlp.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import org.junit.jupiter.api.Test;

public class ItwSslSocketFactoryTest {

    @Test
    public void sharedIsSingletonAndInstallRejectsNull() throws Exception {
        assertSame(ItwSslSocketFactory.shared(), ItwSslSocketFactory.install(
                SSLContext.getDefault().getSocketFactory()));
        assertThrows(NullPointerException.class, () -> ItwSslSocketFactory.install(null));
    }

    @Test
    public void defaultCipherSuitesFollowItwTlsOffer() {
        ItwSslSocketFactory factory = ItwSslSocketFactory.shared();
        assertArrayEquals(ItwTls.ciphers(), factory.getDefaultCipherSuites());
    }

    @Test
    public void peerHostReadsSniThenApplyParametersStampsCiphers() throws Exception {
        SSLSocket sock = (SSLSocket) SSLContext.getDefault().getSocketFactory().createSocket();
        try {
            SSLParameters p = sock.getSSLParameters();
            p.setServerNames(Collections.singletonList(new SNIHostName("cdn.example")));
            sock.setSSLParameters(p);
            assertEquals("cdn.example", ItwSslSocketFactory.peerHost(sock));

            ItwSslSocketFactory.applyParameters(sock);
            assertArrayEquals(ItwTls.suitesFor("cdn.example"), sock.getSSLParameters().getCipherSuites());
        } finally {
            sock.close();
        }
    }

    @Test
    public void applyParametersStampsTls13ProbeProtocol() throws Exception {
        SSLSocket sock = (SSLSocket) SSLContext.getDefault().getSocketFactory().createSocket();
        try {
            SSLParameters p = sock.getSSLParameters();
            p.setServerNames(Collections.singletonList(new SNIHostName("cdn.example")));
            sock.setSSLParameters(p);
            ItwSslSocketFactory.applyParameters(sock);
            assertArrayEquals(new String[] { "TLSv1.3" }, sock.getSSLParameters().getProtocols());
        } finally {
            sock.close();
        }
    }

    @Test
    public void applyParametersFollowsHostOfferAfterCipherFallback() throws Exception {
        String saved = JNLPRuntime.getConfiguration()
                .getProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE);
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE, ItwTls.CIPHER_MODE_PROBE);
        ItwTls.resetHostOfferForTest();
        try {
            assertTrue(ItwTls.shouldRetryWithNextOffer("cdn.example",
                    new javax.net.ssl.SSLHandshakeException("handshake_failure")));
            SSLSocket sock = (SSLSocket) SSLContext.getDefault().getSocketFactory().createSocket();
            try {
                SSLParameters p = sock.getSSLParameters();
                p.setServerNames(Collections.singletonList(new SNIHostName("cdn.example")));
                sock.setSSLParameters(p);
                ItwSslSocketFactory.applyParameters(sock);
                assertArrayEquals(ItwTls.tls12Ciphers(), sock.getSSLParameters().getCipherSuites());
                assertArrayEquals(new String[] { "TLSv1.2" }, sock.getSSLParameters().getProtocols());
            } finally {
                sock.close();
            }
        } finally {
            if (saved != null) {
                JNLPRuntime.getConfiguration()
                        .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE, saved);
            }
            ItwTls.resetHostOfferForTest();
        }
    }

    @Test
    public void peerHostWithoutSniDoesNotThrow() throws Exception {
        SSLSocket sock = (SSLSocket) SSLContext.getDefault().getSocketFactory().createSocket();
        try {
            ItwSslSocketFactory.peerHost(sock);
            assertTrue(ItwSslSocketFactory.shared().getSupportedCipherSuites().length > 0);
        } finally {
            sock.close();
        }
    }
}
