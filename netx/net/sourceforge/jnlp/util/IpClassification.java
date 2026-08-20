/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 */
package net.sourceforge.jnlp.util;

import java.net.InetAddress;
import java.util.Locale;

/**
 * Lexical classification of IP literals. Does not call
 * {@link InetAddress#getHostName()} / reverse-DNS.
 * <p>
 * Ranges used to skip reverse-DNS:
 * RFC 1918 ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}),
 * loopback ({@code 127/8}, {@code ::1}, {@code localhost}),
 * link-local ({@code 169.254/16}, {@code fe80::/10}),
 * CGNAT ({@code 100.64/10}),
 * IPv6 ULA ({@code fc00::/7}).
 * Not included: TEST-NET, multicast, {@code 240/4}, hostnames.
 */
public final class IpClassification {

    public enum Kind {
        LOOPBACK,
        RFC1918,
        LINK_LOCAL,
        CGNAT,
        ULA,
        GLOBAL
    }

    private IpClassification() {
    }

    public static boolean skipReverseDns(InetAddress addr) {
        return addr != null && skipReverseDns(addr.getHostAddress());
    }

    public static boolean skipReverseDns(String host) {
        return classify(host) != Kind.GLOBAL;
    }

    public static Kind classify(InetAddress addr) {
        return addr == null ? Kind.GLOBAL : classify(addr.getHostAddress());
    }

    public static Kind classify(String host) {
        if (host == null || host.isEmpty()) {
            return Kind.GLOBAL;
        }
        String h = stripLiteral(host);
        if (h.isEmpty()) {
            return Kind.GLOBAL;
        }
        if (isLocalhostName(h)) {
            return Kind.LOOPBACK;
        }
        if (isIpv6Loopback(h)) {
            return Kind.LOOPBACK;
        }
        String mappedV4 = ipv4MappedSuffix(h);
        if (mappedV4 != null) {
            return classifyIpv4(mappedV4);
        }
        Kind v4 = classifyIpv4(h);
        if (v4 != Kind.GLOBAL || looksLikeIpv4(h)) {
            return v4;
        }
        if (h.indexOf(':') < 0) {
            return Kind.GLOBAL;
        }
        return classifyIpv6(h);
    }

    private static String stripLiteral(String host) {
        String h = host.trim();
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        int zone = h.indexOf('%');
        if (zone > 0) {
            h = h.substring(0, zone);
        }
        return h;
    }

    private static boolean isLocalhostName(String h) {
        return h.equalsIgnoreCase("localhost") || h.equalsIgnoreCase("localhost.");
    }

    private static boolean isIpv6Loopback(String h) {
        String lower = h.toLowerCase(Locale.ROOT);
        return lower.equals("::1") || lower.equals("0:0:0:0:0:0:0:1");
    }

    private static String ipv4MappedSuffix(String h) {
        String lower = h.toLowerCase(Locale.ROOT);
        int mapped = lower.lastIndexOf(":ffff:");
        if (mapped < 0) {
            return null;
        }
        return h.substring(mapped + 6);
    }

    private static boolean looksLikeIpv4(String h) {
        return h.indexOf('.') >= 0 && h.indexOf(':') < 0;
    }

    private static Kind classifyIpv4(String h) {
        int[] p = parseIpv4Octets(h);
        if (p == null) {
            return Kind.GLOBAL;
        }
        if (p[0] == 127) {
            return Kind.LOOPBACK;
        }
        if (p[0] == 10) {
            return Kind.RFC1918;
        }
        if (p[0] == 192 && p[1] == 168) {
            return Kind.RFC1918;
        }
        if (p[0] == 172 && p[1] >= 16 && p[1] <= 31) {
            return Kind.RFC1918;
        }
        if (p[0] == 169 && p[1] == 254) {
            return Kind.LINK_LOCAL;
        }
        if (p[0] == 100 && p[1] >= 64 && p[1] <= 127) {
            return Kind.CGNAT;
        }
        return Kind.GLOBAL;
    }

    /**
     * First hextet only. {@code fe8::1} is {@code 0x0fe8}, not {@code fe80::/10}.
     */
    private static Kind classifyIpv6(String h) {
        String lower = h.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        String first = colon < 0 ? lower : lower.substring(0, colon);
        if (first.isEmpty()) {
            return Kind.GLOBAL;
        }
        int hextet;
        try {
            hextet = Integer.parseInt(first, 16);
        } catch (NumberFormatException e) {
            return Kind.GLOBAL;
        }
        if (hextet >= 0xfe80 && hextet <= 0xfebf) {
            return Kind.LINK_LOCAL;
        }
        if (hextet >= 0xfc00 && hextet <= 0xfdff) {
            return Kind.ULA;
        }
        return Kind.GLOBAL;
    }

    private static int[] parseIpv4Octets(String h) {
        String[] parts = h.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        int[] p = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty()) {
                    return null;
                }
                p[i] = Integer.parseInt(parts[i]);
                if (p[i] < 0 || p[i] > 255) {
                    return null;
                }
            }
            return p;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
