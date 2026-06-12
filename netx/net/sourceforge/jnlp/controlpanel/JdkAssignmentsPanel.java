/* JdkAssignmentsPanel.java -- JNLP URL to JDK assignment settings.
Copyright (C) 2026 IcedTea-Web contributors.
 */
package net.sourceforge.jnlp.controlpanel;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.KnownJvmAssignmentStore;
import net.sourceforge.jnlp.config.KnownJvmStore;
import net.sourceforge.jnlp.cache.CachedJnlpUrlDiscovery;
import net.sourceforge.jnlp.runtime.Translator;
import net.sourceforge.jnlp.util.JnlpAssignmentLauncher;
import net.sourceforge.jnlp.util.JnlpAssignmentLauncher.LaunchResult;
import net.sourceforge.jnlp.util.JvmDescriptor;

@SuppressWarnings("serial")
public class JdkAssignmentsPanel extends NamedBorderPanel implements SettingsPanelReloader {

    private static final int COLUMN_URL = 0;
    private static final int COLUMN_JDK = 1;

    private final DeploymentConfiguration config;
    private final AssignmentTableModel assignmentModel;
    private final JTable assignmentTable;
    private final List<AssignmentRow> assignmentRows = new ArrayList<>();
    private List<JdkChoice> jdkChoices = new ArrayList<>();

    JdkAssignmentsPanel(DeploymentConfiguration config) {
        super(Translator.R("CPHeadJDKAssignments"), new GridBagLayout());
        this.config = config;
        assignmentModel = new AssignmentTableModel();
        assignmentTable = new JTable(assignmentModel);
        assignmentTable.setName("jdkAssignmentsTable");
        assignmentTable.getColumnModel().getColumn(COLUMN_URL).setPreferredWidth(420);
        assignmentTable.getColumnModel().getColumn(COLUMN_JDK).setPreferredWidth(280);
        assignmentTable.setRowHeight(24);
        assignmentTable.getColumnModel().getColumn(COLUMN_JDK).setCellEditor(new JdkComboBoxEditor());
        assignmentTable.getColumnModel().getColumn(COLUMN_JDK).setCellRenderer(new JdkComboBoxRenderer());
        addComponents();
        reloadFromConfiguration();
    }

    @Override
    public void reloadFromConfiguration() {
        refreshJdkChoices();
        assignmentRows.clear();

        Map<String, Integer> assignedByUrl = new HashMap<>();
        for (KnownJvmAssignmentStore.JvmAssignment assignment : KnownJvmAssignmentStore.getAssignments(config)) {
            assignedByUrl.put(
                    JnlpAssignmentLauncher.canonicalizeJnlpUrl(assignment.getJnlpUrl()),
                    assignment.getJdkIndex());
        }

        Set<String> seenUrls = new LinkedHashSet<>();
        for (String cachedUrl : CachedJnlpUrlDiscovery.discoverCachedJnlpUrls()) {
            seenUrls.add(cachedUrl);
            assignmentRows.add(new AssignmentRow(cachedUrl, assignedByUrl.getOrDefault(cachedUrl, 0)));
        }
        for (KnownJvmAssignmentStore.JvmAssignment assignment : KnownJvmAssignmentStore.getAssignments(config)) {
            String url = JnlpAssignmentLauncher.canonicalizeJnlpUrl(assignment.getJnlpUrl());
            if (!seenUrls.contains(url)) {
                assignmentRows.add(new AssignmentRow(url, assignment.getJdkIndex()));
            }
        }
        assignmentModel.fireTableDataChanged();
    }

    void refreshJdkChoiceList() {
        refreshJdkChoices();
        assignmentModel.fireTableDataChanged();
    }

    private void refreshJdkChoices() {
        jdkChoices = new ArrayList<>();
        jdkChoices.add(new JdkChoice(0, Translator.R("CPJDKAssignmentsDefault"), null));
        List<String> homes = KnownJvmStore.getKnownJvmHomes(config);
        for (int i = 0; i < homes.size(); i++) {
            JvmDescriptor descriptor = JvmDescriptor.describe(homes.get(i));
            jdkChoices.add(new JdkChoice(i + 1, descriptor.getDisplayName(), homes.get(i)));
        }
    }

    private void addComponents() {
        JLabel description = new JLabel("<html>" + Translator.R("CPJDKAssignmentsDescription") + "<hr /></html>");
        JScrollPane scrollPane = new JScrollPane(assignmentTable);
        scrollPane.setPreferredSize(new Dimension(720, 200));

        JButton addButton = new JButton(Translator.R("CPJDKAssignmentsAdd"));
        addButton.setName("jdkAssignmentAddButton");
        addButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                assignmentRows.add(new AssignmentRow("", 0));
                assignmentModel.fireTableDataChanged();
                int row = assignmentRows.size() - 1;
                assignmentTable.getSelectionModel().setSelectionInterval(row, row);
            }
        });

        JButton removeButton = new JButton(Translator.R("CPJDKAssignmentsRemove"));
        removeButton.setName("jdkAssignmentRemoveButton");
        removeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int selected = assignmentTable.getSelectedRow();
                if (selected < 0 || selected >= assignmentRows.size()) {
                    return;
                }
                assignmentRows.remove(selected);
                assignmentModel.fireTableDataChanged();
                persistAssignments();
            }
        });

        JButton launchButton = new JButton(Translator.R("CPJDKAssignmentsLaunchSelected"));
        launchButton.setName("jdkAssignmentLaunchButton");
        launchButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                launchSelectedAssignment();
            }
        });

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.gridwidth = 4;
        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(2, 2, 4, 4);
        add(description, c);

        c.gridy++;
        c.weighty = 0.4;
        add(scrollPane, c);

        c.gridy++;
        c.weighty = 0;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        add(addButton, c);

        GridBagConstraints remove = (GridBagConstraints) c.clone();
        remove.gridx = 1;
        add(removeButton, remove);

        GridBagConstraints launch = (GridBagConstraints) c.clone();
        launch.gridx = 2;
        add(launchButton, launch);

        if (jdkChoices.isEmpty()) {
            c.gridx = 3;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            add(new JLabel(Translator.R("CPJDKAssignmentsNoJdks")), c);
        }
    }

    private void persistAssignments() {
        List<KnownJvmAssignmentStore.JvmAssignment> assignments = new ArrayList<>();
        for (AssignmentRow row : assignmentRows) {
            if (row.jnlpUrl == null || row.jnlpUrl.trim().isEmpty() || row.jdkIndex < 1) {
                continue;
            }
            assignments.add(new KnownJvmAssignmentStore.JvmAssignment(row.jdkIndex, 0, row.jnlpUrl.trim()));
        }
        KnownJvmAssignmentStore.setAssignments(config, assignments);
    }

    private void launchSelectedAssignment() {
        int selected = assignmentTable.getSelectedRow();
        if (selected < 0 || selected >= assignmentRows.size()) {
            JOptionPane.showMessageDialog(this, Translator.R("CPJDKAssignmentsLaunchNoUrl"),
                    Translator.R("CPJDKAssignmentsLaunchSelected"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        String jnlpUrl = assignmentRows.get(selected).jnlpUrl;
        if (jnlpUrl == null || jnlpUrl.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, Translator.R("CPJDKAssignmentsLaunchNoUrl"),
                    Translator.R("CPJDKAssignmentsLaunchSelected"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        final String url = JnlpAssignmentLauncher.canonicalizeJnlpUrl(jnlpUrl);
        final String javaHome = KnownJvmAssignmentStore.findJvmHomeForJnlpUrl(config, url);
        if (JnlpAssignmentLauncher.resolveJavawsBin() == null) {
            showLaunchError(Translator.R("CPJDKAssignmentsLaunchNoLauncher"));
            return;
        }
        openLaunchOutputDialog(url, javaHome);
    }

    private void openLaunchOutputDialog(final String url, final String javaHome) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                final JTextArea outputArea = new JTextArea(JnlpAssignmentLauncher.formatLaunchMetrics(url, javaHome));
                outputArea.setName("jdkAssignmentLaunchOutputArea");
                outputArea.setEditable(false);
                outputArea.setLineWrap(false);
                outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, outputArea.getFont().getSize()));
                outputArea.setCaretPosition(outputArea.getDocument().getLength());
                JScrollPane scrollPane = new JScrollPane(outputArea);
                scrollPane.setPreferredSize(new Dimension(720, 360));

                final JDialog dialog = new JDialog(
                        SwingUtilities.getWindowAncestor(JdkAssignmentsPanel.this),
                        Translator.R("CPJDKAssignmentsLaunchOutputTitle"),
                        JDialog.ModalityType.APPLICATION_MODAL);
                dialog.setName("jdkAssignmentLaunchOutputDialog");
                dialog.setLayout(new GridBagLayout());
                GridBagConstraints c = new GridBagConstraints();
                c.gridx = 0;
                c.gridy = 0;
                c.weightx = 1;
                c.weighty = 1;
                c.fill = GridBagConstraints.BOTH;
                c.insets = new Insets(8, 8, 4, 8);
                dialog.add(scrollPane, c);

                JButton okButton = new JButton(Translator.R("ButOk"));
                okButton.setName("jdkAssignmentLaunchOutputOkButton");
                okButton.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        dialog.dispose();
                    }
                });
                c.gridy = 1;
                c.weighty = 0;
                c.fill = GridBagConstraints.NONE;
                c.anchor = GridBagConstraints.EAST;
                c.insets = new Insets(4, 8, 8, 8);
                dialog.add(okButton, c);
                dialog.pack();
                dialog.setLocationRelativeTo(JdkAssignmentsPanel.this);

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            LaunchResult result = JnlpAssignmentLauncher.launchWithStreamingOutput(
                                    url, javaHome, 0L, new JnlpAssignmentLauncher.LaunchOutputConsumer() {
                                        @Override
                                        public void accept(final String chunk) {
                                            appendLaunchOutput(outputArea, chunk);
                                        }
                                    });
                            appendLaunchOutput(outputArea, formatLaunchCompletion(result));
                        } catch (IOException ex) {
                            appendLaunchOutput(outputArea,
                                    Translator.R("CPJDKAssignmentsLaunchFailed") + "\n" + ex.getMessage() + "\n");
                        }
                    }
                }, "jdk-assignment-launch").start();

                dialog.setVisible(true);
            }
        });
    }

    private static void appendLaunchOutput(final JTextArea outputArea, final String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                outputArea.append(text);
                outputArea.setCaretPosition(outputArea.getDocument().getLength());
            }
        });
    }

    private String formatLaunchCompletion(LaunchResult result) {
        StringBuilder text = new StringBuilder();
        text.append('\n').append(Translator.R("CPJDKAssignmentsLaunchOutputCommand")).append('\n');
        text.append(result.getCommandLine()).append("\n\n");
        if (result.getExitCode() != null) {
            text.append(Translator.R("CPJDKAssignmentsLaunchOutputExit", result.getExitCode())).append('\n');
        } else if (result.isStillRunning()) {
            text.append(Translator.R("CPJDKAssignmentsLaunchOutputStillRunning")).append('\n');
        }
        return text.toString();
    }

    private void showLaunchError(final String message) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                JOptionPane.showMessageDialog(JdkAssignmentsPanel.this, message,
                        Translator.R("CPJDKAssignmentsLaunchSelected"), JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private String labelForJdkIndex(int jdkIndex) {
        if (jdkIndex <= 0) {
            return Translator.R("CPJDKAssignmentsDefault");
        }
        for (JdkChoice choice : jdkChoices) {
            if (choice.jdkIndex == jdkIndex) {
                return choice.label;
            }
        }
        return Translator.R("CPJDKAssignmentsMissingJdk", jdkIndex);
    }

    private final class AssignmentTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return assignmentRows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            if (column == COLUMN_URL) {
                return Translator.R("CPJDKAssignmentsColUrl");
            }
            return Translator.R("CPJDKAssignmentsColJdk");
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            AssignmentRow row = assignmentRows.get(rowIndex);
            if (columnIndex == COLUMN_URL) {
                return row.jnlpUrl;
            }
            return labelForJdkIndex(row.jdkIndex);
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            AssignmentRow row = assignmentRows.get(rowIndex);
            if (columnIndex == COLUMN_URL) {
                row.jnlpUrl = value == null ? "" : String.valueOf(value);
            } else if (value instanceof Integer) {
                row.jdkIndex = (Integer) value;
            } else if (value instanceof JdkChoice) {
                row.jdkIndex = ((JdkChoice) value).jdkIndex;
            }
            persistAssignments();
        }
    }

    private static final class AssignmentRow {
        private String jnlpUrl;
        private int jdkIndex;

        private AssignmentRow(String jnlpUrl, int jdkIndex) {
            this.jnlpUrl = jnlpUrl;
            this.jdkIndex = jdkIndex;
        }
    }

    private static final class JdkChoice {
        private final int jdkIndex;
        private final String label;

        private JdkChoice(int jdkIndex, String label, String homePath) {
            this.jdkIndex = jdkIndex;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final class JdkComboBoxEditor extends AbstractCellEditor implements TableCellEditor {
        private JComboBox<JdkChoice> comboBox;

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
                int row, int column) {
            comboBox = new JComboBox<>();
            for (JdkChoice choice : jdkChoices) {
                comboBox.addItem(choice);
            }
            int rowIndex = table.convertRowIndexToModel(row);
            int jdkIndex = assignmentRows.get(rowIndex).jdkIndex;
            for (int i = 0; i < comboBox.getItemCount(); i++) {
                if (comboBox.getItemAt(i).jdkIndex == jdkIndex) {
                    comboBox.setSelectedIndex(i);
                    break;
                }
            }
            if (comboBox.getSelectedIndex() < 0 && comboBox.getItemCount() > 0) {
                comboBox.setSelectedIndex(0);
            }
            comboBox.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    fireEditingStopped();
                }
            });
            return comboBox;
        }

        @Override
        public Object getCellEditorValue() {
            JdkChoice selected = (JdkChoice) comboBox.getSelectedItem();
            return selected == null ? 0 : selected.jdkIndex;
        }
    }

    private final class JdkComboBoxRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        }
    }
}
