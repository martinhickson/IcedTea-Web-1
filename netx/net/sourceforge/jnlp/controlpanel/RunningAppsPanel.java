package net.sourceforge.jnlp.controlpanel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.Translator;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport.RunningProcess;
import net.sourceforge.jnlp.util.ProcessMemorySupport;
import net.sourceforge.jnlp.util.ProcessMemorySupport.MemoryInfo;
import net.sourceforge.jnlp.util.ProcessMemorySupport.ProcessJvmContext;

@SuppressWarnings("serial")
public class RunningAppsPanel extends NamedBorderPanel {

    private final JPanel listPanel = new JPanel(new GridBagLayout());
    private final JLabel statusLabel = new JLabel();
    private final Timer refreshTimer;

    RunningAppsPanel(DeploymentConfiguration config) {
        super(Translator.R("CPHeadRunningApps"), new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.gridwidth = 1;
        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(2, 2, 4, 4);
        add(new JLabel("<html>" + Translator.R("CPRunningAppsDescription") + "<hr /></html>"), c);

        c.gridy++;
        c.weighty = 1;
        listPanel.setLayout(new GridBagLayout());
        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setPreferredSize(new Dimension(560, 260));
        add(scroll, c);

        c.gridy++;
        c.weighty = 0;
        add(statusLabel, c);

        c.gridy++;
        Component filler = Box.createRigidArea(new Dimension(1, 1));
        c.weighty = 1;
        add(filler, c);

        refreshTimer = new Timer(2000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshList();
            }
        });
        refreshTimer.setRepeats(true);
        addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                refreshList();
                refreshTimer.start();
            }

            @Override
            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
                refreshTimer.stop();
            }

            @Override
            public void ancestorMoved(javax.swing.event.AncestorEvent event) {
            }
        });
        refreshList();
    }

    private void refreshList() {
        List<RunningProcess> processes = JnlpRunningProcessSupport.listRunningJnlpProcesses();
        listPanel.removeAll();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);

        if (processes.isEmpty()) {
            c.gridy = 0;
            listPanel.add(new JLabel(Translator.R("CPRunningAppsNone")), c);
            statusLabel.setText("");
        } else {
            int row = 0;
            for (RunningProcess process : processes) {
                c.gridy = row++;
                listPanel.add(buildProcessRow(process), c);
            }
            statusLabel.setText(Translator.R("CPRunningAppsCount", processes.size()));
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    private JPanel buildProcessRow(final RunningProcess process) {
        JPanel row = new JPanel(new BorderLayout(8, 4));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        ProcessJvmContext jvmContext = ProcessMemorySupport.resolveJvmContext(process);

        JPanel titlePanel = new JPanel(new GridBagLayout());
        GridBagConstraints tc = new GridBagConstraints();
        tc.gridx = 0;
        tc.gridy = 0;
        tc.anchor = GridBagConstraints.WEST;
        tc.insets = new Insets(0, 0, 2, 12);
        String title = process.getAppTitle();
        if (title == null || title.trim().isEmpty()) {
            title = process.getDisplayName();
        }
        titlePanel.add(new JLabel("<b>" + escapeHtml(title) + "</b>"), tc);
        tc.gridx = 1;
        String version = process.getAppVersion();
        if (version == null || version.trim().isEmpty()) {
            version = "\u2014";
        }
        titlePanel.add(new JLabel(Translator.R("CPRunningAppsVersionLabel", version)), tc);
        tc.gridx = 0;
        tc.gridy = 1;
        tc.gridwidth = 2;
        titlePanel.add(new JLabel(formatJvmLabel(jvmContext)), tc);
        row.add(titlePanel, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        final ProcessJvmContext rowJvmContext = jvmContext;
        JButton trimHeap = new JButton(Translator.R("CPRunningAppsTrimHeap"));
        trimHeap.addActionListener(e -> trimHeap(process, rowJvmContext));
        JButton stop = new JButton(Translator.R("CPRunningAppsStop"));
        stop.addActionListener(e -> JnlpRunningProcessSupport.stopProcess(process.getPid(), false));
        JButton forceStop = new JButton(Translator.R("CPRunningAppsForceStop"));
        forceStop.addActionListener(e -> JnlpRunningProcessSupport.stopProcess(process.getPid(), true));
        JButton info = new JButton(Translator.R("CPRunningAppsInfo"));
        info.addActionListener(e -> showInfo(process, rowJvmContext));
        actions.add(trimHeap);
        actions.add(stop);
        actions.add(forceStop);
        actions.add(info);
        row.add(actions, BorderLayout.EAST);
        return row;
    }

    private void trimHeap(RunningProcess process, ProcessJvmContext jvmContext) {
        boolean ok = ProcessMemorySupport.trimHeap(process.getPid(), jvmContext);
        if (ok) {
            JOptionPane.showMessageDialog(this, Translator.R("CPRunningAppsTrimHeapDone"));
        } else {
            JOptionPane.showMessageDialog(this, Translator.R("CPRunningAppsTrimHeapFailed"),
                    Translator.R("CPHeadRunningApps"), JOptionPane.WARNING_MESSAGE);
        }
    }

    private void showInfo(final RunningProcess process, final ProcessJvmContext jvmContext) {
        final java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
        final JDialog dialog = new JDialog(owner, Translator.R("CPRunningAppsInfoTitle"), JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(8, 8));
        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);
        content.add(new JLabel(Translator.R("CPRunningAppsInfoLoading")), c);

        dialog.add(content, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        JButton close = new JButton(Translator.R("ButClose"));
        close.addActionListener(e -> dialog.dispose());
        buttons.add(close);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(420, 180));
        dialog.setLocationRelativeTo(this);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final MemoryInfo info = ProcessMemorySupport.readMemoryInfo(process.getPid(), jvmContext);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        content.removeAll();
                        if (!info.isAvailable()) {
                            c.gridy = 0;
                            content.add(new JLabel(Translator.R("CPRunningAppsInfoUnavailable")), c);
                        } else {
                            addMemoryRow(content, c, 0,
                                    Translator.R("CPRunningAppsHeap"),
                                    info.getHeapUsedBytes(), info.getHeapMaxBytes());
                            addMemoryRow(content, c, 2,
                                    Translator.R("CPRunningAppsRss"),
                                    info.getRssBytes(), info.getSystemTotalBytes());
                        }
                        content.revalidate();
                        content.repaint();
                        dialog.pack();
                    }
                });
            }
        }, "itw-running-app-info").start();

        dialog.setVisible(true);
    }

    private static void addMemoryRow(JPanel panel, GridBagConstraints c, int row,
            String label, long usedBytes, long maxBytes) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 2;
        String valueText = ProcessMemorySupport.formatMegabytes(usedBytes) + " / "
                + ProcessMemorySupport.formatMegabytes(maxBytes);
        panel.add(new JLabel(label + ": " + valueText), c);

        c.gridy = row + 1;
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        int percent = maxBytes > 0 ? (int) Math.min(100, Math.round((usedBytes * 100.0) / maxBytes)) : 0;
        bar.setValue(percent);
        bar.setString(percent + "%");
        panel.add(bar, c);
    }

    private static String formatJvmLabel(ProcessJvmContext jvmContext) {
        if (jvmContext == null) {
            return Translator.R("CPRunningAppsJvmUnknown");
        }
        String vendor = jvmContext.getVendor();
        String jvmVersion = jvmContext.getJvmVersion();
        if ((vendor == null || vendor.isEmpty()) && (jvmVersion == null || jvmVersion.isEmpty())) {
            return Translator.R("CPRunningAppsJvmUnknown");
        }
        if (vendor == null || vendor.isEmpty()) {
            vendor = Translator.R("CPJVMUnknownVendor");
        }
        if (jvmVersion == null || jvmVersion.isEmpty()) {
            jvmVersion = "\u2014";
        }
        return Translator.R("CPRunningAppsJvmLabel", vendor, jvmVersion);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
