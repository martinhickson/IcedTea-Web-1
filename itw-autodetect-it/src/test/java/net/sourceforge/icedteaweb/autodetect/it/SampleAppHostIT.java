package net.sourceforge.icedteaweb.autodetect.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Hosts the sample JNLP on Undertow and stays up so that the printed URL
 * (or landing-page link) can be launched manually. {@code javaws} is not started
 * by this test.
 */
class SampleAppHostIT {

    private static final String JNLP_NAME = "java17-autodetect.jnlp";

    private UndertowJnlpServer server;
    private Path marker;

    @BeforeEach
    void setUp() throws Exception {
        marker = Files.createTempDirectory("itw-sample-app-marker").resolve("success.marker");
        server = UndertowJnlpServer.start(
                AutodetectTestSupport.sampleJar().toPath(), marker, JNLP_NAME);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
        if (marker != null && marker.getParent() != null) {
            try {
                Files.deleteIfExists(marker);
                Files.deleteIfExists(marker.getParent());
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    @Test
    @EnabledIf("net.sourceforge.icedteaweb.autodetect.it.SampleAppHostIT#sampleBuilt")
    void hostSampleAppAndWait() throws Exception {
        String landing = server.baseUrl();
        String jnlp = server.jnlpUrl(JNLP_NAME);

        System.out.println();
        System.out.println("============================================================");
        System.out.println(" ITW sample app host is running (Ctrl+C / stop Maven to end)");
        System.out.println(" Landing page:  " + landing);
        System.out.println(" JNLP link:     " + jnlp);
        System.out.println("============================================================");
        System.out.println();
        System.out.flush();

        // Block until the Failsafe process is killed (profile leaves the server up).
        long waitSeconds = Long.getLong("itw.sample.app.wait.seconds", TimeUnit.DAYS.toSeconds(1));
        new CountDownLatch(1).await(waitSeconds, TimeUnit.SECONDS);
    }

    static boolean sampleBuilt() {
        return AutodetectTestSupport.sampleBuilt();
    }
}
