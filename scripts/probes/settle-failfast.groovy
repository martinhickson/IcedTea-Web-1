#!/usr/bin/env groovy
/**
 * Fast probe: settleSlotBad / failFastOutOfRetries against live classes.
 * Run: scripts/run-itw-groovy.sh --compile-java settle-failfast
 */
import java.net.URL
import net.sourceforge.jnlp.Version
import net.sourceforge.jnlp.cache.Resource
import net.sourceforge.jnlp.cache.ResourceDownloader
import net.sourceforge.jnlp.cache.UpdatePolicy
import net.sourceforge.jnlp.cache.download.JarGroupState
import net.sourceforge.jnlp.cache.download.JarState

def url = { String s -> new URL(s) }

// --- fail-fast absorbs RETRY_PENDING ---
def u = url("http://localhost/probe-fail-fast-${System.nanoTime()}.jar")
def r = Resource.getResource(u, new Version("1.0"), UpdatePolicy.NEVER)
def group = JarGroupState.forJars([u])
def slot = group.slot(0)
r.jarSlot = slot
assert !slot.settleUnusable(1L)
assert slot.state() == JarState.RETRY_PENDING

def d = new ResourceDownloader(r, new Object())
d.failFastOutOfRetries()
assert slot.state() == JarState.SETTLED_BAD
assert r.terminalState == JarState.SETTLED_BAD
assert group.done().isDone()
println "OK failFastOutOfRetries absorbs RETRY_PENDING"

// --- pack wire hint precedence ---
assert ResourceDownloader.packWireHintBytes(100L, 200L, 300L) == 100L
assert ResourceDownloader.packWireHintBytes(0L, 200L, 300L) == 200L
assert ResourceDownloader.packWireHintBytes(0L, -1L, 300L) == 300L
assert ResourceDownloader.packWireHintBytes(0L, -1L, -1L) == 0L
println "OK packWireHintBytes precedence"

// --- UrlRequestResult toString null-safe ---
def urr = new ResourceDownloader.UrlRequestResult(url("http://localhost/a.jar"))
urr.result = 200
def text = urr.toString()
assert text.contains("length: null") : text
assert !urr.shouldRedirect()
urr.result = 302
assert urr.shouldRedirect()
println "OK UrlRequestResult helpers"
println "ALL PROBES PASSED"
System.exit(0)
