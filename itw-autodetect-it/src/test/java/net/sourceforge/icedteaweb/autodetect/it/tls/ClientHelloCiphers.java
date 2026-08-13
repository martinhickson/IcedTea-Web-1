package net.sourceforge.icedteaweb.autodetect.it.tls;

import java.nio.ByteBuffer;

/**
 * Reads cipher-suite IDs out of a TLS ClientHello record so the test server can
 * send {@code close_notify} when the probe offer has no overlap (Apache-like).
 */
final class ClientHelloCiphers {

    static final int TLS_CHACHA20_POLY1305_SHA256 = 0x1303;
    static final int TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256 = 0xCCA9;
    static final int TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 = 0xC02F;

    enum Outcome {
        INCOMPLETE,
        NOT_CLIENT_HELLO,
        MATCH,
        MISS
    }

    private ClientHelloCiphers() {
    }

    static Outcome match(ByteBuffer src, int... acceptedIds) {
        if (src.remaining() < 5) {
            return Outcome.INCOMPLETE;
        }
        int pos = src.position();
        int contentType = src.get(pos) & 0xff;
        if (contentType != 22) {
            return Outcome.NOT_CLIENT_HELLO;
        }
        int recLen = ((src.get(pos + 3) & 0xff) << 8) | (src.get(pos + 4) & 0xff);
        if (src.remaining() < 5 + recLen) {
            return Outcome.INCOMPLETE;
        }
        int hs = pos + 5;
        if (recLen < 4 || (src.get(hs) & 0xff) != 1) {
            return Outcome.NOT_CLIENT_HELLO;
        }
        int body = hs + 4;
        int limit = pos + 5 + recLen;
        if (body + 2 + 32 + 1 > limit) {
            return Outcome.INCOMPLETE;
        }
        body += 2; // legacy version
        body += 32; // random
        int sidLen = src.get(body) & 0xff;
        body += 1 + sidLen;
        if (body + 2 > limit) {
            return Outcome.INCOMPLETE;
        }
        int csLen = ((src.get(body) & 0xff) << 8) | (src.get(body + 1) & 0xff);
        body += 2;
        if (csLen < 2 || (csLen & 1) != 0 || body + csLen > limit) {
            return Outcome.NOT_CLIENT_HELLO;
        }
        for (int i = 0; i < csLen; i += 2) {
            int id = ((src.get(body + i) & 0xff) << 8) | (src.get(body + i + 1) & 0xff);
            for (int accepted : acceptedIds) {
                if (id == accepted) {
                    return Outcome.MATCH;
                }
            }
        }
        return Outcome.MISS;
    }

    static int recordLength(ByteBuffer src) {
        int pos = src.position();
        return 5 + (((src.get(pos + 3) & 0xff) << 8) | (src.get(pos + 4) & 0xff));
    }
}
