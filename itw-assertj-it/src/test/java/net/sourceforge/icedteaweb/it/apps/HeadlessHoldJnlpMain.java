package net.sourceforge.icedteaweb.it.apps;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Headless JNLP test application that stays alive for Running Apps integration tests.
 */
public final class HeadlessHoldJnlpMain {

    public static void main(String[] args) throws Exception {
        String marker = System.getProperty("itw.test.success.marker");
        if (marker == null || marker.trim().isEmpty()) {
            marker = System.getenv("ITW_TEST_SUCCESS_MARKER");
        }
        String payload = "ok jdk=" + System.getProperty("java.version")
                + " class=" + System.getProperty("java.class.version")
                + " vendor=" + System.getProperty("java.vendor")
                + " home=" + System.getProperty("java.home");
        System.out.println("ITW_INTEGRATION_SUCCESS " + payload);
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
