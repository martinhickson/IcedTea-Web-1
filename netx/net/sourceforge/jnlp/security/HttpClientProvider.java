package net.sourceforge.jnlp.security;

import java.util.Locale;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;

/**
 * Provides the configured {@link ItwHttpClient} implementation.
 * Default "apache"; "oracle" selects {@link HttpURLConnection}.
 */
public final class HttpClientProvider {

    private HttpClientProvider() {
    }

    private static class Holder {
        static final ItwHttpClient INSTANCE = build();
    }

    private static ItwHttpClient build() {
        String name = "apache";
        try {
            String v = JNLPRuntime.getConfiguration().getProperty(DeploymentConfiguration.KEY_HTTP_CLIENT);
            if (v != null && !v.trim().isEmpty()) {
                name = v.trim();
            }
        } catch (Exception ignored) {
        }
        if ("oracle".equalsIgnoreCase(name)) {
            return new OracleHttpClient();
        }
        if ("apache".equalsIgnoreCase(name)) {
            return new ApacheHttpClient();
        }
        throw new IllegalStateException(
                "deployment.http.client: unsupported value '" + name + "' (expected 'apache' or 'oracle')");
    }

    public static ItwHttpClient getDefault() {
        return Holder.INSTANCE;
    }
}
