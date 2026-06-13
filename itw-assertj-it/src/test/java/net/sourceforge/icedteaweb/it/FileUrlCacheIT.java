package net.sourceforge.icedteaweb.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.sourceforge.jnlp.cache.CacheUtil;
import net.sourceforge.jnlp.cache.ResourceTracker;
import net.sourceforge.jnlp.cache.UpdatePolicy;
import net.sourceforge.jnlp.config.PathsAndFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FileUrlCacheIT {

    private String originalCacheDir;
    private boolean originalLegacyFileUrlCacheBypass;

    @BeforeEach
    void setUp() throws Exception {
        originalCacheDir = PathsAndFiles.CACHE_DIR.getFullPath();
        originalLegacyFileUrlCacheBypass = CacheUtil.USE_LEGACY_FILE_URL_CACHE_BYPASS;
        PathsAndFiles.CACHE_DIR.setValue(ControlPanelTestSupport.icedteaWebCacheDir().getAbsolutePath());
        ControlPanelTestSupport.resetCacheDir();
        CacheUtil.USE_LEGACY_FILE_URL_CACHE_BYPASS = false;
    }

    @AfterEach
    void tearDown() throws Exception {
        CacheUtil.USE_LEGACY_FILE_URL_CACHE_BYPASS = originalLegacyFileUrlCacheBypass;
        ControlPanelTestSupport.resetCacheDir();
        PathsAndFiles.CACHE_DIR.setValue(originalCacheDir);
    }

    @Test
    void fileUrlResourceIsCopiedIntoNormalCacheByDefault() throws Exception {
        String expected = "file-url-cache";
        Path source = Files.createTempFile("itw-file-url-cache", ".txt");
        Files.write(source, expected.getBytes(StandardCharsets.UTF_8));
        URL url = source.toUri().toURL();

        File cached = CacheUtil.getCachedResourceFile(url, null, UpdatePolicy.FORCE);

        assertThat(cached).isFile();
        assertThat(cached.getCanonicalFile()).isNotEqualTo(source.toFile().getCanonicalFile());
        assertThat(new File(cached.getPath() + ".info")).isFile();
        assertThat(Files.readString(cached.toPath())).isEqualTo(expected);
        assertThat(CacheUtil.isCacheable(url, null)).isTrue();
    }

    @Test
    void legacyFileUrlCacheBypassStillReturnsOriginalFile() throws Exception {
        String expected = "legacy-file-url";
        Path source = Files.createTempFile("itw-file-url-legacy", ".txt");
        Files.write(source, expected.getBytes(StandardCharsets.UTF_8));
        URL url = source.toUri().toURL();
        CacheUtil.USE_LEGACY_FILE_URL_CACHE_BYPASS = true;

        ResourceTracker tracker = new ResourceTracker();
        tracker.addResource(url, null, null, UpdatePolicy.FORCE);
        File resolved = tracker.getCacheFile(url);

        assertThat(CacheUtil.isCacheable(url, null)).isFalse();
        assertThat(resolved.getCanonicalFile()).isEqualTo(source.toFile().getCanonicalFile());
        assertThat(Files.readString(resolved.toPath())).isEqualTo(expected);
    }
}
