package net.sourceforge.icedteaweb.sample1;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.Instant;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * JDK 17 Swing sample launched via JNLP for IcedTea-Web manual testing.
 */
public final class SampleApp1Main {

    public static final String SUCCESS_MARKER = "ITW_SAMPLE_APP_1_SUCCESS";

    private SampleApp1Main() {
    }

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println(SUCCESS_MARKER + " headless jdk="
                    + System.getProperty("java.version"));
            System.out.flush();
            return;
        }

        SwingUtilities.invokeLater(SampleApp1Main::createAndShow);
    }

    private static void createAndShow() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // keep default L&F
        }

        JFrame frame = new JFrame("IcedTea-Web Sample App 1");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(560, 360));

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(Color.WHITE);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(15, 23, 42));
        header.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        JLabel title = new JLabel("Sample App 1");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        header.add(title, BorderLayout.WEST);

        JLabel badge = new JLabel("JDK 17");
        badge.setForeground(new Color(191, 219, 254));
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 13f));
        header.add(badge, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        JPanel body = new JPanel(new GridBagLayout());
        body.setBackground(Color.WHITE);
        body.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 8, 0);

        body.add(infoRow("Java version", System.getProperty("java.version")), gbc);
        gbc.gridy++;
        body.add(infoRow("Vendor", System.getProperty("java.vendor")), gbc);
        gbc.gridy++;
        body.add(infoRow("Runtime", System.getProperty("java.runtime.name")), gbc);
        gbc.gridy++;
        body.add(infoRow("Started", Instant.now().toString()), gbc);

        gbc.gridy++;
        gbc.insets = new Insets(20, 0, 0, 0);
        JLabel hint = new JLabel(
                "If you can see this window, JNLP launch and JDK 17 selection succeeded.");
        hint.setForeground(new Color(71, 85, 105));
        body.add(hint, gbc);

        root.add(body, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(new Color(248, 250, 252));
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(226, 232, 240)),
                BorderFactory.createEmptyBorder(12, 24, 12, 24)));

        JLabel status = new JLabel("Ready");
        status.setForeground(new Color(51, 65, 85));
        footer.add(status, BorderLayout.WEST);

        JButton close = new JButton("Close");
        close.addActionListener(e -> System.exit(0));
        footer.add(close, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);

        System.out.println(SUCCESS_MARKER + " gui jdk="
                + System.getProperty("java.version")
                + " vendor=" + System.getProperty("java.vendor"));
        System.out.flush();
    }

    private static JPanel infoRow(String label, String value) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setBackground(Color.WHITE);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        JLabel key = new JLabel(label);
        key.setForeground(new Color(100, 116, 139));
        key.setPreferredSize(new Dimension(120, key.getPreferredSize().height));
        row.add(key, BorderLayout.WEST);

        JLabel val = new JLabel(value == null ? "—" : value);
        val.setForeground(new Color(15, 23, 42));
        val.setFont(val.getFont().deriveFont(Font.BOLD));
        row.add(val, BorderLayout.CENTER);
        return row;
    }
}
