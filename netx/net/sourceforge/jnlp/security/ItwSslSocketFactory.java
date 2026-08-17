package net.sourceforge.jnlp.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Stamps ITW cipher/protocol order onto sockets from the runtime SSLContext
 * (VariableX509TrustManager). Used by Apache HttpClient (jar downloads) and
 * {@link java.net.HttpURLConnection} — not only the late ConnectionFactory path.
 */
public final class ItwSslSocketFactory extends SSLSocketFactory {

    private static final ItwSslSocketFactory SHARED = new ItwSslSocketFactory();

    public ItwSslSocketFactory() {
    }

    /**
     * Called from {@code JNLPRuntime} once the ITW {@code SSLContext} exists.
     * Delegate sockets are resolved live from {@link JNLPRuntime#getSslContext()}
     * so an early Apache client still picks up the trust chain.
     */
    public static ItwSslSocketFactory install(SSLSocketFactory unusedTrustFactory) {
        // Trust lives on JNLPRuntime.getSslContext(); this wrapper stamps ciphers.
        if (unusedTrustFactory == null) {
            throw new NullPointerException("ssl socket factory");
        }
        return SHARED;
    }

    public static ItwSslSocketFactory shared() {
        return SHARED;
    }

    /** Raw JSSE factory from the runtime SSLContext — never this wrapper. */
    private static SSLSocketFactory delegate() {
        return JNLPRuntime.getSslContext().getSocketFactory();
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return ItwTls.ciphers();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate().getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return stamp((SSLSocket) delegate().createSocket(s, host, port, autoClose), host);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
        return stamp((SSLSocket) delegate().createSocket(host, port), host);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException, UnknownHostException {
        return stamp((SSLSocket) delegate().createSocket(host, port, localHost, localPort), host);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        String name = host != null ? host.getHostAddress() : null;
        return stamp((SSLSocket) delegate().createSocket(host, port), name);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        String name = address != null ? address.getHostAddress() : null;
        return stamp((SSLSocket) delegate().createSocket(address, port, localAddress, localPort), name);
    }

    /**
     * Re-apply cipher selection after Apache HttpClient's {@code excludeWeak}
     * pass, which otherwise overwrites {@link SSLSocket#setEnabledCipherSuites}.
     */
    static void applyParameters(SSLSocket sock) {
        HttpSocketBuffers.apply(sock);
        sock.setSSLParameters(ItwTls.parametersFor(peerHost(sock)));
    }

    private SSLSocket stamp(SSLSocket sock, String host) {
        HttpSocketBuffers.apply(sock);
        sock.setSSLParameters(ItwTls.parametersFor(host != null ? host : peerHost(sock)));
        sock.addHandshakeCompletedListener(e -> {
            SSLSession s = e.getSession();
            String peer = s.getPeerHost();
            String cipher = s.getCipherSuite();
            ItwTls.noteNegotiated(peer, cipher, s.getProtocol());
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                "TLS established: cipher=" + cipher
                + " protocol=" + s.getProtocol()
                + " peer=" + peer
                + " (offered=" + ItwTls.offeredCipherSummary(peer) + ")");
        });
        return sock;
    }

    static String peerHost(SSLSocket sock) {
        SSLParameters p = sock.getSSLParameters();
        List<SNIServerName> names = p.getServerNames();
        if (names != null) {
            for (SNIServerName n : names) {
                if (n instanceof SNIHostName) {
                    return ((SNIHostName) n).getAsciiName();
                }
            }
        }
        InetAddress addr = sock.getInetAddress();
        return addr != null ? addr.getHostAddress() : null;
    }
}
