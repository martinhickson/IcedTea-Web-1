#!/usr/bin/env groovy
/**
 * Fast probe: PackUnpackAdmission reserve math + default-reserve serialization.
 * Run: scripts/run-itw-groovy.sh pack-admission
 */
import net.sourceforge.jnlp.cache.download.PackUnpackAdmission

def admit = PackUnpackAdmission.getInstance()
long wire = 17_200_000L
long reserve = PackUnpackAdmission.estimateReserveBytes(wire, -1L)
assert reserve == wire * 40L : "expected 40× wire, got ${reserve}"

long unknown = PackUnpackAdmission.estimateReserveBytes(0L, -1L)
assert unknown == 256L * 1024L * 1024L : "default reserve should be 256MiB, got ${unknown}"

println "OK pack-admission reserve wire=${wire} → ${reserve} default=${unknown}"
println "activeUnpackers=${admit.activeUnpackers()} inFlightBytes=${admit.inFlightBytes()}"
System.exit(0)
