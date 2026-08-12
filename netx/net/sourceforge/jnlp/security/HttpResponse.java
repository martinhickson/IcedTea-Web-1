package net.sourceforge.jnlp.security;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * A fully executed HTTP response: status, headers, and the body stream.
 * Callers must {@link #close()} to release the connection back to the pool.
 */
public interface HttpResponse extends Closeable {

    int getStatusCode();

    /** Content-Length, or -1 if absent. */
    long getContentLength();

    /** Last-Modified (epoch millis), or -1 if absent. */
    long getLastModified();

    /** Content-Encoding (e.g. gzip, pack200-gzip), or null. */
    String getContentEncoding();

    /** The final URL after redirects. */
    URL getFinalUrl();

    /** The response body (null for HEAD / no-body responses). */
    InputStream getBody() throws IOException;

    /** A single header value, or null. */
    String getHeader(String name);

    /** All response headers. */
    Map<String, List<String>> getHeaders();

    /** Release the connection back to the pool. Idempotent. */
    @Override
    void close();
}
