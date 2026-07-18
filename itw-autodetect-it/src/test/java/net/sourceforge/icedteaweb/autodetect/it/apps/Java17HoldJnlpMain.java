package net.sourceforge.icedteaweb.autodetect.it.apps;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal JNLP main that records success and holds so the control panel can
 * observe the configured JDK after Autodetect.
 */
public final class Java17HoldJnlpMain {

    public static void main(String[] args) throws Exception {
        String marker = System.getProperty("itw.test.success.marker");
        if (marker == null || marker.trim().isEmpty()) {
            marker = System.getenv("ITW_TEST_SUCCESS_MARKER");
        }
        String payload = "ok jdk=" + System.getProperty("java.version")
                + " class=" + System.getProperty("java.class.version")
                + " home=" + System.getProperty("java.home")
                + " vendor=" + System.getProperty("java.vendor");
        System.out.println("ITW_AUTODETECT_IT_SUCCESS " + payload);
        System.out.flush();
        if (marker != null && !marker.trim().isEmpty()) {
            File markerFile = new File(marker);
            File parent = markerFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(markerFile)) {
                out.write(payload.getBytes(StandardCharsets.UTF_8));
            }
        }
        int holdSeconds = Integer.getInteger("itw.test.hold.seconds", 120);
        Thread.sleep(holdSeconds * 1000L);
    }
}
