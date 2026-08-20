/*
 * Copyright (C) 2026 IcedTea-Web Contributors
 */
package net.sourceforge.jnlp.runtime;

import net.bytebuddy.asm.Advice;
import net.sourceforge.jnlp.util.IpClassification;

import java.net.InetAddress;

/**
 * Bootstrap-visible advice for {@link InetAddress#getHostName()} (all
 * overloads, including {@code getHostName(boolean)}) and
 * {@link InetAddress#getCanonicalHostName()}. Must not reference ITW
 * application classes that are not bootstrap-injected.
 */
public final class BootstrapInetAddressNameAdvice {

    private BootstrapInetAddressNameAdvice() {
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static String skipReverseDns(@Advice.This InetAddress addr) {
        String ip = addr.getHostAddress();
        if (!IpClassification.skipReverseDns(ip)) {
            return null;
        }
        return ip;
    }

    @Advice.OnMethodExit
    public static void useNumericHost(
            @Advice.Enter String ip,
            @Advice.Return(readOnly = false) String returned) {
        if (ip != null) {
            returned = ip;
        }
    }
}
