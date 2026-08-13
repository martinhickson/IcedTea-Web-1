#!/usr/bin/env groovy
/**
 * TTFB is connect→first body byte, never group start.
 * Run: scripts/run-itw-groovy.sh ttfb-connect
 */
import net.sourceforge.jnlp.cache.download.JarGroupState
import net.sourceforge.jnlp.cache.download.JarSlot

def url = new URL("http://localhost/late.jar")
def g = JarGroupState.forJars([url])
JarSlot s = g.slot(0)
s.startMillis = 0L
s.onConnect(200_000L, 200_050L)
s.onFirstByte(200_080L)
s.onLastByte(201_000L)
s.addTransferred(4096)
assert s.settleGood(201_500L, false)
assert s.ttfbMillis() == 30L : "ttfb=${s.ttfbMillis()} must be firstByte-connectEnd, not ${s.firstByteMillis}-groupStart"
assert s.durationMillis() == 1500L : "dur=${s.durationMillis()} must be end-connectStart"
assert s.transferMillis() == 920L
println "OK ttfb=${s.ttfbMillis()}ms dur=${s.durationMillis()}ms transfer=${s.transferMillis()}ms"
System.exit(0)
