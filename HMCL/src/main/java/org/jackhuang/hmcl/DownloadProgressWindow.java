/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl;

import org.jackhuang.hmcl.util.SelfDependencyPatcher;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The download window FSL shows while it fetches something it cannot start without.
///
/// Styled after HMCL: a borderless white card, one accent coloured progress bar and a muted status
/// line carrying the amount already downloaded, the archive size, the live speed and the remaining
/// time. Every download the launcher performs before JavaFX is available reports into this window,
/// so that a first start never shows the plain HMCL progress frame.
public final class DownloadProgressWindow
        implements JavaRuntimeBootstrap.ProgressListener, SelfDependencyPatcher.FetchListener {

    private static final Color ACCENT = new Color(0x09, 0x69, 0xDA);
    private static final Color TRACK = new Color(0xEB, 0xED, 0xF0);
    private static final Color BORDER = new Color(0xD8, 0xDE, 0xE4);
    private static final Color TEXT = new Color(0x1F, 0x23, 0x28);
    private static final Color MUTED = new Color(0x6E, 0x77, 0x81);
    private static final Color SURFACE = Color.WHITE;

    private final JDialog dialog;
    private final JLabel titleLabel;
    private final JLabel statusLabel;
    private final ProgressBar progressBar;
    private long lastBytes = -1;
    private long lastSampleTime;
    private double speed;

    private DownloadProgressWindow(JDialog dialog, JLabel titleLabel, ProgressBar progressBar, JLabel statusLabel) {
        this.dialog = dialog;
        this.titleLabel = titleLabel;
        this.progressBar = progressBar;
        this.statusLabel = statusLabel;
    }

    public static DownloadProgressWindow create(String title) {
        if (isHeadless())
            return null;
        try {
            JDialog dialog = new JDialog((java.awt.Frame) null, title, false);
            dialog.setUndecorated(true);
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 15f));
            titleLabel.setForeground(TEXT);
            titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

            ProgressBar progressBar = new ProgressBar();
            progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel statusLabel = new JLabel(i18n("fatal.java_runtime.preparing"));
            statusLabel.setFont(statusLabel.getFont().deriveFont(11.5f));
            statusLabel.setForeground(MUTED);
            statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            content.setBorder(BorderFactory.createEmptyBorder(18, 22, 16, 22));
            content.add(titleLabel);
            content.add(Box.createVerticalStrut(12));
            content.add(progressBar);
            content.add(Box.createVerticalStrut(9));
            content.add(statusLabel);

            CardPanel card = new CardPanel();
            card.setLayout(new BorderLayout());
            card.add(content, BorderLayout.CENTER);
            card.setPreferredSize(new Dimension(430, 116));

            dialog.setContentPane(card);
            dialog.pack();
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
            return new DownloadProgressWindow(dialog, titleLabel, progressBar, statusLabel);
        } catch (Throwable e) {
            LOG.warning("Failed to show the download window", e);
            return null;
        }
    }

    /// Reuses the same window for the next stage of a download instead of opening another one.
    public void setTitle(String title) {
        SwingUtilities.invokeLater(() -> {
            try {
                titleLabel.setText(title);
                statusLabel.setText(i18n("fatal.java_runtime.preparing"));
                progressBar.setProgress(-1);
            } catch (Throwable ignored) {
            }
        });
    }

    public void setStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            try {
                statusLabel.setText(status);
            } catch (Throwable ignored) {
            }
        });
    }

    public void setProgress(double ratio) {
        SwingUtilities.invokeLater(() -> {
            try {
                progressBar.setProgress(ratio);
            } catch (Throwable ignored) {
            }
        });
    }

    public void setIndeterminate() {
        setProgress(-1);
    }

    @Override
    public void onMessage(String message) {
        if (message == null)
            return;
        // The bootstrap reports the mirror URL and the extraction step; a URL is user hostile,
        // a friendly "preparing" hint is what the user expects while the archive is unpacked.
        boolean extracting = !message.startsWith("http");
        SwingUtilities.invokeLater(() -> {
            try {
                if (extracting) {
                    progressBar.setProgress(-1);
                    statusLabel.setText(i18n("fatal.java_runtime.preparing"));
                } else if (lastBytes < 0) {
                    statusLabel.setText(i18n("fatal.java_runtime.preparing"));
                }
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    public void onProgress(long downloaded, long total) {
        long now = System.currentTimeMillis();
        if (lastBytes >= 0 && now - lastSampleTime >= 200) {
            double instant = (downloaded - lastBytes) * 1000.0 / (now - lastSampleTime);
            speed = speed <= 0 ? instant : speed * 0.65 + instant * 0.35;
            lastBytes = downloaded;
            lastSampleTime = now;
        } else if (lastBytes < 0) {
            lastBytes = downloaded;
            lastSampleTime = now;
        }

        double ratio = total > 0 ? (double) downloaded / total : -1;
        String text = describeProgress(downloaded, total, ratio);
        SwingUtilities.invokeLater(() -> {
            try {
                progressBar.setProgress(ratio);
                statusLabel.setText(text);
            } catch (Throwable ignored) {
            }
        });
    }

    /// Switches the same window over to the JavaFX modules, so that a first start shows one
    /// download window instead of one for the runtime and another one for the modules.
    @Override
    public void onStart(int total) {
        SwingUtilities.invokeLater(() -> {
            try {
                lastBytes = -1;
                speed = 0;
                progressBar.setProgress(0);
                statusLabel.setText(i18n("download.javafx"));
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    public void onProgress(String module, int completed, int total) {
        double ratio = total > 0 ? (double) completed / total : -1;
        SwingUtilities.invokeLater(() -> {
            try {
                progressBar.setProgress(ratio);
                statusLabel.setText(module + "   " + completed + " / " + total);
            } catch (Throwable ignored) {
            }
        });
    }

    private String describeProgress(long downloaded, long total, double ratio) {
        StringBuilder builder = new StringBuilder(formatSize(downloaded));
        if (total > 0) {
            builder.append(" / ").append(formatSize(total));
            builder.append("   ").append(Math.round(Math.min(1.0, ratio) * 100)).append('%');
        }
        if (speed >= 1024) {
            builder.append("   ").append(formatSize((long) speed)).append("/s");
            if (total > downloaded) {
                long remaining = (long) Math.ceil((total - downloaded) / speed);
                builder.append("   剩余 ").append(remaining <= 1 ? "1 秒内" : remaining + " 秒");
            }
        }
        return builder.toString();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024)
            return String.format(Locale.ROOT, "%.0f KB", kb);
        return String.format(Locale.ROOT, "%.1f MB", kb / 1024);
    }

    public void dispose() {
        SwingUtilities.invokeLater(() -> {
            try {
                dialog.setVisible(false);
                dialog.dispose();
            } catch (Throwable ignored) {
            }
        });
    }

    private static boolean isHeadless() {
        try {
            return GraphicsEnvironment.isHeadless();
        } catch (Throwable e) {
            return true;
        }
    }

    /// Rounded white card drawn behind the labels.
    private static final class CardPanel extends JPanel {

        CardPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth();
                int height = getHeight();
                g2.setColor(SURFACE);
                g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12);
                g2.setColor(BORDER);
                g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12);
            } finally {
                g2.dispose();
            }
        }
    }

    /// Flat accent coloured progress bar, `-1` renders the indeterminate state.
    ///
    /// The reported ratio arrives once per downloaded megabyte and per connection, so painting it
    /// directly makes the bar hop in visible steps. The filled width therefore eases towards the
    /// latest ratio at ~60 fps, which keeps the motion continuous even with bursty callbacks.
    private static final class ProgressBar extends JComponent {

        private static final int FRAME_INTERVAL_MS = 16;
        private static final double EASING = 0.28;
        private static final double SNAP_THRESHOLD = 0.0005;

        private double progress = -1;
        private double target = -1;
        private Timer animator;

        ProgressBar() {
            setPreferredSize(new Dimension(386, 8));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
            setMinimumSize(new Dimension(64, 8));
        }

        void setProgress(double value) {
            if (value < 0) {
                stopAnimation();
                target = -1;
                progress = -1;
                repaint();
                return;
            }

            target = Math.min(1.0, value);

            // First value, or the ratio moved backwards because the source was switched:
            // snap instead of gliding so the bar never appears to rewind slowly.
            if (progress < 0 || target < progress) {
                progress = target;
                stopAnimation();
                repaint();
                return;
            }

            if (progress < target) {
                startAnimation();
            }
        }

        private void startAnimation() {
            if (animator == null) {
                animator = new Timer(FRAME_INTERVAL_MS, e -> advance());
                animator.setCoalesce(true);
            }
            if (!animator.isRunning()) {
                animator.start();
            }
        }

        private void stopAnimation() {
            if (animator != null && animator.isRunning()) {
                animator.stop();
            }
        }

        /// Runs on the EDT: pull the painted value one easing step closer to the reported ratio.
        private void advance() {
            if (target < 0) {
                stopAnimation();
                return;
            }

            double delta = target - progress;
            if (Math.abs(delta) <= SNAP_THRESHOLD) {
                progress = target;
                stopAnimation();
                repaint();
                return;
            }

            progress += delta * EASING;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth();
                int height = getHeight();
                g2.setColor(TRACK);
                g2.fillRoundRect(0, 0, width, height, height, height);
                if (progress >= 0) {
                    int filled = (int) Math.round(width * Math.min(1.0, progress));
                    if (filled > 0) {
                        filled = Math.max(filled, height);
                        g2.setColor(ACCENT);
                        g2.fillRoundRect(0, 0, filled, height, height, height);
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
