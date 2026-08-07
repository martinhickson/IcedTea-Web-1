// Copyright (C) 2026 IcedTea-Web contributors
package net.sourceforge.jnlp.runtime;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class WindowsInternetSettingsTest {

    @Test
    public void parseRegistryLinesReadsAutoConfigAndProxyServer() {
        List<String> lines = Arrays.asList(
                "",
                "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
                "    ProxyEnable    REG_DWORD    0x0",
                "    AutoConfigURL    REG_SZ    http://bslproxy/proxy.pac",
                "    ProxyServer    REG_SZ    10.179.227.1:8080",
                "    ProxyOverride    REG_SZ    <local>;*.bravurasolutions.local"
        );

        WindowsInternetSettings.Snapshot snap = WindowsInternetSettings.fromRegistryLines(lines);
        Assert.assertEquals("http://bslproxy/proxy.pac", snap.autoConfigUrl);
        Assert.assertFalse(snap.proxyEnabled);
        Assert.assertEquals("10.179.227.1:8080", snap.proxyServer);
        Assert.assertTrue(snap.proxyOverride.contains("<local>"));
    }

    @Test
    public void parseProxyServerSingleHostAppliesToHttpAndHttps() {
        Map<String, String> map = WindowsInternetSettings.parseProxyServerMap("10.179.227.1:8080");
        Assert.assertEquals("10.179.227.1:8080", map.get("http"));
        Assert.assertEquals("10.179.227.1:8080", map.get("https"));
    }

    @Test
    public void parseProxyServerProtocolSpecific() {
        Map<String, String> map = WindowsInternetSettings.parseProxyServerMap(
                "http=10.1.1.1:8080;https=10.2.2.2:8080");
        Assert.assertEquals("10.1.1.1:8080", map.get("http"));
        Assert.assertEquals("10.2.2.2:8080", map.get("https"));
    }

    @Test
    public void splitHostPort() {
        WindowsInternetSettings.HostPort hp = WindowsInternetSettings.splitHostPort("10.179.227.1:8080", 3128);
        Assert.assertEquals("10.179.227.1", hp.host);
        Assert.assertEquals(8080, hp.port);
    }
}
