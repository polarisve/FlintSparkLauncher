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
package org.jackhuang.hmcl.ui.main;

import com.jfoenix.controls.JFXButton;
import javafx.beans.InvalidationListener;
import javafx.beans.WeakInvalidationListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.fsl.ds.Fsl;
import org.jackhuang.hmcl.ui.fsl.ds.FslToggle;
import org.jackhuang.hmcl.ui.fsl.ds.FslUpdateCard;
import org.jackhuang.hmcl.ui.construct.*;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.upgrade.*;
import org.jackhuang.hmcl.util.Lang;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jackhuang.hmcl.util.i18n.SupportedLocale;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.IOUtils;
import org.tukaani.xz.XZInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public final class SettingsPage extends ScrollPane {
    @SuppressWarnings("FieldCanBeLocal")
    private final InvalidationListener updateListener;

    public SettingsPage() {
        this.setFitToWidth(true);

        VBox rootPane = new VBox(10);
        rootPane.setPadding(new Insets(10));
        this.setContent(rootPane);
        FXUtils.smoothScrolling(this);

        {
            VBox updateSection = new VBox(10);

            {
                FslUpdateCard updateCard = new FslUpdateCard(Metadata.FULL_TITLE, SVG.UPDATE.createIcon(18));

                Button recheckButton = Fsl.button(i18n("fsl.update.check_again"), SVG.REFRESH, Fsl.ButtonKind.PRIMARY);

                // FSL: 检测到新版本后，按钮切换为「更新」，点击直接开始更新
                Node recheckIcon = SVG.REFRESH.createIcon(15);
                Node upgradeIcon = SVG.UPDATE.createIcon(15);
                Consumer<Boolean> syncUpdateButton = hasUpdate -> {
                    recheckButton.setText(i18n(hasUpdate ? "fsl.update.do_update" : "fsl.update.check_again"));
                    recheckButton.setGraphic(hasUpdate ? upgradeIcon : recheckIcon);
                    recheckButton.setGraphicTextGap(6);
                };

                recheckButton.setOnAction(e -> {
                    RemoteVersion latest = UpdateChecker.getLatestVersion();
                    if (UpdateChecker.isOutdated() && latest != null) {
                        UpdateHandler.updateFrom(latest);
                    } else {
                        UpdateChecker.requestCheckUpdate(
                                UpdateChannel.STABLE, settings().acceptPreviewUpdateProperty().get());
                    }
                });

                Button changelogButton = Fsl.button(i18n("fsl.update.changelog"), Fsl.ButtonKind.GHOST);
                changelogButton.setOnAction(e -> showChangelog());

                updateCard.trailing().getChildren().addAll(changelogButton, recheckButton);

                updateListener = any -> {
                    RemoteVersion latest = UpdateChecker.getLatestVersion();
                    String source = latest != null && latest.source() != null
                            ? RemoteVersion.sourceName(latest.source())
                            : null;

                    boolean hasUpdate = UpdateChecker.isOutdated() && latest != null;

                    if (UpdateChecker.isCheckingUpdate()) {
                        syncUpdateButton.accept(false);
                        recheckButton.setDisable(true);
                        updateCard.setStatus(i18n("fsl.update.checking_network"), FslUpdateCard.Tone.BUSY);
                        return;
                    }

                    syncUpdateButton.accept(hasUpdate);
                    recheckButton.setDisable(false);

                    Throwable t = UpdateChecker.errorProperty().get();
                    if (t != null) {
                        if (t instanceof SelfVerificationException) {
                            updateCard.setStatus(i18n("update.unverified"), FslUpdateCard.Tone.ERROR);
                        } else {
                            updateCard.setStatus(i18n("update.check_failed",
                                    t.getClass().getSimpleName() + ": " + t.getLocalizedMessage()),
                                    FslUpdateCard.Tone.ERROR);
                        }
                    } else if (hasUpdate) {
                        updateCard.setStatus(source == null
                                        ? i18n("update.newest_version", latest.version())
                                        : i18n("fsl.update.new_version", latest.version(), source),
                                FslUpdateCard.Tone.NEW);
                    } else if (latest != null) {
                        updateCard.setStatus(source == null
                                        ? i18n("update.latest")
                                        : i18n("fsl.update.latest_source", source),
                                FslUpdateCard.Tone.LATEST);
                    } else {
                        updateCard.setStatus(i18n("update.latest"), FslUpdateCard.Tone.NORMAL);
                    }
                };

                UpdateChecker.latestVersionProperty().addListener(new WeakInvalidationListener(updateListener));
                UpdateChecker.outdatedProperty().addListener(new WeakInvalidationListener(updateListener));
                UpdateChecker.checkingUpdateProperty().addListener(new WeakInvalidationListener(updateListener));
                UpdateChecker.errorProperty().addListener(new WeakInvalidationListener(updateListener));

                updateListener.invalidated(null);
                updateSection.getChildren().add(updateCard);
            }


            {
                FslUpdateCard autoShowCard = new FslUpdateCard(
                        i18n("update.disable_auto_show_update_dialog"), SVG.INFO.createIcon(18));
                autoShowCard.setStatus(i18n("update.disable_auto_show_update_dialog.subtitle"));

                FslToggle autoShowToggle = new FslToggle(settings().disableAutoShowUpdateDialogProperty().get());
                autoShowToggle.selectedProperty().bindBidirectional(settings().disableAutoShowUpdateDialogProperty());
                autoShowCard.trailing().getChildren().add(autoShowToggle);

                updateSection.getChildren().add(autoShowCard);
            }

            rootPane.getChildren().addAll(ComponentList.createComponentListTitle(i18n("update")), updateSection);
        }

        {
            ComponentList languagePaneList = new ComponentList();

            {
                var chooseLanguagePane = new LineSelectButton<SupportedLocale>();
                chooseLanguagePane.setTitle(i18n("settings.launcher.language"));

                SupportedLocale currentLocale = I18n.getLocale();
                chooseLanguagePane.setNullSafeConverter(locale -> {
                    if (locale.isDefault())
                        return locale.getDisplayName(currentLocale);
                    else if (locale.isSameLanguage(currentLocale))
                        return locale.getDisplayName(locale);
                    else
                        return locale.getDisplayName(currentLocale) + " - " + locale.getDisplayName(locale);
                });
                chooseLanguagePane.setItems(SupportedLocale.getSupportedLocales());
                chooseLanguagePane.valueProperty().bindBidirectional(settings().languageProperty());

                languagePaneList.getContent().add(chooseLanguagePane);

            }

            rootPane.getChildren().addAll(ComponentList.createComponentListTitle(i18n("settings.launcher.language")), languagePaneList);
        }

        {
            ComponentList miscPaneList = new ComponentList();

            {
                LineToggleButton disableAprilFools = new LineToggleButton();
                disableAprilFools.setTitle(i18n("settings.launcher.disable_april_fools"));
                disableAprilFools.setSubtitle(i18n("settings.take_effect_after_restart"));
                disableAprilFools.selectedProperty().bindBidirectional(settings().disableAprilFoolsProperty());
                miscPaneList.getContent().add(disableAprilFools);
            }

            {
                BorderPane debugPane = new BorderPane();

                Label left = new Label(i18n("settings.launcher.debug"));
                BorderPane.setAlignment(left, Pos.CENTER_LEFT);
                debugPane.setLeft(left);

                JFXButton openLogFolderButton = new JFXButton(i18n("settings.launcher.launcher_log.reveal"));
                openLogFolderButton.setOnAction(e -> openLogFolder());
                openLogFolderButton.getStyleClass().add("jfx-button-border");
                if (LOG.getLogFile() == null)
                    openLogFolderButton.setDisable(true);

                SpinnerPane exportLogPane = new SpinnerPane();

                JFXButton logButton = FXUtils.newBorderButton(i18n("settings.launcher.launcher_log.export"));
                exportLogPane.setContent(logButton);
                logButton.setOnAction(e -> {
                    exportLogPane.showSpinner();
                    onExportLogs().whenCompleteAsync((result, exception) -> {
                        exportLogPane.hideSpinner();
                        if (exception == null) {
                            Controllers.dialog(i18n("settings.launcher.launcher_log.export.success", result));
                            FXUtils.showFileInExplorer(result);
                        } else {
                            LOG.warning("Failed to export logs", exception);
                            Controllers.dialog(
                                    i18n("settings.launcher.launcher_log.export.failed") + "\n" + StringUtils.getStackTrace(exception),
                                    null,
                                    MessageType.ERROR
                            );
                        }
                    }, Schedulers.javafx());
                });

                HBox buttonBox = new HBox();
                buttonBox.setSpacing(10);
                buttonBox.getChildren().addAll(openLogFolderButton, exportLogPane);
                BorderPane.setAlignment(buttonBox, Pos.CENTER_RIGHT);
                debugPane.setRight(buttonBox);

                miscPaneList.getContent().add(debugPane);
            }

            rootPane.getChildren().addAll(ComponentList.createComponentListTitle(i18n("settings.launcher.misc")), miscPaneList);
        }
    }

    private void openLogFolder() {
        FXUtils.openFolder(LOG.getLogFile().getParent());
    }

    private void showChangelog() {
        RemoteVersion latest = UpdateChecker.getLatestVersion();
        if (latest != null && latest.changelog() != null && !latest.changelog().isBlank()) {
            Controllers.dialog(latest.changelog().strip(), i18n("update.changelog"), MessageType.INFO);
        } else if (latest != null && latest.page() != null) {
            FXUtils.openLink(latest.page());
        } else {
            FXUtils.openLink(Metadata.PUBLISH_URL);
        }
    }

    private void onUpdate() {
        RemoteVersion target = UpdateChecker.getLatestVersion();
        if (target == null) {
            return;
        }
        UpdateHandler.updateFrom(target);
    }

    private static String getEntryName(Set<String> entryNames, String name) {
        if (entryNames.add(name)) {
            return name;
        }

        for (long i = 1; ; i++) {
            String newName = name + "." + i;
            if (entryNames.add(newName)) {
                return newName;
            }
        }
    }

    /// This method guarantees to close both `input` and the current zip entry.
    ///
    /// If no exception occurs, this method returns `true`;
    /// If an exception occurs while reading from `input`, this method returns `false`;
    /// If an exception occurs while writing to `output`, this method will throw it as is.
    private static boolean exportLogFile(ZipOutputStream output,
                                         Path file, // For logging
                                         String entryName,
                                         InputStream input,
                                         byte[] buffer) throws IOException {
        //noinspection TryFinallyCanBeTryWithResources
        try {
            output.putNextEntry(new ZipEntry(entryName));
            int read;
            while (true) {
                try {
                    read = input.read(buffer);
                    if (read <= 0)
                        return true;
                } catch (Throwable ex) {
                    LOG.warning("Failed to decompress log file " + file, ex);
                    return false;
                }

                output.write(buffer, 0, read);
            }
        } finally {
            try {
                input.close();
            } catch (Throwable ex) {
                LOG.warning("Failed to close log file " + file, ex);
            }
            output.closeEntry();
        }
    }

    private CompletableFuture<Path> onExportLogs() {
        return CompletableFuture.supplyAsync(Lang.wrap(() -> {
            String nameBase = "hmcl-exported-logs-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"));
            List<Path> recentLogFiles = LOG.findRecentLogFiles(5);

            Path outputFile;
            if (recentLogFiles.isEmpty()) {
                outputFile = Metadata.CURRENT_DIRECTORY.resolve(nameBase + ".log");

                LOG.info("Exporting latest logs to " + outputFile);
                try (OutputStream output = Files.newOutputStream(outputFile)) {
                    LOG.exportLogs(output);
                }
            } else {
                outputFile = Metadata.CURRENT_DIRECTORY.resolve(nameBase + ".zip");

                LOG.info("Exporting latest logs to " + outputFile);

                byte[] buffer = new byte[IOUtils.DEFAULT_BUFFER_SIZE];
                try (var os = Files.newOutputStream(outputFile);
                     var zos = new ZipOutputStream(os)) {

                    Set<String> entryNames = new HashSet<>();

                    for (Path path : recentLogFiles) {
                        String fileName = FileUtils.getName(path);
                        String extension = StringUtils.substringAfterLast(fileName, '.');

                        if ("gz".equals(extension) || "xz".equals(extension)) {
                            // If an exception occurs while decompressing the input file, we should
                            // ensure the input file and the current zip entry are closed,
                            // then copy the compressed file content as-is into a new entry in the zip file.

                            InputStream input = null;
                            try {
                                input = Files.newInputStream(path);
                                input = "gz".equals(extension)
                                        ? new GZIPInputStream(input)
                                        : new XZInputStream(input);
                            } catch (Throwable ex) {
                                LOG.warning("Failed to open log file " + path, ex);
                                IOUtils.closeQuietly(input, ex);
                                input = null;
                            }

                            String entryName = getEntryName(entryNames, StringUtils.substringBeforeLast(fileName, "."));
                            if (input != null && exportLogFile(zos, path, entryName, input, buffer))
                                continue;
                        }

                        // Copy the log file content as-is into a new entry in the zip file.
                        // If an exception occurs while decompressing the input file, we should
                        // ensure the input file and the current zip entry are closed.

                        InputStream input;
                        try {
                            input = Files.newInputStream(path);
                        } catch (Throwable ex) {
                            LOG.warning("Failed to open log file " + path, ex);
                            continue;
                        }

                        exportLogFile(zos, path, getEntryName(entryNames, fileName), input, buffer);
                    }

                    zos.putNextEntry(new ZipEntry(getEntryName(entryNames, "hmcl-latest.log")));
                    LOG.exportLogs(zos);
                    zos.closeEntry();
                }
            }

            return outputFile;
        }), Schedulers.io());
    }
}
