package net.sourceforge.jnlp.security;

import static net.sourceforge.jnlp.runtime.Translator.R;

import net.sourceforge.jnlp.JNLPFile;
import net.sourceforge.jnlp.LaunchException;
import net.sourceforge.jnlp.runtime.JNLPClassLoader.SigningState;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.tools.JarCertVerifier;
import net.sourceforge.jnlp.util.JavaVersionUtils;

/**
 * On JDK 24+ the SecurityManager cannot be installed, so unsigned applications
 * cannot be sandboxed. When security is enabled (the default), only fully signed
 * applications may launch.
 */
public final class JdkSigningRequirementPolicy {

    private JdkSigningRequirementPolicy() {
    }

    public static boolean requiresSignedApplications() {
        return !JavaVersionUtils.isSecurityManagerSupported();
    }

    public static void enforceSignedApplicationIfRequired(JNLPFile file, SigningState signingState,
            JarCertVerifier jcv) throws LaunchException {
        enforceSignedApplicationIfRequired(file, signingState, jcv, JNLPRuntime.isSecurityEnabled());
    }

    static void enforceSignedApplicationIfRequired(JNLPFile file, SigningState signingState,
            JarCertVerifier jcv, boolean securityEnabled) throws LaunchException {
        if (!requiresSignedApplications()) {
            return;
        }
        if (!securityEnabled) {
            return;
        }
        if (signingState == SigningState.FULL && jcv != null && jcv.isFullySigned()) {
            return;
        }
        int major = JavaVersionUtils.getRunningMajorVersion();
        throw new LaunchException(file, null, R("LSFatal"), R("LCClient"),
                R("LUnsignedJdk24Required"),
                R("LUnsignedJdk24RequiredInfo", Integer.toString(major)));
    }
}
