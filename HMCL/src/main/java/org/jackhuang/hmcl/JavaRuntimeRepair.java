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
import org.jackhuang.hmcl.util.platform.Platform;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Repairs the Java environment FSL itself needs to start.
///
/// FSL is expected to start on any computer, therefore a missing JavaFX, a JavaFX build that does not
/// match the running Java, or a Java runtime that lacks modules FSL relies on must never end in a dialog
/// asking the user to install something manually. Instead this class tries, in order:
///
/// 1. repairing JavaFX in place (drop the cached JavaFX jars and download them again),
/// 2. restarting FSL with another Java runtime already installed on this computer,
/// 3. downloading a complete Java runtime into the launcher directory and restarting FSL with it.
///
/// Only when every step above fails the user is asked whether to try again, and the collected reasons
/// of all failed steps are shown.
public final class JavaRuntimeRepair {

    private JavaRuntimeRepair() {
    }

    /// What could not be satisfied by the current Java runtime.
    public enum Failure {
        /// JavaFX is missing, does not match the running Java, or is incomplete.
        JAVA_FX,
        /// The Java runtime lacks modules FSL needs (JNA, JavaFX patching, network, locales, ...).
        RUNTIME_MODULES
    }

    /// Modules FSL itself needs while running: Swing/JavaFX support, JNA, crypto, network and locales.
    private static final List<String> REQUIRED_MODULES = List.of(
            "java.base",
            "java.desktop",
            "java.logging",
            "java.management",
            "java.naming",
            "java.net.http",
            "java.scripting",
            "java.xml",
            "jdk.charsets",
            "jdk.crypto.ec",
            "jdk.localedata",
            "jdk.unsupported",
            "jdk.zipfs");

    /// JavaFX versions at or above this one cannot be patched into every Java runtime,
    /// so a runtime below it is preferred when JavaFX has to be downloaded again.
    private static final int MODERN_JAVAFX_FEATURE_VERSION = 23;

    private static final String JAVAFX_DIRECTORY_NAME = "openjfx";

    /// Verifies that the running Java runtime provides every module FSL needs.
    ///
    /// Missing modules are repaired automatically, this method only returns when the runtime is usable
    /// or after the user gave up.
    public static void ensureRuntimeModules(String[] args) {
        List<String> missing = findMissingModules();
        if (missing.isEmpty())
            return;

        String reason = "The current Java runtime is missing required module(s): " + String.join(", ", missing);
        LOG.warning(reason);
        recover(Failure.RUNTIME_MODULES, reason, args);
    }

    /// Whether the running JVM reports every module FSL needs.
    private static List<String> findMissingModules() {
        List<String> missing = new ArrayList<>();
        try {
            ModuleLayer bootLayer = ModuleLayer.boot();
            for (String module : REQUIRED_MODULES) {
                if (bootLayer.findModule(module).isEmpty())
                    missing.add(module);
            }
        } catch (Throwable e) {
            LOG.warning("Failed to inspect the modules of the current Java runtime", e);
        }
        return missing;
    }

    /// Repairs the Java environment, asking the user only when the automatic repair failed.
    ///
    /// Returns normally only when the environment has been repaired inside the running JVM; every
    /// other successful path restarts FSL and therefore never returns.
    public static void recover(Failure failure, String reason, String[] args) {
        LOG.info("Attempting to repair the Java environment automatically: " + reason);

        int budget = Math.max(JavaRuntimeBootstrap.MAX_ATTEMPTS - JavaRuntimeBootstrap.currentAttempts(), 0);
        String details = null;

        while (true) {
            if (budget > 0) {
                budget--;
                details = attemptRepair(failure, reason, args);
                if (details == null)
                    return;
                LOG.warning("Automatic repair of the Java environment failed:\n" + details);
            }

            // Only ask the user after every automatic attempt has failed.
            String message = i18n("fatal.java_runtime.recovery_failed", details == null ? reason : details);
            if (isHeadless() || !askRetry(message)) {
                LOG.error("Unable to repair the Java environment, giving up");
                EntryPoint.exit(1);
            }
            budget = JavaRuntimeBootstrap.MAX_ATTEMPTS;
        }
    }

    /// Tries to repair the environment once and returns the reasons of every failed step,
    /// or `null` when the environment has been repaired inside the running JVM.
    private static String attemptRepair(Failure failure, String reason, String[] args) {
        List<String> details = new ArrayList<>();
        Path currentHome = JavaRuntimeBootstrap.currentJavaHome();
        int currentFeature = JavaRuntimeBootstrap.currentFeatureVersion();
        LOG.info("Repair attempt " + (JavaRuntimeBootstrap.currentAttempts() + 1) + "/"
                + JavaRuntimeBootstrap.MAX_ATTEMPTS + ", failure: " + failure + ", current: " + currentHome);

        // Step 1: repair JavaFX inside this JVM by dropping the cached jars and downloading them again.
        if (failure == Failure.JAVA_FX) {
            Path cache = javaFxCacheDirectory();
            List<Path> removed = purgeJavaFxCache(cache);
            if (!removed.isEmpty()) {
                LOG.info("Removed " + removed.size() + " cached JavaFX file(s) from " + cache);
                details.add("Removed " + removed.size() + " cached JavaFX file(s) from " + cache);
            }

            try {
                // The cache is empty again, refill it in the styled window instead of letting the
                // patcher open the plain HMCL one.
                prepareJavaFx();
                SelfDependencyPatcher.patch(true);
                if (verifyJavaFx()) {
                    LOG.info("JavaFX has been repaired, continuing startup");
                    return null;
                }
                details.add("JavaFX is still incomplete after downloading it again.");
            } catch (CancellationException e) {
                LOG.info("User cancelled downloading JavaFX, exiting");
                EntryPoint.exit(0);
            } catch (Throwable e) {
                LOG.warning("Failed to download JavaFX again", e);
                details.add("Downloading JavaFX failed: " + describeFailure(e));
            }
        }

        // Step 2: restart with another Java runtime installed on this computer.
        int maxFeature = failure == Failure.JAVA_FX && currentFeature >= MODERN_JAVAFX_FEATURE_VERSION
                ? MODERN_JAVAFX_FEATURE_VERSION - 1
                : Integer.MAX_VALUE;
        List<String> requiredModules = failure == Failure.RUNTIME_MODULES ? REQUIRED_MODULES : null;
        JavaRuntimeBootstrap.Candidate alternative = JavaRuntimeBootstrap.findBest(
                JavaRuntimeBootstrap.MINIMUM_FEATURE_VERSION, maxFeature, requiredModules, currentHome);
        if (alternative != null) {
            LOG.info("Restarting FSL with the Java runtime " + alternative.getVersion()
                    + " (" + alternative.getHome() + ")");
            try {
                JavaRuntimeBootstrap.restart(alternative, args);
            } catch (IOException e) {
                LOG.warning("Failed to restart FSL with " + alternative.getHome(), e);
                details.add("Restarting FSL with Java " + alternative.getVersion() + " failed: " + describeFailure(e));
            }
            // restart() exits the process, reaching this line means it did not happen.
            details.add("Restarting FSL with Java " + alternative.getVersion() + " did not start a new process.");
        } else {
            details.add("No other Java runtime on this computer can run FSL.");
        }

        // Step 3: download a complete Java runtime and restart with it.
        int targetFeature = JavaRuntimeBootstrap.PREFERRED_FEATURE_VERSION;
        if (targetFeature < JavaRuntimeBootstrap.MINIMUM_FEATURE_VERSION)
            targetFeature = JavaRuntimeBootstrap.MINIMUM_FEATURE_VERSION;
        if (failure == Failure.JAVA_FX && targetFeature >= MODERN_JAVAFX_FEATURE_VERSION)
            targetFeature = MODERN_JAVAFX_FEATURE_VERSION - 1;

        Path current = currentHome == null ? null : currentHome.toAbsolutePath().normalize();
        try {
            JavaRuntimeBootstrap.Candidate downloaded = downloadRuntime(targetFeature);
            // Downloading the very same runtime cannot fix anything, ask for another major version so
            // that the retry runs on a freshly downloaded, different Java runtime.
            if (current != null && current.equals(downloaded.getHome())) {
                int alternativeFeature = targetFeature == JavaRuntimeBootstrap.MINIMUM_FEATURE_VERSION
                        ? JavaRuntimeBootstrap.PREFERRED_FEATURE_VERSION
                        : JavaRuntimeBootstrap.MINIMUM_FEATURE_VERSION;
                LOG.info("The downloaded Java runtime is the one already in use, downloading Java "
                        + alternativeFeature + " instead");
                downloaded = downloadRuntime(alternativeFeature);
            }

            LOG.info("Restarting FSL with the downloaded Java runtime " + downloaded.getVersion()
                    + " (" + downloaded.getHome() + ")");
            JavaRuntimeBootstrap.restart(downloaded, args);
            details.add("Restarting FSL with the downloaded Java runtime did not start a new process.");
        } catch (CancellationException e) {
            LOG.info("User cancelled downloading Java, exiting");
            EntryPoint.exit(0);
        } catch (Throwable e) {
            LOG.warning("Failed to download a Java runtime", e);
            details.add("Downloading a Java runtime failed: " + describeFailure(e));
        }

        return String.join("\n", details);
    }

    private static JavaRuntimeBootstrap.Candidate downloadRuntime(int featureVersion) throws IOException {
        LOG.info("Downloading Java " + featureVersion + " into " + JavaRuntimeBootstrap.installRoot());
        DownloadProgressWindow dialog = DownloadProgressWindow.create(i18n("fatal.java_runtime.downloading"));
        try {
            JavaRuntimeBootstrap.Candidate candidate = JavaRuntimeBootstrap.download(featureVersion, dialog);
            LOG.info("Java " + featureVersion + " has been downloaded to " + candidate.getHome());

            // FSL needs the JavaFX modules as well, and they live in a cache of their own. Fetching
            // them here, inside the very same window, is what keeps the first start down to one
            // download window: otherwise FSL restarts, patches JavaFX and shows the old HMCL window
            // immediately after this one, downloading a second time.
            downloadJavaFx(dialog, featureVersion);

            return candidate;
        } finally {
            if (dialog != null)
                dialog.dispose();
        }
    }

    /// Fetches the JavaFX modules before the startup code patches them in.
    ///
    /// The patcher is able to download on its own, but then it opens the plain HMCL progress window.
    /// Preparing the cache beforehand keeps every download inside the same styled window.
    public static void prepareJavaFx() {
        int featureVersion = JavaRuntimeBootstrap.currentFeatureVersion();
        try {
            if (!SelfDependencyPatcher.isMissingDependencies(featureVersion))
                return;
        } catch (Throwable e) {
            LOG.warning("Unable to inspect the JavaFX cache", e);
            return;
        }

        LOG.info("Downloading the JavaFX modules for Java " + featureVersion);
        DownloadProgressWindow dialog = DownloadProgressWindow.create(i18n("download.javafx"));
        try {
            int downloaded = SelfDependencyPatcher.downloadMissingDependencies(featureVersion, dialog);
            LOG.info("Downloaded " + downloaded + " JavaFX module(s)");
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to download the JavaFX modules", e);
        } finally {
            if (dialog != null)
                dialog.dispose();
        }
    }

    /// Downloads the JavaFX modules FSL needs for [featureVersion] into the shared cache.
    ///
    /// Progress is reported into the same window the runtime came from. A failure is only logged:
    /// the regular startup path patches JavaFX again and can still recover on its own.
    private static void downloadJavaFx(DownloadProgressWindow dialog, int featureVersion) {
        try {
            if (!SelfDependencyPatcher.isMissingDependencies(featureVersion)) {
                LOG.info("JavaFX modules for Java " + featureVersion + " are already cached");
                return;
            }

            int downloaded = SelfDependencyPatcher.downloadMissingDependencies(featureVersion, dialog);
            LOG.info("Downloaded " + downloaded + " JavaFX module(s) for Java " + featureVersion);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to download the JavaFX modules", e);
        }
    }

    /// Cached JavaFX jars of the current platform.
    private static Path javaFxCacheDirectory() {
        return Metadata.DEPENDENCIES_DIRECTORY
                .resolve(Platform.CURRENT_PLATFORM.toString())
                .resolve(JAVAFX_DIRECTORY_NAME);
    }

    /// Removes the cached JavaFX jars so that they can be downloaded again for the running Java.
    ///
    /// A JavaFX build downloaded for another Java version, or a partially downloaded one, is the most
    /// common reason for `fatal.javafx.missing` and `fatal.javafx.incomplete`.
    private static List<Path> purgeJavaFxCache(Path directory) {
        List<Path> removed = new ArrayList<>();
        if (!Files.isDirectory(directory))
            return removed;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
            for (Path jar : stream) {
                try {
                    Files.delete(jar);
                    removed.add(jar);
                } catch (IOException e) {
                    LOG.warning("Failed to delete the broken JavaFX file " + jar, e);
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to list the JavaFX cache directory " + directory, e);
        }
        return removed;
    }

    /// Checks whether the JavaFX classes FSL needs are available in this JVM.
    private static boolean verifyJavaFx() {
        try {
            Class.forName("javafx.beans.binding.Binding"); // javafx.base
            Class.forName("javafx.stage.Stage");           // javafx.graphics
            Class.forName("javafx.scene.control.Skin");    // javafx.controls
            return true;
        } catch (Throwable e) {
            LOG.warning("JavaFX is still incomplete after the repair", e);
            return false;
        }
    }

    /// Asks the user whether to try the automatic repair again.
    private static boolean askRetry(String message) {
        try {
            Object[] options = {i18n("fatal.java_runtime.retry"), i18n("fatal.java_runtime.cancel")};
            int result = JOptionPane.showOptionDialog(null, message, i18n("message.warning"),
                    JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE, null, options, options[0]);
            return result == JOptionPane.YES_OPTION;
        } catch (Throwable e) {
            LOG.warning("Failed to show the repair dialog", e);
            return false;
        }
    }

    private static boolean isHeadless() {
        try {
            return GraphicsEnvironment.isHeadless();
        } catch (Throwable e) {
            return true;
        }
    }

    /// Renders a failure and its causes as a single readable line.
    public static String describeFailure(Throwable e) {
        StringBuilder builder = new StringBuilder();
        Throwable current = e;
        for (int depth = 0; current != null && depth < 4; depth++) {
            if (builder.length() > 0)
                builder.append(" <- ");
            builder.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.isEmpty())
                builder.append(": ").append(message);
            current = current.getCause() == current ? null : current.getCause();
        }
        return builder.toString();
    }

}
