#!/usr/bin/env groovy
/**
 * HEAD Last-Modified / wire length settles from cache (no GET).
 * Run: scripts/run-itw-groovy.sh --compile-java head-cache-hit
 */
import java.nio.file.Files
import java.net.URL
import net.sourceforge.jnlp.cache.Resource
import net.sourceforge.jnlp.cache.ResourceDownloader
import net.sourceforge.jnlp.cache.SizeFirstDownloadQueue
import net.sourceforge.jnlp.cache.UpdatePolicy
import net.sourceforge.jnlp.cache.download.JarGroupState
import net.sourceforge.jnlp.cache.download.JarState
import net.sourceforge.jnlp.cache.download.MetricKind

SizeFirstDownloadQueue.resetForTests()
def tmp = Files.createTempDirectory("itw-head-cache")
def u = new URL("http://head-cache.probe/plain-${System.nanoTime()}.bin")
def r = Resource.getResource(u, null, UpdatePolicy.ALWAYS)
def file = tmp.resolve("plain.bin").toFile()
def body = "cached-bytes".bytes
file.bytes = body
r.localFile = file
def group = JarGroupState.forJars([u])
r.jarSlot = group.slot(0)

SizeFirstDownloadQueue.recordHead(r, u, body.length, 1_700_000_000_000L)
def head = SizeFirstDownloadQueue.headMeta(r)
assert head != null
assert head.contentLength == body.length
assert new ResourceDownloader(r, new Object()).trySettleFromHead(head)
assert r.terminalState == JarState.GOOD
assert group.slot(0).kind == MetricKind.CACHED
println "OK HEAD size match settles CACHED"

def forceUrl = new URL("http://head-cache.probe/force-${System.nanoTime()}.bin")
def force = Resource.getResource(forceUrl, null, UpdatePolicy.FORCE)
force.localFile = file
SizeFirstDownloadQueue.recordHead(force, forceUrl, body.length, 1L)
assert !ResourceDownloader.peekCacheHit(force, SizeFirstDownloadQueue.headMeta(force))
println "OK FORCE does not skip GET"

SizeFirstDownloadQueue.resetForTests()
println "ALL PROBES PASSED"
System.exit(0)
