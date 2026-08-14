package net.sourceforge.jnlp.cache;

import java.io.File;

import net.sourceforge.jnlp.tools.JarCertVerifier;

/**
 * Runs the post-write ZIP walks (signature read + nested-jar extract)
 * on the download worker so the launch thread does not do them serially.
 */
public final class LaunchPrep {

    private LaunchPrep() {
    }

    public static void prepare(File jar) {
        if (jar == null || !jar.isFile() || jar.length() <= 0L) {
            return;
        }
        JarCertVerifier.prepare(jar.getAbsolutePath());
        JarActivatePrep.prepare(jar);
    }
}
