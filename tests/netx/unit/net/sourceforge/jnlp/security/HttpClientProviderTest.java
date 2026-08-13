package net.sourceforge.jnlp.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
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

    @Test
    public void defaultHttpClientPropertyIsApache() {
        String value = JNLPRuntime.getConfiguration()
                .getProperty(DeploymentConfiguration.KEY_HTTP_CLIENT);
        assertTrue(value == null || value.trim().isEmpty() || "apache".equalsIgnoreCase(value.trim()),
                "default deployment.http.client should be apache, was: " + value);
    }
}
