package net.sourceforge.jnlp.config;

import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;
import org.junit.Assert;
import org.junit.Test;

public class KnownJvmStoreDynamicKeyTest extends NoStdOutErrTest {

    @Test
    public void recognizesJdkListAndAssignmentKeys() {
        Assert.assertTrue(KnownJvmStore.isKnownDynamicKey("deployment.jdk.1"));
        Assert.assertTrue(KnownJvmStore.isKnownDynamicKey("deployment.jdk.12"));
        Assert.assertTrue(KnownJvmStore.isKnownDynamicKey("deployment.jdk1.assignment1"));
        Assert.assertTrue(KnownJvmStore.isKnownDynamicKey(KnownJvmStore.KEY_MATCH_STRATEGY));
        Assert.assertFalse(KnownJvmStore.isKnownDynamicKey("deployment.jre.dir"));
        Assert.assertFalse(KnownJvmStore.isKnownDynamicKey("deployment.jdk"));
        Assert.assertFalse(KnownJvmStore.isKnownDynamicKey(null));
    }
}
