/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXRadioButton;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.JFXHyperlink;
import org.jackhuang.hmcl.upgrade.RemoteVersion;
import org.jackhuang.hmcl.util.StringUtils;

import java.util.List;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public final class UpgradeDialog extends JFXDialogLayout {

    /// FSL: the two sites every release is published on; the player picks one before updating
    private static final List<String> SOURCES = List.of("Gitee", "GitHub");

    private final RemoteVersion remoteVersion;
    private final Consumer<RemoteVersion> updateAction;
    private Runnable escAction = () -> { };

    public UpgradeDialog(RemoteVersion remoteVersion, Consumer<RemoteVersion> updateAction) {
        this.remoteVersion = remoteVersion;
        this.updateAction = updateAction;
        maxWidthProperty().bind(Controllers.getDecorator().contentWidthProperty().multiply(0.7));
        maxHeightProperty().bind(Controllers.getDecorator().contentHeightProperty().multiply(0.7));
        onEscPressed(this, () -> escAction.run());
        showChangelogPage();
    }

    /// First page: update source, release notes, and "查看" which opens the source chooser.
    private void showChangelogPage() {
        Label heading = new Label(i18n("update.changelog"));
        heading.setPadding(new Insets(0, 0, 8, 0));
        setHeading(heading);

        // FSL: the release notes come with the update JSON; the link opens the release page
        String url = StringUtils.isNotBlank(remoteVersion.page()) ? remoteVersion.page() : Metadata.PUBLISH_URL;
        String changelog = remoteVersion.changelog();

        Label notes = new Label(StringUtils.isBlank(changelog) ? i18n("update.changelog.empty") : changelog.strip());
        notes.setWrapText(true);
        ScrollPane scrollPane = new ScrollPane(notes);
        scrollPane.setFitToWidth(true);
        FXUtils.smoothScrolling(scrollPane);

        // FSL: the one site (Gitee or GitHub) this update comes from
        Label source = new Label(i18n("update.source", remoteVersion.source()));
        source.setWrapText(true);
        VBox body = new VBox(8, source, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        setBody(body);

        JFXHyperlink openInBrowser = new JFXHyperlink(i18n("web.view_in_browser"));
        openInBrowser.setExternalLink(url);

        JFXButton viewButton = new JFXButton(i18n("update.view"));
        viewButton.getStyleClass().add("dialog-accept");
        viewButton.setOnAction(e -> showSourcePage());

        JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));

        setActions(openInBrowser, viewButton, cancelButton);
        escAction = cancelButton::fire;
    }

    /// Second page: choose Gitee or GitHub, then "确认" starts the update from that site.
    private void showSourcePage() {
        Label heading = new Label(i18n("update.source.choose"));
        heading.setPadding(new Insets(0, 0, 8, 0));
        setHeading(heading);

        List<String> available = remoteVersion.sources();
        ToggleGroup group = new ToggleGroup();
        VBox body = new VBox(12);
        for (String name : SOURCES) {
            JFXRadioButton radio = new JFXRadioButton(available.contains(name)
                    ? name : name + " (" + i18n("update.source.unavailable") + ")");
            radio.setUserData(name);
            radio.setToggleGroup(group);
            radio.setDisable(!available.contains(name));
            if (name.equals(remoteVersion.source())) radio.setSelected(true);
            body.getChildren().add(radio);
        }
        if (group.getSelectedToggle() == null) {
            group.getToggles().stream().filter(t -> !((JFXRadioButton) t).isDisable())
                    .findFirst().ifPresent(t -> t.setSelected(true));
        }
        Label hint = new Label(i18n("update.source.hint"));
        hint.setWrapText(true);
        body.getChildren().add(hint);
        setBody(body);

        JFXButton confirmButton = new JFXButton(i18n("button.ok"));
        confirmButton.getStyleClass().add("dialog-accept");
        confirmButton.disableProperty().bind(group.selectedToggleProperty().isNull());
        confirmButton.setOnAction(e -> {
            String chosen = (String) group.getSelectedToggle().getUserData();
            updateAction.accept(remoteVersion.withSource(chosen));
        });

        JFXButton backButton = new JFXButton(i18n("update.source.back"));
        backButton.getStyleClass().add("dialog-cancel");
        backButton.setOnAction(e -> showChangelogPage());

        setActions(backButton, confirmButton);
        escAction = backButton::fire;
    }
}
