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
