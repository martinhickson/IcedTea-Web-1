package net.sourceforge.icedteaweb.it.apps;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Simple Swing GUI sample for JDK Assignments integration tests.
 */
public final class GuiSampleJnlpMain {

    public static final String FRAME_TITLE = "ITW JDK Assignments GUI Sample";
    public static final String FRAME_NAME = "guiSampleFrame";
    public static final String EXIT_BUTTON_NAME = "guiSampleExitButton";
    public static final String PRIMARY_BUTTON_NAME = "guiSamplePrimaryButton";
    public static final String SECONDARY_BUTTON_NAME = "guiSampleSecondaryButton";
    public static final String STATUS_LABEL_NAME = "guiSampleStatusLabel";

    private GuiSampleJnlpMain() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                createAndShow();
            }
        });
    }

    private static void createAndShow() {
        final JFrame frame = new JFrame(FRAME_TITLE);
        frame.setName(FRAME_NAME);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitApplication();
            }
        });

        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        fileMenu.setName("guiSampleFileMenu");
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.setName("guiSampleExitMenuItem");
        exitItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                exitApplication();
            }
        });
        fileMenu.add(exitItem);

        JMenu helpMenu = new JMenu("Help");
        helpMenu.setName("guiSampleHelpMenu");
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.setName("guiSampleAboutMenuItem");
        aboutItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JOptionPane.showMessageDialog(frame,
                        "JDK Assignments GUI sample\n"
                                + System.getProperty("java.version"),
                        "About",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        });
        helpMenu.add(aboutItem);
        menuBar.add(fileMenu);
        menuBar.add(helpMenu);
        frame.setJMenuBar(menuBar);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setName("guiSampleContentPanel");
        final JLabel status = new JLabel("Ready on " + System.getProperty("java.version"));
        status.setName(STATUS_LABEL_NAME);
        content.add(status, BorderLayout.NORTH);

        JPanel buttons = new JPanel();
        buttons.setName("guiSampleButtonPanel");
        JButton primary = new JButton("Primary action");
        primary.setName(PRIMARY_BUTTON_NAME);
        primary.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                status.setText("Primary action clicked");
            }
        });
        JButton secondary = new JButton("Secondary action");
        secondary.setName(SECONDARY_BUTTON_NAME);
        secondary.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                status.setText("Secondary action clicked");
            }
        });
        JButton exit = new JButton("Exit");
        exit.setName(EXIT_BUTTON_NAME);
        exit.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                exitApplication();
            }
        });
        buttons.add(primary);
        buttons.add(secondary);
        buttons.add(exit);
        content.add(buttons, BorderLayout.CENTER);
        frame.setContentPane(content);

        frame.setSize(520, 280);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        System.out.println("ITW_INTEGRATION_SUCCESS gui-sample jdk="
                + System.getProperty("java.version")
                + " vendor=" + System.getProperty("java.vendor"));
        System.out.flush();
    }

    private static void exitApplication() {
        System.exit(0);
    }
}
