package net.sourceforge.icedteaweb.multijar.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import net.sourceforge.icedteaweb.multijar.MultijarMain;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for the download pipeline with many medium-sized jars and
 * the pack200 / gzip matrix.
 *
 * <p>Undertow (127.0.0.1, ephemeral port) serves a JNLP with
 * {@code jnlp.packEnabled=true} plus 20 x ~20 MB jars in a mix of encodings:
 * <ul>
 *   <li>PLAIN — served only as {@code jarN.jar} (pack.gz probe 404s);</li>
 *   <li>PACK_GZ_ONLY — served only as {@code jarN.jar.pack.gz} (the plain URL
 *       404s), i.e. a server that only has pack200-gzip — ITW must probe and
 *       fetch the .pack.gz variant and unpack it;</li>
 *   <li>GZIP — served as {@code jarN.jar} with {@code Content-Encoding: gzip}.</li>
 * </ul>
 *
 * <p>A real {@code javaws} is launched either from the uber jar
 * ({@code -Ditw.uber.jar}, source-tree mode) or via the packaged launcher
 * ({@code -Ditw.javaws.bin}, installed MSI/zip mode). The app must load a class
 * from every jar (proving every variant downloaded, unpacked and became
 * loadable) and 20 jar files must land in the cache.
 *
 * <p>.pack.gz fixtures are generated with JDK 11's {@code java.util.jar.Pack200}
 * (the io.pack200 packer is known-broken; only unpack works), so the pack.gz
 * part of the matrix is skipped on a JDK 14+ test JVM.
 */
@Timeout(500)
class MultiJarDownloadIT {

    private static final int JAR_COUNT = MultijarMain.JAR_COUNT;
    private static final int JAR_SIZE_MB = 20;
    private static final long JAR_SIZE_BYTES = JAR_SIZE_MB * 1024L * 1024L;
    private static final String MARKER = MultijarMain.MARKER;

    private enum Encoding { PLAIN, PACK_GZ_ONLY, GZIP }

    @TempDir
    Path tmp;

    @Test
    void matrixOfTwentyTwentyMbJarsDownloadsAndLaunches() throws Exception {
        boolean canPack = hasJdkPack200();
        if (!canPack) {
            // keep exercising plain + gzip; pack.gz requires JDK 11-13 to build fixtures
            Assumptions.assumeTrue(true, "no java.util.jar.Pack200 — pack.gz fixtures skipped on JDK 14+");
        }

        // deterministic matrix: 8 plain, 6 pack.gz-only, 6 gzip
        List<Encoding> encodings = new ArrayList<>();
        for (int i = 0; i < JAR_COUNT; i++) {
            encodings.add(i < 8 ? Encoding.PLAIN
                    : i < 14 ? Encoding.PACK_GZ_ONLY
                    : Encoding.GZIP);
        }

        byte[] filler = new byte[(int) JAR_SIZE_BYTES];
        new Random(0xC0FFEE).nextBytes(filler);
        String commonsLang3Jar = findClasspathArtifact("commons-lang3-", ".jar");
        byte[] stringUtils = readZipEntry(commonsLang3Jar, "org/apache/commons/lang3/StringUtils.class");
        byte[] multijarMain = readResourceClass(MultijarMain.class);
        Map<Integer, byte[]> jarClasses = compileJarClasses();

        // per-jar encoded bodies: PLAIN -> [body], GZIP -> [gzip body], PACK_GZ_ONLY -> [pack.gz body]
        Map<Integer, byte[]> plain = new LinkedHashMap<>();
        Map<Integer, byte[]> gzip = new LinkedHashMap<>();
        Map<Integer, byte[]> packGz = new LinkedHashMap<>();
        Path jarDir = tmp.resolve("jars");
        Files.createDirectories(jarDir);
        for (int i = 0; i < JAR_COUNT; i++) {
            Path jarFile = jarDir.resolve("jar" + i + ".jar");
            writeJar(jarFile, i == 0, i, multijarMain, jarClasses, stringUtils, filler);
            byte[] bytes = Files.readAllBytes(jarFile);
            plain.put(i, bytes);
            if (encodings.get(i) == Encoding.GZIP) {
                gzip.put(i, gzip(bytes));
            }
            if (encodings.get(i) == Encoding.PACK_GZ_ONLY) {
                packGz.put(i, packGz(jarFile));
            }
        }

        AtomicInteger statusPort = new AtomicInteger();
        Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        Undertow server = startServer(plain, gzip, packGz, encodings, requests, statusPort);
        int port = statusPort.get();

        Path testHome = tmp.resolve("home");
        String jnlp = jnlpXml(port);
        String jnlpUrl = "http://127.0.0.1:" + port + "/app.jnlp";
        try {
            String output = launchJavaws(jnlpUrl, testHome);

            assertThat(output)
                    .as("launch output")
                    .contains(MARKER + " loaded=" + JAR_COUNT)
                    .contains("thirdparty=StringUtils");

            // pack.gz jars are unpacked into the cache lazily at classload; allow
            // the cache writes (which can lag the app marker) to complete. pack.gz
            // resources cache under <name>.jar.gz; plain/gzip under <name>.jar.
            List<Path> cached = List.of();
            long cacheDeadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < cacheDeadline) {
                cached = listCachedResources(testHome);
                if (cached.size() >= JAR_COUNT) {
                    break;
                }
                Thread.sleep(500);
            }
            assertThat(cached)
                    .as("cached resources (full cache tree:\n" + allCacheFiles(testHome) + ")")
                    .hasSize(JAR_COUNT);
            long totalBytes = 0;
            for (Path jar : cached) {
                totalBytes += Files.size(jar);
            }
            assertThat(totalBytes)
                    .as("total cached bytes for %d x %d MB", JAR_COUNT, JAR_SIZE_MB)
                    .isGreaterThanOrEqualTo((long) (JAR_COUNT * JAR_SIZE_BYTES * 0.95));

            // every pack.gz-only jar must actually have been fetched via its .pack.gz URL
            for (int i = 0; i < JAR_COUNT; i++) {
                if (encodings.get(i) == Encoding.PACK_GZ_ONLY) {
                    assertThat(hits(requests, "/lib/jar" + i + ".jar.pack.gz"))
                            .as("jar% d .pack.gz fetch", i)
                            .isGreaterThanOrEqualTo(1);
                }
            }
            // gzip jars must have been served (and uncompressed) — at least a GET hit
            for (int i = 0; i < JAR_COUNT; i++) {
                if (encodings.get(i) == Encoding.GZIP) {
                    assertThat(hits(requests, "/lib/jar" + i + ".jar"))
                            .as("gzip jar% d fetch", i)
                            .isGreaterThanOrEqualTo(1);
                }
            }
        } finally {
            server.stop();
        }
    }

    // ------------------------------------------------------------------ server

    private Undertow startServer(Map<Integer, byte[]> plain, Map<Integer, byte[]> gzip,
                                 Map<Integer, byte[]> packGz, List<Encoding> encodings,
                                 Map<String, AtomicInteger> requests, AtomicInteger portRef) {
        HttpHandler handler = exchange -> {
            String path = exchange.getRequestPath();
            requests.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            if ("/app.jnlp".equals(path)) {
                byte[] bytes = jnlpXml(portRef.get()).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/x-java-jnlp-file");
                exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, bytes.length);
                exchange.setStatusCode(200);
                exchange.getResponseSender().send(ByteBuffer.wrap(bytes));
                return;
            }
            if (path.startsWith("/lib/jar") && (path.endsWith(".jar") || path.endsWith(".pack.gz"))) {
                String base = path.endsWith(".pack.gz")
                        ? path.substring(0, path.length() - ".pack.gz".length())
                        : path;
                int i = Integer.parseInt(base.substring("/lib/jar".length(), base.indexOf(".jar")));
                if (path.endsWith(".pack.gz")) {
                    byte[] bytes = packGz.get(i);
                    if (bytes == null) {
                        exchange.setStatusCode(404);
                        exchange.endExchange();
                        return;
                    }
                    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/java-archive");
                    exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, bytes.length);
                    exchange.setStatusCode(200);
                    exchange.getResponseSender().send(ByteBuffer.wrap(bytes));
                    return;
                }
                byte[] bytes = plain.get(i);
                if (encodings.get(i) == Encoding.GZIP) {
                    bytes = gzip.get(i);
                    exchange.getResponseHeaders().put(Headers.CONTENT_ENCODING, "gzip");
                }
                if (bytes == null) { // pack.gz-only server has no plain copy
                    exchange.setStatusCode(404);
                    exchange.endExchange();
                    return;
                }
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/java-archive");
                exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, bytes.length);
                exchange.setStatusCode(200);
                exchange.getResponseSender().send(ByteBuffer.wrap(bytes));
                return;
            }
            exchange.setStatusCode(404);
            exchange.endExchange();
        };
        Undertow server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(handler)
                .build();
        server.start();
        int port = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
        portRef.set(port);
        return server;
    }

    private String jnlpXml(int port) {
        StringBuilder resources = new StringBuilder();
        for (int i = 0; i < JAR_COUNT; i++) {
            resources.append("        <jar href=\"lib/jar").append(i).append(".jar\"")
                    .append(i == 0 ? " main=\"true\"" : "").append("/>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"http://127.0.0.1:" + port + "/\" href=\"app.jnlp\">\n"
                + "  <information>\n"
                + "    <title>ITW Multi-Jar Matrix</title>\n"
                + "    <vendor>IcedTea-Web IT</vendor>\n"
                + "  </information>\n"
                + "  <resources>\n"
                + "    <j2se version=\"1.8+\"/>\n"
                + "    <property name=\"jnlp.packEnabled\" value=\"true\"/>\n"
                + resources
                + "  </resources>\n"
                + "  <application-desc main-class=\"net.sourceforge.icedteaweb.multijar.MultijarMain\"/>\n"
                + "</jnlp>\n";
    }

    // ------------------------------------------------------------------ fixtures

    private Map<Integer, byte[]> compileJarClasses() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("integration test must run on a full JDK (javac not available)");
        }
        Path srcDir = Files.createTempDirectory("itw-multijar-src");
        Path classesDir = Files.createTempDirectory("itw-multijar-classes");
        List<String> sources = new ArrayList<>();
        for (int i = 0; i < JAR_COUNT; i++) {
            String source = "package net.sourceforge.icedteaweb.multijar.jars;\n"
                    + "public final class Jar_" + i + " {\n"
                    + "  private Jar_" + i + "() { }\n"
                    + "  public static final String ID = \"" + i + "\";\n"
                    + "}\n";
            Path javaFile = srcDir.resolve("Jar_" + i + ".java");
            Files.write(javaFile, source.getBytes(StandardCharsets.UTF_8));
            sources.add(javaFile.toString());
        }
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(classesDir.toString());
        args.addAll(sources);
        int rc = compiler.run(null, null, null, args.toArray(new String[0]));
        if (rc != 0) {
            throw new IllegalStateException("in-memory javac failed with rc=" + rc);
        }
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        for (int i = 0; i < JAR_COUNT; i++) {
            Path classFile = classesDir.resolve(
                    "net/sourceforge/icedteaweb/multijar/jars/Jar_" + i + ".class");
            out.put(i, Files.readAllBytes(classFile));
        }
        return out;
    }

    private void writeJar(Path jar, boolean main, int index, byte[] multijarMain,
                          Map<Integer, byte[]> jarClasses, byte[] stringUtils,
                          byte[] filler) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            if (main) {
                put(jos, "net/sourceforge/icedteaweb/multijar/MultijarMain.class", multijarMain);
            }
            put(jos, "org/apache/commons/lang3/StringUtils.class", stringUtils);
            put(jos, "net/sourceforge/icedteaweb/multijar/jars/Jar_" + index + ".class",
                    jarClasses.get(index));
            jos.putNextEntry(new JarEntry("filler.dat"));
            jos.write(filler);
            jos.closeEntry();
        }
    }

    private static void put(JarOutputStream jos, String name, byte[] data) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(data);
        jos.closeEntry();
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    /** Packs a jar with JDK 11's built-in Pack200 (the io.pack200 packer is broken). */
    private static byte[] packGz(Path jarFile) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarFile jar = new JarFile(jarFile.toFile());
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            java.util.jar.Pack200.newPacker().pack(jar, gz);
        }
        return out.toByteArray();
    }

    private static boolean hasJdkPack200() {
        try {
            Class.forName("java.util.jar.Pack200");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ launch

    private String launchJavaws(String jnlpUrl, Path testHome) throws Exception {
        String javawsBin = System.getProperty("itw.javaws.bin", "").trim();
        List<String> cmd;
        if (!javawsBin.isEmpty()) {
            cmd = new ArrayList<>();
            cmd.add(javawsBin);
            cmd.add("-headless");
            cmd.add("-verbose");
            cmd.add("-Xtrustall");
            cmd.add("-Xnofork");
            cmd.add("-J-Djava.awt.headless=true");
            cmd.add(jnlpUrl);
        } else {
            String uberJar = System.getProperty("itw.uber.jar", "").trim();
            if (uberJar.isEmpty()) {
                throw new IllegalStateException("set -Ditw.uber.jar (source mode) or -Ditw.javaws.bin (packaged mode)");
            }
            String javaHome = System.getProperty("java.home");
            String java = Paths.get(javaHome, "bin",
                    System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                    .toString();
            String agentJar = findClasspathArtifact("byte-buddy-agent-", ".jar");
            cmd = new ArrayList<>();
            cmd.add(java);
            cmd.add("-Xms8m");
            List<String> vmArgs = new ArrayList<>(moduleArgs(javaMajor()));
            vmArgs.add("-javaagent:" + agentJar);
            vmArgs.add("-Dicedtea-web.bin.name=javaws");
            vmArgs.add("-Dicedtea-web.bin.location=" + tmp.resolve("javaws"));
            cmd.addAll(vmArgs);
            cmd.add("-cp");
            cmd.add(uberJar);
            cmd.add("net.sourceforge.jnlp.runtime.JavawsUberLauncher");
            cmd.add("-headless");
            cmd.add("-verbose");
            cmd.add("-Xtrustall");
            cmd.add("-Xnofork");
            cmd.add(jnlpUrl);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("HOME", testHome.toString());
        env.put("XDG_CONFIG_HOME", testHome.resolve(".config").toString());
        env.put("XDG_CACHE_HOME", testHome.resolve(".cache").toString());
        env.put("XDG_DATA_HOME", testHome.resolve(".local/share").toString());
        env.put("JAVA_HOME", System.getProperty("java.home"));

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
            }
        });
        reader.start();

        long timeoutMillis = Long.getLong("itw.multijar.timeout.seconds", 300L) * 1000L;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        Path logDir = testHome.resolve(".config/icedtea-web/log");
        boolean found = false;
        while (System.currentTimeMillis() < deadline) {
            String out;
            synchronized (output) {
                out = output.toString();
            }
            if (out.contains(MARKER) || logsContain(logDir)) {
                found = true;
                break;
            }
            Thread.sleep(500);
        }
        if (!found) {
            process.destroyForcibly();
            reader.join(5_000);
            String out;
            synchronized (output) {
                out = output.toString();
            }
            fail("app did not reach " + MARKER + " within " + timeoutMillis / 1000 + "s; output so far:\n"
                    + out + "\n--- logs ---\n" + recentLogs(logDir));
        }
        // grace: let the ITW process finish and flush cache writes (source mode)
        if (process.isAlive()) {
            process.waitFor(10, TimeUnit.SECONDS);
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        reader.join(3_000);
        String out;
        synchronized (output) {
            out = output.toString();
        }
        return out;
    }

    private static boolean logsContain(Path logDir) {
        if (!Files.isDirectory(logDir)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(logDir)) {
            return walk.filter(Files::isRegularFile).anyMatch(p -> {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8).contains(MARKER);
                } catch (IOException e) {
                    return false;
                }
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static String recentLogs(Path logDir) {
        if (!Files.isDirectory(logDir)) {
            return "(no log dir)";
        }
        try (Stream<Path> walk = Files.walk(logDir)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "(no log dir)";
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int javaMajor() {
        String spec = System.getProperty("java.specification.version");
        if (spec != null && spec.startsWith("1.")) {
            return Integer.parseInt(spec.substring(2));
        }
        return spec != null ? Integer.parseInt(spec.split("\\.")[0]) : 8;
    }

    private static List<String> moduleArgs(int major) {
        String[][] pairs = {
                {"--add-exports", "java.base/sun.net.www.protocol.jar=ALL-UNNAMED"},
                {"--add-opens", "java.base/sun.net.www.protocol.jar=ALL-UNNAMED"},
                {"--add-exports", "java.base/sun.security.action=ALL-UNNAMED"},
                {"--add-exports", "java.base/sun.security.provider=ALL-UNNAMED"},
                {"--add-exports", "java.base/sun.security.util=ALL-UNNAMED"},
                {"--add-exports", "java.base/sun.security.validator=ALL-UNNAMED"},
                {"--add-exports", "java.base/sun.security.x509=ALL-UNNAMED"},
                {"--add-exports", "java.base/jdk.internal.util.jar=ALL-UNNAMED"},
                {"--add-opens", "java.base/jdk.internal.util.jar=ALL-UNNAMED"},
                {"--add-exports", "java.base/sun.net.www.protocol.http=ALL-UNNAMED"},
                {"--add-exports", "java.desktop/sun.applet=ALL-UNNAMED"},
                {"--add-exports", "java.desktop/sun.awt=ALL-UNNAMED"},
                {"--add-exports", "java.desktop/sun.awt.image=ALL-UNNAMED"},
                {"--add-exports", "java.desktop/sun.swing.table=ALL-UNNAMED"},
                {"--add-exports", "java.desktop/sun.swing=ALL-UNNAMED"},
                {"--add-exports", "java.desktop/sun.swing.plaf=ALL-UNNAMED"},
                {"--add-exports", "java.naming/com.sun.jndi.toolkit.url=ALL-UNNAMED"},
                {"--add-opens", "java.base/java.lang=ALL-UNNAMED"},
        };
        List<String> args = new ArrayList<>();
        for (String[] p : pairs) {
            String mp = p[1].substring(0, p[1].indexOf('='));
            if (knownMissing(major, mp)) {
                continue;
            }
            args.add(p[0]);
            args.add(p[1]);
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            args.add("--add-exports");
            args.add("java.desktop/sun.awt.X11=ALL-UNNAMED");
        } else if (os.contains("win")) {
            args.add("--add-exports");
            args.add("java.desktop/sun.awt.windows=ALL-UNNAMED");
        }
        return args;
    }

    private static boolean knownMissing(int major, String modulePackage) {
        if (modulePackage.equals("java.desktop/sun.applet") && major >= 17) {
            return true;
        }
        if (modulePackage.equals("java.base/jdk.internal.util.jar") && major >= 24) {
            return true;
        }
        if (modulePackage.equals("java.base/sun.security.action") && major >= 25) {
            return true;
        }
        return false;
    }

    private static int hits(Map<String, AtomicInteger> requests, String path) {
        AtomicInteger c = requests.get(path);
        return c != null ? c.get() : 0;
    }

    private static byte[] readResourceClass(Class<?> type) throws IOException {
        String path = "/" + type.getName().replace('.', '/') + ".class";
        try (var in = type.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("class resource not found: " + path);
            }
            return in.readAllBytes();
        }
    }

    private static byte[] readZipEntry(String zipPath, String entryName) throws IOException {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipPath)) {
            var entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("entry not found " + entryName + " in " + zipPath);
            }
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static String findClasspathArtifact(String namePrefix, String nameSuffix) {
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (entry.endsWith(nameSuffix)) {
                String fileName = new File(entry).getName();
                if (fileName.contains(namePrefix) && !fileName.contains("sources") && !fileName.contains("javadoc")) {
                    return entry;
                }
            }
        }
        throw new IllegalStateException("classpath artifact matching " + namePrefix + "*" + nameSuffix + " not found");
    }

    private static List<Path> listCachedResources(Path testHome) throws IOException {
        Path cache = testHome.resolve(".cache/icedtea-web/cache");
        if (!Files.isDirectory(cache)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(cache)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".jar") || name.endsWith(".jar.gz");
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static String allCacheFiles(Path testHome) {
        Path cache = testHome.resolve(".cache/icedtea-web/cache");
        if (!Files.isDirectory(cache)) {
            return "(no cache dir)";
        }
        try (Stream<Path> walk = Files.walk(cache)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> cache.relativize(p).toString() + "  (" + sizeMb(p) + ")")
                    .sorted()
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "(unreadable cache)";
        }
    }

    private static String sizeMb(Path p) {
        try {
            return (Files.size(p) / (1024 * 1024)) + "MB";
        } catch (IOException e) {
            return "?";
        }
    }
}
