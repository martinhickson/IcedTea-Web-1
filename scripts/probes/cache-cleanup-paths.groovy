#!/usr/bin/env groovy
/**
 * Fast probe: cleanup deletes only catalog paths and ignores already-gone.
 * Uses the same CACHE_DIR + getInstance() fixture as CacheUtilKeepSlotSidecarTest.
 * Run: scripts/run-itw-groovy.sh --compile-java cache-cleanup-paths
 */
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import net.sourceforge.jnlp.cache.CacheEntryMeta
import net.sourceforge.jnlp.cache.CacheLRUWrapper
import net.sourceforge.jnlp.cache.CacheUtil
import net.sourceforge.jnlp.config.PathsAndFiles

File tempCache = new File(System.getProperty("java.io.tmpdir"),
        "itw-cleanup-probe-" + System.nanoTime())
assert tempCache.mkdirs() : "mkdir ${tempCache}"
String original = PathsAndFiles.CACHE_DIR.getFullPath()
PathsAndFiles.CACHE_DIR.setValue(tempCache.getAbsolutePath())
try {
    URL source = new URL("http://127.0.0.1:4200/jnlp/console/app.jar")
    File jar = CacheUtil.makeNewCacheFile(source, null)
    Files.write(jar.toPath(), "jar-bytes".getBytes(StandardCharsets.UTF_8))
    File sidecar = new File(jar.getPath() + ".pack.gz.download.deadbeef")
    Files.write(sidecar.toPath(), "pack-bytes".getBytes(StandardCharsets.UTF_8))

    CacheLRUWrapper lru = CacheLRUWrapper.getInstance()
    markForDelete(lru, jar.getPath())

    File gone = new File(jar.getParentFile(), "already-gone.jar")
    Files.write(gone.toPath(), "gone".getBytes(StandardCharsets.UTF_8))
    markForDelete(lru, gone.getPath())
    assert gone.delete()

    CacheUtil.cleanCacheOnShutdown()

    assert !jar.exists() : "marked jar must be deleted"
    assert sidecar.isFile() : "unmarked sidecar must stay"
    assert lru.getMetaByPath(gone.getPath()) == null : "already-gone row must drop"
    println "OK cache-cleanup-paths jarGone sidecarKept alreadyGoneIgnored"
} finally {
    PathsAndFiles.CACHE_DIR.setValue(original)
    if (tempCache.exists()) {
        tempCache.deleteDir()
    }
}
System.exit(0)

static void markForDelete(CacheLRUWrapper lru, String path) {
    lru.lock()
    try {
        lru.load()
        if (lru.getMetaByPath(path) == null && !lru.containsValue(path)) {
            boolean added = lru.addEntry(lru.generateKey(path), path)
            if (!added) {
                throw new IllegalStateException("addEntry failed for " + path
                        + " sqlite=" + lru.getSqliteCatalogFile())
            }
        }
        CacheEntryMeta meta = new CacheEntryMeta()
        meta.path = path
        meta.markedDelete = true
        lru.putMeta(meta)
        lru.store()
    } finally {
        lru.unlock()
    }
}
