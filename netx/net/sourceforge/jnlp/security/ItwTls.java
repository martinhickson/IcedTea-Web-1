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
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.util.logging.OutputController;

public final class ItwTls {

    /** Probe stage 1: TLS 1.3 ChaCha only (also the unset/default host state). */
    static final int OFFER_TLS13 = 0;
    static final int OFFER_UNKNOWN = OFFER_TLS13;
    /** Probe stage 2: TLS 1.2 ECDHE-ECDSA ChaCha only. */
    static final int OFFER_TLS12 = 1;
    static final int OFFER_PREFERRED = OFFER_TLS13;
    /** Probe stage 3: TLS 1.2 ECDHE-RSA AES-256-GCM only. */
    static final int OFFER_AES256 = 2;
    /** Probe exhausted: full protocol + cipher list. */
    static final int OFFER_FULL = 3;

    /**
     * Default ({@code probe}, only when {@code deployment.use.fastest.cipher} is
     * {@code true}): three single-suite tries, then the full list.
     * <ol>
     *   <li>TLS 1.3 {@code TLS_CHACHA20_POLY1305_SHA256}</li>
     *   <li>TLS 1.2 {@code TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256}</li>
     *   <li>TLS 1.2 {@code TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384}</li>
     *   <li>full ChaCha-first multi-suite list</li>
     * </ol>
     * Cipher misses short-circuit to the next stage inside the HTTP open;
     * they are not download IO retries. The chosen stage is cached per host.
     * {@code full}, and the default {@code deployment.use.fastest.cipher=false},
     * skip probing and use the multi-suite list.
     */
    public static final String CIPHER_MODE_PROBE = "probe";
    public static final String CIPHER_MODE_FULL = "full";

    static final String TLS13_CHACHA = "TLS_CHACHA20_POLY1305_SHA256";
    static final String TLS12_CHACHA = "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256";
    static final String TLS12_AES256 = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384";

    private static final String[] TLS13_PROTOCOLS = { "TLSv1.3" };
    private static final String[] TLS12_PROTOCOLS = { "TLSv1.2" };
    private static final String[] FULL_PROTOCOLS = { "TLSv1.3", "TLSv1.2" };

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
     * Per-host offer stage: 0 TLS 1.3, 1 TLS 1.2 ChaCha, 2 AES-256-GCM, 3 full.
     * ConcurrentHashMap + AtomicInteger: no locks on the handshake path.
     */
    private static final ConcurrentHashMap<String, AtomicInteger> HOST_OFFER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> LOGGED_TRY_STAGE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> LOGGED_RESULT = new ConcurrentHashMap<>();

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
        static final String[] TLS13 = resolveSupported(TLS13_CHACHA);
        static final String[] TLS12 = resolveSupported(TLS12_CHACHA);
        static final String[] AES256 = resolveSupported(TLS12_AES256);
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
                    + " tls13=" + joinOrNone(Lists.TLS13)
                    + " tls12=" + joinOrNone(Lists.TLS12)
                    + " aes256=" + joinOrNone(Lists.AES256)
                    + (isProbeMode()
                            ? " (try TLS 1.3 ChaCha, then TLS 1.2 ECDHE-ECDSA ChaCha, then TLS 1.2 ECDHE-RSA AES256-GCM, then full list)"
                            : " (legacy multi-suite list)"));
        } catch (Exception ignored) {}
    }

    static String[] fullCiphers() {
        return Lists.FULL;
    }

    static String[] probeCiphers() {
        return Lists.TLS13.length > 0 ? Lists.TLS13 : Lists.TLS12;
    }

    static String[] tls13Ciphers() {
        return Lists.TLS13;
    }

    static String[] tls12Ciphers() {
        return Lists.TLS12;
    }

    static String[] aes256Ciphers() {
        return Lists.AES256;
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
        String[] protocols = protocolsFor(host);
        String[] suites = suitesFor(host);
        p.setProtocols(protocols);
        p.setCipherSuites(suites);
        if (host != null && !host.isEmpty()) {
            try {
                p.setServerNames(Collections.singletonList(new SNIHostName(host)));
            } catch (IllegalArgumentException ignored) {
                // literal IPv4/IPv6: SNI host names are not used
            }
        }
        logTry(host, effectiveStage(offerState(host).get()), protocols, suites);
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
        switch (effectiveStage(offerState(host).get())) {
            case OFFER_TLS13:
                return Lists.TLS13;
            case OFFER_TLS12:
                return Lists.TLS12;
            case OFFER_AES256:
                return Lists.AES256;
            default:
                return Lists.FULL;
        }
    }

    static String[] protocolsFor(String host) {
        if (!isProbeMode()) {
            return FULL_PROTOCOLS;
        }
        switch (effectiveStage(offerState(host).get())) {
            case OFFER_TLS13:
                return TLS13_PROTOCOLS;
            case OFFER_TLS12:
                return TLS12_PROTOCOLS;
            case OFFER_AES256:
                return TLS12_PROTOCOLS;
            default:
                return FULL_PROTOCOLS;
        }
    }

    static int effectiveStage(int state) {
        if (state == OFFER_TLS13 && Lists.TLS13.length == 0) {
            return effectiveStage(OFFER_TLS12);
        }
        if (state == OFFER_TLS12 && Lists.TLS12.length == 0) {
            return effectiveStage(OFFER_AES256);
        }
        if (state == OFFER_AES256 && Lists.AES256.length == 0) {
            return OFFER_FULL;
        }
        return state;
    }

    static int effectiveOffer(String host) {
        return effectiveStage(offerState(host).get());
    }

    static String stageName(int state) {
        switch (effectiveStage(state)) {
            case OFFER_TLS13:
                return "tls13";
            case OFFER_TLS12:
                return "tls12";
            case OFFER_AES256:
                return "aes256";
            default:
                return "full";
        }
    }

    static boolean isProbeMode() {
        if (cipherSuitesOverride() != null) {
            return false;
        }
        if (!isUseFastestCipher()) {
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
        return !CIPHER_MODE_FULL.equalsIgnoreCase(mode.trim());
    }

    static boolean isUseFastestCipher() {
        try {
            String flag = JNLPRuntime.getConfiguration()
                    .getProperty(DeploymentConfiguration.KEY_USE_FASTEST_CIPHER);
            return flag != null && Boolean.parseBoolean(flag.trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    static AtomicInteger offerState(String host) {
        return HOST_OFFER.computeIfAbsent(hostKey(host), h -> new AtomicInteger(OFFER_UNKNOWN));
    }

    static void noteNegotiated(String host, String cipher) {
        noteNegotiated(host, cipher, null);
    }

    static void noteNegotiated(String host, String cipher, String protocol) {
        if (cipher == null) {
            return;
        }
        int stay = stageForNegotiatedCipher(cipher);
        if (isProbeMode()) {
            offerState(host).set(stay);
        }
        logResult(host, cipher, protocol, stay);
    }

    static int stageForNegotiatedCipher(String cipher) {
        if (TLS13_CHACHA.equals(cipher)) {
            return OFFER_TLS13;
        }
        if (TLS12_CHACHA.equals(cipher)) {
            return OFFER_TLS12;
        }
        if (TLS12_AES256.equals(cipher)) {
            return OFFER_AES256;
        }
        return OFFER_FULL;
    }

    /**
     * @return true if the caller should retry this host with the next probe stage
     *         (TLS 1.3 → TLS 1.2 ChaCha → AES-256-GCM → full). False once the
     *         host is already on the full list — the offer does not advance further.
     */
    static boolean shouldRetryWithNextOffer(String host, Throwable failure) {
        return shouldRetryWithFullCiphers(host, failure);
    }

    /**
     * Cipher probe miss: advance the host offer if a narrower stage is still
     * current, log DEBUG (no stack), and return true so {@code open()} tries
     * the next offer on this same request. Not a download failure and not an
     * IO retry.
     */
    public static boolean shortCircuitToNextOffer(String host, Throwable failure) {
        if (!isProbeMode() || !isCipherNegotiationFailure(failure)) {
            return false;
        }
        return shouldRetryWithNextOffer(host, failure);
    }

    /**
     * Inner {@code open()} short-circuit: try the next cipher offer on this
     * same URL. Returns false once the full list has already been tried for
     * this attempt ({@code stageAtStart == FULL}) or the failure is not a
     * probe miss. A sibling thread may already have moved the host to full
     * while this socket still used a narrower offer — that still continues
     * once, with the current (full) list.
     */
    public static boolean continueOpenAfterHandshakeMiss(String host, Throwable failure,
            int stageAtStart) {
        if (shortCircuitToNextOffer(host, failure)) {
            return true;
        }
        return isProbeMode()
                && isCipherNegotiationFailure(failure)
                && stageAtStart != OFFER_FULL;
    }

    /**
     * @return true if the caller should retry this host with the next offer stage
     */
    static boolean shouldRetryWithFullCiphers(String host, Throwable failure) {
        if (!isProbeMode() || !isCipherNegotiationFailure(failure)) {
            return false;
        }
        int previous = offerState(host).get();
        int next = nextStage(previous);
        if (next == previous) {
            return false;
        }
        String[] missed = suitesFor(host);
        offerState(host).compareAndSet(previous, next);
        logCipherNotNegotiated(host, previous, next, missed, failure);
        return true;
    }

    private static void logCipherNotNegotiated(String host, int previous, int next,
            String[] missed, Throwable failure) {
        try {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "ItwTls cipher suite not negotiated host=" + hostKey(host)
                    + " stage=" + stageName(previous)
                    + " suites=" + String.join(",", missed)
                    + " (" + handshakeMissReason(failure) + "); trying "
                    + stageName(next));
        } catch (Exception ignored) {}
    }

    public static String handshakeMissReason(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m == null) {
                continue;
            }
            String l = m.toLowerCase(Locale.ROOT);
            if (l.contains("close_notify")) {
                return "close_notify";
            }
            if (l.contains("handshake_failure") || l.contains("received fatal alert: handshake")) {
                return "handshake_failure";
            }
            if (l.contains("no cipher suites in common")) {
                return "no cipher suites in common";
            }
        }
        return t != null ? t.getClass().getSimpleName() : "unknown";
    }

    static int nextStage(int current) {
        int stage = effectiveStage(current);
        if (stage == OFFER_TLS13) {
            return effectiveStage(OFFER_TLS12);
        }
        if (stage == OFFER_TLS12) {
            return effectiveStage(OFFER_AES256);
        }
        return OFFER_FULL;
    }

    static boolean isPreferredCipher(String cipher) {
        return TLS13_CHACHA.equals(cipher)
                || TLS12_CHACHA.equals(cipher)
                || TLS12_AES256.equals(cipher);
    }

    static void resetHostOfferForTest() {
        HOST_OFFER.clear();
        LOGGED_TRY_STAGE.clear();
        LOGGED_RESULT.clear();
    }

    private static String hostKey(String host) {
        if (host == null || host.isEmpty()) {
            return "";
        }
        return host.toLowerCase(Locale.ROOT);
    }

    public static boolean isCipherNegotiationFailure(Throwable t) {
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
                        || l.contains("received fatal alert: handshake")
                        || l.contains("close_notify")) {
                    handshake = true;
                }
            }
            // SSLHandshakeException, SSLProtocolException (close_notify), and
            // other handshake SSLExceptions are probe misses — not only the
            // handshake_failure alert string.
            if (c instanceof SSLException) {
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

    private static String[] resolveSupported(String suite) {
        Set<String> supported = new HashSet<>(Arrays.asList(
                CTX.getSocketFactory().getSupportedCipherSuites()));
        if (supported.contains(suite)) {
            return new String[] { suite };
        }
        try {
            OutputController.getLogger().log(OutputController.Level.WARNING_ALL,
                    "ItwTls: probe suite not supported on this JDK: " + suite);
        } catch (Exception ignored) {}
        return new String[0];
    }

    private static void logResolved() {
        try {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG,
                    "ItwTls probe tls13=" + joinOrNone(Lists.TLS13)
                    + " tls12=" + joinOrNone(Lists.TLS12)
                    + " aes256=" + joinOrNone(Lists.AES256));
        } catch (Exception ignored) {}
    }

    private static String joinOrNone(String[] suites) {
        return suites.length == 0 ? "(none)" : String.join(",", suites);
    }

    private static void logTry(String host, int stage, String[] protocols, String[] suites) {
        if (!isProbeMode()) {
            return;
        }
        String key = hostKey(host);
        Integer prev = LOGGED_TRY_STAGE.put(key, stage);
        if (prev != null && prev == stage) {
            return;
        }
        try {
            OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                    "ItwTls try host=" + (key.isEmpty() ? "-" : key)
                    + " stage=" + stageName(stage)
                    + " tls=" + String.join(",", protocols)
                    + " suites=" + String.join(",", suites));
        } catch (Exception ignored) {}
    }

    private static void logResult(String host, String cipher, String protocol, int stay) {
        String key = hostKey(host);
        String tls = protocol != null && !protocol.isEmpty() ? protocol : "unknown";
        String line = tls + "/" + cipher + "/" + stageName(stay);
        String prev = LOGGED_RESULT.put(key, line);
        OutputController.Level level = line.equals(prev)
                ? OutputController.Level.MESSAGE_DEBUG
                : OutputController.Level.MESSAGE_ALL;
        try {
            OutputController.getLogger().log(level,
                    "ItwTls result host=" + (key.isEmpty() ? "-" : key)
                    + " tls=" + tls
                    + " cipher=" + cipher
                    + " stage=" + stageName(stay)
                    + (stay == OFFER_FULL ? " (full list)" : " (probe hold)"));
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
