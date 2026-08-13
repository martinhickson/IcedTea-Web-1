package net.sourceforge.icedteaweb.autodetect.it.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class ClientHelloCiphersTest {

    @Test
    void aesGcmRsaIsAMatchAndTls13ChaChaIsAMiss() {
        ByteBuffer aes = hello(ClientHelloCiphers.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256);
        assertEquals(ClientHelloCiphers.Outcome.MATCH,
                ClientHelloCiphers.match(aes, ClientHelloCiphers.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256));
        aes.rewind();
        assertEquals(ClientHelloCiphers.Outcome.MISS,
                ClientHelloCiphers.match(aes, ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256));
    }

    @Test
    void shortBufferIsIncomplete() {
        ByteBuffer tiny = ByteBuffer.wrap(new byte[] { 22, 3, 3 });
        assertEquals(ClientHelloCiphers.Outcome.INCOMPLETE,
                ClientHelloCiphers.match(tiny, ClientHelloCiphers.TLS_CHACHA20_POLY1305_SHA256));
    }

    private static ByteBuffer hello(int cipherId) {
        byte[] random = new byte[32];
        int body = 2 + 32 + 1 + 2 + 2 + 1 + 1;
        int recLen = 4 + body;
        ByteBuffer buf = ByteBuffer.allocate(5 + recLen);
        buf.put((byte) 22);
        buf.put((byte) 3);
        buf.put((byte) 3);
        buf.put((byte) (recLen >> 8));
        buf.put((byte) recLen);
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.put((byte) (body >> 8));
        buf.put((byte) body);
        buf.put((byte) 3);
        buf.put((byte) 3);
        buf.put(random);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 2);
        buf.put((byte) (cipherId >> 8));
        buf.put((byte) cipherId);
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.flip();
        return buf;
    }
}
