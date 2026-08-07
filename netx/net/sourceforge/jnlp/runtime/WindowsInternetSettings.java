// Copyright (C) 2026 IcedTea-Web contributors
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.

package net.sourceforge.jnlp.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reads WinINET proxy settings from the Windows registry (same source OpenWebStart
 * uses for {@code deployment.proxy.type=4} / system proxy).
 *
 * <p>Key: {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings}
 * — {@code AutoConfigURL}, {@code ProxyEnable}, {@code ProxyServer}, {@code ProxyOverride}.
 */
public final class WindowsInternetSettings {

    public static final String REGISTRY_KEY =
            "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";

    public static final String AUTO_CONFIG_URL = "AutoConfigURL";
    public static final String PROXY_ENABLE = "ProxyEnable";
    public static final String PROXY_SERVER = "ProxyServer";
    public static final String PROXY_OVERRIDE = "ProxyOverride";

    private WindowsInternetSettings() {
    }

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("windows");
    }

    /**
     * Snapshot of WinINET proxy-related values. Missing values are null / false.
     */
    public static final class Snapshot {
        public final String autoConfigUrl;
        public final boolean proxyEnabled;
        public final String proxyServer;
        public final String proxyOverride;

        public Snapshot(String autoConfigUrl, boolean proxyEnabled, String proxyServer, String proxyOverride) {
            this.autoConfigUrl = autoConfigUrl;
            this.proxyEnabled = proxyEnabled;
            this.proxyServer = proxyServer;
            this.proxyOverride = proxyOverride;
        }
    }

    public static Snapshot read() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("reg", "query", REGISTRY_KEY);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("reg query timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException("reg query failed with exit " + process.exitValue());
        }
        return fromRegistryLines(lines);
    }

    /**
     * Parse {@code reg query} stdout into a snapshot. Package-visible for tests.
     */
    static Snapshot fromRegistryLines(List<String> lines) {
        Map<String, String> values = parseRegistryValues(lines);
        String autoConfigUrl = emptyToNull(values.get(AUTO_CONFIG_URL));
        boolean proxyEnabled = parseDwordBoolean(values.get(PROXY_ENABLE));
        String proxyServer = emptyToNull(values.get(PROXY_SERVER));
        String proxyOverride = emptyToNull(values.get(PROXY_OVERRIDE));
        return new Snapshot(autoConfigUrl, proxyEnabled, proxyServer, proxyOverride);
    }

    static Map<String, String> parseRegistryValues(List<String> lines) {
        Map<String, String> map = new HashMap<>();
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty() || line.contains(REGISTRY_KEY)) {
                continue;
            }
            int typeIdx = line.indexOf("REG_");
            if (typeIdx < 1) {
                continue;
            }
            String name = line.substring(0, typeIdx).trim();
            String rest = line.substring(typeIdx).trim();
            String[] typeAndValue = rest.split("\\s+", 2);
            String value = typeAndValue.length > 1 ? typeAndValue[1].trim() : "";
            map.put(name, value);
        }
        return map;
    }

    /**
     * Parse WinINET {@code ProxyServer} into protocol → "host:port".
     * Supports {@code host:port} (all schemes) and {@code http=h:p;https=h:p}.
     */
    static Map<String, String> parseProxyServerMap(String proxyServer) {
        if (proxyServer == null || proxyServer.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        String trimmed = proxyServer.trim();
        Map<String, String> map = new HashMap<>();
        if (!trimmed.contains("=")) {
            map.put("http", trimmed);
            map.put("https", trimmed);
            map.put("ftp", trimmed);
            return map;
        }
        for (String part : trimmed.split(";")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                continue;
            }
            String scheme = entry.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String hostPort = entry.substring(eq + 1).trim();
            map.put(scheme, hostPort);
        }
        return map;
    }

    static HostPort splitHostPort(String hostPort, int defaultPort) {
        if (hostPort == null || hostPort.trim().isEmpty()) {
            return null;
        }
        String value = hostPort.trim();
        // strip accidental scheme
        int schemeSep = value.indexOf("://");
        if (schemeSep >= 0) {
            value = value.substring(schemeSep + 3);
        }
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return new HostPort(value, defaultPort);
        }
        String host = value.substring(0, colon);
        try {
            int port = Integer.parseInt(value.substring(colon + 1));
            return new HostPort(host, port);
        } catch (NumberFormatException e) {
            return new HostPort(value, defaultPort);
        }
    }

    static final class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static boolean parseDwordBoolean(String raw) {
        if (raw == null) {
            return false;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("0x")) {
            try {
                return Integer.parseInt(v.substring(2), 16) != 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        try {
            return Integer.parseInt(v) != 0;
        } catch (NumberFormatException e) {
            return "true".equals(v) || "yes".equals(v);
        }
    }

    private static String emptyToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
