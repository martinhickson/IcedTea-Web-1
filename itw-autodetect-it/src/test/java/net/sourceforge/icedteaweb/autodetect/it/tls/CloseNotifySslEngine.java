package net.sourceforge.icedteaweb.autodetect.it.tls;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * When the ClientHello has no overlap with the server cipher, emit TLS
 * {@code close_notify} instead of {@code handshake_failure} — the abort
 * Apache sent on the TLS 1.3 ChaCha probe.
 */
final class CloseNotifySslEngine extends SSLEngine {

    private static final byte[] CLOSE_NOTIFY = {
            21, 3, 3, 0, 2, 1, 0
    };

    private final SSLEngine delegate;
    private final int[] acceptedCipherIds;
    private final AtomicInteger closeNotifyCount;
    private boolean inspected;
    private boolean rejected;
    private boolean closeNotifySent;

    CloseNotifySslEngine(SSLEngine delegate, int[] acceptedCipherIds, AtomicInteger closeNotifyCount) {
        super(delegate.getPeerHost(), delegate.getPeerPort());
        this.delegate = delegate;
        this.acceptedCipherIds = acceptedCipherIds;
        this.closeNotifyCount = closeNotifyCount;
    }

    @Override
    public SSLEngineResult unwrap(ByteBuffer src, ByteBuffer dst) throws SSLException {
        return unwrap(src, new ByteBuffer[] { dst }, 0, 1);
    }

    @Override
    public SSLEngineResult unwrap(ByteBuffer src, ByteBuffer[] dsts, int offset, int length)
            throws SSLException {
        if (!inspected) {
            ClientHelloCiphers.Outcome outcome = ClientHelloCiphers.match(src, acceptedCipherIds);
            if (outcome == ClientHelloCiphers.Outcome.INCOMPLETE) {
                return new SSLEngineResult(Status.BUFFER_UNDERFLOW, HandshakeStatus.NEED_UNWRAP, 0, 0);
            }
            inspected = true;
            if (outcome == ClientHelloCiphers.Outcome.MISS) {
                rejected = true;
                closeNotifyCount.incrementAndGet();
                int consumed = Math.min(src.remaining(), ClientHelloCiphers.recordLength(src));
                src.position(src.position() + consumed);
                delegate.closeOutbound();
                return new SSLEngineResult(Status.OK, HandshakeStatus.NEED_WRAP, consumed, 0);
            }
        }
        if (rejected) {
            int leftover = src.remaining();
            src.position(src.limit());
            return new SSLEngineResult(Status.CLOSED, HandshakeStatus.NOT_HANDSHAKING, leftover, 0);
        }
        return delegate.unwrap(src, dsts, offset, length);
    }

    @Override
    public SSLEngineResult wrap(ByteBuffer src, ByteBuffer dst) throws SSLException {
        return wrap(new ByteBuffer[] { src }, 0, 1, dst);
    }

    @Override
    public SSLEngineResult wrap(ByteBuffer[] srcs, int offset, int length, ByteBuffer dst)
            throws SSLException {
        if (rejected) {
            if (closeNotifySent) {
                return new SSLEngineResult(Status.CLOSED, HandshakeStatus.NOT_HANDSHAKING, 0, 0);
            }
            if (dst.remaining() < CLOSE_NOTIFY.length) {
                return new SSLEngineResult(Status.BUFFER_OVERFLOW, HandshakeStatus.NEED_WRAP, 0, 0);
            }
            dst.put(CLOSE_NOTIFY);
            closeNotifySent = true;
            return new SSLEngineResult(Status.CLOSED, HandshakeStatus.NOT_HANDSHAKING,
                    0, CLOSE_NOTIFY.length);
        }
        return delegate.wrap(srcs, offset, length, dst);
    }

    @Override
    public Runnable getDelegatedTask() {
        return delegate.getDelegatedTask();
    }

    @Override
    public void closeInbound() throws SSLException {
        delegate.closeInbound();
    }

    @Override
    public boolean isInboundDone() {
        return rejected || delegate.isInboundDone();
    }

    @Override
    public void closeOutbound() {
        delegate.closeOutbound();
    }

    @Override
    public boolean isOutboundDone() {
        return rejected || delegate.isOutboundDone();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public String[] getEnabledCipherSuites() {
        return delegate.getEnabledCipherSuites();
    }

    @Override
    public void setEnabledCipherSuites(String[] suites) {
        delegate.setEnabledCipherSuites(suites);
    }

    @Override
    public String[] getSupportedProtocols() {
        return delegate.getSupportedProtocols();
    }

    @Override
    public String[] getEnabledProtocols() {
        return delegate.getEnabledProtocols();
    }

    @Override
    public void setEnabledProtocols(String[] protocols) {
        delegate.setEnabledProtocols(protocols);
    }

    @Override
    public SSLSession getSession() {
        return delegate.getSession();
    }

    @Override
    public void beginHandshake() throws SSLException {
        delegate.beginHandshake();
    }

    @Override
    public HandshakeStatus getHandshakeStatus() {
        if (rejected) {
            return closeNotifySent ? HandshakeStatus.NOT_HANDSHAKING : HandshakeStatus.NEED_WRAP;
        }
        return delegate.getHandshakeStatus();
    }

    @Override
    public void setUseClientMode(boolean clientMode) {
        delegate.setUseClientMode(clientMode);
    }

    @Override
    public boolean getUseClientMode() {
        return delegate.getUseClientMode();
    }

    @Override
    public void setNeedClientAuth(boolean need) {
        delegate.setNeedClientAuth(need);
    }

    @Override
    public boolean getNeedClientAuth() {
        return delegate.getNeedClientAuth();
    }

    @Override
    public void setWantClientAuth(boolean want) {
        delegate.setWantClientAuth(want);
    }

    @Override
    public boolean getWantClientAuth() {
        return delegate.getWantClientAuth();
    }

    @Override
    public void setEnableSessionCreation(boolean flag) {
        delegate.setEnableSessionCreation(flag);
    }

    @Override
    public boolean getEnableSessionCreation() {
        return delegate.getEnableSessionCreation();
    }

    @Override
    public SSLParameters getSSLParameters() {
        return delegate.getSSLParameters();
    }

    @Override
    public void setSSLParameters(SSLParameters params) {
        delegate.setSSLParameters(params);
    }

    @Override
    public SSLSession getHandshakeSession() {
        return delegate.getHandshakeSession();
    }

    @Override
    public String getApplicationProtocol() {
        return delegate.getApplicationProtocol();
    }

    @Override
    public String getHandshakeApplicationProtocol() {
        return delegate.getHandshakeApplicationProtocol();
    }
}
