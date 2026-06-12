package net.sourceforge.jnlp.about;

import static net.sourceforge.jnlp.runtime.Translator.R;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Locale;

import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;

import net.sourceforge.jnlp.util.docprovider.TextsProvider;
import net.sourceforge.jnlp.util.docprovider.formatters.formatters.HtmlFormatter;
import net.sourceforge.jnlp.util.logging.OutputController;

/**
 * Embeddable about / authors / news / changelog / license / help / FAQ viewer.
 * Used inline in the control panel About tab and wrapped by {@link AboutDialog}.
 */
public final class AboutContentPanel extends JPanel implements ActionListener {

    private static final String ABOUT_URL_STUB = "/net/sourceforge/jnlp/resources/about";
    private static final String AUTHORS_URL = "/net/sourceforge/jnlp/resources/AUTHORS.html";
    private static final String CHANGELOG_URL = "/net/sourceforge/jnlp/resources/ChangeLog.html";
    private static final String COPYING_URL = "/net/sourceforge/jnlp/resources/COPYING.html";
    private static final String NEWS_URL = "/net/sourceforge/jnlp/resources/NEWS.html";

    public enum ShowPage {
        ABOUT,
        AUTHORS,
        NEWS,
        CHANGELOG,
        LICENSE,
        HELP,
        FAQ
    }

    private static HTMLPanel aboutPanel;
    private static HTMLPanel authorsPanel;
    private static HTMLPanel newsPanel;
    private static HTMLPanel changelogPanel;
    private static HTMLPanel copyingPanel;
    private static HTMLPanel helpPanel;
    private static JPanel faqPanel;

    private final String app;
    private JPanel contentPane;
    private final JButton aboutButton;
    private final JButton authorsButton;
    private final JButton newsButton;
    private final JButton changelogButton;
    private final JButton copyingButton;
    private final JButton helpButton;
    private final JButton faqButton;

    private final URL resAuthors = getClass().getResource(AUTHORS_URL);
    private final URL resNews = getClass().getResource(NEWS_URL);
    private final URL resChangelog = getClass().getResource(CHANGELOG_URL);
    private final URL resCopying = getClass().getResource(COPYING_URL);

    public AboutContentPanel(String app) {
        this(app, ShowPage.ABOUT);
    }

    public AboutContentPanel(String app, ShowPage showPage) {
        super(new GridBagLayout());
        this.app = app;

        aboutButton = new JButton(R("AboutDialogueTabAbout"));
        aboutButton.addActionListener(this);
        authorsButton = new JButton(R("AboutDialogueTabAuthors"));
        authorsButton.addActionListener(this);
        newsButton = new JButton(R("AboutDialogueTabNews"));
        newsButton.addActionListener(this);
        changelogButton = new JButton(R("AboutDialogueTabChangelog"));
        changelogButton.addActionListener(this);
        copyingButton = new JButton(R("AboutDialogueTabGPLv2"));
        copyingButton.addActionListener(this);
        helpButton = new JButton(R("APPEXTSECguiPanelHelpButton"));
        helpButton.addActionListener(this);
        faqButton = new JButton(R("AboutDialogueTabFaq"));
        faqButton.addActionListener(this);

        switch (showPage) {
            case AUTHORS:
                showPage(authorsButton);
                break;
            case CHANGELOG:
                showPage(changelogButton);
                break;
            case HELP:
                showPage(helpButton);
                break;
            case LICENSE:
                showPage(copyingButton);
                break;
            case NEWS:
                showPage(newsButton);
                break;
            case FAQ:
                showPage(faqButton);
                break;
            case ABOUT:
            default:
                showPage(aboutButton);
                break;
        }
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        showPage((JButton) e.getSource());
    }

    private void showPage(JButton source) {
        if (source == aboutButton) {
            if (aboutPanel == null) {
                String lang = Locale.getDefault().getLanguage();
                URL aboutLang;
                try {
                    aboutLang = getClass().getResource(ABOUT_URL_STUB + "_" + lang + ".html");
                    aboutLang.openStream().close();
                } catch (Exception ex) {
                    OutputController.getLogger().log(OutputController.Level.ERROR_DEBUG, ex);
                    aboutLang = getClass().getResource(ABOUT_URL_STUB + "_en.html");
                }
                aboutPanel = new HTMLPanel(aboutLang);
            }
            contentPane = aboutPanel;
        } else if (source == authorsButton) {
            if (authorsPanel == null) {
                authorsPanel = new HTMLPanel(resAuthors);
            }
            contentPane = authorsPanel;
        } else if (source == newsButton) {
            if (newsPanel == null) {
                newsPanel = new HTMLPanel(resNews);
            }
            contentPane = newsPanel;
        } else if (source == changelogButton) {
            if (changelogPanel == null) {
                changelogPanel = new HTMLPanel(resChangelog);
            }
            contentPane = changelogPanel;
        } else if (source == copyingButton) {
            if (copyingPanel == null) {
                copyingPanel = new HTMLPanel(resCopying);
            }
            contentPane = copyingPanel;
        } else if (source == helpButton) {
            if (helpPanel == null) {
                try {
                    File f = File.createTempFile("icedtea-web", "help");
                    f.delete();
                    f.mkdir();
                    f.deleteOnExit();
                    TextsProvider.generateRuntimeHtmlTexts(f);
                    File target = new File(f, TextsProvider.ITW + "." + HtmlFormatter.SUFFIX);
                    if (app != null) {
                        target = new File(f, app + "." + HtmlFormatter.SUFFIX);
                    }
                    helpPanel = new InternalHTMLPanel(target.toURI().toURL());
                } catch (IOException ex) {
                    OutputController.getLogger().log(ex);
                }
            }
            contentPane = helpPanel;
        } else if (source == faqButton) {
            if (faqPanel == null) {
                faqPanel = createFaqPanel();
            }
            contentPane = faqPanel;
        }

        layoutContent();
    }

    private static JPanel createFaqPanel() {
        String html = "<html><body style='font-family: sans-serif; margin: 8px;'>"
                + "<h3>" + R("CPAboutFaqQuestion") + "</h3>"
                + "<p><em>" + R("CPAboutFaqDevNote") + "</em> " + R("CPAboutFaqAnswer") + "</p>"
                + "<pre style='background:#f4f4f4;padding:8px;font-family:monospace;font-size:12px;'>"
                + "frame.setIconImage(\n"
                + "    new ImageIcon(getClass().getResource(\"/icons/app.png\")).getImage());\n"
                + "\n"
                + "frame.setIconImages(java.util.List.of(\n"
                + "    new ImageIcon(getClass().getResource(\"/icons/app-16.png\")).getImage(),\n"
                + "    new ImageIcon(getClass().getResource(\"/icons/app-32.png\")).getImage()));\n"
                + "</pre>"
                + "</body></html>";
        JEditorPane pane = new JEditorPane("text/html", html);
        pane.setEditable(false);
        JPanel panel = new JPanel(new java.awt.BorderLayout());
        panel.add(new JScrollPane(pane), java.awt.BorderLayout.CENTER);
        return panel;
    }

    private void layoutContent() {
        removeAll();

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.gridy = 0;
        gbc.gridx = 0;
        gbc.gridwidth = 7;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        add(contentPane, gbc);

        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.ipady = 16;
        gbc.weighty = 0;

        gbc.gridx = 0;
        add(aboutButton, gbc);
        gbc.gridx = 1;
        add(authorsButton, gbc);
        gbc.gridx = 2;
        add(newsButton, gbc);
        gbc.gridx = 3;
        add(changelogButton, gbc);
        gbc.gridx = 4;
        add(copyingButton, gbc);
        gbc.gridx = 5;
        add(faqButton, gbc);
        gbc.gridx = 6;
        add(helpButton, gbc);

        Dimension contentSize = new Dimension(640, 480);
        contentPane.setMinimumSize(contentSize);
        contentPane.setPreferredSize(contentSize);
        contentPane.setBorder(new EmptyBorder(0, 0, 8, 0));

        revalidate();
        repaint();
    }
}
