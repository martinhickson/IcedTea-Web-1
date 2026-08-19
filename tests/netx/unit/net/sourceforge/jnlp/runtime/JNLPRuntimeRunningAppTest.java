package net.sourceforge.jnlp.runtime;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class JNLPRuntimeRunningAppTest {

    @Test
    public void settingsAndCliOpsDoNotTakeARunningAppLease() {
        assertFalse(JNLPRuntime.shouldRegisterCacheRunningApp(null));
    }
}
