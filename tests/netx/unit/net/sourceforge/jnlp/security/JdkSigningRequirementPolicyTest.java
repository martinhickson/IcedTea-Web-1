package net.sourceforge.jnlp.security;

import net.sourceforge.jnlp.LaunchException;
import net.sourceforge.jnlp.runtime.JNLPClassLoader.SigningState;
import net.sourceforge.jnlp.util.JavaVersionUtils;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class JdkSigningRequirementPolicyTest {

    @Test
    public void requiresSignedApplicationsOnJdk24Plus() {
        int major = JavaVersionUtils.getRunningMajorVersion();
        if (major >= JavaVersionUtils.SECURITY_MANAGER_REMOVED_MAJOR) {
            Assert.assertTrue(JdkSigningRequirementPolicy.requiresSignedApplications());
        } else {
            Assert.assertFalse(JdkSigningRequirementPolicy.requiresSignedApplications());
        }
    }

    @Test
    public void blocksUnsignedWhenSecurityEnabledOnJdk24Plus() {
        Assume.assumeTrue(JdkSigningRequirementPolicy.requiresSignedApplications());
        try {
            JdkSigningRequirementPolicy.enforceSignedApplicationIfRequired(null, SigningState.NONE, null, true);
            Assert.fail("Expected LaunchException for unsigned application");
        } catch (LaunchException ex) {
            Assert.assertTrue(ex.getSummary().contains("not signed"));
            Assert.assertFalse(ex.getDescription().toLowerCase().contains("nosecurity"));
            Assert.assertTrue(ex.getDescription().contains("Java"));
        }
    }

    @Test
    public void allowsUnsignedWhenSecurityDisabledOnJdk24Plus() throws LaunchException {
        Assume.assumeTrue(JdkSigningRequirementPolicy.requiresSignedApplications());
        JdkSigningRequirementPolicy.enforceSignedApplicationIfRequired(null, SigningState.NONE, null, false);
    }
}
