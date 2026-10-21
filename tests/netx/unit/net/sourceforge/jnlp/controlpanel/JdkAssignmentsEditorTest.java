package net.sourceforge.jnlp.controlpanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.KnownJvmAssignmentStore;
import net.sourceforge.jnlp.config.KnownJvmStore;
import net.sourceforge.jnlp.runtime.Translator;
import org.junit.Test;

public class JdkAssignmentsEditorTest {

    @Test
    public void jdkDropdownStaysInTheEditUntilTheListCloses() throws Exception {
        DeploymentConfiguration config = loadEmpty();
        KnownJvmStore.setKnownJvmHomes(config, Collections.singletonList("/opt/jdk-assignment-editor"));
        KnownJvmAssignmentStore.setAssignments(config, Collections.singletonList(
                new KnownJvmAssignmentStore.JvmAssignment(1, 0, "https://example.com/apps/editor.jnlp")));
        JdkAssignmentsPanel panel = new JdkAssignmentsPanel(config);

        SwingUtilities.invokeAndWait(() -> {
            JTable table = findAssignmentsTable(panel);
            assertNotNull(table);
            assertTrue(table.editCellAt(0, 1));
            JComboBox<?> combo = (JComboBox<?>) table.getEditorComponent();
            assertEquals(Boolean.TRUE, combo.getClientProperty("JComboBox.isTableCellEditor"));
            assertTrue(combo.isLightWeightPopupEnabled());

            combo.setSelectedIndex(0);
            assertTrue(table.isEditing());

            for (PopupMenuListener listener : combo.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeInvisible(new PopupMenuEvent(combo));
            }
            assertFalse(table.isEditing());
            assertEquals(Translator.R("CPJDKAssignmentsDefault"), String.valueOf(table.getValueAt(0, 1)));
            javax.swing.JLabel hint = findLabel(panel, "jdkAssignmentsNoJdksLabel");
            assertNotNull(hint);
            assertFalse(hint.isVisible());
        });
    }

    private static javax.swing.JLabel findLabel(Component component, String name) {
        if (component instanceof javax.swing.JLabel && name.equals(component.getName())) {
            return (javax.swing.JLabel) component;
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                javax.swing.JLabel found = findLabel(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JTable findAssignmentsTable(Component component) {
        if (component instanceof JTable && "jdkAssignmentsTable".equals(component.getName())) {
            return (JTable) component;
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                JTable found = findAssignmentsTable(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static DeploymentConfiguration loadEmpty() throws Exception {
        File dir = Files.createTempDirectory("itw-jdk-assignment-editor").toFile();
        File userFile = new File(dir, "deployment.properties");
        Files.write(userFile.toPath(), "# empty\n".getBytes(StandardCharsets.UTF_8));
        DeploymentConfiguration config = new DeploymentConfiguration();
        Method load = DeploymentConfiguration.class.getDeclaredMethod(
                "load", URL.class, File.class, boolean.class);
        load.setAccessible(true);
        load.invoke(config, new Object[] { null, userFile, Boolean.FALSE });
        return config;
    }
}
