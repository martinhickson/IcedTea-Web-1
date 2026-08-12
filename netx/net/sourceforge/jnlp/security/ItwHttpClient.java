package net.sourceforge.jnlp.security;

import java.io.IOException;
import java.net.URL;
import java.util.Map;
import net.sourceforge.jnlp.cache.download.ConnectionTiming;

/**
 * Abstraction over the actual HTTP client implementation ("apache" or "oracle").
 * A frontend client — the name deliberately avoids "backend".
 */
public interface ItwHttpClient {

    /**
     * Executes {@code method} (GET/HEAD) against {@code url} with the given
     * request headers, capturing connection timing when supplied. The returned
     * {@link HttpResponse} carries the status, headers and body stream; callers
     * must close it.
     */
    HttpResponse open(URL url, String method, Map<String, String> requestHeaders, ConnectionTiming timing)
            throws IOException;
}
