package net.sourceforge.jnlp.controlpanel;

import java.awt.event.ActionListener;
import java.awt.event.ItemListener;
import javax.swing.JComboBox;

final class ControlPanelUiUtils {

    private ControlPanelUiUtils() {
    }

    static void setComboBoxSelectionWithoutNotify(JComboBox<?> comboBox, Object value) {
        ActionListener[] actionListeners = comboBox.getActionListeners();
        ItemListener[] itemListeners = comboBox.getItemListeners();
        for (ActionListener listener : actionListeners) {
            comboBox.removeActionListener(listener);
        }
        for (ItemListener listener : itemListeners) {
            comboBox.removeItemListener(listener);
        }
        comboBox.setSelectedItem(value);
        for (ActionListener listener : actionListeners) {
            comboBox.addActionListener(listener);
        }
        for (ItemListener listener : itemListeners) {
            comboBox.addItemListener(listener);
        }
    }
}
