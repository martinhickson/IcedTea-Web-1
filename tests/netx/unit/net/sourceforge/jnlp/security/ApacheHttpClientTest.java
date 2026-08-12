package net.sourceforge.jnlp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the non-HTTP scheme fallback: Apache HttpClient only understands http(s),
 * so file:/jar: URLs must route through java.net.URLConnection (as the classic
 * client did). Regression: "javaws /path/to/app.jnlp" failed with
 * "Target host is not specified" under the apache default.
 */
public class ApacheHttpClientTest {

    @TempDir
    Path tmp;

    @Test
    public void fileSchemeFallsBackToUrlConnection() throws Exception {
        Path file = tmp.resolve("hello.txt");
        Files.write(file, "hello file-url\n".getBytes(StandardCharsets.UTF_8));

        ApacheHttpClient client = new ApacheHttpClient();
        try (HttpResponse response = client.open(file.toUri().toURL(), "GET", null, null)) {
            assertEquals(200, response.getStatusCode());
            String body;
            try (InputStream in = response.getBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertEquals("hello file-url\n", body);
        }
    }
}
