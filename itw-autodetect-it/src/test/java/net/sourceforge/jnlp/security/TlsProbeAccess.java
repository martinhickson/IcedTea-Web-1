package net.sourceforge.jnlp.security;

import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;

/**
 * Test-only access to package-private TLS probe state.
 */
public final class TlsProbeAccess {

    private TlsProbeAccess() {
    }

    public static void forceProbeMode() {
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE, ItwTls.CIPHER_MODE_PROBE);
        JNLPRuntime.getConfiguration()
                .setProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_SUITES, "");
        ItwTls.resetHostOfferForTest();
    }

    public static void resetHostOffer() {
        ItwTls.resetHostOfferForTest();
    }

    public static int offerStage(String host) {
        return ItwTls.offerState(host).get();
    }

    public static int tls13() {
        return ItwTls.OFFER_TLS13;
    }

    public static int tls12() {
        return ItwTls.OFFER_TLS12;
    }

    public static int full() {
        return ItwTls.OFFER_FULL;
    }
}
