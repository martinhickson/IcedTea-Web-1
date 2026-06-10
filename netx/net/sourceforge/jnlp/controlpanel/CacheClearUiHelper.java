package net.sourceforge.jnlp.controlpanel;

import java.awt.Component;

import javax.swing.JOptionPane;

import net.sourceforge.jnlp.cache.CacheUtil;
import net.sourceforge.jnlp.runtime.Translator;

/**
 * Shared UI flow for cache clearing with running-JNLP detection.
 */
public final class CacheClearUiHelper {

    private CacheClearUiHelper() {
    }

    public static void clearEntireCache(Component parent) {
        RunningJnlpProcessesDialog.runClearAfterProcessesStopped(
                parent,
                null,
                Translator.R("CacheProceedClearAll"),
                new Runnable() {
                    @Override
                    public void run() {
                        CachePane.visualCleanCache(parent);
                    }
                });
    }

    public static boolean clearApplicationCache(Component parent, String application, boolean jnlpPath, boolean domain) {
        if (!RunningJnlpProcessesDialog.ensureProcessesStopped(parent, application)) {
            return false;
        }
        if (!CacheUtil.clearCache(application, jnlpPath, domain)) {
            JOptionPane.showMessageDialog(parent, Translator.R("CCannotClearCache"));
            return false;
        }
        JOptionPane.showMessageDialog(
                parent,
                Translator.R("CacheClearedForAppSuccessfully", application),
                Translator.R("CPHeadTempInternetFiles"),
                JOptionPane.INFORMATION_MESSAGE);
        return true;
    }
}
