package net.sourceforge.jnlp.util;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaVersionUtilsTest {

    @Test
    void securityManagerSupportedOnJdk21OnlyWhenAllowPropertySet() {
        int major = JavaVersionUtils.getRunningMajorVersion();
        assertTrue(major >= 11, "test JVM major version");
        if (major >= JavaVersionUtils.SECURITY_MANAGER_REMOVED_MAJOR) {
            assertFalse(JavaVersionUtils.isSecurityManagerSupported());
        } else if (JavaVersionUtils.needsSecurityManagerAllowFlag(major)) {
            String prop = System.getProperty("java.security.manager");
            if ("allow".equals(prop)) {
                assertTrue(JavaVersionUtils.isSecurityManagerSupported());
            } else {
                assertFalse(JavaVersionUtils.isSecurityManagerSupported());
            }
        } else {
            assertTrue(JavaVersionUtils.isSecurityManagerSupported());
        }
    }

    @Test
    void addSecurityManagerCompatibilityArgsForModernJdkHome() {
        List<String> vmArgs = new ArrayList<>();
        JavaVersionUtils.addSecurityManagerCompatibilityArgs(vmArgs, "/usr/lib/jvm/java-21-openjdk-amd64");
        if (JavaVersionUtils.needsSecurityManagerAllowFlag(21)) {
            assertEquals(1, vmArgs.size());
            assertEquals("-Djava.security.manager=allow", vmArgs.get(0));
        }
    }

    @Test
    void addSecurityManagerCompatibilityArgsDoesNotDuplicateExistingFlag() {
        List<String> vmArgs = new ArrayList<>();
        vmArgs.add("-Djava.security.manager=disallow");
        JavaVersionUtils.addSecurityManagerCompatibilityArgs(vmArgs, "/usr/lib/jvm/java-21-openjdk-amd64");
        assertEquals(1, vmArgs.size());
    }

    @Test
    void removeLegacyJavaXmlBindAddModulesStripsEqualsFormOnJdk11Plus() {
        List<String> vmArgs = new ArrayList<>();
        vmArgs.add("-Xmx512m");
        vmArgs.add("--add-modules=java.xml.bind");
        JavaVersionUtils.removeLegacyJavaXmlBindAddModules(vmArgs, "/usr/lib/jvm/java-11-openjdk-amd64");
        assertEquals(1, vmArgs.size());
        assertEquals("-Xmx512m", vmArgs.get(0));
    }

    @Test
    void removeLegacyJavaXmlBindAddModulesStripsSpaceSeparatedFormOnJdk11Plus() {
        List<String> vmArgs = new ArrayList<>();
        vmArgs.add("--add-modules");
        vmArgs.add("java.xml.bind");
        JavaVersionUtils.removeLegacyJavaXmlBindAddModules(vmArgs, "C:\\Program Files\\Java\\jdk-17");
        assertTrue(vmArgs.isEmpty());
    }

    @Test
    void removeLegacyJavaXmlBindAddModulesLeavesOtherModulesUntouched() {
        List<String> vmArgs = new ArrayList<>();
        vmArgs.add("--add-modules");
        vmArgs.add("java.sql");
        JavaVersionUtils.removeLegacyJavaXmlBindAddModules(vmArgs, "/usr/lib/jvm/java-17-openjdk-amd64");
        assertEquals(2, vmArgs.size());
    }

    @Test
    void removeLegacyJavaXmlBindAddModulesNoOpOnJdk8() {
        List<String> vmArgs = new ArrayList<>();
        vmArgs.add("--add-modules=java.xml.bind");
        JavaVersionUtils.removeLegacyJavaXmlBindAddModules(vmArgs, "/usr/lib/jvm/java-8-openjdk-amd64");
        assertEquals(1, vmArgs.size());
    }
}
