package net.sourceforge.jnlp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.sourceforge.jnlp.cache.download.ConnectionTiming;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class OracleHttpClientTest {

    @TempDir
    Path tmp;

    @Test
    public void fileGetRecordsConnectTiming() throws Exception {
        Path file = tmp.resolve("oracle.txt");
        Files.write(file, "oracle-file\n".getBytes(StandardCharsets.UTF_8));
        ConnectionTiming timing = new ConnectionTiming();
        OracleHttpClient client = new OracleHttpClient();
        try (HttpResponse response = client.open(file.toUri().toURL(), "GET", null, timing)) {
            assertEquals(200, response.getStatusCode());
            try (InputStream in = response.getBody()) {
                assertEquals("oracle-file\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertTrue(timing.connectStartMillis > 0, "connect start must be stamped");
        assertTrue(timing.connectEndMillis >= timing.connectStartMillis);
    }
}
