/* JVMPanel.java -- JVM settings in the IcedTea-Web control panel.
Copyright (C) 2012, Red Hat, Inc.
 */
package net.sourceforge.jnlp.controlpanel;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.naming.ConfigurationException;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.DefaultListCellRenderer;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.JdkMatchStrategy;
import net.sourceforge.jnlp.config.KnownJvmStore;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.runtime.Translator;
import net.sourceforge.jnlp.util.JvmAutodetector;
import net.sourceforge.jnlp.util.JvmDescriptor;
import net.sourceforge.jnlp.util.JvmProbeSupport;
import net.sourceforge.jnlp.util.logging.OutputController;

@SuppressWarnings("serial")
public class JVMPanel extends NamedBorderPanel implements SettingsPanelReloader {

    public static class JvmValidationResult {

        public static enum STATE {
            EMPTY, NOT_DIR, NOT_VALID_DIR, NOT_VALID_JDK, VALID_JDK;
        }
        public final String formattedText;
        public final STATE id;
        private final String stds;

        public JvmValidationResult(String formattedText, STATE id, String stdouts) {
            this.id = id;
            this.formattedText = formattedText;
            this.stds = stdouts;
        }

        public String getReportableOutput() {
            return stds;
        }
    }

    private static final int COLUMN_STATUS = 0;
    private static final int COLUMN_VENDOR = 1;
    private static final int COLUMN_VERSION = 2;
    private static final int COLUMN_PATH = 3;

    /** Compile-time only; not a deployment.properties setting. Keep false for a clean JDK Settings UI. */
    private static final boolean SHOW_KNOWN_JVM_LIST_DESCRIPTION = false;

    private final DeploymentConfiguration config;
    private File lastPath = new File("/usr/lib/jvm/");
    private final DefaultTableModel knownJvmModel;
    private final JTable knownJvmTable;
    private final List<JvmDescriptor> knownJvms = new ArrayList<>();
    private JTextField pluginJvmArgumentsField;
    private JComboBox<JdkMatchStrategy> matchStrategyCombo;

    JVMPanel(DeploymentConfiguration config) {
        super(Translator.R("CPHeadJDKSettings"), new GridBagLayout());
        this.config = config;
        knownJvmModel = new DefaultTableModel(
                new Object[] {
                    Translator.R("CPJVMColStatus"),
                    Translator.R("CPJVMColVendor"),
                    Translator.R("CPJVMColVersion"),
                    Translator.R("CPJVMColPath")
                }, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        knownJvmTable = new JTable(knownJvmModel);
        knownJvmTable.getColumnModel().getColumn(COLUMN_STATUS).setMaxWidth(36);
        knownJvmTable.getColumnModel().getColumn(COLUMN_STATUS).setMinWidth(36);
        knownJvmTable.getColumnModel().getColumn(COLUMN_VENDOR).setPreferredWidth(160);
        knownJvmTable.getColumnModel().getColumn(COLUMN_VERSION).setPreferredWidth(100);
        knownJvmTable.getColumnModel().getColumn(COLUMN_PATH).setPreferredWidth(360);
        knownJvmTable.setRowHeight(22);
        knownJvmTable.setName("jvmKnownTable");
        knownJvmTable.getColumnModel().getColumn(COLUMN_STATUS)
                .setCellRenderer(new JvmStatusCellRenderer());
        addComponents();
        reloadKnownJvmsFromConfig();
    }

    @Override
    public void reloadFromConfiguration() {
        reloadKnownJvmsFromConfig();
        if (pluginJvmArgumentsField != null) {
            String args = config.getProperty(DeploymentConfiguration.KEY_PLUGIN_JVM_ARGUMENTS);
            pluginJvmArgumentsField.setText(args == null ? "" : args);
        }
        if (matchStrategyCombo != null) {
            ControlPanelUiUtils.setComboBoxSelectionWithoutNotify(
                    matchStrategyCombo, KnownJvmStore.getMatchStrategy(config));
        }
    }

    void resetTestFieldArgumentsExec() {
        reloadKnownJvmsFromConfig();
    }

    private void reloadKnownJvmsFromConfig() {
        knownJvms.clear();
        knownJvmModel.setRowCount(0);
        for (String home : KnownJvmStore.getKnownJvmHomes(config)) {
            addKnownJvm(home, false);
        }
    }

    private void persistKnownJvms() {
        List<String> homes = new ArrayList<>();
        for (JvmDescriptor descriptor : knownJvms) {
            homes.add(descriptor.getHomePath());
        }
        KnownJvmStore.setKnownJvmHomes(config, homes);
    }

    private void addKnownJvm(String homePath, boolean persist) {
        String normalized = homePath == null ? "" : homePath.trim();
        if (normalized.isEmpty()) {
            return;
        }
        for (JvmDescriptor existing : knownJvms) {
            if (existing.getHomePath().equals(normalized)) {
                return;
            }
        }
        JvmDescriptor descriptor = JvmDescriptor.describe(normalized);
        knownJvms.add(descriptor);
        knownJvmModel.addRow(new Object[] {
            statusLabel(descriptor),
            vendorLabel(descriptor),
            versionLabel(descriptor),
            descriptor.getHomePath()
        });
        if (persist) {
            persistKnownJvms();
        }
    }

    private static String statusLabel(JvmDescriptor descriptor) {
        return descriptor.isValid() ? "\u2713" : "\u2717";
    }

    private static String vendorLabel(JvmDescriptor descriptor) {
        String flavour = descriptor.getFlavour();
        return flavour.isEmpty() ? Translator.R("CPJVMUnknownVendor") : flavour;
    }

    private static String versionLabel(JvmDescriptor descriptor) {
        return descriptor.getVersion();
    }

    private void updateJvmRow(int row, JvmDescriptor descriptor) {
        knownJvms.set(row, descriptor);
        knownJvmModel.setValueAt(statusLabel(descriptor), row, COLUMN_STATUS);
        knownJvmModel.setValueAt(vendorLabel(descriptor), row, COLUMN_VENDOR);
        knownJvmModel.setValueAt(versionLabel(descriptor), row, COLUMN_VERSION);
        knownJvmModel.setValueAt(descriptor.getHomePath(), row, COLUMN_PATH);
    }

    private void editSelectedJvm() {
        int selected = knownJvmTable.getSelectedRow();
        if (selected < 0 || selected >= knownJvms.size()) {
            return;
        }
        JvmDescriptor current = knownJvms.get(selected);
        File startDir = new File(current.getHomePath());
        if (!startDir.isDirectory()) {
            startDir = startDir.getParentFile();
        }
        if (startDir == null || !startDir.exists()) {
            startDir = lastPath;
        }
        JFileChooser jfch = createJvmDirectoryChooser(startDir, Translator.R("CPJVMEdit"));
        int i = jfch.showOpenDialog(JVMPanel.this);
        if (i != JFileChooser.APPROVE_OPTION || jfch.getSelectedFile() == null) {
            return;
        }
        String newPath = jfch.getSelectedFile().getAbsolutePath();
        if (newPath.equals(current.getHomePath())) {
            return;
        }
        for (int row = 0; row < knownJvms.size(); row++) {
            if (row != selected && knownJvms.get(row).getHomePath().equals(newPath)) {
                return;
            }
        }
        lastPath = jfch.getSelectedFile().getParentFile();
        updateJvmRow(selected, JvmDescriptor.describe(newPath));
        persistKnownJvms();
    }

    private void removeSelectedJvm() {
        int selected = knownJvmTable.getSelectedRow();
        if (selected < 0 || selected >= knownJvms.size()) {
            return;
        }
        knownJvms.remove(selected);
        knownJvmModel.removeRow(selected);
        persistKnownJvms();
    }

    private void moveSelectedJvm(int delta) {
        int selected = knownJvmTable.getSelectedRow();
        if (selected < 0 || selected >= knownJvms.size()) {
            return;
        }
        int target = selected + delta;
        if (target < 0 || target >= knownJvms.size()) {
            return;
        }
        JvmDescriptor moved = knownJvms.remove(selected);
        knownJvms.add(target, moved);
        knownJvmModel.moveRow(selected, selected, target);
        knownJvmTable.getSelectionModel().setSelectionInterval(target, target);
        persistKnownJvms();
    }

    private void autodetectKnownJvms() {
        if (KnownJvmStore.applyAutodetectedJvms(config)) {
            reloadKnownJvmsFromConfig();
        }
    }

    /**
     * Discard the in-memory JDK table view and reload {@code deployment.properties}
     * from disk (picks up launch-time autodetect writes while Settings is open).
     */
    private void refreshKnownJvmsFromProperties() {
        if (config.hasPendingChanges()) {
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    Translator.R("CPJVMRefreshDiscardPending"),
                    Translator.R("CPJVMRefresh"),
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
        }
        Window ancestor = SwingUtilities.getWindowAncestor(this);
        if (ancestor instanceof ControlPanel) {
            ((ControlPanel) ancestor).reloadConfigurationFromDisk();
            return;
        }
        try {
            config.load();
        } catch (ConfigurationException ex) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, ex);
            JOptionPane.showMessageDialog(this, ex);
            return;
        }
        reloadFromConfiguration();
    }

    private static JFileChooser createJvmDirectoryChooser(File startDir, String dialogTitle) {
        JFileChooser jfch = startDir != null && startDir.exists()
                ? new JFileChooser(startDir) : new JFileChooser();
        jfch.setDialogTitle(dialogTitle);
        jfch.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        jfch.setApproveButtonText(Translator.R("ButSelect"));
        return jfch;
    }

    private void addComponents() {
        matchStrategyCombo = new JComboBox<>(JdkMatchStrategy.values());
        matchStrategyCombo.setName("jdkMatchStrategyCombo");
        matchStrategyCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof JdkMatchStrategy) {
                    setText(((JdkMatchStrategy) value).getConfigValue());
                }
                return this;
            }
        });
        matchStrategyCombo.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                if (e.getStateChange() == ItemEvent.SELECTED) {
                    JdkMatchStrategy selected = (JdkMatchStrategy) matchStrategyCombo.getSelectedItem();
                    KnownJvmStore.setMatchStrategy(config, selected);
                }
            }
        });
        ControlPanelUiUtils.setComboBoxSelectionWithoutNotify(
                matchStrategyCombo, KnownJvmStore.getMatchStrategy(config));

        final JLabel matchStrategyLabel = new JLabel(Translator.R("CPJDKMatchStrategy") + ":");
        final JLabel description = new JLabel("<html>" + Translator.R("CPJVMPluginArguments") + "<hr /></html>");
        pluginJvmArgumentsField = new JTextField(25);
        pluginJvmArgumentsField.getDocument().addDocumentListener(
                new DocumentAdapter(config, DeploymentConfiguration.KEY_PLUGIN_JVM_ARGUMENTS));
        pluginJvmArgumentsField.setText(config.getProperty(DeploymentConfiguration.KEY_PLUGIN_JVM_ARGUMENTS));

        final JLabel descriptionExec = SHOW_KNOWN_JVM_LIST_DESCRIPTION
                ? new JLabel("<html>" + Translator.R("CPJVMKnownListDescription") + "<hr /></html>")
                : null;
        final JScrollPane knownJvmScroll = new JScrollPane(knownJvmTable);
        knownJvmScroll.setName("jvmKnownScrollPane");
        knownJvmTable.setPreferredScrollableViewportSize(new Dimension(400, 132));

        final JButton autodetectJvm = new JButton(Translator.R("CPJVMAutodetect"));
        autodetectJvm.setName("jvmAutodetectButton");
        autodetectJvm.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                autodetectKnownJvms();
            }
        });

        final JButton refreshJvm = new JButton(Translator.R("CPJVMRefresh"));
        refreshJvm.setName("jvmRefreshButton");
        refreshJvm.setToolTipText(Translator.R("CPJVMRefreshTip"));
        refreshJvm.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshKnownJvmsFromProperties();
            }
        });

        final JButton addJvm = new JButton(Translator.R("CPJVMAdd"));
        addJvm.setName("jvmAddButton");
        addJvm.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser jfch = createJvmDirectoryChooser(lastPath, Translator.R("CPJVMAdd"));
                int i = jfch.showOpenDialog(JVMPanel.this);
                if (i == JFileChooser.APPROVE_OPTION && jfch.getSelectedFile() != null) {
                    lastPath = jfch.getSelectedFile().getParentFile();
                    addKnownJvm(jfch.getSelectedFile().getAbsolutePath(), true);
                }
            }
        });

        final JButton editJvm = new JButton(Translator.R("CPJVMEdit"));
        editJvm.setName("jvmEditButton");
        editJvm.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                editSelectedJvm();
            }
        });

        final JButton removeJvm = new JButton(Translator.R("CPJVMRemove"));
        removeJvm.setName("jvmRemoveButton");
        removeJvm.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                removeSelectedJvm();
            }
        });

        final JButton moveUpJvm = new JButton(Translator.R("CPJVMMoveUp"));
        moveUpJvm.setName("jvmMoveUpButton");
        moveUpJvm.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moveSelectedJvm(-1);
            }
        });

        final JButton moveDownJvm = new JButton(Translator.R("CPJVMMoveDown"));
        moveDownJvm.setName("jvmMoveDownButton");
        moveDownJvm.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                moveSelectedJvm(1);
            }
        });

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.gridwidth = 4;
        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(2, 2, 4, 4);

        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        this.add(matchStrategyLabel, c);
        GridBagConstraints strategyCombo = (GridBagConstraints) c.clone();
        strategyCombo.gridx = 1;
        strategyCombo.weightx = 1;
        strategyCombo.gridwidth = 3;
        strategyCombo.fill = GridBagConstraints.HORIZONTAL;
        this.add(matchStrategyCombo, strategyCombo);
        c.gridy++;
        c.gridwidth = 4;
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.BOTH;
        this.add(description, c);
        c.gridy++;
        this.add(pluginJvmArgumentsField, c);
        c.gridy++;
        if (descriptionExec != null) {
            this.add(descriptionExec, c);
            c.gridy++;
        }
        c.weighty = 1;
        this.add(knownJvmScroll, c);
        c.gridy++;
        c.weighty = 0;
        GridBagConstraints buttonRow = (GridBagConstraints) c.clone();
        buttonRow.fill = GridBagConstraints.NONE;
        buttonRow.gridwidth = 1;
        buttonRow.weightx = 0;
        this.add(autodetectJvm, buttonRow);
        GridBagConstraints refreshButton = (GridBagConstraints) buttonRow.clone();
        refreshButton.gridx = 1;
        this.add(refreshJvm, refreshButton);
        GridBagConstraints addButton = (GridBagConstraints) buttonRow.clone();
        addButton.gridx = 2;
        this.add(addJvm, addButton);
        GridBagConstraints editButton = (GridBagConstraints) buttonRow.clone();
        editButton.gridx = 3;
        this.add(editJvm, editButton);
        c.gridy++;
        GridBagConstraints orderRow = (GridBagConstraints) buttonRow.clone();
        orderRow.gridy = c.gridy;
        this.add(removeJvm, orderRow);
        GridBagConstraints moveUpButton = (GridBagConstraints) orderRow.clone();
        moveUpButton.gridx = 1;
        this.add(moveUpJvm, moveUpButton);
        GridBagConstraints moveDownButton = (GridBagConstraints) orderRow.clone();
        moveDownButton.gridx = 2;
        this.add(moveDownJvm, moveDownButton);
    }

    public static JvmValidationResult validateJvm(String cmd) {
        if (cmd == null || cmd.trim().equals("")) {
            return new JvmValidationResult("<span color=\"orange\">" + Translator.R("CPJVMvalueNotSet") + "</span>",
                    JvmValidationResult.STATE.EMPTY, "");
        }
        String validationResult = "";
        File jreDirFile = new File(cmd);
        JvmValidationResult.STATE latestOne = JvmValidationResult.STATE.EMPTY;
        if (jreDirFile.isDirectory()) {
            validationResult += "<span color=\"green\">" + Translator.R("CPJVMisDir") + "</span><br />";
        } else {
            validationResult += "<span color=\"red\">" + Translator.R("CPJVMnotDir") + "</span><br />";
            latestOne = JvmValidationResult.STATE.NOT_DIR;
        }
        File javaFile = new File(cmd + File.separator + "bin" +
                                       File.separator + "java" +
                                       (JNLPRuntime.isWindows() ? ".exe" : ""));
        if (javaFile.isFile()) {
            validationResult += "<span color=\"green\">" + Translator.R("CPJVMjava") + "</span><br />";
        } else {
            validationResult += "<span color=\"red\">" + Translator.R("CPJVMnoJava") + "</span><br />";
            if (latestOne != JvmValidationResult.STATE.NOT_DIR) {
                latestOne = JvmValidationResult.STATE.NOT_VALID_JDK;
            }
        }
        int probedMajor = JvmProbeSupport.probeMajorVersion(cmd);
        String processErrorStream = "";
        String processStdOutStream = "";
        Integer r = null;
        if (probedMajor > 0) {
            r = 0;
            String versionLine = JvmProbeSupport.syntheticVersionOutput(probedMajor);
            processErrorStream = versionLine.toLowerCase();
            OutputController.getLogger().log(OutputController.Level.MESSAGE_DEBUG, versionLine);
        }
        if (r == null) {
            validationResult += "<span color=\"red\">" + Translator.R("CPJVMnotLaunched") + "</span>";
            if (latestOne != JvmValidationResult.STATE.NOT_DIR) {
                latestOne = JvmValidationResult.STATE.NOT_VALID_JDK;
            }
            return new JvmValidationResult(validationResult, latestOne, "");
        }
        String reportableOutputs = processErrorStream + "\n" + processStdOutStream;
        if (r != 0) {
            validationResult += "<span color=\"red\">" + Translator.R("CPJVMnoSuccess") + "</span>";
            if (latestOne != JvmValidationResult.STATE.NOT_DIR) {
                latestOne = JvmValidationResult.STATE.NOT_VALID_JDK;
            }
            return new JvmValidationResult(validationResult, latestOne, reportableOutputs);
        }
        boolean findRT = false;
        boolean jdk9up = false;
        for (int i = 9; i <= 99; i++) {
            if (processErrorStream.contains("\"" + i) || processStdOutStream.contains("\"" + i)) {
                jdk9up = true;
            }
        }
        if (jdk9up) {
            validationResult += "<span color=\"green\">" + Translator.R("CPJVMjdk9") + "</span><br />";
            findRT = false;
        } else if (processErrorStream.contains("1.8.0") || processStdOutStream.contains("1.8.0")) {
            validationResult += "<span color=\"#00EE00\">" + Translator.R("CPJVMjdk8") + "</span><br />";
            findRT = true;
        } else if (processErrorStream.contains("1.7.0") || processStdOutStream.contains("1.7.0")) {
            validationResult += "<span color=\"#EE0000\">" + Translator.R("CPJVMjdk7") + "</span><br />";
            if (latestOne != JvmValidationResult.STATE.NOT_DIR) {
                latestOne = JvmValidationResult.STATE.NOT_VALID_JDK;
                findRT = true;
            }
        } else if (processErrorStream.contains("1.6.0") || processStdOutStream.contains("1.6.0")) {
            validationResult += "<span color=\"#EE0000\">" + Translator.R("CPJVMjdk6") + "</span><br />";
            if (latestOne != JvmValidationResult.STATE.NOT_DIR) {
                latestOne = JvmValidationResult.STATE.NOT_VALID_JDK;
                findRT = true;
            }
        } else {
            validationResult += "<span color=\"yellow\">" + Translator.R("CPJVMjdk") + "</span><br />";
            if (latestOne != JvmValidationResult.STATE.NOT_DIR) {
                latestOne = JvmValidationResult.STATE.NOT_VALID_JDK;
                findRT = false;
            }
        }
        if (findRT) {
            File rtFile = new File(cmd + File.separator + "lib" + File.separator + "rt.jar");
            if (rtFile.isFile()) {
                validationResult += "<span color=\"green\">" + Translator.R("CPJVMrtJar") + "</span><br />";
            } else {
                validationResult += "<span color=\"red\">" + Translator.R("CPJVMnoRtJar") + "</span><br />";
                if (latestOne != JvmValidationResult.STATE.NOT_DIR) {
                    latestOne = JvmValidationResult.STATE.NOT_VALID_JDK;
                }
            }
        }
        if (processErrorStream.contains("openjdk") || processStdOutStream.contains("openjdk")) {
            validationResult += "<span color=\"#00EE00\">" + Translator.R("CPJVMopenJdkFound") + "</span>";
            return new JvmValidationResult(validationResult, JvmValidationResult.STATE.VALID_JDK, reportableOutputs);
        }
        if (processErrorStream.contains("ibm") || processStdOutStream.contains("ibm")) {
            validationResult += "<span color=\"green\">" + Translator.R("CPJVMibmFound") + "</span>";
            if (latestOne != JvmValidationResult.STATE.NOT_DIR) {
                latestOne = JvmValidationResult.STATE.NOT_VALID_JDK;
            }
            return new JvmValidationResult(validationResult, latestOne, reportableOutputs);
        }
        if (processErrorStream.contains("gij") || processStdOutStream.contains("gij")) {
            validationResult += "<span color=\"orange\">" + Translator.R("CPJVMgijFound") + "</span>";
            if (latestOne != JvmValidationResult.STATE.NOT_DIR) {
                latestOne = JvmValidationResult.STATE.NOT_VALID_JDK;
            }
            return new JvmValidationResult(validationResult, latestOne, reportableOutputs);
        }
        if (processErrorStream.contains("oracle") || processStdOutStream.contains("oracle")
                || processErrorStream.contains("java(tm)") || processStdOutStream.contains("java(tm)")) {
            validationResult += "<span color=\"green\">" + Translator.R("CPJVMoracleFound") + "</span>";
            return new JvmValidationResult(validationResult, JvmValidationResult.STATE.VALID_JDK, reportableOutputs);
        }
        if (processErrorStream.contains("corretto") || processStdOutStream.contains("corretto")
                || processErrorStream.contains("temurin") || processStdOutStream.contains("temurin")) {
            validationResult += "<span color=\"#00EE00\">" + Translator.R("CPJVMopenJdkFound") + "</span>";
            return new JvmValidationResult(validationResult, JvmValidationResult.STATE.VALID_JDK, reportableOutputs);
        }
        if (jdk9up && latestOne != JvmValidationResult.STATE.NOT_DIR) {
            validationResult += "<span color=\"#00EE00\">" + Translator.R("CPJVMopenJdkFound") + "</span>";
            return new JvmValidationResult(validationResult, JvmValidationResult.STATE.VALID_JDK, reportableOutputs);
        }
        validationResult += "<span color=\"orange\">" + Translator.R("CPJVMstrangeProcess") + "</span>";
        return new JvmValidationResult(validationResult, JvmValidationResult.STATE.NOT_VALID_JDK, reportableOutputs);
    }

    private static final class JvmStatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if ("\u2713".equals(String.valueOf(value))) {
                setForeground(new Color(0, 140, 0));
            } else if ("\u2717".equals(String.valueOf(value))) {
                setForeground(new Color(180, 0, 0));
            } else {
                setForeground(table.getForeground());
            }
            setHorizontalAlignment(CENTER);
            return component;
        }
    }
}
