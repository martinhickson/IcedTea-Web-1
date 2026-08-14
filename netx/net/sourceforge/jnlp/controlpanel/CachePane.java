/* CachePane.java -- Displays the specified folder and allows modification to its content.
Copyright (C) 2013 Red Hat

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package net.sourceforge.jnlp.controlpanel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.SystemColor;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.io.File;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;

import net.sourceforge.jnlp.cache.CacheDirectory;
import net.sourceforge.jnlp.cache.CacheEntry;
import net.sourceforge.jnlp.cache.CacheEntryMeta;
import net.sourceforge.jnlp.cache.CacheUtil;
import net.sourceforge.jnlp.cache.DirectoryNode;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.runtime.Translator;
import net.sourceforge.jnlp.util.CacheDateTimeFormats;
import net.sourceforge.jnlp.util.logging.OutputController;
import net.sourceforge.jnlp.util.ui.NonEditableTableModel;
import net.sourceforge.swing.SwingUtils;

public class CachePane extends JPanel {
    final JDialog parent;
    final DeploymentConfiguration config;
    private JComponent defaultFocusComponent;
    String[] columns = {
            Translator.R("CVCPColName"),
            Translator.R("CVCPColPath"),
            Translator.R("CVCPColType"),
            Translator.R("CVCPColDomain"),
            Translator.R("CVCPColSize"),
            Translator.R("CVCPColLastModified"),
        CacheEntry.KEY_JNLP_PATH
    };
    JTable cacheTable;
    private JButton deleteButton, refreshButton, doneButton, cleanAll, infoButton;

    /**
     * Creates a new instance of the CachePane.
     * 
     * @param parent The parent dialog that uses this pane.
     * @param config configuration tobe worked on
     */
    public CachePane(JDialog parent, DeploymentConfiguration config) {
        super(new BorderLayout());
        this.parent = parent;
        this.config = config;
        addComponents();
    }

    /**
     * Add components to the pane.
     */
    private void addComponents() {
        JPanel topPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;

        TableModel model = new NonEditableTableModel(columns, 0);

        cacheTable = new JTable(model);
        cacheTable.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cacheTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            @Override
            final public void valueChanged(ListSelectionEvent listSelectionEvent) {
                // If no row has been selected, disable the delete button, else enable it
                if (cacheTable.getSelectionModel().isSelectionEmpty()) {
                    deleteButton.setEnabled(false);
                    infoButton.setEnabled(false);
                }
                else {
                    deleteButton.setEnabled(true);
                    infoButton.setEnabled(true);
                }
            }
        });
        cacheTable.setAutoResizeMode(JTable.AUTO_RESIZE_NEXT_COLUMN);
        cacheTable.setPreferredScrollableViewportSize(new Dimension(600, 200));
        cacheTable.setFillsViewportHeight(true);
        JScrollPane scrollPane = new JScrollPane(cacheTable);

        TableRowSorter<TableModel> tableSorter = new TableRowSorter<>(model);
        final Comparator<Comparable<?>> comparator = new Comparator<Comparable<?>>() { // General purpose Comparator
            @Override
            @SuppressWarnings("unchecked")
            public final int compare(final Comparable a, final Comparable b) {
                return a.compareTo(b);
            }
        };
        tableSorter.setComparator(1, comparator); // Comparator for path column.
        tableSorter.setComparator(4, comparator); // Comparator for size column.
        tableSorter.setComparator(5, comparator); // Comparator for modified column.
        cacheTable.setRowSorter(tableSorter);
        final DefaultTableCellRenderer tableCellRenderer = new DefaultTableCellRenderer() {
            @Override
            public final Component getTableCellRendererComponent(final JTable table, final Object value, final boolean isSelected, final boolean hasFocus, final int row, final int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

                switch (column) {
                    case 1: // Path column
                    // Render absolute path
                    super.setText(((File)value).getAbsolutePath());
                    break;
                    case 4: // Size column
                    // Render size formatted to default locale's number format
                    super.setText(NumberFormat.getInstance().format(value));
                    break;
                    case 5: // last modified column
                    super.setText(value instanceof Date
                            ? CacheDateTimeFormats.formatDate((Date) value)
                            : String.valueOf(value));
                }

                return this;
            }
        };
        // TableCellRenderer for path column
        cacheTable.getColumn(this.columns[1]).setCellRenderer(tableCellRenderer);
        // TableCellRenderer for size column
        cacheTable.getColumn(this.columns[4]).setCellRenderer(tableCellRenderer);
        // TableCellRenderer for last modified column
        cacheTable.getColumn(this.columns[5]).setCellRenderer(tableCellRenderer);

        c.weightx = 1;
        c.weighty = 1;
        c.gridx = 0;
        c.gridy = 0;
        topPanel.add(scrollPane, c);
        this.add(topPanel, BorderLayout.CENTER);
        this.add(createButtonPanel(), BorderLayout.SOUTH);
    }

    /**
     * Create the buttons panel.
     * 
     * @return JPanel containing the buttons.
     */
    private Component createButtonPanel() {
        JPanel buttonPanel = new JPanel(new GridLayout(1, 0));
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.TRAILING));

        List<JButton> buttons = new ArrayList<>();

        this.infoButton = new JButton(Translator.R("ButMoreInformation"));
        infoButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JDialog jd = new JDialog(parent, true);
                JTextArea t = new JTextArea();
                t.setEditable(false);
                
                int row = cacheTable.getSelectedRow();
                try {
                    int modelRow = cacheTable.convertRowIndexToModel(row);
                    DirectoryNode fileNode = ((DirectoryNode) cacheTable.getModel().getValueAt(modelRow, 0));
                    CacheEntryMeta meta = fileNode.getMeta();
                    String info = meta == null ? "" : meta.formatAsInfoText();
                    t.setText(formatCacheInfoContent(info));
                } catch (Exception ex) {
                    t.setText(ex.toString());
                }
                jd.add(t);
                jd.setSize(300, 300);
                jd.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                jd.setVisible(true);
            }
        });
        infoButton.setEnabled(false);
        buttons.add(infoButton);

        this.deleteButton = new JButton(Translator.R("CVCPButDelete"));
        deleteButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                disableButtons();
                // Delete on AWT thread after this action has been performed
                // in order to allow the cache viewer to update itself
                invokeLaterDelete();
            }
        });
        deleteButton.setEnabled(false);
        buttons.add(deleteButton);

        this.cleanAll = new JButton(Translator.R("CVCPCleanCache"));
        cleanAll.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                disableButtons();
                // Delete on AWT thread after this action has been performed
                // in order to allow the cache viewer to update itself
                invokeLaterDeleteAll();
            }
        });
        buttons.add(cleanAll);

        this.refreshButton = new JButton(Translator.R("CVCPButRefresh"));
        refreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                disableButtons();
                // Populate cacheTable on AWT thread after this action event has been performed
                invokeLaterPopulateTable();
            }
        });
        refreshButton.setEnabled(false);
        buttons.add(refreshButton);

        this.doneButton = new JButton(Translator.R("ButDone"));
        doneButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(
                        new WindowEvent(parent, WindowEvent.WINDOW_CLOSING));
            }
        });

        int maxWidth = 0;
        int maxHeight = 0;
        for (JButton button : buttons) {
            maxWidth = Math.max(button.getMinimumSize().width, maxWidth);
            maxHeight = Math.max(button.getMinimumSize().height, maxHeight);
        }

        int wantedWidth = maxWidth + 10;
        int wantedHeight = maxHeight;
        for (JButton button : buttons) {
            button.setPreferredSize(new Dimension(wantedWidth, wantedHeight));
            leftPanel.add(button);
        }

        doneButton.setPreferredSize(new Dimension(wantedWidth, wantedHeight));
        doneButton.setEnabled(false);
        rightPanel.add(doneButton);
        buttonPanel.add(leftPanel);
        buttonPanel.add(rightPanel);

        return buttonPanel;
    }

    /**
     * Posts an event to the event queue to delete the currently selected
     * resource in {@link CachePane#cacheTable} after the {@code CachePane} and
     * {@link CacheViewer} have been instantiated and painted.
     * @see CachePane#cacheTable
     */
    private void invokeLaterDelete() {
        final String clearId = getSelectedClearId();
        SwingUtils.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    RunningJnlpProcessesDialog.runClearAfterProcessesStopped(
                            parent,
                            clearId,
                            Translator.R("CacheProceedClearSelected"),
                            new Runnable() {
                                @Override
                                public void run() {
                                    deleteSelectedCacheEntry();
                                }
                            });
                } catch (Exception exception) {
                    OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, exception);
                } finally {
                    restoreDisabled();
                }
            }
        });
    }

    private void deleteSelectedCacheEntry() {
        int row = cacheTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        int modelRow = cacheTable.convertRowIndexToModel(row);
        DirectoryNode fileNode = ((DirectoryNode) cacheTable.getModel().getValueAt(modelRow, 0));
        String clearId = CacheUtil.clearIdForMeta(fileNode.getMeta());
        if (clearId != null && !CacheUtil.canClearApplicationCache(clearId)) {
            JOptionPane.showMessageDialog(parent, Translator.R("CCannotClearCache"));
            return;
        }
        File selected = fileNode.getFile();
        boolean removed = selected == null || !selected.exists() || selected.delete();
        if (removed) {
            fileNode.removeCatalogRow();
            ((NonEditableTableModel) cacheTable.getModel()).removeRow(modelRow);
            cacheTable.getSelectionModel().clearSelection();
            CacheDirectory.cleanParent(fileNode);
        }
    }

    private String getSelectedClearId() {
        int row = cacheTable.getSelectedRow();
        if (row < 0) {
            return null;
        }
        int modelRow = cacheTable.convertRowIndexToModel(row);
        DirectoryNode fileNode = ((DirectoryNode) cacheTable.getModel().getValueAt(modelRow, 0));
        return CacheUtil.clearIdForMeta(fileNode.getMeta());
    }

    private static String formatCacheInfoContent(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        StringBuilder sb = new StringBuilder();
        String[] lines = raw.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(formatCacheInfoLine(lines[i]));
        }
        return sb.toString();
    }

    private static String formatCacheInfoLine(String line) {
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return line;
        }
        String key = line.substring(0, eq).trim();
        String value = line.substring(eq + 1).trim();
        if (!"last-modified".equals(key) && !"last-updated".equals(key)) {
            return line;
        }
        try {
            long epochMillis = Long.parseLong(value);
            return line + " (" + CacheDateTimeFormats.formatEpochMillis(epochMillis) + ")";
        } catch (NumberFormatException ex) {
            return line;
        }
    }

    private void invokeLaterDeleteAll() {
        SwingUtils.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    RunningJnlpProcessesDialog.runClearAfterProcessesStopped(
                            parent,
                            null,
                            Translator.R("CacheProceedClearAll"),
                            new Runnable() {
                                @Override
                                public void run() {
                                    visualCleanCache(parent);
                                    populateTable();
                                }
                            });
                } catch (Exception exception) {
                    OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, exception);
                } finally {
                    restoreDisabled();
                }
            }
        });
    }

    /**
     * Posts an event to the event queue to populate the
     * {@link CachePane#cacheTable} after the {@code CachePane} and
     * {@link CacheViewer} have been instantiated and painted.
     * @see CachePane#populateTable
     */
    final void invokeLaterPopulateTable() {
        SwingUtils.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    populateTable();
                    // Disable cacheTable when no data to display, so no events are generated
                    if (cacheTable.getModel().getRowCount() == 0) {
                        cacheTable.setEnabled(false);
                        cacheTable.setBackground(SystemColor.control);
                        // No data in cacheTable, so nothing to delete
                        deleteButton.setEnabled(false);
                        infoButton.setEnabled(false);                        
                    } else {
                        cacheTable.setEnabled(true);
                        cacheTable.setBackground(SystemColor.text);
                    }
                } catch (Exception exception) {
                        OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, exception);
                } finally {
                    refreshButton.setEnabled(true);
                    doneButton.setEnabled(true);
                    cleanAll.setEnabled(true);
                }
            }
        });
    }

    /**
     * Populate the table with fresh data. Any manual updates to the cache
     * directory will be updated in the table.
     */
    private void populateTable() {
        try {
            // Populating the cacheTable may take a while, so indicate busy by cursor
            parent.getContentPane().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

            NonEditableTableModel tableModel;
            (tableModel = (NonEditableTableModel)cacheTable.getModel()).setRowCount(0); //Clears the table
            for (Object[] v : generateData()) {
                tableModel.addRow(v);
            }
        } catch (Exception exception) {
            OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, exception);
        } finally {
            // Reset cursor
            parent.getContentPane().setCursor(Cursor.getDefaultCursor());
        }
    }

   
    /**
     * This creates the data for the table.
     *
     * @return ArrayList containing an Object array of data for each row in the
     * table.
     */
    public static ArrayList<Object[]> generateData() {
        return CacheDirectory.listViewerRows();
    }

    /**
     * Put focus onto default button.
     */
    public void focusOnDefaultButton() {
        if (defaultFocusComponent != null) {
            defaultFocusComponent.requestFocusInWindow();
        }
    }

    public void disableButtons() {
        // may take a while, so indicate busy by cursor
        parent.getContentPane().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        // Disable dialog and buttons while operating
        deleteButton.setEnabled(false);
        infoButton.setEnabled(false);
        refreshButton.setEnabled(false);
        doneButton.setEnabled(false);
        cleanAll.setEnabled(false);
    }

    public void restoreDisabled() {
        cleanAll.setEnabled(true);
        // If nothing selected then keep deleteButton disabled
        if (!cacheTable.getSelectionModel().isSelectionEmpty()) {
            deleteButton.setEnabled(true);
            infoButton.setEnabled(true);
        }
        // Enable buttons
        refreshButton.setEnabled(true);
        doneButton.setEnabled(true);
        // If cacheTable is empty disable it and set background
        // color to indicate being disabled
        if (cacheTable.getModel().getRowCount() == 0) {
            cacheTable.setEnabled(false);
            cacheTable.setBackground(SystemColor.control);
        }
        // Reset cursor
        parent.getContentPane().setCursor(Cursor.getDefaultCursor());
    }

    public static void visualCleanCache(Component parent) {
        try {
            boolean success = CacheUtil.clearCache();
            if (!success) {
                JOptionPane.showMessageDialog(parent, Translator.R("CCannotClearCache"));
                return;
            }
            JOptionPane.showMessageDialog(
                    parent,
                    Translator.R("CacheClearedSuccessfully"),
                    Translator.R("CPHeadTempInternetFiles"),
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, Translator.R("CCannotClearCache"));
        }
    }
}


