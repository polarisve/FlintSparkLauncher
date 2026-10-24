/*
 * Flint Spark Launcher (FSL), a modified version of Hello Minecraft! Launcher.
 * Copyright (C) 2026 FSL contributors
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

import javafx.application.Platform;
import javafx.stage.Stage;
import org.jackhuang.hmcl.ui.Controllers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// FSL: only one launcher window per user, like PCL.
///
/// The first launcher holds a lock on `fsl.lock` in the user data folder and listens on a random
/// loopback port, written into `fsl.port` (Windows does not let other processes read a locked file). A second launch fails to get the lock, asks the
/// first one to show its window, and exits. The listener only accepts connections from this
/// computer and only reacts to one fixed word, so nothing else can be triggered through it.
@NotNullByDefault
public final class FslSingleInstance {
    /// The only message the listener understands.
    private static final byte[] SHOW = "FSL-SHOW\n".getBytes(StandardCharsets.US_ASCII);

    /// Kept open for the whole run; the OS releases the lock when the process ends, even on a crash.
    private static @Nullable FileChannel channel;
    private static @Nullable FileLock lock;

    private FslSingleInstance() {
    }

    /// Becomes the one running launcher, or hands over to the one already running.
    ///
    /// @param dataFolder the per-user FSL folder holding the lock file
    /// @return `true` to go on starting; `false` if another launcher was asked to show itself
    ///         and this process should exit
    public static boolean acquire(Path dataFolder) {
        Path lockFile = dataFolder.resolve("fsl.lock");
        Path portFile = dataFolder.resolve("fsl.port");
        try {
            Files.createDirectories(dataFolder);
            FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            @Nullable FileLock held;
            try {
                held = ch.tryLock();
            } catch (IOException e) {
                held = null;
            }
            if (held == null) {
                ch.close();
                notifyRunning(portFile);
                return false;
            }
            channel = ch;
            lock = held;
            listen(portFile);
            return true;
        } catch (IOException | RuntimeException e) {
            // Never stop the launcher from starting because of this check
            LOG.warning("Single-instance check failed; starting anyway", e);
            return true;
        }
    }

    /// Asks the running launcher to show its window. This process exits either way: two launchers
    /// on the same settings would overwrite each other.
    private static void notifyRunning(Path portFile) {
        try {
            String text = Files.readString(portFile, StandardCharsets.US_ASCII).trim();
            int port = Integer.parseInt(text);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
                socket.getOutputStream().write(SHOW);
                socket.getOutputStream().flush();
            }
            LOG.info("FSL is already running; asked it to show its window");
        } catch (IOException | RuntimeException e) {
            // The other launcher holds the lock but does not answer (still starting, or stuck)
            LOG.warning("FSL is already running but did not answer", e);
        }
    }

    /// Starts the loopback listener and writes its port into `portFile`.
    private static void listen(Path portFile) throws IOException {
        ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        Files.writeString(portFile, Integer.toString(server.getLocalPort()), StandardCharsets.US_ASCII);

        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(2000);
                    if (readsShow(client.getInputStream())) {
                        Platform.runLater(FslSingleInstance::showWindow);
                    }
                } catch (IOException e) {
                    if (!server.isClosed()) {
                        LOG.warning("Single-instance listener error", e);
                    }
                }
            }
        }, "FSL Single Instance");
        thread.setDaemon(true);
        thread.start();
    }

    /// Reads at most the length of [#SHOW] bytes and checks they match exactly.
    private static boolean readsShow(InputStream in) throws IOException {
        byte[] buffer = in.readNBytes(SHOW.length);
        return Arrays.equals(buffer, SHOW);
    }

    /// Brings the main window to the front, restoring it if it was minimized or hidden.
    private static void showWindow() {
        @Nullable Stage stage = Controllers.getStage();
        if (stage == null) {
            // Still starting up: the window will appear by itself
            return;
        }
        if (stage.isIconified()) {
            stage.setIconified(false);
        }
        if (!stage.isShowing()) {
            stage.show();
        }
        stage.toFront();
        stage.requestFocus();
        // Windows may refuse to steal focus; briefly forcing always-on-top makes the window come up anyway
        boolean onTop = stage.isAlwaysOnTop();
        stage.setAlwaysOnTop(true);
        stage.setAlwaysOnTop(onTop);
    }
}
