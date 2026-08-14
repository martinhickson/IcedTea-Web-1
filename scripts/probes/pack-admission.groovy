#!/usr/bin/env groovy
/**
 * Fast probe: PackUnpackAdmission reserve math — two largest fit, third waits.
 * Run: scripts/run-itw-groovy.sh pack-admission
 */
import net.sourceforge.jnlp.cache.download.PackUnpackAdmission

def admit = PackUnpackAdmission.getInstance()
admit.setWireMultiplierOverride(30)
admit.setBudgetOverrideBytes(1800L << 20)

long packA = PackUnpackAdmission.estimateReserveBytes(27_742_491L, 0L)
long packB = PackUnpackAdmission.estimateReserveBytes(24_062_954L, 0L)
long packC = PackUnpackAdmission.estimateReserveBytes(17_193_474L, 0L)
assert packA == 27_742_491L * 30L : "packA reserve ${packA}"
assert packB == 24_062_954L * 30L : "packB reserve ${packB}"
assert packA + packB < admit.budgetBytes() : "two largest must fit ${packA + packB} vs ${admit.budgetBytes()}"
assert packA + packB + packC >= admit.budgetBytes() : "third must wait ${packA + packB + packC} vs ${admit.budgetBytes()}"

admit.setDefaultReserveOverrideBytes(null)
long unknown = admit.defaultReserveBytes()
assert 2L * unknown < admit.budgetBytes()
assert 3L * unknown >= admit.budgetBytes()

admit.setBudgetOverrideBytes(null)
admit.setWireMultiplierOverride(null)
println "OK pack-admission two-largest=${packA + packB} third=${packA + packB + packC} budget1800=${1800L << 20}"
println "autoUnknown=${unknown} active=${admit.activeUnpackers()}"
System.exit(0)
