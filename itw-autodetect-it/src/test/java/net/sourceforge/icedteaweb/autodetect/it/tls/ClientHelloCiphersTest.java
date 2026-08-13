package net.sourceforge.icedteaweb.autodetect.it.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class ClientHelloCiphersTest {

    @Test
    void aesGcmRsaIsAMatchAndTls13ChaChaIsAMiss() {
        ByteBuffer aes = ClientHelloCiphers.syntheticHello(
                ClientHelloCiphers.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256);
        assertEquals(ClientHelloCiphers.Outcome.MATCH,
                ClientHelloCiphers.match(aes, ClientHelloCiphers.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256));
        aes.rewind();
        assertEquals(ClientHelloCiphers.Outcome.MISS,
                ClientHelloCiphers.match(aes, ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256));
    }

    @Test
    void tls13ChaChaMatchesAmongSeveralOffers() {
        ByteBuffer hello = ClientHelloCiphers.syntheticHello(
                0x1301,
                ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256,
                ClientHelloCiphers.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256);
        assertEquals(ClientHelloCiphers.Outcome.MATCH,
                ClientHelloCiphers.match(hello, ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256));
    }

    @Test
    void shortBufferIsIncomplete() {
        ByteBuffer tiny = ByteBuffer.wrap(new byte[] { 22, 3, 3 });
        assertEquals(ClientHelloCiphers.Outcome.INCOMPLETE,
                ClientHelloCiphers.match(tiny, ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256));
    }

    @Test
    void declaredRecordLongerThanBufferIsIncomplete() {
        byte[] bytes = new byte[] { 22, 3, 3, 0, 40, 1, 0, 0, 1 };
        assertEquals(ClientHelloCiphers.Outcome.INCOMPLETE,
                ClientHelloCiphers.match(ByteBuffer.wrap(bytes),
                        ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256));
    }

    @Test
    void applicationDataIsNotClientHello() {
        ByteBuffer buf = ByteBuffer.wrap(new byte[] { 23, 3, 3, 0, 1, 0 });
        assertEquals(ClientHelloCiphers.Outcome.NOT_CLIENT_HELLO,
                ClientHelloCiphers.match(buf, ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256));
    }

    @Test
    void serverHelloHandshakeTypeIsNotClientHello() {
        ByteBuffer hello = ClientHelloCiphers.syntheticHello(
                ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256);
        hello.put(5, (byte) 2);
        hello.rewind();
        assertEquals(ClientHelloCiphers.Outcome.NOT_CLIENT_HELLO,
                ClientHelloCiphers.match(hello, ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256));
    }

    @Test
    void oddCipherListLengthIsNotClientHello() {
        ByteBuffer hello = ClientHelloCiphers.syntheticHello(
                ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256);
        int csLenPos = 5 + 4 + 2 + 32 + 1;
        hello.put(csLenPos + 1, (byte) 1);
        hello.rewind();
        assertEquals(ClientHelloCiphers.Outcome.NOT_CLIENT_HELLO,
                ClientHelloCiphers.match(hello, ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256));
    }

    @Test
    void recordLengthCountsTheTlsHeader() {
        ByteBuffer hello = ClientHelloCiphers.syntheticHello(
                ClientHelloCiphers.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256);
        assertEquals(hello.remaining(), ClientHelloCiphers.recordLength(hello));
    }
}
