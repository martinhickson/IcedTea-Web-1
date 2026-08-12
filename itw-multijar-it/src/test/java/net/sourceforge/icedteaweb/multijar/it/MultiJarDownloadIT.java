package net.sourceforge.icedteaweb.multijar.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import net.sourceforge.icedteaweb.multijar.MultijarMain;
import net.sourceforge.jnlp.util.JavaVersionUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import io.undertow.Undertow;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.util.MimeMappings;

/**
 * Integration test for the download pipeline with many medium-sized jars.
 *
 * <p>Scenario: an Undertow HTTP server (127.0.0.1, ephemeral port) serves a JNLP
 * plus 20 jars of ~20 MB each (random filler + a per-jar class + a third-party
 * class from commons-lang3). A real {@code javaws} is launched (uber jar +
 * {@code JavawsUberLauncher}, {@code -Xnofork} so the app runs in-process) and
 * must download all 20 jars concurrently and load a class from every one of
 * them — the exact shape that exercises the Apache client connection pool
 * (max 6 per route) under load. Regression guard for pool starvation / leaked
 * connections on many medium resources.
 */
@Timeout(400)
class MultiJarDownloadIT {

    private static final int JAR_COUNT = MultijarMain.JAR_COUNT;
    private static final int JAR_SIZE_MB = 20;
    private static final long JAR_SIZE_BYTES = JAR_SIZE_MB * 1024L * 1024L;

    @TempDir
    Path tmp;

    @Test
    void downloadsAndLaunchesTwentyTwentyMbJars() throws Exception {
        Path webRoot = tmp.resolve("web");
        Files.createDirectories(webRoot.resolve("lib"));

        byte[] filler = new byte[(int) JAR_SIZE_BYTES];
        new Random(0xC0FFEE).nextBytes(filler);

        String commonsLang3Jar = findClasspathArtifact("commons-lang3-", ".jar");
        byte[] stringUtils = readZipEntry(commonsLang3Jar, "org/apache/commons/lang3/StringUtils.class");
        byte[] multijarMain = readResourceClass(MultijarMain.class);

        Map<Integer, byte[]> jarClasses = compileJarClasses();
        for (int i = 0; i < JAR_COUNT; i++) {
            writeJar(webRoot.resolve("lib/jar" + i + ".jar"), i == 0, i,
                    multijarMain, jarClasses, stringUtils, filler);
        }

        Undertow server = startServer(webRoot);
        int port;
        try {
            port = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
        } catch (Exception e) {
            server.stop();
            throw e;
        }
        Path jnlp = webRoot.resolve("app.jnlp");
        writeJnlp(jnlp, port);

        Path testHome = tmp.resolve("home");
        String jnlpUrl = "http://127.0.0.1:" + port + "/app.jnlp";
        String output = launchJavaws(jnlpUrl, testHome, port);

        // The app proves every jar downloaded and became loadable.
        assertThat(output)
                .as("launch output")
                .contains("ITW_MULTIJAR_SUCCESS loaded=" + JAR_COUNT)
                .contains("thirdparty=StringUtils");
        // Pool starvation / leaked connections surface as timeouts or hang -> timeout fails the test.

        // Second piece of evidence: 20 jar files in the cache at ~20 MB each.
        List<Path> cached = listJarsInCache(testHome);
        assertThat(cached).as("cached jar files").hasSize(JAR_COUNT);
        long totalBytes = 0;
        for (Path jar : cached) {
            totalBytes += Files.size(jar);
        }
        assertThat(totalBytes)
                .as("total cached jar bytes for %d x %d MB", JAR_COUNT, JAR_SIZE_MB)
                .isGreaterThanOrEqualTo((long) (JAR_COUNT * JAR_SIZE_BYTES * 0.95));

        server.stop();
    }

    // ------------------------------------------------------------------ setup

    private Undertow startServer(Path webRoot) {
        MimeMappings mappings = MimeMappings.builder()
                .addMapping("jnlp", "application/x-java-jnlp-file")
                .addMapping("jar", "application/java-archive")
                .build();
        ResourceHandler handler = new ResourceHandler(
                PathResourceManager.builder()
                        .setBase(webRoot.toAbsolutePath())
                        .build());
        handler.setMimeMappings(mappings);
        Undertow server = Undertow.builder()
                .addHttpListener(0, "127.0.0.1")
                .setHandler(handler)
                .build();
        server.start();
        return server;
    }

    private void writeJnlp(Path jnlp, int port) throws IOException {
        StringBuilder resources = new StringBuilder();
        for (int i = 0; i < JAR_COUNT; i++) {
            resources.append("        <jar href=\"lib/jar").append(i).append(".jar\"")
                    .append(i == 0 ? " main=\"true\"" : "").append("/>\n");
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jnlp spec=\"1.0+\" codebase=\"http://127.0.0.1:" + port + "/\" href=\"app.jnlp\">\n"
                + "  <information>\n"
                + "    <title>ITW Multi-Jar</title>\n"
                + "    <vendor>IcedTea-Web IT</vendor>\n"
                + "  </information>\n"
                + "  <resources>\n"
                + "    <j2se version=\"1.8+\"/>\n"
                + resources
                + "  </resources>\n"
                + "  <application-desc main-class=\"net.sourceforge.icedteaweb.multijar.MultijarMain\"/>\n"
                + "</jnlp>\n";
        Files.write(jnlp, xml.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Compiles the 20 per-jar classes ({@code Jar_<i>}) in-memory so the test is
     * self-contained (no 20 near-identical checked-in sources).
     */
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
        java.util.LinkedHashMap<Integer, byte[]> out = new java.util.LinkedHashMap<>();
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

    // ------------------------------------------------------------------ launch

    private String launchJavaws(String jnlpUrl, Path testHome, int port) throws Exception {
        String javaHome = System.getProperty("java.home");
        String java = Paths.get(javaHome, "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
        String uberJar = findClasspathArtifact("icedtea-web-", "-uber.jar");
        String agentJar = findClasspathArtifact("byte-buddy-agent-", ".jar");

        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        cmd.add("-Xms8m");
        List<String> vmArgs = new ArrayList<>();
        JavaVersionUtils.addSecurityManagerCompatibilityArgs(vmArgs, javaHome);
        JavaVersionUtils.addModularJdkCompatibilityArgs(vmArgs, javaHome);
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

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("HOME", testHome.toString());
        env.put("XDG_CONFIG_HOME", testHome.resolve(".config").toString());
        env.put("XDG_CACHE_HOME", testHome.resolve(".cache").toString());
        env.put("XDG_DATA_HOME", testHome.resolve(".local/share").toString());
        env.put("JAVA_HOME", javaHome);

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
                // process died
            }
        });
        reader.start();

        long timeoutSeconds = Long.getLong("itw.multijar.timeout.seconds", 300L);
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        reader.join(5_000);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        String out;
        synchronized (output) {
            out = output.toString();
        }
        if (!finished) {
            fail("javaws did not finish within " + timeoutSeconds + "s (pool starvation?); output so far:\n" + out);
        }
        if (!out.contains(MultijarMain.MARKER)) {
            fail("app did not reach " + MultijarMain.MARKER + "; exit=" + process.exitValue() + ";\n" + out);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

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
        try (ZipFile zip = new ZipFile(zipPath)) {
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

    private static List<Path> listJarsInCache(Path testHome) throws IOException {
        Path cache = testHome.resolve(".cache/icedtea-web/cache");
        if (!Files.isDirectory(cache)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(cache)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }
}
