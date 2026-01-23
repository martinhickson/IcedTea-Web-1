package net.sourceforge.jnlp.runtime.test;

import net.sourceforge.jnlp.config.Setting;
import net.sourceforge.jnlp.runtime.JNLPPolicy;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import org.junit.jupiter.api.Test;

import static net.sourceforge.jnlp.config.DeploymentConfiguration.KEY_SYSTEM_SECURITY_POLICY;
import static net.sourceforge.jnlp.config.DeploymentConfiguration.KEY_USER_SECURITY_POLICY;


/**
 * Test for {@link JNLPPolicy}.
 */
public class JNLPPolicyTest {

    @Test
    public void configLocationForWindowsLoads() {
        final String fileURI = "file://C:/Users/philippe doussot/.config/icedtea-web/security/java.policy";
        System.setProperty(KEY_SYSTEM_SECURITY_POLICY, fileURI);
        JNLPRuntime.getConfiguration().getRaw().put(KEY_SYSTEM_SECURITY_POLICY,
                new Setting<>(KEY_SYSTEM_SECURITY_POLICY, "", false, null, KEY_SYSTEM_SECURITY_POLICY, fileURI, fileURI)
        );
        new JNLPPolicy();
    }

    @Test
    public void configLocationForNixLoads() {
        final String fileURI = "file://a/b/c/java.policy";
        System.setProperty(KEY_SYSTEM_SECURITY_POLICY, fileURI);
        JNLPRuntime.getConfiguration().getRaw().put(KEY_SYSTEM_SECURITY_POLICY,
                new Setting<>(KEY_SYSTEM_SECURITY_POLICY, "", false, null, KEY_SYSTEM_SECURITY_POLICY, fileURI, fileURI)
        );
        new JNLPPolicy();
    }

    @Test
    public void configLocationForUriLoads() {
        final String fileURI = "http://my:8080/policy/locationjava.policy";
        System.setProperty(KEY_SYSTEM_SECURITY_POLICY, fileURI);
        JNLPRuntime.getConfiguration().getRaw().put(KEY_SYSTEM_SECURITY_POLICY,
                new Setting<>(KEY_SYSTEM_SECURITY_POLICY, "", false, null, KEY_SYSTEM_SECURITY_POLICY, fileURI, fileURI)
        );
        new JNLPPolicy();
    }

    /**
     * Test that Windows file paths with backslashes in file:// URIs are handled correctly.
     * This is the specific case that was failing: file://C:\Users\... with backslashes.
     */
    @Test
    public void configLocationForWindowsWithBackslashesLoads() {
        // This is the exact format that was failing - file:// with Windows path containing backslashes
        final String fileURI = "file://C:\\Users\\mhickson\\.config\\icedtea-web\\security\\java.policy";
        System.setProperty(KEY_USER_SECURITY_POLICY, fileURI);
        JNLPRuntime.getConfiguration().getRaw().put(KEY_USER_SECURITY_POLICY,
                new Setting<>(KEY_USER_SECURITY_POLICY, "", false, null, KEY_USER_SECURITY_POLICY, fileURI, fileURI)
        );
        // This should not throw URISyntaxException
        new JNLPPolicy();
    }

    /**
     * Test that Windows file paths with backslashes in file:// URIs are handled correctly
     * for system policy as well.
     */
    @Test
    public void systemPolicyLocationForWindowsWithBackslashesLoads() {
        // Test system policy with Windows backslashes
        final String fileURI = "file://C:\\Windows\\System32\\java.policy";
        System.setProperty(KEY_SYSTEM_SECURITY_POLICY, fileURI);
        JNLPRuntime.getConfiguration().getRaw().put(KEY_SYSTEM_SECURITY_POLICY,
                new Setting<>(KEY_SYSTEM_SECURITY_POLICY, "", false, null, KEY_SYSTEM_SECURITY_POLICY, fileURI, fileURI)
        );
        // This should not throw URISyntaxException
        new JNLPPolicy();
    }

    /**
     * Test that already normalized Windows paths (forward slashes) still work.
     */
    @Test
    public void configLocationForWindowsNormalizedLoads() {
        final String fileURI = "file:///C:/Users/test/.config/icedtea-web/security/java.policy";
        System.setProperty(KEY_USER_SECURITY_POLICY, fileURI);
        JNLPRuntime.getConfiguration().getRaw().put(KEY_USER_SECURITY_POLICY,
                new Setting<>(KEY_USER_SECURITY_POLICY, "", false, null, KEY_USER_SECURITY_POLICY, fileURI, fileURI)
        );
        new JNLPPolicy();
    }

    /**
     * Test that non-file URIs with backslashes are handled (should convert to forward slashes).
     */
    @Test
    public void nonFileUriWithBackslashesLoads() {
        final String fileURI = "http://example.com\\path\\to\\policy";
        System.setProperty(KEY_SYSTEM_SECURITY_POLICY, fileURI);
        JNLPRuntime.getConfiguration().getRaw().put(KEY_SYSTEM_SECURITY_POLICY,
                new Setting<>(KEY_SYSTEM_SECURITY_POLICY, "", false, null, KEY_SYSTEM_SECURITY_POLICY, fileURI, fileURI)
        );
        new JNLPPolicy();
    }
}




