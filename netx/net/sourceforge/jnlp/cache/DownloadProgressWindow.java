package net.sourceforge.jnlp.cache;

import static net.sourceforge.jnlp.runtime.Translator.R;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

import net.sourceforge.jnlp.util.ImageResources;
import net.sourceforge.jnlp.util.ScreenFinder;
import net.sourceforge.jnlp.util.logging.OutputController;
import net.sourceforge.swing.SwingUtils;

/**
 * Non-modal download window: overall determinate bar, mean + 10s throughput,
 * ETA from the instant rate, and an expand control for the 12 lane slots.
 */
final class DownloadProgressWindow {

    private static final int BAR_TICK_MS = 1_000;
    private static final int RATE_TICK_MS = 10_000;

    private static JDialog dialog;
    private static Timer barTimer;
    private static Timer rateTimer;
    private static JProgressBar overallBar;
    private static JPanel unpackWrap;
    private static JProgressBar unpackBar;
    private static JLabel unpackHeader;
    private static JLabel header;
    private static JLabel rates;
    private static JPanel slotPanel;
    private static JLabel[] slotLabels;
    private static JProgressBar[] slotBars;
    private static boolean expanded;
    private static DownloadProgress model;

    private DownloadProgressWindow() {
    }

    static void open(final DownloadProgress progress) {
        if (progress == null) {
            return;
        }
        SwingUtils.invokeLater(new Runnable() {
            @Override
            public void run() {
                show(progress);
            }
        });
    }

    static void close() {
        SwingUtils.invokeLater(new Runnable() {
            @Override
            public void run() {
                hide();
            }
        });
    }

    private static void show(DownloadProgress progress) {
        hide();
        model = progress;
        expanded = false;
        dialog = new JDialog((JFrame) null, R("CDownloading") + "…");
        dialog.setName("DownloadProgressDialog");
        dialog.setAlwaysOnTop(true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setIconImages(ImageResources.INSTANCE.getApplicationImages());

        boolean advanced = DownloadProgress.isAdvanced();
        header = new JLabel(" ");
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        overallBar = new JProgressBar(0, 100);
        overallBar.setStringPainted(true);
        overallBar.setPreferredSize(new Dimension(360, 18));

        JPanel main = new JPanel(new BorderLayout(0, 6));
        main.setBorder(new EmptyBorder(10, 12, 10, 12));
        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(header, BorderLayout.NORTH);
        if (advanced) {
            unpackHeader = new JLabel(" ");
            unpackBar = new JProgressBar(0, 100);
            unpackBar.setStringPainted(true);
            unpackBar.setPreferredSize(new Dimension(360, 18));
            unpackWrap = new JPanel(new BorderLayout(0, 2));
            unpackWrap.add(unpackHeader, BorderLayout.NORTH);
            unpackWrap.add(unpackBar, BorderLayout.CENTER);
            unpackWrap.setVisible(false);
            unpackWrap.setBorder(new EmptyBorder(6, 0, 0, 0));
            rates = new JLabel(" ");
            JPanel bars = new JPanel();
            bars.setLayout(new BoxLayout(bars, BoxLayout.Y_AXIS));
            bars.add(overallBar);
            bars.add(unpackWrap);
            top.add(bars, BorderLayout.CENTER);
            top.add(rates, BorderLayout.SOUTH);

            final JButton details = new JButton(R("ButShowDetails"));
            details.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    expanded = !expanded;
                    details.setText(expanded ? R("ButHideDetails") : R("ButShowDetails"));
                    slotPanel.setVisible(expanded);
                    packAndPlace();
                    paint(true);
                }
            });
            int n = progress.slots.length;
            slotPanel = new JPanel(new GridLayout(n, 1, 0, 2));
            slotLabels = new JLabel[n];
            slotBars = new JProgressBar[n];
            for (int i = 0; i < n; i++) {
                JPanel row = new JPanel(new BorderLayout(6, 0));
                slotBars[i] = new JProgressBar(0, 100);
                slotBars[i].setPreferredSize(new Dimension(120, 14));
                slotBars[i].setStringPainted(true);
                slotLabels[i] = new JLabel("slot " + (i + 1) + " idle");
                row.add(slotBars[i], BorderLayout.WEST);
                row.add(slotLabels[i], BorderLayout.CENTER);
                slotPanel.add(row);
            }
            slotPanel.setVisible(false);
            main.add(top, BorderLayout.NORTH);
            main.add(slotPanel, BorderLayout.CENTER);
            JPanel south = new JPanel(new BorderLayout());
            south.add(details, BorderLayout.EAST);
            main.add(south, BorderLayout.SOUTH);
        } else {
            top.add(overallBar, BorderLayout.CENTER);
            main.add(top, BorderLayout.CENTER);
        }
        main.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(), new EmptyBorder(8, 10, 8, 10)));

        dialog.setContentPane(main);
        packAndPlace();
        dialog.setVisible(true);

        barTimer = new Timer(BAR_TICK_MS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                paint(false);
            }
        });
        barTimer.start();
        rateTimer = new Timer(RATE_TICK_MS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                paint(true);
            }
        });
        rateTimer.setInitialDelay(RATE_TICK_MS);
        rateTimer.start();
        paint(true);
        OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                "Download progress window: "
                        + (advanced ? progress.slots.length + " slots" : "simple")
                        + ", total=" + DownloadProgress.formatBytes(progress.knownTotal));
    }

    private static void hide() {
        if (barTimer != null) {
            barTimer.stop();
            barTimer = null;
        }
        if (rateTimer != null) {
            rateTimer.stop();
            rateTimer = null;
        }
        if (dialog != null) {
            dialog.setVisible(false);
            dialog.dispose();
            dialog = null;
        }
        model = null;
        slotPanel = null;
        slotLabels = null;
        slotBars = null;
        unpackWrap = null;
        unpackBar = null;
        unpackHeader = null;
        rates = null;
    }

    private static void paint(boolean ratesTick) {
        DownloadProgress p = model;
        if (p == null || dialog == null) {
            return;
        }
        DownloadProgress.Snapshot s = p.snapshot();
        boolean advanced = DownloadProgress.isAdvanced();
        String phase = s.unpacking ? R("CUnpacking")
                : (s.loading ? R("CLoading") : R("CDownloading"));
        if (!advanced) {
            header.setText(phase + "  " + s.percent + "%");
            overallBar.setValue(s.percent);
            overallBar.setString(s.percent + "%");
            if (dialog != null) {
                dialog.setTitle(phase + "…");
            }
            if (ratesTick) {
                OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                        phase + " progress " + s.mathLine());
            }
            return;
        }
        String name = s.title == null || s.title.isEmpty() ? "" : s.title + " ";
        String extra = s.finishing != null && !s.finishing.isEmpty() ? "  " + s.finishing : "";
        header.setText(R("CDownloading") + " " + name + s.percent + "%  "
                + DownloadProgress.formatBytes(s.bytes) + " / "
                + DownloadProgress.formatBytes(s.knownTotal) + extra);
        overallBar.setValue(s.percent);
        overallBar.setString(s.percent + "%");
        boolean showUnpack = s.unpack.showBar(s.percent, p.complete);
        if (unpackWrap != null) {
            if (showUnpack != unpackWrap.isVisible()) {
                unpackWrap.setVisible(showUnpack);
                if (showUnpack) {
                    dialog.setTitle(R("CUnpacking") + "…");
                } else {
                    dialog.setTitle(R("CDownloading") + "…");
                }
                packAndPlace();
            }
            if (showUnpack) {
                String uName = s.unpack.active ? s.unpack.name : s.finishing;
                String queued = s.unpack.queued > 0 ? "  queued " + s.unpack.queued : "";
                unpackHeader.setText(R("CUnpacking") + " " + uName + "  "
                        + s.unpack.percent + "%  "
                        + DownloadProgress.formatBytes(s.unpack.bytes) + " / ~"
                        + DownloadProgress.formatBytes(s.unpack.total) + queued);
                unpackBar.setValue(s.unpack.percent);
                unpackBar.setString(s.unpack.percent + "%");
            }
        }
        if (ratesTick || rates.getText().trim().isEmpty()) {
            String left = s.finishing != null && !s.finishing.isEmpty()
                    ? s.finishing
                    : "left " + DownloadProgress.formatEta(s.etaMs);
            rates.setText("mean " + DownloadProgress.formatRate(s.meanBps)
                    + "   now " + DownloadProgress.formatRate(s.nowBps)
                    + "   " + left);
            if (ratesTick) {
                OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                        "Download progress " + s.mathLine()
                                + " mean=" + DownloadProgress.formatRate(s.meanBps)
                                + " now=" + DownloadProgress.formatRate(s.nowBps)
                                + " eta=" + DownloadProgress.formatEta(s.etaMs)
                                + extra);
                if (showUnpack) {
                    OutputController.getLogger().log(OutputController.Level.MESSAGE_ALL,
                            "Unpack progress " + s.unpack.percent + "% "
                                    + DownloadProgress.formatBytes(s.unpack.bytes) + "/~"
                                    + DownloadProgress.formatBytes(s.unpack.total)
                                    + " " + s.unpack.name
                                    + (s.unpack.queued > 0 ? " queued=" + s.unpack.queued : ""));
                }
            }
        }
        if (expanded && slotLabels != null) {
            for (int i = 0; i < s.slots.length && i < slotLabels.length; i++) {
                DownloadProgress.SlotSnap sl = s.slots[i];
                slotBars[i].setValue(sl.busy ? sl.percent : 0);
                slotBars[i].setString(sl.busy ? sl.percent + "%" : "");
                if (sl.busy) {
                    slotLabels[i].setText((i + 1) + "  " + sl.name + "  "
                            + DownloadProgress.formatBytes(sl.bytes) + "/"
                            + DownloadProgress.formatBytes(sl.size)
                            + "  mean " + DownloadProgress.formatRate(sl.meanBps)
                            + "  now " + DownloadProgress.formatRate(sl.nowBps)
                            + "  left " + DownloadProgress.formatEta(sl.etaMs));
                } else {
                    slotLabels[i].setText((i + 1) + "  idle");
                }
            }
        }
    }

    private static void packAndPlace() {
        if (dialog == null) {
            return;
        }
        dialog.pack();
        try {
            Rectangle bounds = ScreenFinder.getCurrentScreenSizeWithoutBounds();
            dialog.setLocation(bounds.width + bounds.x - dialog.getWidth() - 16,
                    bounds.height + bounds.y - dialog.getHeight() - 48);
        } catch (HeadlessException ignored) {
        }
    }
}
