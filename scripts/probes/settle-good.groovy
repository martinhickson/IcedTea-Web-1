#!/usr/bin/env groovy
/**
 * Fast probe: settleSlotGood paths (no slot / fromCache ghost / non-jar success).
 * Run: scripts/run-itw-groovy.sh --compile-java settle-good
 */
import java.nio.file.Files
import java.net.URL
import net.sourceforge.jnlp.cache.Resource
import net.sourceforge.jnlp.cache.ResourceDownloader
import net.sourceforge.jnlp.cache.UpdatePolicy
import net.sourceforge.jnlp.cache.download.JarGroupState
import net.sourceforge.jnlp.cache.download.JarState

def url = { String s -> new URL(s) }
def tmp = Files.createTempDirectory("itw-settle-good")

// non-jar with no slot → terminal GOOD
def plainUrl = url("http://localhost/probe-plain-${System.nanoTime()}.bin")
def plain = Resource.getResource(plainUrl, null, UpdatePolicy.NEVER)
def plainFile = tmp.resolve("plain.bin").toFile()
plainFile.text = "hello"
plain.localFile = plainFile
plain.jarSlot = null
new ResourceDownloader(plain, new Object()).settleSlotGood(false)
assert plain.terminalState == JarState.GOOD
println "OK settleSlotGood without slot"

// fromCache ghost: no localFile → skip integrity, park RETRY_PENDING (not GOOD)
def ghostUrl = url("http://localhost/probe-ghost-${System.nanoTime()}.jar")
def ghost = Resource.getResource(ghostUrl, null, UpdatePolicy.NEVER)
def group = JarGroupState.forJars([ghostUrl])
ghost.jarSlot = group.slot(0)
ghost.localFile = null
new ResourceDownloader(ghost, new Object()).settleSlotGood(true)
assert ghost.jarSlot.state() == JarState.RETRY_PENDING
assert ghost.terminalState == null
println "OK settleSlotGood fromCache ghost parks retry"

println "ALL PROBES PASSED"
System.exit(0)
