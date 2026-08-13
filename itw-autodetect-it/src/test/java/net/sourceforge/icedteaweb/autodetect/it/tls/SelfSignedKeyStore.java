package net.sourceforge.icedteaweb.autodetect.it.tls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a short-lived self-signed JKS via {@code keytool} (RSA or EC).
 */
final class SelfSignedKeyStore {

    static final String PASSWORD = "changeit";
    static final String ALIAS = "server";

    private SelfSignedKeyStore() {
    }

    static Path rsa(Path dir) throws IOException, InterruptedException {
        return generate(dir, "RSA", null);
    }

    static Path ecdsa(Path dir) throws IOException, InterruptedException {
        return generate(dir, "EC", "secp256r1");
    }

    private static Path generate(Path dir, String keyAlg, String group) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Path ks = dir.resolve("server.jks");
        Files.deleteIfExists(ks);
        Path keytool = keytoolPath();
        List<String> args = new ArrayList<>();
        args.add(keytool.toString());
        args.add("-genkeypair");
        args.add("-alias");
        args.add(ALIAS);
        args.add("-keyalg");
        args.add(keyAlg);
        if ("RSA".equals(keyAlg)) {
            args.add("-keysize");
            args.add("2048");
        } else if (group != null) {
            args.add("-groupname");
            args.add(group);
        }
        args.add("-validity");
        args.add("2");
        args.add("-keystore");
        args.add(ks.toString());
        args.add("-storepass");
        args.add(PASSWORD);
        args.add("-keypass");
        args.add(PASSWORD);
        args.add("-dname");
        args.add("CN=localhost");
        args.add("-ext");
        args.add("SAN=DNS:localhost,IP:127.0.0.1");
        run(args);
        if (!Files.isRegularFile(ks)) {
            throw new IOException("keytool did not create " + ks);
        }
        return ks;
    }

    private static Path keytoolPath() {
        String exe = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "keytool.exe" : "keytool";
        return Path.of(System.getProperty("java.home"), "bin", exe);
    }

    private static void run(List<String> args) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .start();
        byte[] out = process.getInputStream().readAllBytes();
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException("keytool failed (" + code + "): "
                    + new String(out, StandardCharsets.UTF_8));
        }
    }
}
