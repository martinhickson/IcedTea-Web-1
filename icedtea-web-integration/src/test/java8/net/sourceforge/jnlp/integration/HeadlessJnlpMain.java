package net.sourceforge.jnlp.integration;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Minimal JNLP application used by multi-JDK integration tests (no GUI).
 */
public final class HeadlessJnlpMain {

    public static void main(String[] args) throws Exception {
        printDiagnostics(args);

        String marker = System.getProperty("itw.test.success.marker");
        if (marker == null || marker.trim().isEmpty()) {
            marker = System.getenv("ITW_TEST_SUCCESS_MARKER");
        }
        String payload = "ok jdk=" + System.getProperty("java.version")
                + " vendor=" + System.getProperty("java.vendor");
        System.out.println("ITW_INTEGRATION_SUCCESS " + payload);
        System.out.flush();
        if (marker != null && !marker.trim().isEmpty()) {
            try {
                File markerFile = new File(marker);
                File parent = markerFile.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (FileOutputStream out = new FileOutputStream(markerFile)) {
                    out.write(payload.getBytes(StandardCharsets.UTF_8));
                }
            } catch (SecurityException e) {
                System.out.println("ITW_INTEGRATION_MARKER_SKIPPED " + e.getMessage());
            }
        }
        System.out.println("ITW JNLP APP: about to exit main method JNLP app");
        System.out.flush();
    }

    private static void printDiagnostics(String[] args) {
        System.out.println("================================================================================");
        System.out.println("ITW JNLP APP: main method of the JNLP app has been invoked");
        System.out.println("ITW JNLP APP: detailed diagnostics from inside the launched application");
        System.out.println("================================================================================");
        System.out.println("Arguments: " + Arrays.toString(args));
        System.out.println("Current directory: " + new File(".").getAbsolutePath());
        System.out.println("Main thread: " + Thread.currentThread().getName());
        System.out.println("Context class loader: " + Thread.currentThread().getContextClassLoader());
        System.out.println("Application class loader: " + HeadlessJnlpMain.class.getClassLoader());
        System.out.println("Headless mode: " + System.getProperty("java.awt.headless"));
        System.out.println();

        System.out.println("---- Java runtime ----");
        printProperty("java.version");
        printProperty("java.vendor");
        printProperty("java.vendor.version");
        printProperty("java.home");
        printProperty("java.vm.name");
        printProperty("java.vm.vendor");
        printProperty("java.vm.version");
        printProperty("java.runtime.name");
        printProperty("java.runtime.version");
        printProperty("java.class.version");
        printProperty("os.name");
        printProperty("os.arch");
        printProperty("os.version");
        printProperty("user.name");
        printProperty("user.home");
        printProperty("user.dir");
        printProperty("file.encoding");
        printProperty("sun.java.command");
        printProperty("sun.boot.class.path");
        printProperty("jdk.module.path");
        printProperty("java.class.path");
        System.out.println();

        System.out.println("---- IcedTea-Web and JNLP system properties ----");
        printMatchingProperties("icedtea");
        printMatchingProperties("itw.");
        printMatchingProperties("jnlp");
        printMatchingProperties("javawebstart");
        printMatchingProperties("deployment.");
        System.out.println();

        System.out.println("---- Useful environment variables ----");
        printUsefulEnvironment();
        System.out.println();

        System.out.println("---- Runtime sizing ----");
        Runtime runtime = Runtime.getRuntime();
        System.out.println("Available processors: " + runtime.availableProcessors());
        System.out.println("Max memory bytes: " + runtime.maxMemory());
        System.out.println("Total memory bytes: " + runtime.totalMemory());
        System.out.println("Free memory bytes: " + runtime.freeMemory());
        System.out.println("================================================================================");
    }

    private static void printProperty(String name) {
        System.out.println(name + "=" + System.getProperty(name));
    }

    private static void printMatchingProperties(String prefixOrNeedle) {
        Properties properties = System.getProperties();
        Map<String, String> matches = new TreeMap<>();
        for (String name : properties.stringPropertyNames()) {
            String lowerName = name.toLowerCase();
            String lowerNeedle = prefixOrNeedle.toLowerCase();
            if (lowerName.startsWith(lowerNeedle) || lowerName.contains(lowerNeedle)) {
                matches.put(name, properties.getProperty(name));
            }
        }
        if (matches.isEmpty()) {
            System.out.println(prefixOrNeedle + "*=<none>");
            return;
        }
        for (Map.Entry<String, String> entry : matches.entrySet()) {
            System.out.println(entry.getKey() + "=" + entry.getValue());
        }
    }

    private static void printUsefulEnvironment() {
        Map<String, String> environment = new TreeMap<>(System.getenv());
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String name = entry.getKey();
            if (isUsefulEnvironmentVariable(name)) {
                System.out.println(name + "=" + entry.getValue());
            }
        }
    }

    private static boolean isUsefulEnvironmentVariable(String name) {
        return name.startsWith("ITW")
                || name.startsWith("ICEDTEA")
                || name.startsWith("JAVA")
                || name.startsWith("JDK")
                || name.startsWith("JNLP")
                || name.startsWith("XDG")
                || "PATH".equals(name)
                || "HOME".equals(name)
                || "USER".equals(name)
                || "LOGNAME".equals(name)
                || "SHELL".equals(name)
                || "DISPLAY".equals(name)
                || "WAYLAND_DISPLAY".equals(name)
                || "DESKTOP_SESSION".equals(name);
    }
}
