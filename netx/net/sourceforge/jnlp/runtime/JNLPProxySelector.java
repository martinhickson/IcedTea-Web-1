// Copyright (C) 2010 Red Hat, Inc.
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.

package net.sourceforge.jnlp.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.Proxy.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.util.UrlUtils;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * A ProxySelector specific to JNLPs. This proxy uses the deployment
 * configuration to determine what to do.
 *
 * @see java.net.ProxySelector
 */
public abstract class JNLPProxySelector extends ProxySelector {

    public static final int PROXY_TYPE_UNKNOWN = -1;
    public static final int PROXY_TYPE_NONE = 0;
    public static final int PROXY_TYPE_MANUAL = 1;
    public static final int PROXY_TYPE_AUTO = 2;
    public static final int PROXY_TYPE_BROWSER = 3;
    /** Windows Internet Settings / OS proxy (config value 4). */
    public static final int PROXY_TYPE_SYSTEM = 4;

    /** The default port to use as a fallback. Currently squid's default port */
    public static final int FALLBACK_PROXY_PORT = 3128;

    private PacEvaluator pacEvaluator = null;

    /** The proxy type. See PROXY_TYPE_* constants */
    private int proxyType = PROXY_TYPE_UNKNOWN;

    /** the URL to the PAC file */
    private URL autoConfigUrl = null;

    /** a list of URLs that should be bypassed for proxy purposes */
    private List<String> bypassList = null;

    /** whether localhost should be bypassed for proxy purposes */
    private boolean bypassLocal = false;

    /**
     * whether the http proxy should be used for https and ftp protocols as well
     */
    private boolean sameProxy = false;

    private String proxyHttpHost;
    private int proxyHttpPort;
    private String proxyHttpsHost;
    private int proxyHttpsPort;
    private String proxyFtpHost;
    private int proxyFtpPort;
    private String proxySocks4Host;
    private int proxySocks4Port;

    // FIXME what is this? where should it be used?
    private String overrideHosts = null;

    /** Cache of proxy selections per host to avoid redundant lookups */
    private final ConcurrentHashMap<String, List<Proxy>> proxyCache = new ConcurrentHashMap<>();

    public JNLPProxySelector(DeploymentConfiguration config) {
        parseConfiguration(config);
    }

    /**
     * Initialize this ProxySelector by reading the configuration
     */
    private void parseConfiguration(DeploymentConfiguration config) {
        proxyType = Integer.valueOf(config.getProperty(DeploymentConfiguration.KEY_PROXY_TYPE));

        String autoConfigString = config.getProperty(DeploymentConfiguration.KEY_PROXY_AUTO_CONFIG_URL);
        if (autoConfigString != null) {
            try {
                autoConfigUrl = new URL(autoConfigString);
            } catch (MalformedURLException e) {
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            }
        }

        if (autoConfigUrl != null) {
            pacEvaluator = PacEvaluatorFactory.getPacEvaluator(autoConfigUrl);
        }

        bypassList = new ArrayList<>();
        String proxyBypass = config.getProperty(DeploymentConfiguration.KEY_PROXY_BYPASS_LIST);
        if (proxyBypass != null) {
            StringTokenizer tokenizer = new StringTokenizer(proxyBypass, ",");
            while (tokenizer.hasMoreTokens()) {
                String host = tokenizer.nextToken();
                if (host != null && host.trim().length() != 0) {
                    bypassList.add(host);
                }
            }
        }

        bypassLocal = Boolean.valueOf(config
                .getProperty(DeploymentConfiguration.KEY_PROXY_BYPASS_LOCAL));

        sameProxy = Boolean.valueOf(config.getProperty(DeploymentConfiguration.KEY_PROXY_SAME));

        proxyHttpHost = getHost(config, DeploymentConfiguration.KEY_PROXY_HTTP_HOST);
        proxyHttpPort = getPort(config, DeploymentConfiguration.KEY_PROXY_HTTP_PORT);

        proxyHttpsHost = getHost(config, DeploymentConfiguration.KEY_PROXY_HTTPS_HOST);
        proxyHttpsPort = getPort(config, DeploymentConfiguration.KEY_PROXY_HTTPS_PORT);

        proxyFtpHost = getHost(config, DeploymentConfiguration.KEY_PROXY_FTP_HOST);
        proxyFtpPort = getPort(config, DeploymentConfiguration.KEY_PROXY_FTP_PORT);

        proxySocks4Host = getHost(config, DeploymentConfiguration.KEY_PROXY_SOCKS4_HOST);
        proxySocks4Port = getPort(config, DeploymentConfiguration.KEY_PROXY_SOCKS4_PORT);

        overrideHosts = config.getProperty(DeploymentConfiguration.KEY_PROXY_OVERRIDE_HOSTS);

        // After deployment.properties: overlay WinINET / env for system proxy.
        if (proxyType == PROXY_TYPE_SYSTEM) {
            applySystemProxySettings();
        }
    }

    /**
     * Uses the given key to get a host from the configuraion
     */
    private String getHost(DeploymentConfiguration config, String key) {
        String proxyHost = config.getProperty(key);
        if (proxyHost != null) {
            proxyHost = proxyHost.trim();
        }
        return proxyHost;
    }

    /**
     * Uses the given key to get a port from the configuration
     */
    private int getPort(DeploymentConfiguration config, String key) {
        int proxyPort = FALLBACK_PROXY_PORT;
        String port;
        port = config.getProperty(key);
        if (port != null && port.trim().length() != 0) {
            try {
                proxyPort = Integer.valueOf(port);
            } catch (NumberFormatException e) {
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            }
        }
        return proxyPort;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        OutputController.getLogger().log(OutputController.Level.ERROR_ALL, ioe);
    }

    /**
     * {@inheritDoc}
     * @return list of proxies on URI
     */
    @Override
    public List<Proxy> select(URI uri) {
        if (proxyType == PROXY_TYPE_NONE) {
            return Collections.singletonList(Proxy.NO_PROXY);
        }


        String cacheKey = uri.getScheme() + "://" + uri.getHost();
        
        return proxyCache.computeIfAbsent(cacheKey, k -> {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, 
                "Selecting proxy for: " + uri);
            if (inBypassList(uri)) {
                List<Proxy> proxies = Collections.singletonList(Proxy.NO_PROXY);
                OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, 
                    "Selected proxies: " + Arrays.toString(proxies.toArray()));
                return proxies;
            }
            List<Proxy> proxies = new ArrayList<>();
            switch (proxyType) {
                case PROXY_TYPE_MANUAL:
                    proxies.addAll(getFromConfiguration(uri));
                    break;
                case PROXY_TYPE_AUTO:
                    proxies.addAll(getFromPAC(uri));
                    break;
                case PROXY_TYPE_BROWSER:
                    proxies.addAll(getFromBrowser(uri));
                    break;
                case PROXY_TYPE_SYSTEM:
                    proxies.addAll(getFromSystem(uri));
                    break;
                case PROXY_TYPE_UNKNOWN:
                    // fall through
                default:
                    proxies.add(Proxy.NO_PROXY);
                    break;
            }
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, 
                "Selected proxies: " + Arrays.toString(proxies.toArray()));

            return proxies.isEmpty() ? Collections.singletonList(Proxy.NO_PROXY) : proxies;
        });
    }

    /**
     * Returns true if the uri should be bypassed for proxy purposes
     */
    private boolean inBypassList(URI uri) {
        try {
            String scheme = uri.getScheme();
            /* scheme can be http/https/ftp/socket */
            switch (scheme) {
                case "http":
                case "https":
                case "ftp":
                    URL url = uri.toURL();
                    if (bypassLocal && isLocalHost(url.getHost())) {
                        return true;
                    }   if (bypassList.contains(url.getHost())) {
                    return true;
                }   break;
                case "socket":
                    String host = uri.getHost();
                    if (bypassLocal && isLocalHost(host)) {
                        return true;
                    }   if (bypassList.contains(host)) {
                    return true;
                }   break;
            }
        } catch (MalformedURLException e) {
            return false;
        }

        return false;
    }

    /**
     * @return true if the host is the hostname or the IP address of the
     * localhost
     * @param  host host to verify
     */
    private boolean isLocalHost(String host) {
        return UrlUtils.isLocalHostName(host);
    }

    /**
     * Returns a list of proxies by using the information in the deployment
     * configuration
     *
     * @param uri uri to read
     * @return a List of Proxy objects
     */
    private List<Proxy> getFromConfiguration(URI uri) {
        return getFromArguments(uri, sameProxy, false,
                proxyHttpsHost, proxyHttpsPort,
                proxyHttpHost, proxyHttpPort,
                proxyFtpHost, proxyFtpPort,
                proxySocks4Host, proxySocks4Port);
    }

    /**
     * Returns a list of proxies by using the arguments
     *
     * @param uri name and code says it all
     * @param sameProxy name and code says it all
     * @param sameProxyIncludesSocket name and code says it all
     * @param proxyHttpsHost name and code says it all
     * @param proxyHttpsPort name and code says it all
     * @param proxyHttpHost name and code says it all
     * @param proxyHttpPort name and code says it all
     * @param proxyFtpHost name and code says it all
     * @param proxyFtpPort name and code says it all
     * @param proxySocks4Host name and code says it all
     * @param proxySocks4Port name and code says it all
     * @return a List of Proxy objects
     */
    protected static List<Proxy> getFromArguments(URI uri,
            boolean sameProxy, boolean sameProxyIncludesSocket,
            String proxyHttpsHost, int proxyHttpsPort,
            String proxyHttpHost, int proxyHttpPort,
            String proxyFtpHost, int proxyFtpPort,
            String proxySocks4Host, int proxySocks4Port) {

        List<Proxy> proxies = new ArrayList<>();

        String scheme = uri.getScheme();

        boolean socksProxyAdded = false;

        if (sameProxy) {
            if (proxyHttpHost != null) {
                SocketAddress sa = new InetSocketAddress(proxyHttpHost, proxyHttpPort);
                if ((scheme.equals("https") || scheme.equals("http") || scheme.equals("ftp"))) {
                    Proxy proxy = new Proxy(Type.HTTP, sa);
                    proxies.add(proxy);
                } else if (scheme.equals("socket") && sameProxyIncludesSocket) {
                    Proxy proxy = new Proxy(Type.SOCKS, sa);
                    proxies.add(proxy);
                    socksProxyAdded = true;
                }
            }
        } else if (scheme.equals("http") && proxyHttpHost != null) {
            SocketAddress sa = new InetSocketAddress(proxyHttpHost, proxyHttpPort);
            proxies.add(new Proxy(Type.HTTP, sa));
        } else if (scheme.equals("https") && proxyHttpsHost != null) {
            SocketAddress sa = new InetSocketAddress(proxyHttpsHost, proxyHttpsPort);
            proxies.add(new Proxy(Type.HTTP, sa));
        } else if (scheme.equals("ftp") && proxyFtpHost != null) {
            SocketAddress sa = new InetSocketAddress(proxyFtpHost, proxyFtpPort);
            proxies.add(new Proxy(Type.HTTP, sa));
        }

        if (!socksProxyAdded && (proxySocks4Host != null)) {
            SocketAddress sa = new InetSocketAddress(proxySocks4Host, proxySocks4Port);
            proxies.add(new Proxy(Type.SOCKS, sa));
            socksProxyAdded = true;
        }

        if (proxies.isEmpty()) {
            proxies.add(Proxy.NO_PROXY);
        }

        return proxies;
    }

    /**
     * Returns a list of proxies by using the Proxy Auto Config (PAC) file. See
     * http://en.wikipedia.org/wiki/Proxy_auto-config#The_PAC_file for more
     * information.
     *
     * @param uri uri to PAC
     * @return a List of valid Proxy objects
     */
    protected List<Proxy> getFromPAC(URI uri) {
        if (autoConfigUrl == null || uri.getScheme().equals("socket")) {
            return Arrays.asList(new Proxy[] { Proxy.NO_PROXY });
        }

        List<Proxy> proxies = new ArrayList<>();

        try {
            String proxiesString = pacEvaluator.getProxies(uri.toURL());
            proxies.addAll(getProxiesFromPacResult(proxiesString));
        } catch (MalformedURLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            proxies.add(Proxy.NO_PROXY);
        }

        return proxies;
    }

    /**
     * Returns a list of proxies by querying the browser
     *
     * @param uri the uri to get proxies for
     * @return a list of proxies
     */
    protected abstract List<Proxy> getFromBrowser(URI uri);

    /**
     * Apply OS proxy settings for {@link #PROXY_TYPE_SYSTEM}.
     * On Windows, prefer {@code AutoConfigURL} (PAC), else WinINET manual {@code ProxyServer}.
     */
    private void applySystemProxySettings() {
        if (WindowsInternetSettings.isWindows()) {
            try {
                WindowsInternetSettings.Snapshot snap = WindowsInternetSettings.read();
                if (snap.autoConfigUrl != null) {
                    OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                            "System proxy: using AutoConfigURL PAC " + snap.autoConfigUrl);
                    try {
                        autoConfigUrl = new URL(snap.autoConfigUrl);
                        pacEvaluator = PacEvaluatorFactory.getPacEvaluator(autoConfigUrl);
                    } catch (MalformedURLException e) {
                        OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
                    }
                    applyProxyOverride(snap.proxyOverride);
                    return;
                }
                if (snap.proxyEnabled && snap.proxyServer != null) {
                    OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                            "System proxy: using ProxyServer " + snap.proxyServer);
                    applyWinInetProxyServer(snap.proxyServer);
                    applyProxyOverride(snap.proxyOverride);
                    return;
                }
                OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                        "System proxy: no AutoConfigURL / ProxyEnable on Windows; using DIRECT");
            } catch (Exception e) {
                OutputController.getLogger().log(OutputController.Level.ERROR_ALL,
                        "System proxy: failed to read Windows Internet Settings: " + e.getMessage());
                OutputController.getLogger().log(e);
            }
            return;
        }
        applyUnixEnvProxies();
    }

    private void applyWinInetProxyServer(String proxyServer) {
        Map<String, String> byScheme = WindowsInternetSettings.parseProxyServerMap(proxyServer);
        boolean singleEntry = !proxyServer.contains("=");
        sameProxy = singleEntry;

        WindowsInternetSettings.HostPort http = WindowsInternetSettings.splitHostPort(
                byScheme.get("http"), FALLBACK_PROXY_PORT);
        if (http != null) {
            proxyHttpHost = http.host;
            proxyHttpPort = http.port;
        }
        WindowsInternetSettings.HostPort https = WindowsInternetSettings.splitHostPort(
                byScheme.get("https"), FALLBACK_PROXY_PORT);
        if (https != null) {
            proxyHttpsHost = https.host;
            proxyHttpsPort = https.port;
        } else if (sameProxy && http != null) {
            proxyHttpsHost = http.host;
            proxyHttpsPort = http.port;
        }
        WindowsInternetSettings.HostPort ftp = WindowsInternetSettings.splitHostPort(
                byScheme.get("ftp"), FALLBACK_PROXY_PORT);
        if (ftp != null) {
            proxyFtpHost = ftp.host;
            proxyFtpPort = ftp.port;
        }
        WindowsInternetSettings.HostPort socks = WindowsInternetSettings.splitHostPort(
                byScheme.get("socks"), FALLBACK_PROXY_PORT);
        if (socks != null) {
            proxySocks4Host = socks.host;
            proxySocks4Port = socks.port;
        }
    }

    private void applyProxyOverride(String proxyOverride) {
        if (proxyOverride == null) {
            return;
        }
        if (bypassList == null) {
            bypassList = new ArrayList<>();
        }
        for (String token : proxyOverride.split(";")) {
            String host = token.trim();
            if (host.isEmpty()) {
                continue;
            }
            if ("<local>".equalsIgnoreCase(host)) {
                bypassLocal = true;
                continue;
            }
            if (!bypassList.contains(host)) {
                bypassList.add(host);
            }
        }
    }

    private void applyUnixEnvProxies() {
        String https = firstEnv("https_proxy", "HTTPS_PROXY");
        String http = firstEnv("http_proxy", "HTTP_PROXY");
        String chosen = https != null ? https : http;
        if (chosen == null) {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "System proxy: no http(s)_proxy env; using DIRECT");
            return;
        }
        try {
            URL u = chosen.contains("://") ? new URL(chosen) : new URL("http://" + chosen);
            proxyHttpHost = u.getHost();
            proxyHttpPort = u.getPort() > 0 ? u.getPort() : FALLBACK_PROXY_PORT;
            proxyHttpsHost = proxyHttpHost;
            proxyHttpsPort = proxyHttpPort;
            sameProxy = true;
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "System proxy: using env proxy " + proxyHttpHost + ":" + proxyHttpPort);
        } catch (MalformedURLException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }
        String noProxy = firstEnv("no_proxy", "NO_PROXY");
        if (noProxy != null) {
            applyProxyOverride(noProxy.replace(',', ';'));
        }
    }

    private static String firstEnv(String a, String b) {
        String v = System.getenv(a);
        if (v != null && !v.trim().isEmpty()) {
            return v.trim();
        }
        v = System.getenv(b);
        if (v != null && !v.trim().isEmpty()) {
            return v.trim();
        }
        return null;
    }

    /**
     * System proxy selection: PAC from AutoConfigURL, else manual hosts filled from OS/env.
     */
    private List<Proxy> getFromSystem(URI uri) {
        if (autoConfigUrl != null && pacEvaluator != null) {
            return getFromPAC(uri);
        }
        if (proxyHttpHost != null || proxyHttpsHost != null || proxyFtpHost != null || proxySocks4Host != null) {
            return getFromConfiguration(uri);
        }
        return Collections.singletonList(Proxy.NO_PROXY);
    }

    /**
     * Converts a proxy string from a browser into a List of Proxy objects
     * suitable for java.
     * @param pacString a string indicating proxies. For example
     * "PROXY foo.bar:3128; DIRECT"
     * @return a list of Proxy objects representing the parsed string. In
     * case of malformed input, an empty list may be returned
     */
    public static List<Proxy> getProxiesFromPacResult(String pacString) {
        List<Proxy> proxies = new ArrayList<>();

        String[] tokens = pacString.split(";");
        for (String token: tokens) {
            if (token.startsWith("PROXY")) {
                String hostPortPair = token.substring("PROXY".length()).trim();
                if (!hostPortPair.contains(":")) {
                    continue;
                }
                String host = hostPortPair.split(":")[0];
                int port;
                try {
                    port = Integer.valueOf(hostPortPair.split(":")[1]);
                } catch (NumberFormatException nfe) {
                    continue;
                }
                SocketAddress sa = new InetSocketAddress(host, port);
                proxies.add(new Proxy(Type.HTTP, sa));
            } else if (token.startsWith("SOCKS")) {
                String hostPortPair = token.substring("SOCKS".length()).trim();
                if (!hostPortPair.contains(":")) {
                    continue;
                }
                String host = hostPortPair.split(":")[0];
                int port;
                try {
                    port = Integer.valueOf(hostPortPair.split(":")[1]);
                } catch (NumberFormatException nfe) {
                    continue;
                }
                SocketAddress sa = new InetSocketAddress(host, port);
                proxies.add(new Proxy(Type.SOCKS, sa));
            } else if (token.startsWith("DIRECT")) {
                proxies.add(Proxy.NO_PROXY);
            } else {
                 OutputController.getLogger().log("Unrecognized proxy token: " + token);
            }
        }

        return proxies;
    }

}
