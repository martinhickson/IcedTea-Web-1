/* ControlPanel.java -- Display the control panel for modifying deployment settings.
Copyright (C) 2011 Red Hat

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

import static net.sourceforge.jnlp.runtime.Translator.R;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.naming.ConfigurationException;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import net.sourceforge.jnlp.jdk89acesses.SunMiscLauncher;
import net.sourceforge.jnlp.config.DeploymentConfiguration;
import net.sourceforge.jnlp.config.DeploymentConfiguration.PendingChangeListener;
import net.sourceforge.jnlp.config.ItwFeatureFlags;
import net.sourceforge.jnlp.config.PathsAndFiles;
import net.sourceforge.jnlp.controlpanel.JVMPanel.JvmValidationResult;
import net.sourceforge.jnlp.runtime.JNLPRuntime;
import net.sourceforge.jnlp.runtime.Translator;
import net.sourceforge.jnlp.security.viewer.CertificatePane;
import net.sourceforge.jnlp.util.ImageResources;
import net.sourceforge.jnlp.util.logging.OutputController;
import net.sourceforge.swing.SwingUtils;

/**
 * This is the control panel for Java. It provides a GUI for modifying the
 * deployments.properties file.
 * 
 * @author Andrew Su (asu@redhat.com, andrew.su@utoronto.ca)
 * 
 */
public class ControlPanel extends JFrame {
    private static final String APPLY_MARK_SAVED = "\u2713 ";
    private static final String APPLY_MARK_PENDING = "\u25cf ";

    private JdkAssignmentsPanel jdkAssignmentsPanel;
    private JvmTuningPanel jvmTuningPanel;
    private JVMPanel jvmPanel;
    private JButton applyButton;
    private JButton revertButton;
    private final List<SettingsPanelReloader> settingsReloaders = new ArrayList<>();
    private UnsignedAppletsTrustingListPanel extendedAppletSecurityPanel;
    private TemporaryInternetFilesPanel cachePanel;
    private DebuggingPanel debuggingPanel;
    private NetworkSettingsPanel networkSettingsPanel;
    private SecuritySettingsPanel securitySettingsPanel;
    private DesktopShortcutPanel desktopShortcutPanel;

    /**
     * Class for keeping track of the panels and their associated text.
     * 
     * @author @author Andrew Su (asu@redhat.com, andrew.su@utoronto.ca)
     * 
     */
    private static class SettingsPanel {
        final String value;
        final JPanel panel;

        public SettingsPanel(String value, JPanel panel) {
            this.value = value;
            this.panel = panel;
        }

        public JPanel getPanel() {
            return panel;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private DeploymentConfiguration config = null;

    /**
     * Creates a new instance of the ControlPanel.
     * 
     * @param config
     *            Loaded DeploymentsConfiguration file.
     * 
     */
    public ControlPanel(DeploymentConfiguration config) {
        super();
        setTitle(Translator.R("CPHead"));
        setIconImages(ImageResources.INSTANCE.getApplicationImages());

        this.config = config;
        this.config.beginEditorSession();
        this.config.addPendingChangeListener(new PendingChangeListener() {
            @Override
            public void onPendingChangesChanged() {
                SwingUtils.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        updateEditorButtons();
                    }
                });
            }
        });

        JPanel topPanel = createTopPanel();
        config.beginSuppressedPropertyUpdates();
        JPanel mainPanel;
        try {
            mainPanel = createMainSettingsPanel();
        } finally {
            config.endSuppressedPropertyUpdates();
        }
        JPanel buttonPanel = createButtonPanel();

        add(topPanel, BorderLayout.PAGE_START);
        add(mainPanel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.PAGE_END);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        pack();
        applyGoldenRatioWindowSize();
        updateEditorButtons();
    }

    private void applyGoldenRatioWindowSize() {
        final double goldenRatio = 1.61803398875d;
        Dimension size = getSize();
        int width = size.width;
        int height = (int) Math.round(width / goldenRatio);
        Dimension target = new Dimension(width, height);
        setMinimumSize(target);
        setSize(target);
    }

    private JPanel createTopPanel() {
        Font currentFont;
        JLabel about = new JLabel(R("CPMainDescriptionShort"));
        currentFont = about.getFont();
        about.setFont(currentFont.deriveFont(currentFont.getSize2D() + 2));
        currentFont = about.getFont();
        about.setFont(currentFont.deriveFont(Font.BOLD));

        JLabel description = new JLabel(R("CPMainDescriptionLong"));
        description.setBorder(new EmptyBorder(2, 0, 2, 0));

        JPanel descriptionPanel = new JPanel(new GridLayout(0, 1));
        descriptionPanel.setBackground(UIManager.getColor("TextPane.background"));
        descriptionPanel.add(about);
        descriptionPanel.add(description);

        JLabel image = new JLabel();
        image.setIcon(new ImageIcon(ImageResources.INSTANCE.getApplicationImages().get(0)));


        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBackground(UIManager.getColor("TextPane.background"));
        topPanel.add(descriptionPanel, BorderLayout.LINE_START);
        topPanel.add(image, BorderLayout.LINE_END);
        topPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        return topPanel;
    }
    
    private int validateJdk() {
        String s = ControlPanel.this.config.getProperty(DeploymentConfiguration.KEY_JRE_DIR);
        JvmValidationResult validationResult = JVMPanel.validateJvm(s);
        if (validationResult.id == JvmValidationResult.STATE.NOT_DIR
                || validationResult.id == JvmValidationResult.STATE.NOT_VALID_DIR
                || validationResult.id == JvmValidationResult.STATE.NOT_VALID_JDK) {
            return JOptionPane.showConfirmDialog(ControlPanel.this,
                    "<html>"+Translator.R("CPJVMNotokMessage1", s)+"<br/>"
                    + validationResult.formattedText+"<br/>"
                    + Translator.R("CPJVMNotokMessage2", DeploymentConfiguration.KEY_JRE_DIR, PathsAndFiles.USER_DEPLOYMENT_FILE.getFullPath(config))+"</html>",
                    Translator.R("CPJVMconfirmInvalidJdkTitle"),JOptionPane.OK_CANCEL_OPTION);
        }
        return JOptionPane.OK_OPTION;
    }

    /**
     * Creates the "ok" "apply" and "cancel" buttons.
     * 
     * @return A panel with the "ok" "apply" and "cancel" button.
     */
    private JPanel createButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.TRAILING));

        List<JButton> buttons = new ArrayList<JButton>();

        JButton okButton = new JButton(Translator.R("ButOk"));
        okButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ControlPanel.this.saveConfiguration();
                int validationResult = validateJdk();
                if (validationResult!= JOptionPane.OK_OPTION){
                    return;
                }
                JNLPRuntime.exit(0);
            }
        });
        buttons.add(okButton);

        revertButton = new JButton(Translator.R("ButRevert"));
        revertButton.setName("controlPanelRevertButton");
        revertButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                revertConfiguration();
            }
        });
        buttons.add(revertButton);

        applyButton = new JButton(Translator.R("ButApply"));
        applyButton.setName("controlPanelApplyButton");
        applyButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ControlPanel.this.saveConfiguration();
                int validationResult = validateJdk();
                if (validationResult != JOptionPane.OK_OPTION) {
                    int i = JOptionPane.showConfirmDialog(ControlPanel.this,
                            Translator.R("CPJVMconfirmReset"),
                            Translator.R("CPJVMconfirmReset"), JOptionPane.OK_CANCEL_OPTION);
                    if (i == JOptionPane.OK_OPTION) {
                        jvmPanel.resetTestFieldArgumentsExec();
                    }
                }
            }
        });
        buttons.add(applyButton);

        JButton cancelButton = new JButton(Translator.R("ButCancel"));
        cancelButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JNLPRuntime.exit(0);
            }
        });
        buttons.add(cancelButton);

        int maxWidth = 0;
        int maxHeight = 0;
        for (JButton button : buttons) {
            maxWidth = Math.max(button.getMinimumSize().width, maxWidth);
            maxHeight = Math.max(button.getMinimumSize().height, maxHeight);
        }

        int wantedWidth = maxWidth + 10;
        int wantedHeight = maxHeight + 2;
        for (JButton button : buttons) {
            button.setPreferredSize(new Dimension(wantedWidth, wantedHeight));
            buttonPanel.add(button);
        }

        return buttonPanel;
    }

    /**
     * Add the different settings panels to the GUI.
     * 
     * @return A panel with all the components in place.
     */
    private JPanel createMainSettingsPanel() {
        createExtendedAppletSecurityPanel();
        List<SettingsPanel> panelList = new ArrayList<>();
        panelList.add(new SettingsPanel(Translator.R("CPTabAbout"), createAboutPanel()));
        panelList.add(new SettingsPanel(Translator.R("CPTabCache"), createCacheSettingsPanel()));
        panelList.add(new SettingsPanel(Translator.R("CPTabCertificate"), createCertificatesSettingsPanel()));
        panelList.add(new SettingsPanel(Translator.R("CPTabDebugging"), createDebugSettingsPanel()));
        panelList.add(new SettingsPanel(Translator.R("CPTabDesktopIntegration"), createDesktopSettingsPanel()));
        panelList.add(new SettingsPanel(Translator.R("CPTabJDKAssignments"), createJdkAssignmentsPanel()));
        if (ItwFeatureFlags.isJvmTuningTabEnabled()) {
            panelList.add(new SettingsPanel(Translator.R("CPTabJvmTuning"), createJvmTuningPanel()));
        }
        panelList.add(new SettingsPanel(Translator.R("CPTabJDKSettings"), createJVMSettingsPanel()));
        panelList.add(new SettingsPanel(Translator.R("CPTabRunningApps"), new RunningAppsPanel(this.config)));
        panelList.add(new SettingsPanel(Translator.R("CPTabNetwork"), createNetworkSettingsPanel()));
        panelList.add(new SettingsPanel(Translator.R("CPTabSecurity"), createSecuritySettingsPanel()));
        panelList.add(new SettingsPanel(Translator.R("CPTabPolicy"), createPolicySettingsPanel()));
        panelList.add(new SettingsPanel(Translator.R("APPEXTSECControlPanelExtendedAppletSecurityTitle"),
                extendedAppletSecurityPanel));
        SettingsPanel[] panels = panelList.toArray(new SettingsPanel[0]);

        // Add panels.
        final JPanel settingsPanel = new JPanel(new CardLayout());

        // Calculate largest minimum size we should use.
        int height = 0;
        int width = 0;
        for (SettingsPanel panel : panels) {
            JPanel p = panel.getPanel();
            Dimension d = p.getMinimumSize();
            if (d.height > height) {
                height = d.height;
            }
            if (d.width > width) {
                width = d.width;
            }
        }
        Dimension dim = new Dimension(width, height);

        for (SettingsPanel panel : panels) {
            JPanel p = panel.getPanel();
            p.setPreferredSize(dim);
            settingsPanel.add(p, panel.toString());
        }

        final JList<SettingsPanel> settingsList = new JList<>(panels);
        settingsList.setName("controlPanelSettingsList");
        settingsList.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) {
                    return;
                }
                @SuppressWarnings("unchecked")
                JList<SettingsPanel> list = (JList<SettingsPanel>) e.getSource();
                SettingsPanel panel = list.getSelectedValue();
                if (panel == null) {
                    return;
                }
                if (panel.getPanel() == jdkAssignmentsPanel) {
                    jdkAssignmentsPanel.refreshJdkChoiceList();
                }
                if (jvmTuningPanel != null && panel.getPanel() == jvmTuningPanel) {
                    jvmTuningPanel.refreshJdkChoiceList();
                }
                CardLayout cl = (CardLayout) settingsPanel.getLayout();
                cl.show(settingsPanel, panel.toString());
            }
        });
        JScrollPane settingsListScrollPane = new JScrollPane(settingsList);
        settingsListScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        final JPanel settingsDetailPanel = new JPanel();
        settingsDetailPanel.setLayout(new BorderLayout());
        settingsDetailPanel.add(settingsPanel, BorderLayout.CENTER);
        settingsDetailPanel.setBorder(new EmptyBorder(0, 5, -3, 0));

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(settingsListScrollPane, BorderLayout.LINE_START);
        mainPanel.add(settingsDetailPanel, BorderLayout.CENTER);
        mainPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

        settingsList.setSelectedIndex(0);

        return mainPanel;
    }

    private JPanel createAboutPanel() {
        return new AboutPanel();
    }

    private JPanel createCacheSettingsPanel() {
        cachePanel = new TemporaryInternetFilesPanel(this.config);
        registerSettingsReloader(cachePanel);
        return cachePanel;
    }

    private JPanel createCertificatesSettingsPanel() {
        JPanel p = new NamedBorderPanel(Translator.R("CPHeadCertificates"), new BorderLayout());
        p.add(new CertificatePane(null), BorderLayout.CENTER);
        return p;
    }

    private JPanel createClassLoaderSettingsPanel() {
        return createNotImplementedPanel();
    }

    private JPanel createDebugSettingsPanel() {
        debuggingPanel = new DebuggingPanel(this.config);
        registerSettingsReloader(debuggingPanel);
        return debuggingPanel;
    }

    private JPanel createDesktopSettingsPanel() {
        desktopShortcutPanel = new DesktopShortcutPanel(this.config);
        registerSettingsReloader(desktopShortcutPanel);
        return desktopShortcutPanel;
    }

    private JPanel createNetworkSettingsPanel() {
        networkSettingsPanel = new NetworkSettingsPanel(this.config);
        registerSettingsReloader(networkSettingsPanel);
        return networkSettingsPanel;
    }

    private JPanel createRuntimesSettingsPanel() {
        return new JREPanel();
    }

    private JPanel createSecuritySettingsPanel() {
        securitySettingsPanel = new SecuritySettingsPanel(this.config);
        registerSettingsReloader(securitySettingsPanel);
        return securitySettingsPanel;
    }

    private JPanel createPolicySettingsPanel() {
        return new PolicyPanel(this, this.config);
    }

    private JPanel createJdkAssignmentsPanel() {
        jdkAssignmentsPanel = new JdkAssignmentsPanel(this.config);
        registerSettingsReloader(jdkAssignmentsPanel);
        return jdkAssignmentsPanel;
    }

    private JPanel createJvmTuningPanel() {
        jvmTuningPanel = new JvmTuningPanel(this.config);
        registerSettingsReloader(jvmTuningPanel);
        return jvmTuningPanel;
    }

    private JPanel createJVMSettingsPanel() {
        jvmPanel = new JVMPanel(this.config);
        registerSettingsReloader(jvmPanel);
        return jvmPanel;
    }

    private void createExtendedAppletSecurityPanel() {
        extendedAppletSecurityPanel = new UnsignedAppletsTrustingListPanel(
                PathsAndFiles.APPLET_TRUST_SETTINGS_SYS.getFile(),
                PathsAndFiles.APPLET_TRUST_SETTINGS_USER.getFile(),
                this.config);
        registerSettingsReloader(extendedAppletSecurityPanel);
    }

    private void registerSettingsReloader(SettingsPanelReloader reloader) {
        settingsReloaders.add(reloader);
    }

    private void updateEditorButtons() {
        if (applyButton == null || revertButton == null) {
            return;
        }
        boolean pending = config.hasPendingChanges();
        if (pending) {
            applyButton.setText(APPLY_MARK_PENDING + Translator.R("ButApply"));
            applyButton.setToolTipText(Translator.R("CPApplyPendingTip"));
        } else {
            applyButton.setText(APPLY_MARK_SAVED + Translator.R("ButApply"));
            applyButton.setToolTipText(Translator.R("CPApplySavedTip"));
        }
        revertButton.setEnabled(pending);
    }

    private void revertConfiguration() {
        config.revertPendingChanges();
        config.beginSuppressedPropertyUpdates();
        try {
            for (SettingsPanelReloader reloader : settingsReloaders) {
                reloader.reloadFromConfiguration();
            }
        } finally {
            config.endSuppressedPropertyUpdates();
        }
        updateEditorButtons();
    }

    /**
     * This is a placeholder panel.
     * 
     * @return a placeholder panel
     * @see JPanel
     */
    private JPanel createNotImplementedPanel() {

        JPanel notImplementedPanel = new NamedBorderPanel("Unimplemented");
        notImplementedPanel.setLayout(new BorderLayout());

        URL imgUrl = SunMiscLauncher.getResourceUrl("net/sourceforge/jnlp/resources/warning.png");
        Image img;
        try {
            if (imgUrl == null) {
                throw new IOException("Bundled warning icon not found");
            }
            img = ImageIO.read(imgUrl);
            ImageIcon icon = new ImageIcon(img);
            JLabel label = new JLabel("Not Implemented", icon, SwingConstants.CENTER);
            notImplementedPanel.add(label);
        } catch (IOException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }
        return notImplementedPanel;
    }

    /**
     * Save the configuration changes.
     */
    private void saveConfiguration() {
        try {
            config.applyPendingChanges();
            updateEditorButtons();
        } catch (IOException e) {
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
            JOptionPane.showMessageDialog(this, e);
        }
    }

    public static void main(String[] args) throws Exception {
        DeploymentConfiguration.move14AndOlderFilesTo15StructureCatched();
        final DeploymentConfiguration config = new DeploymentConfiguration();
        try {
            config.load();
        } catch (ConfigurationException e) {
            // FIXME inform user about this and exit properly
            // the only known condition under which this can happen is when a
            // required system configuration file is not found

            // if configuration is not loaded, we will get NullPointerExceptions
            // everywhere
            OutputController.getLogger().log(OutputController.Level.ERROR_ALL, e);
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // ignore; not a big deal
        }

        SwingUtils.invokeLater(new Runnable() {
            @Override
            public void run() {
                final ControlPanel editor = new ControlPanel(config);
                editor.setVisible(true);
            }
        });
    }
}
