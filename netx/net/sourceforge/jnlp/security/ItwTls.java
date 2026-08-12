package net.sourceforge.jnlp.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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

    /**
     * Cipher-order strategy: default to ChaCha20-first and DISABLE AES-NI
     * auto-detection (benchmark + CPUID flags). Rationale: ChaCha20-Poly1305 is
     * fast in software on hardware without AES-NI (EKS/Graviton/VMs), robust to
     * AES execution-unit contention on shared cores, and side-channel immune.
     * The cost is only ~2-4x slower client decrypt than AES-NI AES on Win11.
     * Set to true to re-enable detection. An explicit
     * deployment.tls.client.cipherSuites override ALWAYS wins regardless.
     */
    private static final boolean ENABLE_AES_NI_DETECTION = false;

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
        } else if (!ENABLE_AES_NI_DETECTION) {
            order = NO_AES_NIO_ORDER;
            source = "chacha-default";
        } else {
            Detection d = detectCipherPreference();
            order = d.prefersAes ? AES_NIO_ORDER : NO_AES_NIO_ORDER;
            source = d.source;
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

    private static final class Detection {
        final boolean prefersAes;
        final String source;
        Detection(boolean prefersAes, String source) {
            this.prefersAes = prefersAes;
            this.source = source;
        }
    }

    /**
     * Choose AES-GCM vs ChaCha20 by MEASURING the actual per-byte cipher speed
     * on this JVM/CPU, falling back to CPUID flags when the benchmark can't run
     * (e.g. ChaCha20 cipher absent on JDK < 12). Measuring catches two things a
     * static flag check cannot: an AES-NI-less/slow CPU, AND contention on a
     * shared/hyperthreaded core (EKS pods sharing a physical core both doing
     * AES-heavy work degrade each other's AES throughput).
     */
    static Detection detectCipherPreference() {
        final int size = 1 << 20; // 1 MiB
        byte[] data = new byte[size];
        new SecureRandom().nextBytes(data);
        try {
            Long aesMs = benchmark("AES/GCM/NoPadding", data);
            Long chachaMs = benchmark("ChaCha20-Poly1305", data);
            if (aesMs != null && chachaMs != null) {
                return new Detection(aesMs <= chachaMs,
                        aesMs <= chachaMs ? "aes-benchmark" : "chacha-benchmark");
            }
            // ChaCha unavailable (JDK < 12) — trust the flag heuristic
            boolean aes = hasAesNni();
            return new Detection(aes, aes ? "aes-ni-detected" : "no-aes-ni-fallback");
        } catch (Throwable t) {
            boolean aes = hasAesNni();
            return new Detection(aes, aes ? "aes-ni-detected" : "no-aes-ni-fallback");
        }
    }

    /** Encrypt {@code data} once with {@code alg}; returns ms, or null if unavailable. */
    private static Long benchmark(String alg, byte[] data) {
        try {
            Cipher c = Cipher.getInstance(alg);
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            if (alg.startsWith("ChaCha")) {
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                        new IvParameterSpec(nonce));
            } else {
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, nonce));
            }
            long t0 = System.nanoTime();
            c.doFinal(data);
            return (System.nanoTime() - t0) / 1_000_000L;
        } catch (Exception e) {
            return null; // cipher or params unavailable on this JDK
        }
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
