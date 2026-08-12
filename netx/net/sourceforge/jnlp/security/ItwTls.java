package net.sourceforge.jnlp.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.OutputController;

public final class ItwTls {

    private static final String[] PROTOCOLS = { "TLSv1.3", "TLSv1.2" };

    private static final String[] AES_NIO_ORDER = {
        "TLS_AES_128_GCM_SHA256",
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
    };

    private static final String[] NO_AES_NIO_ORDER = {
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
    };

    private static final SSLContext CTX = build();

    private ItwTls() {}

    /**
     * CIPHERS is resolved LAZILY (not a static final at class-init) so the
     * deployment.tls.client.cipherSuites override is read after the
     * deployment.properties file has been merged into the configuration —
     * resolveCipherSuites() at class-load time could see the pre-merge config
     * and silently ignore the override.
     */
    private static class CiphersHolder {
        static final String[] VALUE = resolveCipherSuites();
    }

    static String[] ciphers() { return CiphersHolder.VALUE; }

    private static SSLContext build() {
        try {
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(null, null, null);
            return c;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create ITW SSLContext", e);
        }
    }

    public static SSLContext context() { return CTX; }

    public static SSLParameters parameters() {
        SSLParameters p = CTX.getDefaultSSLParameters();
        p.setProtocols(PROTOCOLS);
        p.setCipherSuites(ciphers());
        return p;
    }

    static String offeredCipherSummary() {
        return String.join(",", ciphers());
    }

    private static String[] resolveCipherSuites() {
        String override = null;
        try {
            override = JNLPRuntime.getConfiguration()
                    .getProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_SUITES);
        } catch (Exception ignored) {}
        String[] order;
        String source;
        if (override != null && !override.trim().isEmpty()) {
            order = splitCsv(override);
            source = "override";
        } else {
            boolean aes = hasAesNni();
            order = aes ? AES_NIO_ORDER : NO_AES_NIO_ORDER;
            source = aes ? "aes-ni-detected" : "no-aes-ni-fallback";
        }
        String resolved = String.join(",", order);
        try {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "ItwTls cipher order [" + source + "]: " + resolved);
        } catch (Exception ignored) {}
        return validateOrFail(order);
    }

    private static String[] validateOrFail(String[] requested) {
        Set<String> supported = new HashSet<>(Arrays.asList(
                CTX.getSocketFactory().getSupportedCipherSuites()));
        List<String> bad = new ArrayList<>();
        for (String c : requested) {
            if (!supported.contains(c)) bad.add(c);
        }
        if (!bad.isEmpty()) {
            throw new IllegalStateException(
                "deployment.tls.client.cipherSuites: unsupported cipher(s): " + bad
                + "\nSupported by this JDK (" + System.getProperty("java.version") + "): "
                + supported);
        }
        return requested;
    }

    private static String[] splitCsv(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    static boolean hasAesNni() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("linux")) {
                // x86_64: "flags" line; aarch64 (Graviton): "Features" line.
                // Containers see the host CPU's /proc/cpuinfo, so this is accurate
                // inside EKS pods too. Absent the token → false → ChaCha-first (safe).
                for (String line : Files.readAllLines(Paths.get("/proc/cpuinfo"))) {
                    String t = line.trim();
                    if ((t.startsWith("flags") || t.startsWith("Features")) && line.contains(" aes")) {
                        return true;
                    }
                }
                return false;
            }
            if (os.contains("mac") || os.contains("darwin")) {
                Process p = new ProcessBuilder("sysctl", "-n", "hw.optional.aes")
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                p.waitFor();
                return out.equals("1");
            }
            if (os.contains("windows")) {
                String arch = System.getProperty("os.arch", "");
                if (arch.contains("64") || arch.contains("x86")) return true;
                return false;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
