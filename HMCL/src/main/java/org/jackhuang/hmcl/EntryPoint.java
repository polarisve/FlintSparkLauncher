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

import org.jackhuang.hmcl.util.FileSaver;
import org.jackhuang.hmcl.util.SelfDependencyPatcher;
import org.jackhuang.hmcl.util.SwingUtils;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.JarUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;

import javax.swing.JOptionPane;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CancellationException;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public final class EntryPoint {

    private EntryPoint() {
    }

    public static void main(String[] args) {
        System.getProperties().putIfAbsent("java.net.useSystemProxies", "true");
        System.getProperties().putIfAbsent("javafx.autoproxy.disable", "true");
        System.getProperties().putIfAbsent("http.agent", "FSL/" + Metadata.VERSION);

        createHMCLDirectories();
        LOG.start(Metadata.HMCL_LOCAL_HOME.resolve("logs"));

        checkWine();

        if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
            System.getProperties().putIfAbsent("apple.awt.application.appearance", "system");
            if (!isInsideMacAppBundle())
                initIcon();
        }

        // FSL must start on any computer: when this Java runtime cannot provide what FSL needs,
        // download and install the missing pieces instead of asking the user to do it manually.
        JavaRuntimeRepair.ensureRuntimeModules(args);

        checkJavaFX(args);
        verifyJavaFX(args);
        addEnableNativeAccess(args);
        enableUnsafeMemoryAccess();

        try {
            Launcher.main(args);
        } catch (LinkageError e) {
            // A Java runtime without jdk.unsupported (JNA), java.desktop (Swing/JavaFX) or
            // jdk.localedata fails with a linkage error. Repair the runtime and try once more.
            if (!isRuntimeModuleFailure(e))
                throw e;

            LOG.error("FSL cannot start with the current Java runtime", e);
            JavaRuntimeRepair.recover(JavaRuntimeRepair.Failure.RUNTIME_MODULES,
                    JavaRuntimeRepair.describeFailure(e), args);
            Launcher.main(args);
        }
    }

    /**
     * Whether a linkage error was caused by a Java runtime that lacks the modules FSL needs.
     */
    private static boolean isRuntimeModuleFailure(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("jna")
                    || message.contains("jdk.unsupported")
                    || message.contains("java.desktop")
                    || message.contains("jdk.crypto.ec")
                    || message.contains("jdk.localedata")
                    || message.contains("native library"))
                return true;
            if (current.getCause() == current)
                break;
        }
        return false;
    }

    public static void exit(int exitCode) {
        FileSaver.shutdown();
        LOG.shutdown();
        System.exit(exitCode);
    }

    private static void createHMCLDirectories() {
        if (!Files.isDirectory(Metadata.HMCL_LOCAL_HOME)) {
            try {
                Files.createDirectories(Metadata.HMCL_LOCAL_HOME);
                if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                    try {
                        Files.setAttribute(Metadata.HMCL_LOCAL_HOME, "dos:hidden", true);
                    } catch (IOException e) {
                        LOG.warning("Failed to set hidden attribute of " + Metadata.HMCL_LOCAL_HOME, e);
                    }
                }
            } catch (IOException e) {
                // Logger has not been started yet, so print directly to System.err
                System.err.println("Failed to create HMCL directory: " + Metadata.HMCL_LOCAL_HOME);
                e.printStackTrace(System.err);
                showErrorAndExit(i18n("fatal.create_hmcl_current_directory_failure", Metadata.HMCL_LOCAL_HOME));
            }
        }

        if (!Files.isDirectory(Metadata.HMCL_USER_HOME)) {
            try {
                Files.createDirectories(Metadata.HMCL_USER_HOME);
            } catch (IOException e) {
                LOG.warning("Failed to create HMCL user home " + Metadata.HMCL_USER_HOME, e);
            }
        }
    }

    private static boolean isInsideMacAppBundle() {
        Path thisJar = JarUtils.thisJarPath();
        if (thisJar == null)
            return false;

        for (Path current = thisJar.getParent();
             current != null && current.getParent() != null;
             current = current.getParent()
        ) {
            if ("Contents".equals(FileUtils.getName(current))
                    && FileUtils.getName(current.getParent()).endsWith(".app")
                    && Files.exists(current.resolve("Info.plist"))
            ) {
                return true;
            }
        }
        return false;
    }

    private static void initIcon() {
        try {
            if (java.awt.Taskbar.isTaskbarSupported()) {
                var image = java.awt.Toolkit.getDefaultToolkit().getImage(EntryPoint.class.getResource("/assets/img/icon-mac.png"));
                java.awt.Taskbar.getTaskbar().setIconImage(image);
            }
        } catch (Throwable e) {
            LOG.warning("Failed to set application icon", e);
        }
    }

    private static void checkJavaFX(String[] args) {
        try {
            SelfDependencyPatcher.patch();
        } catch (SelfDependencyPatcher.PatchException e) {
            LOG.error("Unable to patch JVM", e);
            // JavaFX is missing or does not match the running Java: download and patch it again,
            // restarting FSL with another runtime only when that is impossible.
            JavaRuntimeRepair.recover(JavaRuntimeRepair.Failure.JAVA_FX,
                    "Unable to patch JVM: " + JavaRuntimeRepair.describeFailure(e), args);
        } catch (CancellationException e) {
            LOG.error("User cancels downloading JavaFX", e);
            exit(0);
        }
    }

    /**
     * Check if JavaFX exists but is incomplete
     */
    private static void verifyJavaFX(String[] args) {
        try {
            Class.forName("javafx.beans.binding.Binding"); // javafx.base
            Class.forName("javafx.stage.Stage");           // javafx.graphics
            Class.forName("javafx.scene.control.Skin");    // javafx.controls
        } catch (Exception e) {
            LOG.warning("JavaFX is incomplete or not found", e);
            // Drop the incomplete JavaFX and download a complete one instead of asking the user
            // to replace the Java runtime.
            JavaRuntimeRepair.recover(JavaRuntimeRepair.Failure.JAVA_FX,
                    "JavaFX is incomplete or not found: " + JavaRuntimeRepair.describeFailure(e), args);
        }
    }

    private static void checkWine() {
        if (OperatingSystem.isRunningUnderWine()) {
            LOG.warning("HMCL is running under Wine or its distributions!");
            showWarning(i18n("fatal.wine_warning"));
        }
    }

    private static void addEnableNativeAccess(String[] args) {
        if (JavaRuntime.CURRENT_VERSION > 21) {
            try {
                // javafx.graphics
                Module module = Class.forName("javafx.stage.Stage").getModule();
                if (module.isNamed()) {
                    try {
                        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Module.class, MethodHandles.lookup());
                        MethodHandle implAddEnableNativeAccess = lookup.findVirtual(Module.class,
                                "implAddEnableNativeAccess", MethodType.methodType(Module.class));
                        Module ignored = (Module) implAddEnableNativeAccess.invokeExact(module);
                    } catch (Throwable e) {
                        e.printStackTrace(System.err);
                    }
                }
            } catch (ClassNotFoundException e) {
                LOG.error("Failed to add enable native access for JavaFX", e);
                // JavaFX cannot be loaded at all: it has to be downloaded for this Java runtime.
                JavaRuntimeRepair.recover(JavaRuntimeRepair.Failure.JAVA_FX,
                        "Failed to add enable native access for JavaFX: " + JavaRuntimeRepair.describeFailure(e), args);
            }
        }
    }

    private static void enableUnsafeMemoryAccess() {
        // https://openjdk.org/jeps/498
        if (JavaRuntime.CURRENT_VERSION == 24 || JavaRuntime.CURRENT_VERSION == 25) {
            try {
                Class<?> clazz = Class.forName("sun.misc.Unsafe");
                boolean ignored = (boolean) MethodHandles.privateLookupIn(clazz, MethodHandles.lookup())
                        .findStatic(clazz, "trySetMemoryAccessWarned", MethodType.methodType(boolean.class))
                        .invokeExact();
            } catch (Throwable e) {
                LOG.warning("Failed to enable unsafe memory access", e);
            }
        }
    }

    static void showWarning(String message) {
        SwingUtils.initLookAndFeel();

        int result = JOptionPane.showOptionDialog(null, message, i18n("message.warning"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE, null, null, null);

        if (result == JOptionPane.CANCEL_OPTION || result == JOptionPane.CLOSED_OPTION) {
            exit(1);
        }
    }

    /**
     * Indicates that a fatal error has occurred, and that the application cannot start.
     */
    static void showErrorAndExit(String message) {
        SwingUtils.showErrorDialog(message);
        exit(1);
    }
}
