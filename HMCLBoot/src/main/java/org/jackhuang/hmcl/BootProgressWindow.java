/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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

import org.jackhuang.hmcl.util.SwingUtils;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.util.Locale;
import java.util.ResourceBundle;

/// A small Swing window shown while FSL is acquiring a Java runtime.
///
/// It intentionally depends on nothing but the JDK, because it is used from [Main] which may run on
/// a very old Java version. The window is informational only: the user does not have to click anything.
public final class BootProgressWindow implements JavaRuntimeBootstrap.ProgressListener {

    private final JDialog dialog;
    private final JLabel messageLabel;
    private final JProgressBar progressBar;

    private BootProgressWindow(ResourceBundle bundle, String note) {
        dialog = new JDialog((java.awt.Frame) null, bundle.getString("boot.java_runtime.title"), false);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JLabel titleLabel = new JLabel(bundle.getString("boot.java_runtime.title"));
        titleLabel.setFont(titleLabel.getFont().deriveFont(titleLabel.getFont().getStyle() | java.awt.Font.BOLD,
                titleLabel.getFont().getSize2D() + 2.0f));
        titleLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        panel.add(titleLabel);
        panel.add(Box.createVerticalStrut(8));

        for (String line : note.split("\n")) {
            JLabel noteLabel = new JLabel(line);
            noteLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
            panel.add(noteLabel);
        }
        panel.add(Box.createVerticalStrut(12));

        messageLabel = new JLabel(" ");
        messageLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        panel.add(messageLabel);
        panel.add(Box.createVerticalStrut(6));

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString(bundle.getString("boot.java_runtime.prepare"));
        progressBar.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        panel.add(progressBar);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setAlwaysOnTop(true);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        dialog.setLocation((screen.width - dialog.getWidth()) / 2, (screen.height - dialog.getHeight()) / 2);
        dialog.setVisible(true);
    }

    /// Shows the window. Must be called before a long running download starts.
    public static BootProgressWindow show(String note) {
        SwingUtils.initLookAndFeel();
        ResourceBundle bundle = BootProperties.getResourceBundle();
        final BootProgressWindow[] holder = new BootProgressWindow[1];
        runOnEdtAndWait(() -> holder[0] = new BootProgressWindow(bundle, note));
        return holder[0];
    }

    @Override
    public void onMessage(String message) {
        SwingUtilities.invokeLater(() -> messageLabel.setText(message));
    }

    @Override
    public void onProgress(long downloaded, long total) {
        SwingUtilities.invokeLater(() -> {
            if (total > 0) {
                int percent = (int) Math.min(100, downloaded * 100 / total);
                progressBar.setValue(percent);
                progressBar.setString(percent + "%  " + formatSize(downloaded) + " / " + formatSize(total));
            } else {
                progressBar.setIndeterminate(true);
                progressBar.setString(formatSize(downloaded));
            }
        });
    }

    /// Closes the window. Safe to call more than once.
    public void close() {
        runOnEdtAndWait(() -> {
            dialog.setVisible(false);
            dialog.dispose();
        });
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        double value = bytes / 1024.0;
        if (value < 1024)
            return String.format(Locale.ROOT, "%.1f KB", value);
        value /= 1024.0;
        return String.format(Locale.ROOT, "%.1f MB", value);
    }

    private static void runOnEdtAndWait(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            runnable.run();
        }
    }
}
