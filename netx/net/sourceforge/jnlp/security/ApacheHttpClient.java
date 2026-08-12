package net.sourceforge.jnlp.security;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.sourceforge.jnlp.cache.download.ConnectionTiming;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;

/**
 * Apache HttpClient 5 (httpclient5) implementation of {@link ItwHttpClient} —
 * the default. Provides explicit connection pooling (max 6 per route, matching
 * the parallel download thread count), client-level connect/read timeouts, and
 * uses ITW's SSL context (VariableX509TrustManager chain) for TLS.
 */
public final class ApacheHttpClient implements ItwHttpClient {

    private static final int MAX_PER_ROUTE = 6;

    private final CloseableHttpClient client;

    public ApacheHttpClient() {
        SSLConnectionSocketFactory sslsf;
        try {
            sslsf = SSLConnectionSocketFactoryBuilder.create()
                    .setSslContext(JNLPRuntime.getSslContext())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build Apache SSL connection socket factory", e);
        }

        int connectTimeout = timeout(DeploymentConfiguration.KEY_HTTPCONNECTION_CONNECT_TIMEOUT, 30000);
        int readTimeout = timeout(DeploymentConfiguration.KEY_HTTPCONNECTION_READ_TIMEOUT, 30000);

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .setSocketTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .build();

        PoolingHttpClientConnectionManager pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(sslsf)
                .setMaxConnTotal(MAX_PER_ROUTE)
                .setMaxConnPerRoute(MAX_PER_ROUTE)
                .setDefaultConnectionConfig(connectionConfig)
                .build();

        // Disable HC5 content-decoding. JNLP servers commonly return
        // Content-Encoding: pack200-gzip (and sometimes gzip) for jar.pack.gz /
        // negotiated payloads. HC5 only understands gzip/deflate and throws
        // "Unsupported Content-Encoding: pack200-gzip", which made ResourceDownloader
        // log "GET failed" and skip the only working Alta02 artifact. ITW unpacks
        // pack200-gzip itself in ResourceDownloader.
        this.client = HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(requestConfig)
                .disableContentCompression()
                .build();
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

        if (timing != null) {
            timing.connectStartMillis = System.currentTimeMillis();
        }
        ClassicHttpResponse response = client.execute(request);
        if (timing != null) {
            timing.connectEndMillis = System.currentTimeMillis();
        }
        return new ApacheResponse(url, response);
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
                    response.close();
                } catch (IOException e) {
                    // release best-effort
                }
            }
        }
    }
}
