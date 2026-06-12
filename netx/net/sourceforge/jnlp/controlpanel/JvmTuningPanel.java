/* JvmTuningPanel.java -- JNLP JVM tuning settings.
Copyright (C) 2026 IcedTea-Web contributors.
 */
package net.sourceforge.jnlp.controlpanel;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractCellEditor;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.JvmTuningGcType;
import net.sourceforge.jnlp.config.KnownJvmStore;
import net.sourceforge.jnlp.config.KnownJvmTuningStore;
import net.sourceforge.jnlp.config.KnownJvmTuningStore.JvmTuningEntry;
import net.sourceforge.jnlp.runtime.Translator;
import net.sourceforge.jnlp.util.JvmDescriptor;
import net.sourceforge.jnlp.util.JvmTuningCapabilities;

@SuppressWarnings("serial")
public class JvmTuningPanel extends NamedBorderPanel implements SettingsPanelReloader {

    private static final int COLUMN_URL = 0;
    private static final int COLUMN_JDK = 1;
    private static final int COLUMN_GC = 2;
    private static final int COLUMN_MAX_HEAP = 3;
    private static final int COLUMN_SOFT_MAX = 4;

    private static final int SOFT_MAX_MIN = 25;
    private static final int SOFT_MAX_MAX = 100;

    private final DeploymentConfiguration config;
    private final TuningTableModel tuningModel;
    private final JTable tuningTable;
    private final List<TuningRow> tuningRows = new ArrayList<>();
    private List<JdkChoice> jdkChoices = new ArrayList<>();

    JvmTuningPanel(DeploymentConfiguration config) {
        super(Translator.R("CPHeadJvmTuning"), new GridBagLayout());
        this.config = config;
        tuningModel = new TuningTableModel();
        tuningTable = new JTable(tuningModel);
        tuningTable.setName("jvmTuningTable");
        tuningTable.getColumnModel().getColumn(COLUMN_URL).setPreferredWidth(260);
        tuningTable.getColumnModel().getColumn(COLUMN_JDK).setPreferredWidth(180);
        tuningTable.getColumnModel().getColumn(COLUMN_GC).setPreferredWidth(110);
        tuningTable.getColumnModel().getColumn(COLUMN_MAX_HEAP).setPreferredWidth(110);
        tuningTable.getColumnModel().getColumn(COLUMN_SOFT_MAX).setPreferredWidth(90);
        tuningTable.setRowHeight(24);
        tuningTable.getColumnModel().getColumn(COLUMN_JDK).setCellEditor(new JdkComboBoxEditor());
        tuningTable.getColumnModel().getColumn(COLUMN_JDK).setCellRenderer(new JdkComboBoxRenderer());
        tuningTable.getColumnModel().getColumn(COLUMN_GC).setCellEditor(new GcComboBoxEditor());
        tuningTable.getColumnModel().getColumn(COLUMN_GC).setCellRenderer(new GcComboBoxRenderer());
        tuningTable.getColumnModel().getColumn(COLUMN_MAX_HEAP).setCellEditor(new MaxHeapEditor());
        tuningTable.getColumnModel().getColumn(COLUMN_SOFT_MAX).setCellEditor(new SoftMaxEditor());
        tuningTable.getColumnModel().getColumn(COLUMN_SOFT_MAX).setCellRenderer(new SoftMaxRenderer());
        addComponents();
        reloadFromConfiguration();
    }

    @Override
    public void reloadFromConfiguration() {
        refreshJdkChoices();
        tuningRows.clear();
        for (JvmTuningEntry entry : KnownJvmTuningStore.getTuningEntries(config)) {
            tuningRows.add(new TuningRow(
                    entry.getJnlpUrl(),
                    entry.getJdkIndex(),
                    entry.getGcType(),
                    entry.getMaxHeapMb(),
                    entry.getSoftMaxPercent()));
        }
        tuningModel.fireTableDataChanged();
    }

    void refreshJdkChoiceList() {
        refreshJdkChoices();
        tuningModel.fireTableDataChanged();
    }

    private void refreshJdkChoices() {
        jdkChoices = new ArrayList<>();
        List<String> homes = KnownJvmStore.getKnownJvmHomes(config);
        for (int i = 0; i < homes.size(); i++) {
            JvmDescriptor descriptor = JvmDescriptor.describe(homes.get(i));
            jdkChoices.add(new JdkChoice(i + 1, descriptor.getDisplayName(), homes.get(i)));
        }
    }

    private int majorForJdkIndex(int jdkIndex) {
        for (JdkChoice choice : jdkChoices) {
            if (choice.jdkIndex == jdkIndex) {
                return JvmTuningCapabilities.majorVersionOfJvmHome(choice.homePath);
            }
        }
        return 0;
    }

    private void addComponents() {
        JLabel description = new JLabel("<html>" + Translator.R("CPJvmTuningDescription") + "<hr /></html>");
        JScrollPane scrollPane = new JScrollPane(tuningTable);
        scrollPane.setPreferredSize(new Dimension(820, 200));

        JButton addButton = new JButton(Translator.R("CPJvmTuningAdd"));
        addButton.setName("jvmTuningAddButton");
        addButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int defaultJdkIndex = jdkChoices.isEmpty() ? 0 : jdkChoices.get(0).jdkIndex;
                tuningRows.add(new TuningRow("", defaultJdkIndex, JvmTuningGcType.DEFAULT, "", ""));
                tuningModel.fireTableDataChanged();
                int row = tuningRows.size() - 1;
                tuningTable.getSelectionModel().setSelectionInterval(row, row);
                persistTuningEntries();
            }
        });

        JButton removeButton = new JButton(Translator.R("CPJvmTuningRemove"));
        removeButton.setName("jvmTuningRemoveButton");
        removeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int selected = tuningTable.getSelectedRow();
                if (selected < 0 || selected >= tuningRows.size()) {
                    return;
                }
                tuningRows.remove(selected);
                tuningModel.fireTableDataChanged();
                persistTuningEntries();
            }
        });

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.gridwidth = 3;
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

        if (jdkChoices.isEmpty()) {
            c.gridx = 2;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            add(new JLabel(Translator.R("CPJvmTuningNoJdks")), c);
        }
    }

    private void persistTuningEntries() {
        List<JvmTuningEntry> entries = new ArrayList<>();
        for (TuningRow row : tuningRows) {
            if (row.jnlpUrl == null || row.jnlpUrl.trim().isEmpty() || row.jdkIndex < 1) {
                continue;
            }
            entries.add(new JvmTuningEntry(
                    row.jdkIndex,
                    0,
                    row.jnlpUrl.trim(),
                    row.gcType,
                    row.maxHeapMb,
                    row.softMaxPercent));
        }
        KnownJvmTuningStore.setTuningEntries(config, entries);
    }

    private String labelForJdkIndex(int jdkIndex) {
        for (JdkChoice choice : jdkChoices) {
            if (choice.jdkIndex == jdkIndex) {
                return choice.label;
            }
        }
        return jdkIndex > 0 ? Translator.R("CPJDKAssignmentsMissingJdk", jdkIndex) : "";
    }

    private String labelForGcType(int jdkIndex, JvmTuningGcType gcType) {
        if (gcType == null || gcType == JvmTuningGcType.DEFAULT) {
            return Translator.R("CPJvmTuningGcDefault");
        }
        return gcType.getConfigValue();
    }

    private static String displayMaxHeap(String maxHeapMb) {
        if (maxHeapMb == null || maxHeapMb.trim().isEmpty()) {
            return Translator.R("CPJvmTuningDefaultMaxHeap");
        }
        return maxHeapMb.trim();
    }

    private static String displaySoftMax(int jdkIndex, JvmTuningPanel panel, String softMaxPercent) {
        if (!JvmTuningCapabilities.supportsSoftMaxHeap(panel.majorForJdkIndex(jdkIndex))) {
            return Translator.R("CPJvmTuningUnavailable");
        }
        if (softMaxPercent == null || softMaxPercent.trim().isEmpty()) {
            return Translator.R("CPJvmTuningDefaultSoftMax");
        }
        return softMaxPercent.trim() + "%";
    }

    private final class TuningTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return tuningRows.size();
        }

        @Override
        public int getColumnCount() {
            return 5;
        }

        @Override
        public String getColumnName(int column) {
            switch (column) {
                case COLUMN_URL:
                    return Translator.R("CPJvmTuningColUrl");
                case COLUMN_JDK:
                    return Translator.R("CPJvmTuningColJdk");
                case COLUMN_GC:
                    return Translator.R("CPJvmTuningColGc");
                case COLUMN_MAX_HEAP:
                    return Translator.R("CPJvmTuningColMaxHeap");
                case COLUMN_SOFT_MAX:
                    return Translator.R("CPJvmTuningColSoftMax");
                default:
                    return "";
            }
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            if (column == COLUMN_JDK) {
                return !jdkChoices.isEmpty();
            }
            if (column == COLUMN_SOFT_MAX) {
                TuningRow tuningRow = tuningRows.get(row);
                return JvmTuningCapabilities.supportsSoftMaxHeap(majorForJdkIndex(tuningRow.jdkIndex));
            }
            return true;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            TuningRow row = tuningRows.get(rowIndex);
            switch (columnIndex) {
                case COLUMN_URL:
                    return row.jnlpUrl;
                case COLUMN_JDK:
                    return labelForJdkIndex(row.jdkIndex);
                case COLUMN_GC:
                    return labelForGcType(row.jdkIndex, row.gcType);
                case COLUMN_MAX_HEAP:
                    return displayMaxHeap(row.maxHeapMb);
                case COLUMN_SOFT_MAX:
                    return displaySoftMax(row.jdkIndex, JvmTuningPanel.this, row.softMaxPercent);
                default:
                    return "";
            }
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            TuningRow row = tuningRows.get(rowIndex);
            switch (columnIndex) {
                case COLUMN_URL:
                    row.jnlpUrl = value == null ? "" : String.valueOf(value);
                    break;
                case COLUMN_JDK:
                    if (value instanceof Integer) {
                        row.jdkIndex = (Integer) value;
                    } else if (value instanceof JdkChoice) {
                        row.jdkIndex = ((JdkChoice) value).jdkIndex;
                    }
                    if (!JvmTuningCapabilities.supportsGcType(majorForJdkIndex(row.jdkIndex), row.gcType)) {
                        row.gcType = JvmTuningGcType.DEFAULT;
                    }
                    if (!JvmTuningCapabilities.supportsSoftMaxHeap(majorForJdkIndex(row.jdkIndex))) {
                        row.softMaxPercent = "";
                    }
                    break;
                case COLUMN_GC:
                    if (value instanceof JvmTuningGcType) {
                        row.gcType = (JvmTuningGcType) value;
                    }
                    break;
                case COLUMN_MAX_HEAP:
                    row.maxHeapMb = normalizeMaxHeap(value);
                    break;
                case COLUMN_SOFT_MAX:
                    row.softMaxPercent = normalizeSoftMax(value);
                    break;
                default:
                    break;
            }
            persistTuningEntries();
        }
    }

    private static String normalizeMaxHeap(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || Translator.R("CPJvmTuningDefaultMaxHeap").equals(text)) {
            return "";
        }
        return text.replaceAll("[^0-9]", "");
    }

    private static String normalizeSoftMax(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            int percent = ((Number) value).intValue();
            percent = Math.max(SOFT_MAX_MIN, Math.min(SOFT_MAX_MAX, percent));
            return Integer.toString(percent);
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || Translator.R("CPJvmTuningDefaultSoftMax").equals(text)) {
            return "";
        }
        text = text.replace("%", "").trim();
        if (text.isEmpty()) {
            return "";
        }
        try {
            int percent = Integer.parseInt(text);
            percent = Math.max(SOFT_MAX_MIN, Math.min(SOFT_MAX_MAX, percent));
            return Integer.toString(percent);
        } catch (NumberFormatException ex) {
            return "";
        }
    }

    private static final class TuningRow {
        private String jnlpUrl;
        private int jdkIndex;
        private JvmTuningGcType gcType;
        private String maxHeapMb;
        private String softMaxPercent;

        private TuningRow(String jnlpUrl, int jdkIndex, JvmTuningGcType gcType,
                String maxHeapMb, String softMaxPercent) {
            this.jnlpUrl = jnlpUrl;
            this.jdkIndex = jdkIndex;
            this.gcType = gcType == null ? JvmTuningGcType.DEFAULT : gcType;
            this.maxHeapMb = maxHeapMb == null ? "" : maxHeapMb;
            this.softMaxPercent = softMaxPercent == null ? "" : softMaxPercent;
        }
    }

    private static final class JdkChoice {
        private final int jdkIndex;
        private final String label;
        private final String homePath;

        private JdkChoice(int jdkIndex, String label, String homePath) {
            this.jdkIndex = jdkIndex;
            this.label = label;
            this.homePath = homePath;
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
            comboBox.setEnabled(!jdkChoices.isEmpty());
            int rowIndex = table.convertRowIndexToModel(row);
            int jdkIndex = tuningRows.get(rowIndex).jdkIndex;
            selectJdkChoice(jdkIndex);
            comboBox.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    fireEditingStopped();
                }
            });
            return comboBox;
        }

        private void selectJdkChoice(int jdkIndex) {
            for (int i = 0; i < comboBox.getItemCount(); i++) {
                if (comboBox.getItemAt(i).jdkIndex == jdkIndex) {
                    comboBox.setSelectedIndex(i);
                    return;
                }
            }
            if (comboBox.getItemCount() > 0) {
                comboBox.setSelectedIndex(0);
            }
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
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean enabled = !jdkChoices.isEmpty();
            component.setEnabled(enabled);
            if (!enabled) {
                setText("");
                setForeground(table.getBackground().darker());
            }
            return component;
        }
    }

    private final class GcComboBoxEditor extends AbstractCellEditor implements TableCellEditor {
        private JComboBox<GcChoice> comboBox;

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
                int row, int column) {
            int rowIndex = table.convertRowIndexToModel(row);
            int jdkMajor = majorForJdkIndex(tuningRows.get(rowIndex).jdkIndex);
            comboBox = new JComboBox<>();
            for (GcChoice choice : gcChoicesForMajor(jdkMajor)) {
                comboBox.addItem(choice);
            }
            JvmTuningGcType current = tuningRows.get(rowIndex).gcType;
            selectGcChoice(current);
            comboBox.setRenderer(new GcListCellRenderer());
            comboBox.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    GcChoice selected = (GcChoice) comboBox.getSelectedItem();
                    if (selected != null && selected.enabled) {
                        fireEditingStopped();
                    }
                }
            });
            return comboBox;
        }

        private void selectGcChoice(JvmTuningGcType current) {
            for (int i = 0; i < comboBox.getItemCount(); i++) {
                GcChoice choice = comboBox.getItemAt(i);
                if (choice.gcType == current && choice.enabled) {
                    comboBox.setSelectedIndex(i);
                    return;
                }
            }
            comboBox.setSelectedIndex(0);
        }

        @Override
        public Object getCellEditorValue() {
            GcChoice selected = (GcChoice) comboBox.getSelectedItem();
            return selected == null || !selected.enabled ? JvmTuningGcType.DEFAULT : selected.gcType;
        }
    }

    private List<GcChoice> gcChoicesForMajor(int jdkMajor) {
        List<GcChoice> choices = new ArrayList<>();
        choices.add(new GcChoice(JvmTuningGcType.DEFAULT, true));
        choices.add(new GcChoice(JvmTuningGcType.G1GC,
                JvmTuningCapabilities.supportsGcType(jdkMajor, JvmTuningGcType.G1GC)));
        choices.add(new GcChoice(JvmTuningGcType.ZGC,
                JvmTuningCapabilities.supportsGcType(jdkMajor, JvmTuningGcType.ZGC)));
        return choices;
    }

    private final class GcComboBoxRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            int rowIndex = table.convertRowIndexToModel(row);
            JvmTuningGcType gcType = tuningRows.get(rowIndex).gcType;
            int jdkMajor = majorForJdkIndex(tuningRows.get(rowIndex).jdkIndex);
            boolean enabled = JvmTuningCapabilities.supportsGcType(jdkMajor, gcType);
            component.setEnabled(enabled);
            if (!enabled) {
                setForeground(Color.GRAY);
            }
            return component;
        }
    }

    private static final class GcChoice {
        private final JvmTuningGcType gcType;
        private final boolean enabled;

        private GcChoice(JvmTuningGcType gcType, boolean enabled) {
            this.gcType = gcType;
            this.enabled = enabled;
        }

        @Override
        public String toString() {
            if (gcType == JvmTuningGcType.DEFAULT) {
                return Translator.R("CPJvmTuningGcDefault");
            }
            return gcType.getConfigValue();
        }
    }

    private static final class GcListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof GcChoice) {
                GcChoice choice = (GcChoice) value;
                setText(choice.toString());
                setEnabled(choice.enabled);
                if (!choice.enabled) {
                    setForeground(Color.GRAY);
                }
            }
            return this;
        }
    }

    private static final class MaxHeapEditor extends AbstractCellEditor implements TableCellEditor {
        private final JTextField field = new JTextField();

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
                int row, int column) {
            String text = value == null ? "" : String.valueOf(value);
            if (Translator.R("CPJvmTuningDefaultMaxHeap").equals(text)) {
                text = "";
            }
            field.setText(text);
            return field;
        }

        @Override
        public Object getCellEditorValue() {
            return field.getText();
        }
    }

    private final class SoftMaxEditor extends AbstractCellEditor implements TableCellEditor {
        private final JSpinner spinner = new JSpinner(new SpinnerNumberModel(80, SOFT_MAX_MIN, SOFT_MAX_MAX, 5));

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected,
                int row, int column) {
            int rowIndex = table.convertRowIndexToModel(row);
            boolean supported = JvmTuningCapabilities.supportsSoftMaxHeap(
                    majorForJdkIndex(tuningRows.get(rowIndex).jdkIndex));
            spinner.setEnabled(supported);
            if (supported) {
                String current = tuningRows.get(rowIndex).softMaxPercent;
                if (current != null && !current.trim().isEmpty()) {
                    try {
                        spinner.setValue(Integer.parseInt(current.trim()));
                    } catch (NumberFormatException ignored) {
                        spinner.setValue(80);
                    }
                } else {
                    spinner.setValue(80);
                }
            }
            return spinner;
        }

        @Override
        public Object getCellEditorValue() {
            if (!spinner.isEnabled()) {
                return "";
            }
            return spinner.getValue();
        }
    }

    private final class SoftMaxRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            int rowIndex = table.convertRowIndexToModel(row);
            boolean supported = JvmTuningCapabilities.supportsSoftMaxHeap(
                    majorForJdkIndex(tuningRows.get(rowIndex).jdkIndex));
            component.setEnabled(supported);
            if (!supported) {
                setText(Translator.R("CPJvmTuningUnavailable"));
                setForeground(Color.GRAY);
            }
            return component;
        }
    }
}
