#!/usr/bin/env groovy
/**
 * Fast probe: ResourceTracker wait/getCacheFile/requeue without HTTP downloads.
 * Run: scripts/run-itw-groovy.sh --compile-java tracker-wait-cache
 */
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import net.sourceforge.jnlp.cache.Resource
import net.sourceforge.jnlp.cache.ResourceTracker
import net.sourceforge.jnlp.cache.UpdatePolicy
import net.sourceforge.jnlp.cache.download.JarState

class NoDownloadTracker extends ResourceTracker {
    final AtomicInteger downloadStarts = new AtomicInteger()
    @Override
    protected void startDownloadThread(Resource resource) {
        downloadStarts.incrementAndGet()
    }
}

def tmp = Files.createTempDirectory("itw-tracker-probe")
def file = tmp.resolve("ready.bin").toFile()
file.text = "payload"
def u = file.toURI().toURL()

def rt = new NoDownloadTracker()
rt.addResource(u, null, null, UpdatePolicy.NEVER)
def r = Resource.getResource(u, null, UpdatePolicy.NEVER)
r.localFile = file
r.terminalState = JarState.GOOD

assert rt.waitForResource(u, 2000L)
assert rt.getCacheFile(u).canonicalFile == file.canonicalFile
assert rt.getCacheURL(u).toString().contains("ready.bin")
assert rt.startResource(u)
assert rt.downloadStarts.get() == 0
rt.logDownloadStats()
println "OK wait/getCacheFile on already-GOOD local file"

def missingUrl = new URL("http://localhost/requeue-${System.nanoTime()}.bin")
def rt2 = new NoDownloadTracker()
rt2.addResource(missingUrl, null, null, UpdatePolicy.NEVER)
def r2 = Resource.getResource(missingUrl, null, UpdatePolicy.NEVER)
r2.localFile = tmp.resolve("missing.bin").toFile()
r2.terminalState = JarState.SETTLED_BAD
assert rt2.resolveUsableLocalFile(r2, missingUrl) == null
assert rt2.requeueUnusableTerminal(r2)
assert r2.terminalState == null
assert r2.jarSlot == null
assert rt2.downloadStarts.get() == 1
assert !rt2.requeueUnusableTerminal(r2)
println "OK requeueUnusableTerminal one-shot actually starts download"

println "ALL PROBES PASSED"
System.exit(0)
