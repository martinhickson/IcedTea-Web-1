package net.sourceforge.jnlp.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLSession;
import net.sourceforge.jnlp.util.logging.OutputController;

public final class ItwSslSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public ItwSslSocketFactory() {
        // Wrap ITW's DEFAULT factory (configured by JNLPRuntime with the
        // VariableX509TrustManager chain), so trusted.jssecacerts / cert-dialog
        // handling is preserved. We only stamp cipher/protocol order + handshake
        // logging on top — never replace the trust chain.
        this.delegate = HttpsURLConnection.getDefaultSSLSocketFactory();
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return ItwTls.offeredCipherSummary().split(",");
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return stamp((SSLSocket) delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
        return stamp((SSLSocket) delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException, UnknownHostException {
        return stamp((SSLSocket) delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return stamp((SSLSocket) delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return stamp((SSLSocket) delegate.createSocket(address, port, localAddress, localPort));
    }

    private SSLSocket stamp(SSLSocket sock) {
        sock.setSSLParameters(ItwTls.parameters());
        sock.addHandshakeCompletedListener(e -> {
            SSLSession s = e.getSession();
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                "TLS established: cipher=" + s.getCipherSuite()
                + " protocol=" + s.getProtocol()
                + " peer=" + s.getPeerHost()
                + " (offered=" + ItwTls.offeredCipherSummary() + ")");
        });
        return sock;
    }
}
