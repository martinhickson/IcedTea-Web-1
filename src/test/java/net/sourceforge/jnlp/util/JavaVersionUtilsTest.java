package net.sourceforge.jnlp.util;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class JavaVersionUtilsTest {

    @Test
    public void securityManagerSupportedOnJdk21OnlyWhenAllowPropertySet() {
        int major = JavaVersionUtils.getRunningMajorVersion();
        Assert.assertTrue("test JVM major version", major >= 11);
        if (major >= JavaVersionUtils.SECURITY_MANAGER_REMOVED_MAJOR) {
            Assert.assertFalse(JavaVersionUtils.isSecurityManagerSupported());
        } else if (JavaVersionUtils.needsSecurityManagerAllowFlag(major)) {
            String prop = System.getProperty("java.security.manager");
            if ("allow".equals(prop)) {
                Assert.assertTrue(JavaVersionUtils.isSecurityManagerSupported());
            } else {
                Assert.assertFalse(JavaVersionUtils.isSecurityManagerSupported());
            }
        } else {
            Assert.assertTrue(JavaVersionUtils.isSecurityManagerSupported());
        }
    }

    @Test
    public void addSecurityManagerCompatibilityArgsForModernJdkHome() {
        List<String> vmArgs = new ArrayList<>();
        JavaVersionUtils.addSecurityManagerCompatibilityArgs(vmArgs, "/usr/lib/jvm/java-21-openjdk-amd64");
        if (JavaVersionUtils.needsSecurityManagerAllowFlag(21)) {
            Assert.assertEquals(1, vmArgs.size());
            Assert.assertEquals("-Djava.security.manager=allow", vmArgs.get(0));
        }
    }

    @Test
    public void addSecurityManagerCompatibilityArgsDoesNotDuplicateExistingFlag() {
        List<String> vmArgs = new ArrayList<>();
        vmArgs.add("-Djava.security.manager=disallow");
        JavaVersionUtils.addSecurityManagerCompatibilityArgs(vmArgs, "/usr/lib/jvm/java-21-openjdk-amd64");
        Assert.assertEquals(1, vmArgs.size());
    }
}
