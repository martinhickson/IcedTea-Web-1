package net.sourceforge.jnlp.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.OutputController;

public final class ItwTls {

    static final int OFFER_UNKNOWN = 0;
    static final int OFFER_PREFERRED = 1;
    static final int OFFER_FULL = 2;

    /**
     * Default ({@code probe}): offer only the fastest ChaCha20-Poly1305 suite.
     * TLS 1.3 {@code TLS_CHACHA20_POLY1305_SHA256} is AEAD-only; the typical
     * ECDH group on current JDKs is X25519 (Ed25519 is a signature algorithm,
     * not a TLS 1.3 cipher). If that handshake fails, fall back to the full
     * list and cache the result per host.
     * {@code full}: previous behaviour (ChaCha-first multi-suite list).
     */
    public static final String CIPHER_MODE_PROBE = "probe";
    public static final String CIPHER_MODE_FULL = "full";

    private static final String[] PROTOCOLS = { "TLSv1.3", "TLSv1.2" };

    /**
     * Single-suite probe order: first supported candidate is the only offer.
     * TLS 1.3 ChaCha, then TLS 1.2 ECDHE-ECDSA ChaCha, then ECDHE-RSA ChaCha.
     */
    private static final String[] PREFERRED_CANDIDATES = {
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
    };

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
     * Per-host offer state. 0 unknown (probe), 1 preferred negotiated, 2 must
     * use the full list. ConcurrentHashMap + AtomicInteger: no locks on the
     * handshake path.
     */
    private static final ConcurrentHashMap<String, AtomicInteger> HOST_OFFER = new ConcurrentHashMap<>();

    /**
     * Cipher-order strategy for {@code full} mode: default to ChaCha20-first
     * and DISABLE AES-NI auto-detection. An explicit
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
    private static class Lists {
        static final String[] FULL = resolveFullCipherSuites();
        static final String[] PROBE = resolveProbeCipher(FULL);
        static {
            logResolved();
        }
    }

    /** Force cipher-list resolution and log the mode before the first GET. */
    public static void warm() {
        Lists.class.getName();
        try {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                    "ItwTls ready: mode=" + (isProbeMode() ? CIPHER_MODE_PROBE : CIPHER_MODE_FULL)
                    + " preferred=" + String.join(",", Lists.PROBE)
                    + (isProbeMode()
                            ? " (single suite; fallback to full list on handshake failure)"
                            : " (legacy multi-suite list)"));
        } catch (Exception ignored) {}
    }

    static String[] fullCiphers() {
        return Lists.FULL;
    }

    static String[] probeCiphers() {
        return Lists.PROBE;
    }

    static String[] ciphers() {
        return suitesFor(null);
    }

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
        return parametersFor(null);
    }

    public static SSLParameters parametersFor(String host) {
        SSLParameters p = CTX.getDefaultSSLParameters();
        p.setProtocols(PROTOCOLS);
        p.setCipherSuites(suitesFor(host));
        if (host != null && !host.isEmpty()) {
            try {
                p.setServerNames(Collections.singletonList(new SNIHostName(host)));
            } catch (IllegalArgumentException ignored) {
                // literal IPv4/IPv6: SNI host names are not used
            }
        }
        return p;
    }

    static String offeredCipherSummary() {
        return offeredCipherSummary(null);
    }

    static String offeredCipherSummary(String host) {
        return String.join(",", suitesFor(host));
    }

    static String[] suitesFor(String host) {
        if (!isProbeMode()) {
            return Lists.FULL;
        }
        int state = offerState(host).get();
        if (state == OFFER_FULL) {
            return Lists.FULL;
        }
        return Lists.PROBE;
    }

    static boolean isProbeMode() {
        if (cipherSuitesOverride() != null) {
            return false;
        }
        String mode = null;
        try {
            mode = JNLPRuntime.getConfiguration()
                    .getProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_MODE);
        } catch (Exception ignored) {}
        if (mode == null || mode.trim().isEmpty()) {
            return true;
        }
        return CIPHER_MODE_PROBE.equalsIgnoreCase(mode.trim());
    }

    static AtomicInteger offerState(String host) {
        return HOST_OFFER.computeIfAbsent(hostKey(host), h -> new AtomicInteger(OFFER_UNKNOWN));
    }

    static void noteNegotiated(String host, String cipher) {
        if (!isProbeMode() || cipher == null) {
            return;
        }
        offerState(host).set(isPreferredCipher(cipher) ? OFFER_PREFERRED : OFFER_FULL);
    }

    /**
     * @return true if the caller should retry this host with the full cipher list
     */
    static boolean shouldRetryWithFullCiphers(String host, Throwable failure) {
        if (!isProbeMode() || !isCipherNegotiationFailure(failure)) {
            return false;
        }
        int previous = offerState(host).getAndSet(OFFER_FULL);
        if (previous == OFFER_FULL) {
            return false;
        }
        try {
            OutputController.getLogger().log(OutputController.Level.WARNING_ALL,
                    "ItwTls: preferred suite not negotiated for " + hostKey(host)
                    + " (" + failure.getClass().getSimpleName() + "); retrying with full cipher list");
        } catch (Exception ignored) {}
        return true;
    }

    static boolean isPreferredCipher(String cipher) {
        if (cipher == null) {
            return false;
        }
        for (String c : Lists.PROBE) {
            if (cipher.equals(c)) {
                return true;
            }
        }
        return false;
    }

    static void resetHostOfferForTest() {
        HOST_OFFER.clear();
    }

    private static String hostKey(String host) {
        if (host == null || host.isEmpty()) {
            return "";
        }
        return host.toLowerCase(Locale.ROOT);
    }

    static boolean isCipherNegotiationFailure(Throwable t) {
        boolean handshake = false;
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SSLPeerUnverifiedException) {
                return false;
            }
            String m = c.getMessage();
            if (m != null) {
                String l = m.toLowerCase(Locale.ROOT);
                if (l.contains("pkix")
                        || l.contains("unable to find valid certification")
                        || l.contains("certificate_unknown")
                        || l.contains("certificate_expired")) {
                    return false;
                }
                if (l.contains("no cipher suites in common")
                        || l.contains("handshake_failure")
                        || l.contains("received fatal alert: handshake")) {
                    handshake = true;
                }
            }
            if (c instanceof SSLHandshakeException) {
                handshake = true;
            }
        }
        return handshake;
    }

    private static String cipherSuitesOverride() {
        try {
            String override = JNLPRuntime.getConfiguration()
                    .getProperty(DeploymentConfiguration.KEY_TLS_CLIENT_CIPHER_SUITES);
            if (override != null && !override.trim().isEmpty()) {
                return override;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String[] resolveFullCipherSuites() {
        String override = cipherSuitesOverride();
        String[] order;
        String source;
        if (override != null) {
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
                    "ItwTls full cipher order [" + source + "]: " + resolved);
        } catch (Exception ignored) {}
        return validateOrFail(order);
    }

    private static String[] resolveProbeCipher(String[] full) {
        Set<String> supported = new HashSet<>(Arrays.asList(
                CTX.getSocketFactory().getSupportedCipherSuites()));
        for (String c : PREFERRED_CANDIDATES) {
            if (supported.contains(c)) {
                return new String[] { c };
            }
        }
        try {
            OutputController.getLogger().log(OutputController.Level.WARNING_ALL,
                    "ItwTls: no ChaCha probe suite on this JDK; using full list");
        } catch (Exception ignored) {}
        return full;
    }

    private static void logResolved() {
        try {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "ItwTls probe cipher: " + String.join(",", Lists.PROBE));
        } catch (Exception ignored) {}
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
