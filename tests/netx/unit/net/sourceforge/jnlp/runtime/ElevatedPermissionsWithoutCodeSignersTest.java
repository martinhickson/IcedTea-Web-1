package net.sourceforge.jnlp.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URL;

import org.junit.Test;

import net.sourceforge.jnlp.SecurityDesc;
import net.sourceforge.jnlp.mock.DummyJNLPFile;
import net.sourceforge.jnlp.runtime.JNLPClassLoader.SigningState;
import net.sourceforge.jnlp.util.logging.NoStdOutErrTest;

/**
 * Guard for the rule that fully trusted ALL/J2EE apps elevate synthetic
 * CodeSources (proxies / ByteBuddy) that have no CodeSigners.
 */
public class ElevatedPermissionsWithoutCodeSignersTest extends NoStdOutErrTest {

    @Test
    public void fullSigningWithAllPermissionsGrantsElevationWithoutSigners() throws Exception {
        DummyJNLPFile file = new DummyJNLPFile();
        SecurityDesc all = new SecurityDesc(file, SecurityDesc.ALL_PERMISSIONS, new URL("http://example.invalid/"));
        assertTrue(JNLPClassLoader.shouldGrantElevatedPermissionsWithoutCodeSigners(
                SigningState.FULL, all));
    }

    @Test
    public void fullSigningWithJ2eePermissionsGrantsElevationWithoutSigners() throws Exception {
        DummyJNLPFile file = new DummyJNLPFile();
        SecurityDesc j2ee = new SecurityDesc(file, SecurityDesc.J2EE_PERMISSIONS, new URL("http://example.invalid/"));
        assertTrue(JNLPClassLoader.shouldGrantElevatedPermissionsWithoutCodeSigners(
                SigningState.FULL, j2ee));
    }

    @Test
    public void sandboxOrPartialDoesNotElevateWithoutSigners() throws Exception {
        DummyJNLPFile file = new DummyJNLPFile();
        SecurityDesc all = new SecurityDesc(file, SecurityDesc.ALL_PERMISSIONS, new URL("http://example.invalid/"));
        SecurityDesc sandbox = new SecurityDesc(file, SecurityDesc.SANDBOX_PERMISSIONS, new URL("http://example.invalid/"));

        assertFalse(JNLPClassLoader.shouldGrantElevatedPermissionsWithoutCodeSigners(
                SigningState.PARTIAL, all));
        assertFalse(JNLPClassLoader.shouldGrantElevatedPermissionsWithoutCodeSigners(
                SigningState.NONE, all));
        assertFalse(JNLPClassLoader.shouldGrantElevatedPermissionsWithoutCodeSigners(
                SigningState.FULL, sandbox));
        assertFalse(JNLPClassLoader.shouldGrantElevatedPermissionsWithoutCodeSigners(
                SigningState.FULL, null));
    }
}
