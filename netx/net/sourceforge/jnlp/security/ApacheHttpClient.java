package net.sourceforge.jnlp.security;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLSocket;
import net.sourceforge.jnlp.cache.AdaptiveBackgroundThreads;
import net.sourceforge.jnlp.cache.download.ConnectionTiming;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.OutputController;
import org.apache.hc.client5.http.RouteInfo;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.SystemDefaultRoutePlanner;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.pool.PoolStats;
import org.apache.hc.core5.util.TimeValue;

/**
 * Apache HttpClient 5 (httpclient5) implementation of {@link ItwHttpClient} —
 * the default. Provides explicit connection pooling (per-route slots match the
 * adaptive download-thread ceiling, default 12), client-level connect/read timeouts, and
 * uses {@link ItwSslSocketFactory} (ITW trust chain + cipher probe/fallback).
 * Passing only {@code SSLContext} skips cipher stamping — jar downloads would
 * then use the JDK default order until a later {@code HttpURLConnection} path.
 * Routes use {@link java.net.ProxySelector#getDefault()} at request time so
 * {@code deployment.proxy.*} (via {@code JNLPProxySelector}) is honoured even
 * when this client is constructed before {@code JNLPRuntime.initialize}.
 */
public final class ApacheHttpClient implements ItwHttpClient {

    private final CloseableHttpClient client;
    private final PoolingHttpClientConnectionManager pool;
    private final int perRoute;
    private final AtomicBoolean loggedCapacity = new AtomicBoolean();

    public ApacheHttpClient() {
        // Wrap the ITW factory so probe/full cipher selection applies to every
        // layered TLS socket. prepareSocket re-stamps after HC5 excludeWeak.
        SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
                ItwSslSocketFactory.shared(), null) {
            @Override
            protected void prepareSocket(SSLSocket socket, HttpContext context) throws IOException {
                HttpSocketBuffers.apply(socket);
                ItwSslSocketFactory.applyParameters(socket);
            }
        };

        int connectTimeout = timeout(DeploymentConfiguration.KEY_HTTPCONNECTION_CONNECT_TIMEOUT, 30000);
        int readTimeout = timeout(DeploymentConfiguration.KEY_HTTPCONNECTION_READ_TIMEOUT, 30000);

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .setSocketTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .build();

        int perRoute = downloadSlots();
        int maxTotal = Math.max(perRoute * 2, perRoute);
        System.setProperty("http.maxConnections", String.valueOf(perRoute));
        SocketConfig.Builder socket = SocketConfig.custom();
        int rcvBuf = HttpSocketBuffers.receiveBufferSize();
        if (rcvBuf > 0) {
            socket.setRcvBufSize(rcvBuf);
        }
        int sndBuf = HttpSocketBuffers.sendBufferSize();
        if (sndBuf > 0) {
            socket.setSndBufSize(sndBuf);
        }
        PoolingHttpClientConnectionManager pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(sslsf)
                .setMaxConnTotal(maxTotal)
                .setMaxConnPerRoute(perRoute)
                .setDefaultConnectionConfig(connectionConfig)
                .setDefaultSocketConfig(socket.build())
                .build();
        try {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                    "HTTP connection pool maxPerRoute=" + perRoute + " maxTotal=" + maxTotal
                            + " rcvBuf=" + rcvBuf + " sndBuf=" + sndBuf);
        } catch (Exception ignored) {
        }

        // Disable HC5 content-decoding. JNLP servers commonly return
        // Content-Encoding: pack200-gzip (and sometimes gzip) for jar.pack.gz /
        // negotiated payloads. HC5 only understands gzip/deflate and throws
        // "Unsupported Content-Encoding: pack200-gzip", which made ResourceDownloader
        // log "GET failed" and skip the only working artifact. ITW unpacks
        // pack200-gzip itself in ResourceDownloader.
        this.pool = pool;
        this.perRoute = perRoute;
        // null selector → ProxySelector.getDefault() on each request, not a
        // snapshot from construction (JNLPRuntime installs the selector later).
        this.client = HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(requestConfig)
                .setRoutePlanner(new SystemDefaultRoutePlanner(null))
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .disableContentCompression()
                .build();
    }

    private static int downloadSlots() {
        int n = 6;
        boolean adaptive = true;
        try {
            n = Integer.parseInt(JNLPRuntime.getConfiguration()
                    .getProperty(DeploymentConfiguration.KEY_BACKGROUND_THREADS_COUNT));
        } catch (Exception ignored) {
        }
        try {
            String a = JNLPRuntime.getConfiguration()
                    .getProperty(DeploymentConfiguration.KEY_BACKGROUND_THREADS_ADAPTIVE);
            if (a != null && !a.trim().isEmpty()) {
                adaptive = Boolean.parseBoolean(a.trim());
            }
        } catch (Exception ignored) {
        }
        return AdaptiveBackgroundThreads.connectionSlots(n, adaptive);
    }

    private static int timeout(String key, int fallback) {
        try {
            return Integer.parseInt(JNLPRuntime.getConfiguration().getProperty(key));
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public HttpResponse open(URL url, String method, Map<String, String> requestHeaders, ConnectionTiming timing)
            throws IOException {
        String scheme = url.getProtocol();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            // Apache HttpClient only understands http(s). Non-HTTP schemes
            // (file:, jar:, ftp:, ...) must fall back to java.net.URLConnection,
            // which the classic client handled natively (e.g. file: for local
            // JNLP/jar launches). Without this, "javaws /path/to/app.jnlp" fails.
            return new OracleHttpClient().open(url, method, requestHeaders, timing);
        }
        URI uri;
        try {
            uri = url.toURI();
        } catch (Exception e) {
            throw new IOException("Invalid URL " + url, e);
        }
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            throw new java.net.ProtocolException("Unsupported request method: " + method);
        }
        if (timing != null) {
            timing.connectStartMillis = System.currentTimeMillis();
        }
        ClassicHttpResponse response = null;
        IOException last = null;
        boolean reused = false;
        // Cipher-offer short-circuit only: a probe miss tries the next stage
        // on this same request. Not an IO/download retry budget.
        while (true) {
            int stageAtStart = ItwTls.effectiveOffer(url.getHost());
            try {
                PoolStats before = pool.getTotalStats();
                HttpClientContext context = HttpClientContext.create();
                response = client.execute(newRequest(uri, method, requestHeaders), context);
                reused = before.getAvailable() > 0;
                last = null;
                logProxyRoute(method, uri, context);
                logPoolAdmission(method);
                break;
            } catch (IOException e) {
                last = e;
                if (!ItwTls.continueOpenAfterHandshakeMiss(url.getHost(), e, stageAtStart)) {
                    throw e;
                }
            }
        }
        if (response == null) {
            throw last != null ? last : new IOException("TLS probe retries exhausted for " + url);
        }
        if (timing != null) {
            timing.connectEndMillis = System.currentTimeMillis();
            timing.reused = reused;
        }
        return new ApacheResponse(url, response);
    }

    private static void logProxyRoute(String method, URI uri, HttpClientContext context) {
        try {
            RouteInfo route = context.getHttpRoute();
            if (route != null && route.getProxyHost() != null) {
                OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                        method + " " + uri + " via proxy " + route.getProxyHost());
            }
        } catch (Exception ignored) {
        }
    }

    private void logPoolAdmission(String method) {
        try {
            PoolStats stats = pool.getTotalStats();
            if (stats.getLeased() >= perRoute && loggedCapacity.compareAndSet(false, true)) {
                OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                        "HTTP pool at capacity leased=" + stats.getLeased()
                                + "/" + perRoute + " available=" + stats.getAvailable()
                                + " pending=" + stats.getPending()
                                + " (" + method + ")");
            } else {
                OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                        "HTTP pool leased=" + stats.getLeased()
                                + "/" + perRoute + " available=" + stats.getAvailable()
                                + " pending=" + stats.getPending()
                                + " (" + method + ")");
            }
        } catch (Exception ignored) {
        }
    }

    private static HttpUriRequestBase newRequest(URI uri, String method, Map<String, String> requestHeaders) {
        HttpUriRequestBase request;
        if ("HEAD".equalsIgnoreCase(method)) {
            request = new org.apache.hc.client5.http.classic.methods.HttpHead(uri);
        } else {
            request = new org.apache.hc.client5.http.classic.methods.HttpGet(uri);
        }
        if (requestHeaders != null) {
            for (Map.Entry<String, String> h : requestHeaders.entrySet()) {
                request.addHeader(h.getKey(), h.getValue());
            }
        }
        if (request.getFirstHeader("Connection") == null) {
            request.addHeader("Connection", "keep-alive");
        }
        return request;
    }

    private static final class ApacheResponse implements HttpResponse {
        private final URL url;
        private final ClassicHttpResponse response;
        private boolean closed;

        ApacheResponse(URL url, ClassicHttpResponse response) {
            this.url = url;
            this.response = response;
        }

        @Override
        public int getStatusCode() {
            return response.getCode();
        }

        @Override
        public long getContentLength() {
            HttpEntity entity = response.getEntity();
            if (entity != null && entity.getContentLength() >= 0) {
                return entity.getContentLength();
            }
            // HEAD responses often have no entity; the length is still on the header.
            Header h = response.getFirstHeader("Content-Length");
            if (h != null && h.getValue() != null) {
                try {
                    return Long.parseLong(h.getValue().trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return entity != null ? entity.getContentLength() : -1;
        }

        @Override
        public long getLastModified() {
            String value = getHeader("Last-Modified");
            if (value == null) {
                return -1;
            }
            try {
                java.time.ZonedDateTime z = java.time.ZonedDateTime.parse(value.trim(),
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                return z.toInstant().toEpochMilli();
            } catch (Exception e) {
                return -1;
            }
        }

        @Override
        public String getContentEncoding() {
            Header h = response.getFirstHeader("Content-Encoding");
            if (h != null) {
                return h.getValue();
            }
            HttpEntity entity = response.getEntity();
            return entity != null ? entity.getContentEncoding() : null;
        }

        @Override
        public URL getFinalUrl() {
            return url;
        }

        @Override
        public InputStream getBody() throws IOException {
            HttpEntity entity = response.getEntity();
            return entity != null ? entity.getContent() : null;
        }

        @Override
        public String getHeader(String name) {
            Header h = response.getFirstHeader(name);
            return h != null ? h.getValue() : null;
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            java.util.Map<String, List<String>> map = new java.util.LinkedHashMap<>();
            for (Header h : response.getHeaders()) {
                map.computeIfAbsent(h.getName(), k -> new java.util.ArrayList<>()).add(h.getValue());
            }
            return map;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    EntityUtils.consumeQuietly(response.getEntity());
                    response.close();
                } catch (Exception e) {
                    // release best-effort
                }
            }
        }
    }
}
