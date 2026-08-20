package net.sourceforge.jnlp.util;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpClassificationTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "10.0.0.0, RFC1918",
            "10.1.2.3, RFC1918",
            "10.255.255.255, RFC1918",
            "172.16.0.0, RFC1918",
            "172.31.255.255, RFC1918",
            "172.20.10.1, RFC1918",
            "172.15.255.255, GLOBAL",
            "172.32.0.1, GLOBAL",
            "192.168.0.0, RFC1918",
            "192.168.1.1, RFC1918",
            "192.168.255.255, RFC1918",
            "192.167.255.255, GLOBAL",
            "192.169.0.1, GLOBAL",
            "127.0.0.1, LOOPBACK",
            "127.1.2.3, LOOPBACK",
            "127.255.255.255, LOOPBACK",
            "localhost, LOOPBACK",
            "LOCALHOST, LOOPBACK",
            "localhost., LOOPBACK",
            "::1, LOOPBACK",
            "0:0:0:0:0:0:0:1, LOOPBACK",
            "[::1], LOOPBACK",
            "169.254.0.1, LINK_LOCAL",
            "169.254.1.1, LINK_LOCAL",
            "169.254.255.1, LINK_LOCAL",
            "169.253.1.1, GLOBAL",
            "169.255.1.1, GLOBAL",
            "100.64.0.0, CGNAT",
            "100.64.0.1, CGNAT",
            "100.127.255.255, CGNAT",
            "100.63.255.255, GLOBAL",
            "100.128.0.1, GLOBAL",
            "fe80::1, LINK_LOCAL",
            "fe80::1%eth0, LINK_LOCAL",
            "[fe80::1], LINK_LOCAL",
            "febf::1, LINK_LOCAL",
            "fe8::1, GLOBAL",
            "fec0::1, GLOBAL",
            "fc00::1, ULA",
            "fd00::1, ULA",
            "fd12:3456::789a, ULA",
            "fe00::1, GLOBAL",
            "::ffff:10.0.0.1, RFC1918",
            "::ffff:127.0.0.1, LOOPBACK",
            "::ffff:8.8.8.8, GLOBAL",
            "192.0.2.10, GLOBAL",
            "198.51.100.1, GLOBAL",
            "203.0.113.1, GLOBAL",
            "8.8.8.8, GLOBAL",
            "1.1.1.1, GLOBAL",
            "example.test, GLOBAL",
    })
    void classifyLiteral(String host, IpClassification.Kind expected) {
        assertEquals(expected, IpClassification.classify(host));
        assertEquals(expected != IpClassification.Kind.GLOBAL, IpClassification.skipReverseDns(host));
    }

    @Test
    void classifyNullIsGlobal() {
        assertEquals(IpClassification.Kind.GLOBAL, IpClassification.classify((String) null));
        assertFalse(IpClassification.skipReverseDns((String) null));
        assertEquals(IpClassification.Kind.GLOBAL, IpClassification.classify((InetAddress) null));
        assertFalse(IpClassification.skipReverseDns((InetAddress) null));
    }

    @Test
    void classifyInetAddressUsesHostAddressOnly() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertEquals(IpClassification.Kind.LOOPBACK, IpClassification.classify(loopback));
        assertTrue(IpClassification.skipReverseDns(loopback));

        InetAddress rfc1918 = InetAddress.getByName("10.1.2.3");
        assertEquals(IpClassification.Kind.RFC1918, IpClassification.classify(rfc1918));
        assertTrue(IpClassification.skipReverseDns(rfc1918));

        InetAddress publicIp = InetAddress.getByName("8.8.8.8");
        assertEquals(IpClassification.Kind.GLOBAL, IpClassification.classify(publicIp));
        assertFalse(IpClassification.skipReverseDns(publicIp));
    }
}
