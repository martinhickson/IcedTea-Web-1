package net.sourceforge.jnlp.runtime;

import net.sourceforge.jnlp.util.JavaVersionUtils;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JarUrlCacheProtectionTest {

    @Test
    public void installIsNoOpBelowJdk24() {
        if (JavaVersionUtils.getRunningMajorVersion() >= JavaVersionUtils.SECURITY_MANAGER_REMOVED_MAJOR) {
            return;
        }
        assertFalse(JarUrlCacheProtection.install());
        assertFalse(JarUrlCacheProtection.isActive());
    }

    @Test
    public void installOnJdk24PlusWhenByteBuddyPresent() {
        if (JavaVersionUtils.getRunningMajorVersion() < JavaVersionUtils.SECURITY_MANAGER_REMOVED_MAJOR) {
            return;
        }
        assertTrue(JarUrlCacheProtection.install());
        assertTrue(JarUrlCacheProtection.isActive());
    }
}
