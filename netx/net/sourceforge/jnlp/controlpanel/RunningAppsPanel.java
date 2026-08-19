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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.Translator;
import net.sourceforge.jnlp.util.JnlpLockMetadata;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport.RunningProcess;
import net.sourceforge.jnlp.util.ProcessMemorySupport;
import net.sourceforge.jnlp.util.ProcessMemorySupport.MemoryInfo;
import net.sourceforge.jnlp.util.ProcessMemorySupport.ProcessJvmContext;

@SuppressWarnings("serial")
public class RunningAppsPanel extends NamedBorderPanel {

    private static final int MEMORY_BAR_WIDTH = 190;
    private static final int MEMORY_BAR_HEIGHT = 18;
    private static final int REFRESH_INTERVAL_MS = 10_000;

    private final DeploymentConfiguration config;
    private final JPanel listPanel = new JPanel(new GridBagLayout());
    private final JLabel statusLabel = new JLabel();
    private final Timer refreshTimer;
    private final Map<Integer, ProcessRowWidgets> rowWidgetsByPid = new LinkedHashMap<>();

    RunningAppsPanel(DeploymentConfiguration config) {
        super(Translator.R("CPHeadRunningApps"), new GridBagLayout());
        this.config = config;
        listPanel.setName("runningAppsListPanel");
        statusLabel.setName("runningAppsStatusLabel");
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.gridwidth = 2;
        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(2, 2, 4, 4);
        add(new JLabel(Translator.R("CPRunningAppsDescription")), c);

        c.gridx = 2;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        JButton refreshButton = new JButton(Translator.R("CPRunningAppsRefresh"));
        refreshButton.setName("runningAppsRefreshButton");
        refreshButton.addActionListener(e -> refreshList());
        add(refreshButton, c);

        c.gridx = 0;
        c.gridwidth = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.BOTH;
        c.anchor = GridBagConstraints.WEST;
        c.gridy++;
        c.weighty = 1;
        listPanel.setLayout(new GridBagLayout());
        JScrollPane scroll = new JScrollPane(listPanel);
        scroll.setPreferredSize(new Dimension(720, 280));
        scroll.setName("runningAppsScrollPane");
        add(scroll, c);

        c.gridy++;
        c.weighty = 0;
        add(statusLabel, c);

        c.gridy++;
        Component filler = Box.createRigidArea(new Dimension(1, 1));
        c.weighty = 1;
        add(filler, c);

        refreshTimer = new Timer(REFRESH_INTERVAL_MS, new ActionListener() {
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
    }

    private void refreshList() {
        List<RunningProcess> processes = JnlpRunningProcessSupport.listRunningJnlpProcesses();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);

        listPanel.removeAll();
        if (processes.isEmpty()) {
            rowWidgetsByPid.clear();
            c.gridy = 0;
            JLabel emptyLabel = new JLabel(Translator.R("CPRunningAppsNone"));
            emptyLabel.setName("runningAppsEmptyLabel");
            listPanel.add(emptyLabel, c);
            statusLabel.setText("");
        } else {
            Set<Integer> activePids = new HashSet<>();
            for (RunningProcess process : processes) {
                activePids.add(process.getPid());
            }
            rowWidgetsByPid.keySet().retainAll(activePids);

            int row = 0;
            for (RunningProcess process : processes) {
                ProcessRowWidgets widgets = rowWidgetsByPid.get(process.getPid());
                if (widgets == null) {
                    widgets = buildProcessRow(process);
                    rowWidgetsByPid.put(process.getPid(), widgets);
                }
                c.gridy = row++;
                listPanel.add(widgets.panel, c);
                widgets.loadMemory();
            }
            statusLabel.setText(Translator.R("CPRunningAppsCount", processes.size()));
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    private ProcessRowWidgets buildProcessRow(final RunningProcess process) {
        JPanel row = new JPanel(new BorderLayout(8, 4));
        row.setName("runningAppRow-" + process.getPid());
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        ProcessJvmContext jvmContext = ProcessMemorySupport.resolveJvmContext(process);

        JPanel details = new JPanel(new GridBagLayout());
        GridBagConstraints dc = new GridBagConstraints();
        dc.gridx = 0;
        dc.gridy = 0;
        dc.anchor = GridBagConstraints.WEST;
        dc.insets = new Insets(0, 0, 4, 0);
        dc.fill = GridBagConstraints.HORIZONTAL;
        dc.weightx = 1;

        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        String title = process.getAppTitle();
        if (title == null || title.trim().isEmpty()) {
            title = process.getDisplayName();
        }
        titlePanel.add(new JLabel(formatTitleLabel(title, process.getAppVersion())));
        JLabel jvmLabel = new JLabel(formatJvmLabel(jvmContext));
        jvmLabel.setName("runningAppJvmLabel-" + process.getPid());
        titlePanel.add(jvmLabel);
        details.add(titlePanel, dc);

        dc.gridy = 1;
        ProcessRowWidgets widgets = new ProcessRowWidgets(process.getPid(), jvmContext);
        details.add(widgets.memoryPanel, dc);
        row.add(details, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        if (config.isRunningAppsTrimHeapEnabled()) {
            JButton trimHeap = new JButton(Translator.R("CPRunningAppsTrimHeap"));
            trimHeap.setName("runningAppTrimHeap-" + process.getPid());
            trimHeap.addActionListener(e -> trimHeap(process, widgets));
            actions.add(trimHeap);
        }
        if (config.isRunningAppsStopEnabled()) {
            JButton stop = new JButton(Translator.R("CPRunningAppsStop"));
            stop.setName("runningAppStop-" + process.getPid());
            stop.addActionListener(e -> JnlpRunningProcessSupport.stopProcess(
                    process.getPid(), process.getProcessStart(), false));
            actions.add(stop);
        }
        if (config.isRunningAppsForceStopEnabled()) {
            JButton forceStop = new JButton(Translator.R("CPRunningAppsForceStop"));
            forceStop.setName("runningAppForceStop-" + process.getPid());
            forceStop.addActionListener(e -> JnlpRunningProcessSupport.stopProcess(
                    process.getPid(), process.getProcessStart(), true));
            actions.add(forceStop);
        }
        row.add(actions, BorderLayout.EAST);

        widgets.panel = row;
        return widgets;
    }

    private void trimHeap(RunningProcess process, ProcessRowWidgets widgets) {
        boolean ok = ProcessMemorySupport.trimHeap(process.getPid(), widgets.jvmContext);
        if (!ok) {
            JOptionPane.showMessageDialog(this, Translator.R("CPRunningAppsTrimHeapFailed"),
                    Translator.R("CPHeadRunningApps"), JOptionPane.WARNING_MESSAGE);
        }
        widgets.loadMemory();
    }

    private static String formatTitleLabel(String title, String version) {
        StringBuilder label = new StringBuilder(title == null ? "" : title.trim());
        String normalizedVersion = JnlpLockMetadata.normalizeConcreteVersion(version);
        if (normalizedVersion != null) {
            label.append(" v").append(normalizedVersion);
        }
        return label.toString();
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
            return vendor;
        }
        return vendor + " " + jvmVersion;
    }

    private static final class ProcessRowWidgets {
        private JPanel panel;
        private final JPanel memoryPanel;
        private final int pid;
        private final ProcessJvmContext jvmContext;
        private final JProgressBar heapBar;
        private final JProgressBar rssBar;

        private ProcessRowWidgets(int pid, ProcessJvmContext jvmContext) {
            this.pid = pid;
            this.jvmContext = jvmContext;
            memoryPanel = new JPanel(new GridBagLayout());
            GridBagConstraints mc = new GridBagConstraints();
            mc.gridy = 0;
            mc.insets = new Insets(0, 0, 0, 8);
            mc.anchor = GridBagConstraints.WEST;

            mc.gridx = 0;
            memoryPanel.add(new JLabel(Translator.R("CPRunningAppsHeap")), mc);

            mc.gridx = 1;
            mc.weightx = 0.5;
            mc.fill = GridBagConstraints.HORIZONTAL;
            heapBar = createMemoryBar();
            memoryPanel.add(heapBar, mc);

            mc.gridx = 2;
            mc.weightx = 0;
            mc.fill = GridBagConstraints.NONE;
            mc.insets = new Insets(0, 12, 0, 8);
            memoryPanel.add(new JLabel(Translator.R("CPRunningAppsRss")), mc);

            mc.gridx = 3;
            mc.weightx = 0.5;
            mc.fill = GridBagConstraints.HORIZONTAL;
            mc.insets = new Insets(0, 0, 0, 0);
            rssBar = createMemoryBar();
            memoryPanel.add(rssBar, mc);
        }

        private void loadMemory() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final MemoryInfo info = ProcessMemorySupport.readMemoryInfo(pid, jvmContext);
                    javax.swing.SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            updateBars(info);
                        }
                    });
                }
            }, "itw-running-app-mem-" + pid).start();
        }

        private void updateBars(MemoryInfo info) {
            if (!info.isAvailable()) {
                applyBar(heapBar, 0, Translator.R("CPRunningAppsMemoryUnavailable"));
                applyBar(rssBar, 0, Translator.R("CPRunningAppsMemoryUnavailable"));
                return;
            }
            if (info.getHeapMaxBytes() <= 0 && info.getHeapUsedBytes() <= 0) {
                applyBar(heapBar, 0, Translator.R("CPRunningAppsMemoryUnavailable"));
            } else {
                applyBar(heapBar, percent(info.getHeapUsedBytes(), info.getHeapMaxBytes()),
                        formatBarCaption(info.getHeapUsedBytes(), info.getHeapMaxBytes()));
            }
            if (info.getRssBytes() <= 0 || info.getSystemTotalBytes() <= 0) {
                applyBar(rssBar, 0, Translator.R("CPRunningAppsMemoryUnavailable"));
            } else {
                applyBar(rssBar, percent(info.getRssBytes(), info.getSystemTotalBytes()),
                        formatBarCaption(info.getRssBytes(), info.getSystemTotalBytes()));
            }
        }

        private static int percent(long usedBytes, long maxBytes) {
            if (maxBytes <= 0) {
                return 0;
            }
            return (int) Math.min(100, Math.round((usedBytes * 100.0) / maxBytes));
        }

        private static String formatBarCaption(long usedBytes, long maxBytes) {
            return ProcessMemorySupport.formatMegabytes(usedBytes) + " / "
                    + ProcessMemorySupport.formatMegabytes(maxBytes);
        }

        private static void applyBar(JProgressBar bar, int percent, String caption) {
            bar.setValue(percent);
            bar.setString(caption);
        }

        private static JProgressBar createMemoryBar() {
            JProgressBar bar = new JProgressBar(0, 100);
            bar.setStringPainted(true);
            bar.setPreferredSize(new Dimension(MEMORY_BAR_WIDTH, MEMORY_BAR_HEIGHT));
            bar.setMinimumSize(new Dimension(MEMORY_BAR_WIDTH, MEMORY_BAR_HEIGHT));
            bar.setValue(0);
            bar.setString("");
            return bar;
        }
    }
}
