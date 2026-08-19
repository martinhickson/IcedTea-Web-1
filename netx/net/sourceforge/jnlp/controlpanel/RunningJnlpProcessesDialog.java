package net.sourceforge.jnlp.controlpanel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import net.sourceforge.jnlp.cache.CacheUtil;
import net.sourceforge.jnlp.runtime.Translator;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport.RunningProcess;

/**
 * Lists running JNLP JVMs from the cache catalog and lets the user stop them
 * before clearing cache.
 */
public final class RunningJnlpProcessesDialog extends JDialog {

    private final JPanel processListPanel = new JPanel(new GridBagLayout());
    private final JLabel statusLabel = new JLabel();
    private final JButton proceedButton;
    private final Runnable onProceed;
    private final String jnlpPathFilter;
    private final Timer refreshTimer;
    private final List<RunningProcess> tracked = new ArrayList<>();
    private boolean proceedClicked;

    private RunningJnlpProcessesDialog(Window owner, String title, Runnable onProceed,
            String jnlpPathFilter, String proceedLabelKey) {
        super(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        this.onProceed = onProceed;
        this.jnlpPathFilter = jnlpPathFilter;
        setLayout(new BorderLayout(8, 8));
        setMinimumSize(new Dimension(520, 280));

        JLabel description = new JLabel(jnlpPathFilter == null || jnlpPathFilter.trim().isEmpty()
                ? Translator.R("CacheRunningJnlpDescription")
                : Translator.R("CacheRunningJnlpDescriptionSelected", jnlpPathFilter));
        add(description, BorderLayout.NORTH);

        processListPanel.setLayout(new GridBagLayout());
        add(new JScrollPane(processListPanel), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.add(statusLabel, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        JButton cancelButton = new JButton(Translator.R("ButCancel"));
        cancelButton.addActionListener(e -> dispose());
        proceedButton = new JButton(proceedLabelKey);
        proceedButton.setEnabled(false);
        proceedButton.addActionListener(e -> {
            // E.6 verify-on-proceed: re-run the authoritative check at CLICK time,
            // not the last 2s-refresh snapshot. Between the refresh and the click a
            // new javaws could have started (or a lock been taken) — clearing then
            // would race. If still not clear, re-render the fresh list and stay open.
            if (!canClearCacheNow(jnlpPathFilter)) {
                refreshProcessList();
                return;
            }
            proceedClicked = true;
            if (onProceed != null) {
                onProceed.run();
            }
            dispose();
        });
        buttons.add(cancelButton);
        buttons.add(proceedButton);
        south.add(buttons, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        refreshTimer = new Timer(2000, e -> refreshProcessList());
        refreshTimer.setRepeats(true);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                refreshProcessList();
                refreshTimer.start();
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                refreshTimer.stop();
            }
        });
        pack();
    }

    /**
     * If JNLP apps that would lose cache files are running, shows this dialog until they
     * are stopped or the user cancels. JAR cache ids are included: they do not appear in
     * process metadata, so matching only the JNLP path used to skip the busy guard.
     */
    public static boolean ensureProcessesStopped(Component parent, String jnlpPathFilter) {
        if (canClearCacheNow(jnlpPathFilter)) {
            return true;
        }
        Window owner = parent != null ? SwingUtilities.getWindowAncestor(parent) : null;
        RunningJnlpProcessesDialog dialog = new RunningJnlpProcessesDialog(
                owner,
                Translator.R("CacheRunningJnlpTitle"),
                null,
                jnlpPathFilter,
                Translator.R("CacheProceedContinue"));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return dialog.proceedClicked && canClearCacheNow(jnlpPathFilter);
    }

    public static boolean ensureProcessesStopped(Component parent) {
        return ensureProcessesStopped(parent, null);
    }

    /**
     * Shows the running-process dialog and runs {@code clearAction} when the user proceeds.
     */
    public static void runClearAfterProcessesStopped(Component parent, String jnlpPathFilter,
            String proceedLabelKey, Runnable clearAction) {
        if (canClearCacheNow(jnlpPathFilter)) {
            clearAction.run();
            return;
        }
        Window owner = parent != null ? SwingUtilities.getWindowAncestor(parent) : null;
        RunningJnlpProcessesDialog dialog = new RunningJnlpProcessesDialog(
                owner,
                Translator.R("CacheRunningJnlpTitle"),
                clearAction,
                jnlpPathFilter,
                proceedLabelKey);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static boolean canClearCacheNow(String jnlpPathFilter) {
        if (JnlpRunningProcessSupport.cacheClearBlockedByRunningApps(jnlpPathFilter)) {
            return false;
        }
        if (!requiresGlobalCacheLockClear(jnlpPathFilter)) {
            return true;
        }
        return !CacheUtil.isCacheLockedByOtherInstance();
    }

    private boolean requiresGlobalCacheLockClear() {
        return requiresGlobalCacheLockClear(jnlpPathFilter);
    }

    private static boolean requiresGlobalCacheLockClear(String jnlpPathFilter) {
        return jnlpPathFilter == null || jnlpPathFilter.trim().isEmpty();
    }

    public static void runClearAfterProcessesStopped(Component parent, Runnable clearAction) {
        runClearAfterProcessesStopped(parent, null, Translator.R("CacheProceedClearAll"), clearAction);
    }

    private void refreshProcessList() {
        List<RunningProcess> latest = new ArrayList<>();
        for (RunningProcess process : JnlpRunningProcessSupport.listRunningJnlpProcessesForCacheClear()) {
            if (jnlpPathFilter == null || jnlpPathFilter.trim().isEmpty()
                    || process.blocksCacheClear(jnlpPathFilter)) {
                latest.add(process);
            }
        }
        tracked.clear();
        tracked.addAll(latest);

        processListPanel.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);

        if (tracked.isEmpty()) {
            c.gridy = 0;
            if (requiresGlobalCacheLockClear() && CacheUtil.isCacheLockedByOtherInstance()) {
                processListPanel.add(new JLabel(Translator.R("CacheRunningJnlpLockHeld")), c);
                statusLabel.setText(Translator.R("CacheRunningJnlpLockHeld"));
                proceedButton.setEnabled(false);
            } else {
                boolean canClear = canClearCacheNow(jnlpPathFilter);
                processListPanel.add(new JLabel(Translator.R("CacheRunningJnlpNone")), c);
                statusLabel.setText(canClear ? Translator.R("CacheRunningJnlpReady") : "");
                proceedButton.setEnabled(canClear);
            }
        } else {
            int row = 0;
            for (RunningProcess process : tracked) {
                c.gridy = row++;
                processListPanel.add(buildProcessRow(process), c);
            }
            if (CacheUtil.isCacheLockedByOtherInstance()) {
                statusLabel.setText(Translator.R("CacheRunningJnlpWaitingWithLock", tracked.size()));
            } else {
                statusLabel.setText(Translator.R("CacheRunningJnlpWaiting", tracked.size()));
            }
            proceedButton.setEnabled(false);
        }
        processListPanel.revalidate();
        processListPanel.repaint();
    }

    private JPanel buildProcessRow(final RunningProcess process) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        String label = Translator.R("CacheRunningJnlpProcess", process.getDisplayName(), process.getPid());
        row.add(new JLabel(label), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        JButton stopButton = new JButton(Translator.R("CacheStop"));
        stopButton.addActionListener(e -> {
            JnlpRunningProcessSupport.stopProcess(process.getPid(), process.getProcessStart(), false);
            refreshProcessList();   // E.7: re-render now, don't wait for the 2s timer
        });
        JButton forceStopButton = new JButton(Translator.R("CacheForceStop"));
        forceStopButton.addActionListener(e -> {
            JnlpRunningProcessSupport.stopProcess(process.getPid(), process.getProcessStart(), true);
            refreshProcessList();
        });
        actions.add(stopButton);
        actions.add(forceStopButton);
        row.add(actions, BorderLayout.EAST);
        return row;
    }

    private static boolean containsPid(List<RunningProcess> processes, int pid) {
        for (RunningProcess process : processes) {
            if (process.getPid() == pid) {
                return true;
            }
        }
        return false;
    }
}
