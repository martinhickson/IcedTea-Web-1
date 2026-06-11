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

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

import net.sourceforge.jnlp.runtime.Translator;
import net.sourceforge.jnlp.util.JnlpAppTuningRegistry;
import net.sourceforge.jnlp.util.JnlpAppTuningRegistry.AppTuning;
import net.sourceforge.jnlp.util.JnlpRunningProcessSupport.RunningProcess;
import net.sourceforge.jnlp.util.ProcessMemorySupport.ProcessJvmContext;

@SuppressWarnings("serial")
final class RunningAppsTuneDialog extends JDialog {

    private final RunningProcess process;
    private final ProcessJvmContext jvmContext;
    private final JLabel statusLabel = new JLabel(Translator.R("CPRunningAppsTuneLoading"));
    private final JSpinner maxHeapSpinner;
    private final JSpinner softMaxHeapSpinner;
    private final JComboBox<String> gcCombo;
    private final JLabel defaultMaxLabel;
    private final JLabel defaultSoftMaxLabel;
    private final JButton relaunchButton;
    private AppTuning loadedTuning;

    RunningAppsTuneDialog(Window owner, RunningProcess process, ProcessJvmContext jvmContext) {
        super(owner, Translator.R("CPRunningAppsTuneTitle"), Dialog.ModalityType.APPLICATION_MODAL);
        this.process = process;
        this.jvmContext = jvmContext;
        setLayout(new BorderLayout(8, 8));
        setMinimumSize(new Dimension(460, 280));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 8, 4, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;

        defaultMaxLabel = new JLabel();
        defaultSoftMaxLabel = new JLabel();
        maxHeapSpinner = new JSpinner(new SpinnerNumberModel(512, 1, 65536, 64));
        softMaxHeapSpinner = new JSpinner(new SpinnerNumberModel(100, 1, 65536, 16));
        gcCombo = new JComboBox<>(new String[] {
                JnlpAppTuningRegistry.GC_G1,
                JnlpAppTuningRegistry.GC_ZGC
        });

        addRow(form, c, 0, Translator.R("CPRunningAppsTuneDefaultMaxHeap"), defaultMaxLabel);
        addRow(form, c, 1, Translator.R("CPRunningAppsTuneMaxHeap"), maxHeapSpinner);
        addRow(form, c, 2, Translator.R("CPRunningAppsTuneGc"), gcCombo);
        addRow(form, c, 3, Translator.R("CPRunningAppsTuneDefaultSoftMaxHeap"), defaultSoftMaxLabel);
        addRow(form, c, 4, Translator.R("CPRunningAppsTuneSoftMaxHeap"), softMaxHeapSpinner);

        add(form, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        JButton cancel = new JButton(Translator.R("ButCancel"));
        cancel.addActionListener(e -> dispose());
        JButton reset = new JButton(Translator.R("CPRunningAppsTuneReset"));
        reset.addActionListener(e -> confirmAndReset());
        JButton relaunch = new JButton(Translator.R("CPRunningAppsTuneRelaunch"));
        relaunch.setEnabled(false);
        relaunch.addActionListener(e -> confirmAndRelaunch());
        buttons.add(cancel);
        buttons.add(reset);
        buttons.add(relaunch);
        this.relaunchButton = relaunch;
        add(buttons, BorderLayout.SOUTH);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setLocationRelativeTo(owner);
        loadValuesAsync();
    }

    static void showDialog(Component parent, RunningProcess process, ProcessJvmContext jvmContext) {
        Window owner = parent instanceof Window ? (Window) parent : SwingUtilities.getWindowAncestor(parent);
        RunningAppsTuneDialog dialog = new RunningAppsTuneDialog(owner, process, jvmContext);
        dialog.setVisible(true);
    }

    private void loadValuesAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AppTuning tuning = JnlpAppTuningRegistry.loadForProcess(process, jvmContext);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        applyTuningToForm(tuning);
                    }
                });
            }
        }, "itw-running-app-tune-" + process.getPid()).start();
    }

    private void applyTuningToForm(AppTuning tuning) {
        loadedTuning = tuning;
        long defaultMaxMb = bytesToMb(tuning.getDefaultMaxHeapBytes());
        long defaultSoftMb = bytesToMb(tuning.getDefaultSoftMaxHeapBytes());
        long maxMb = bytesToMb(tuning.getMaxHeapBytes());
        long softMb = bytesToMb(tuning.getSoftMaxHeapBytes());
        long minMaxMb = bytesToMb(JnlpAppTuningRegistry.minimumAllowedMaxHeap(tuning.getDefaultMaxHeapBytes()));
        long softFixedMb = bytesToMb(JnlpAppTuningRegistry.softMaxDefaultForMaxHeap(mbToBytes(maxMb)));

        defaultMaxLabel.setText(defaultMaxMb + " MB");
        defaultSoftMaxLabel.setText(defaultSoftMb + " MB");
        maxHeapSpinner.setModel(new SpinnerNumberModel(
                (int) Math.max(minMaxMb, maxMb),
                (int) Math.max(1, minMaxMb),
                65536,
                64));
        softMaxHeapSpinner.setModel(new SpinnerNumberModel(
                (int) softFixedMb,
                (int) softFixedMb,
                (int) softFixedMb,
                1));
        gcCombo.setSelectedItem(tuning.getGcType());
        maxHeapSpinner.addChangeListener(e -> updateSoftMaxForCurrentMax());
        statusLabel.setText("");
        relaunchButton.setEnabled(true);
    }

    private void updateSoftMaxForCurrentMax() {
        long maxMb = ((Number) maxHeapSpinner.getValue()).longValue();
        long softMb = bytesToMb(JnlpAppTuningRegistry.softMaxDefaultForMaxHeap(mbToBytes(maxMb)));
        softMaxHeapSpinner.setModel(new SpinnerNumberModel((int) softMb, (int) softMb, (int) softMb, 1));
    }

    private void confirmAndRelaunch() {
        if (!validateInput()) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(
                this,
                Translator.R("CPRunningAppsTuneRelaunchConfirm"),
                Translator.R("CPRunningAppsTuneTitle"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            saveTuningFromForm();
            if (!JnlpAppTuningRegistry.relaunchApplication(process)) {
                JOptionPane.showMessageDialog(this,
                        JnlpAppTuningRegistry.getRelaunchFailureMessage(process),
                        Translator.R("CPRunningAppsTuneTitle"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            dispose();
        } catch (Exception ex) {
            String detail = ex.getMessage();
            String message = detail == null || detail.trim().isEmpty()
                    ? Translator.R("CPRunningAppsTuneSaveFailed")
                    : Translator.R("CPRunningAppsTuneSaveFailedDetail", detail);
            JOptionPane.showMessageDialog(this, message,
                    Translator.R("CPRunningAppsTuneTitle"), JOptionPane.WARNING_MESSAGE);
        }
    }

    private void confirmAndReset() {
        int choice = JOptionPane.showConfirmDialog(
                this,
                Translator.R("CPRunningAppsTuneResetConfirm"),
                Translator.R("CPRunningAppsTuneTitle"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        JnlpAppTuningRegistry.delete(JnlpAppTuningRegistry.tuningFileFor(process));
        if (!JnlpAppTuningRegistry.relaunchApplication(process)) {
            JOptionPane.showMessageDialog(this,
                    JnlpAppTuningRegistry.getRelaunchFailureMessage(process),
                    Translator.R("CPRunningAppsTuneTitle"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        dispose();
    }

    private void saveTuningFromForm() throws Exception {
        JnlpAppTuningRegistry.saveTunedValues(
                JnlpAppTuningRegistry.tuningFileFor(process),
                loadedTuning,
                mbToBytes(((Number) maxHeapSpinner.getValue()).longValue()),
                (String) gcCombo.getSelectedItem(),
                mbToBytes(((Number) softMaxHeapSpinner.getValue()).longValue()));
    }

    private boolean validateInput() {
        if (loadedTuning == null) {
            return false;
        }
        long maxMb = ((Number) maxHeapSpinner.getValue()).longValue();
        long minMb = bytesToMb(JnlpAppTuningRegistry.minimumAllowedMaxHeap(loadedTuning.getDefaultMaxHeapBytes()));
        if (maxMb < minMb) {
            JOptionPane.showMessageDialog(this,
                    Translator.R("CPRunningAppsTuneMaxHeapTooSmall", minMb),
                    Translator.R("CPRunningAppsTuneTitle"),
                    JOptionPane.WARNING_MESSAGE);
            return false;
        }
        long expectedSoftMb = bytesToMb(JnlpAppTuningRegistry.softMaxDefaultForMaxHeap(mbToBytes(maxMb)));
        long softMb = ((Number) softMaxHeapSpinner.getValue()).longValue();
        if (softMb != expectedSoftMb) {
            JOptionPane.showMessageDialog(this,
                    Translator.R("CPRunningAppsTuneSoftMaxInvalid", expectedSoftMb),
                    Translator.R("CPRunningAppsTuneTitle"),
                    JOptionPane.WARNING_MESSAGE);
            return false;
        }
        return true;
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String label, Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(field, c);
    }

    private static long bytesToMb(long bytes) {
        return Math.max(1, Math.round(bytes / (1024.0 * 1024.0)));
    }

    private static long mbToBytes(long mb) {
        return mb * 1024L * 1024L;
    }
}
