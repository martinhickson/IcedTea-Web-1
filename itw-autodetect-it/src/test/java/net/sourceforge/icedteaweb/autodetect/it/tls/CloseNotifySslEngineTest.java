package net.sourceforge.icedteaweb.autodetect.it.tls;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import org.junit.jupiter.api.Test;

class CloseNotifySslEngineTest {

    @Test
    void missUnwrapEmitsCloseNotifyOnce() throws Exception {
        SSLEngine delegate = SSLContext.getDefault().createSSLEngine();
        delegate.setUseClientMode(false);
        AtomicInteger closes = new AtomicInteger();
        CloseNotifySslEngine engine = new CloseNotifySslEngine(delegate,
                new int[] { ClientHelloCiphers.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 }, closes);

        ByteBuffer hello = ClientHelloCiphers.syntheticHello(
                ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256);
        ByteBuffer app = ByteBuffer.allocate(64);
        SSLEngineResult unwrap = engine.unwrap(hello, app);
        assertEquals(Status.OK, unwrap.getStatus());
        assertEquals(HandshakeStatus.NEED_WRAP, unwrap.getHandshakeStatus());
        assertEquals(1, closes.get());
        assertEquals(0, hello.remaining());

        ByteBuffer out = ByteBuffer.allocate(16);
        SSLEngineResult wrap = engine.wrap(ByteBuffer.allocate(0), out);
        assertEquals(Status.CLOSED, wrap.getStatus());
        assertEquals(7, wrap.bytesProduced());
        out.flip();
        byte[] alert = new byte[7];
        out.get(alert);
        assertArrayEquals(new byte[] { 21, 3, 3, 0, 2, 1, 0 }, alert);

        SSLEngineResult second = engine.wrap(ByteBuffer.allocate(0), ByteBuffer.allocate(16));
        assertEquals(Status.CLOSED, second.getStatus());
        assertEquals(0, second.bytesProduced());
        assertEquals(HandshakeStatus.NOT_HANDSHAKING, engine.getHandshakeStatus());
        assertTrue(engine.isOutboundDone());
    }

    @Test
    void incompleteClientHelloAsksForMoreBytes() throws Exception {
        SSLEngine delegate = SSLContext.getDefault().createSSLEngine();
        delegate.setUseClientMode(false);
        CloseNotifySslEngine engine = new CloseNotifySslEngine(delegate,
                new int[] { ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256 }, new AtomicInteger());
        ByteBuffer tiny = ByteBuffer.wrap(new byte[] { 22, 3, 3, 0, 40 });
        SSLEngineResult result = engine.unwrap(tiny, ByteBuffer.allocate(32));
        assertEquals(Status.BUFFER_UNDERFLOW, result.getStatus());
        assertEquals(HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
        assertEquals(0, result.bytesConsumed());
    }

    @Test
    void wrapOverflowsWhenDestinationCannotHoldAlert() throws Exception {
        SSLEngine delegate = SSLContext.getDefault().createSSLEngine();
        delegate.setUseClientMode(false);
        CloseNotifySslEngine engine = new CloseNotifySslEngine(delegate,
                new int[] { ClientHelloCiphers.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 },
                new AtomicInteger());
        engine.unwrap(ClientHelloCiphers.syntheticHello(
                ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256), ByteBuffer.allocate(32));
        SSLEngineResult overflow = engine.wrap(ByteBuffer.allocate(0), ByteBuffer.allocate(3));
        assertEquals(Status.BUFFER_OVERFLOW, overflow.getStatus());
        assertEquals(HandshakeStatus.NEED_WRAP, overflow.getHandshakeStatus());
        assertEquals(0, overflow.bytesProduced());
    }
}
