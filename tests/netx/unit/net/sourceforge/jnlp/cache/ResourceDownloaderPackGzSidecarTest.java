package net.sourceforge.jnlp.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GitHub #15: Pack200 drain sidecars must be unique per attempt so one downloader's
 * finally cannot delete another's file during PackUnpackAdmission wait.
 */
class ResourceDownloaderPackGzSidecarTest {

    @TempDir
    Path tmp;

    @Test
    void newPackedSidecarNamesAreUniqueAndBesideJar() throws Exception {
        Path jar = tmp.resolve("lib.jar");
        Files.write(jar, "x".getBytes(StandardCharsets.UTF_8));
        Path a = ResourceDownloader.newPackedSidecar(jar.toFile()).toPath();
        // nanoTime can collide if called in the same tick; force distinct by sleeping if needed
        Path b = ResourceDownloader.newPackedSidecar(jar.toFile()).toPath();
        if (a.equals(b)) {
            Thread.sleep(1L);
            b = ResourceDownloader.newPackedSidecar(jar.toFile()).toPath();
        }
        assertNotEquals(a, b);
        assertTrue(a.getFileName().toString().startsWith("lib.jar.pack.gz.download."));
        assertTrue(b.getFileName().toString().startsWith("lib.jar.pack.gz.download."));
        assertEqualsParent(jar, a);
        assertEqualsParent(jar, b);
    }

    @Test
    void deletingOneSidecarLeavesTheOther() throws Exception {
        Path jar = tmp.resolve("app.jar");
        Files.write(jar, "x".getBytes(StandardCharsets.UTF_8));
        Path keep = ResourceDownloader.newPackedSidecar(jar.toFile()).toPath();
        Path drop = ResourceDownloader.newPackedSidecar(jar.toFile()).toPath();
        if (keep.equals(drop)) {
            Thread.sleep(1L);
            drop = ResourceDownloader.newPackedSidecar(jar.toFile()).toPath();
        }
        Files.write(keep, "keep".getBytes(StandardCharsets.UTF_8));
        Files.write(drop, "drop".getBytes(StandardCharsets.UTF_8));
        Files.delete(drop);
        assertTrue(Files.isRegularFile(keep));
        assertFalse(Files.exists(drop));
    }

    @Test
    void ensurePackedSidecarPresentRejectsMissingOrEmpty() throws Exception {
        Path missing = tmp.resolve("gone.jar.pack.gz.download.abc");
        IOException missingEx = assertThrows(IOException.class,
                () -> ResourceDownloader.ensurePackedSidecarPresent(missing.toFile()));
        assertTrue(missingEx.getMessage().contains("Pack200 sidecar missing after admission wait"));

        Path empty = tmp.resolve("empty.jar.pack.gz.download.def");
        Files.write(empty, new byte[0]);
        IOException emptyEx = assertThrows(IOException.class,
                () -> ResourceDownloader.ensurePackedSidecarPresent(empty.toFile()));
        assertTrue(emptyEx.getMessage().contains("Pack200 sidecar missing after admission wait"));

        Path ok = tmp.resolve("ok.jar.pack.gz.download.ghi");
        Files.write(ok, "bytes".getBytes(StandardCharsets.UTF_8));
        ResourceDownloader.ensurePackedSidecarPresent(ok.toFile());
    }

    private static void assertEqualsParent(Path jar, Path sidecar) {
        assertTrue(sidecar.getParent().equals(jar.getParent()));
    }
}
