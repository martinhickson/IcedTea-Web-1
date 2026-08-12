package net.sourceforge.jnlp.security;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.sourceforge.jnlp.cache.download.ConnectionTiming;
import net.sourceforge.jnlp.util.HttpUtils;

/**
 * The classic {@link java.net.HttpURLConnection} implementation of
 * {@link ItwHttpClient} — non-default, kept as an escape hatch. Uses
 * {@link ConnectionFactory} (timeouts + ITW HTTPS socket factory).
 */
public final class OracleHttpClient implements ItwHttpClient {

    @Override
    public HttpResponse open(URL url, String method, Map<String, String> requestHeaders, ConnectionTiming timing)
            throws IOException {
        if (timing != null) {
            timing.connectStartMillis = System.currentTimeMillis();
        }
        URLConnection connection = ConnectionFactory.getConnectionFactory().openConnection(url);
        if (requestHeaders != null) {
            for (Map.Entry<String, String> h : requestHeaders.entrySet()) {
                connection.addRequestProperty(h.getKey(), h.getValue());
            }
        }
        if (connection instanceof HttpURLConnection && method != null) {
            ((HttpURLConnection) connection).setRequestMethod(method);
        }
        // getResponseCode() executes the request; capture the status eagerly so it
        // does not throw when read later from the response object.
        int statusCode = HttpURLConnection.HTTP_OK;
        if (connection instanceof HttpURLConnection) {
            statusCode = ((HttpURLConnection) connection).getResponseCode();
        }
        if (timing != null) {
            timing.connectEndMillis = System.currentTimeMillis();
        }
        return new OracleResponse(connection, statusCode);
    }

    private static final class OracleResponse implements HttpResponse {
        private final URLConnection connection;
        private final int statusCode;
        private boolean closed;

        OracleResponse(URLConnection connection, int statusCode) {
            this.connection = connection;
            this.statusCode = statusCode;
        }

        @Override
        public int getStatusCode() {
            return statusCode;
        }

        @Override
        public long getContentLength() {
            return connection.getContentLengthLong();
        }

        @Override
        public long getLastModified() {
            return connection.getLastModified();
        }

        @Override
        public String getContentEncoding() {
            return connection.getContentEncoding();
        }

        @Override
        public URL getFinalUrl() {
            return connection.getURL();
        }

        @Override
        public InputStream getBody() throws IOException {
            return connection.getInputStream();
        }

        @Override
        public String getHeader(String name) {
            return connection.getHeaderField(name);
        }

        @Override
        public Map<String, List<String>> getHeaders() {
            return new LinkedHashMap<>(connection.getHeaderFields());
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (connection instanceof HttpURLConnection) {
                HttpUtils.consumeAndCloseConnectionSilently((HttpURLConnection) connection, connection.getURL());
            } else {
                try {
                    connection.getInputStream().close();
                } catch (IOException e) {
                    // best-effort
                }
            }
        }
    }
}
