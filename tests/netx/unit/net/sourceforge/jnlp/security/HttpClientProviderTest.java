package net.sourceforge.jnlp.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Default download client is Apache; the oracle path is an explicit opt-in.
 */
public class HttpClientProviderTest {

    @Test
    public void defaultClientIsApache() {
        assertTrue(HttpClientProvider.getDefault() instanceof ApacheHttpClient,
                HttpClientProvider.getDefault().getClass().getName());
    }
}
