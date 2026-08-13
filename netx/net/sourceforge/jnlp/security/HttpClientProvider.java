package net.sourceforge.jnlp.security;

import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Provides the configured {@link ItwHttpClient} implementation.
 * Default "apache"; "oracle" selects {@link java.net.HttpURLConnection}.
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
        ItwHttpClient client;
        if ("oracle".equalsIgnoreCase(name)) {
            client = new OracleHttpClient();
        } else if ("apache".equalsIgnoreCase(name)) {
            client = new ApacheHttpClient();
        } else {
            throw new IllegalStateException(
                    "deployment.http.client: unsupported value '" + name + "' (expected 'apache' or 'oracle')");
        }
        try {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                    "HTTP downloads using " + name + " client"
                    + ("apache".equalsIgnoreCase(name)
                            ? " (Apache HttpClient 5 + ItwSslSocketFactory)"
                            : " (HttpURLConnection + ItwSslSocketFactory)"));
        } catch (Exception ignored) {
        }
        return client;
    }

    public static ItwHttpClient getDefault() {
        return Holder.INSTANCE;
    }
}
