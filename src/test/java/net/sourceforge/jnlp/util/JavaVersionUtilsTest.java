package net.sourceforge.jnlp.util;

import org.junit.Assert;
import org.junit.Test;

public class JavaVersionUtilsTest {

    @Test
    public void securityManagerSupportedOnJdk21() {
        int major = JavaVersionUtils.getRunningMajorVersion();
        Assert.assertTrue("test JVM major version", major >= 11);
        if (major < 24) {
            Assert.assertTrue(JavaVersionUtils.isSecurityManagerSupported());
        } else {
            Assert.assertFalse(JavaVersionUtils.isSecurityManagerSupported());
        }
    }
}
