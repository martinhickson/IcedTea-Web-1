package net.sourceforge.icedteaweb.autodetect.it.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

/**
 * Thin bridge from the javaagent into icedtea-web's privileged
 * {@code AutodetectDialogClicker}. Resolves the clicker class once during
 * premain (before {@code JNLPSecurityManager}) and only invokes cached methods
 * afterwards — {@code Class.forName} of product packages is denied later.
 */
public final class DialogClickSupport {

    private static final String CLICKER = "net.sourceforge.jnlp.util.AutodetectDialogClicker";

    private static volatile Method startMethod;
    private static volatile Method armMethod;

    private DialogClickSupport() {
    }

    public static void init(Instrumentation inst) {
        resolveClicker(inst);
    }

    public static void startBackgroundClicker() {
        resolveClicker(null);
        Method start = startMethod;
        if (start == null) {
            System.err.println("[itw-autodetect-dialog-agent] AutodetectDialogClicker not resolved");
            return;
        }
        try {
            start.invoke(null);
            System.err.println("[itw-autodetect-dialog-agent] started AutodetectDialogClicker");
        } catch (Throwable ex) {
            System.err.println("[itw-autodetect-dialog-agent] start clicker failed: " + ex);
            ex.printStackTrace(System.err);
        }
    }

    public static void scheduleEdtDoClickPass() {
        Method arm = armMethod;
        if (arm == null) {
            System.err.println("[itw-autodetect-dialog-agent] arm skipped — clicker not resolved");
            return;
        }
        try {
            arm.invoke(null);
            System.err.println("[itw-autodetect-dialog-agent] scheduleEdtDoClickPass armed");
        } catch (Throwable ex) {
            System.err.println("[itw-autodetect-dialog-agent] arm clicker failed: " + ex);
            ex.printStackTrace(System.err);
        }
    }

    private static synchronized void resolveClicker(Instrumentation inst) {
        if (armMethod != null) {
            return;
        }
        try {
            Class<?> clicker = null;
            try {
                clicker = Class.forName(CLICKER, true, ClassLoader.getSystemClassLoader());
            } catch (Throwable forNameFailed) {
                if (inst != null) {
                    for (Class<?> loaded : inst.getAllLoadedClasses()) {
                        if (CLICKER.equals(loaded.getName())) {
                            clicker = loaded;
                            break;
                        }
                    }
                }
                if (clicker == null) {
                    throw forNameFailed;
                }
            }
            startMethod = clicker.getMethod("start");
            armMethod = clicker.getMethod("arm");
            System.err.println("[itw-autodetect-dialog-agent] resolved AutodetectDialogClicker");
        } catch (Throwable ex) {
            System.err.println("[itw-autodetect-dialog-agent] resolve clicker failed: " + ex);
            ex.printStackTrace(System.err);
        }
    }
}
