/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.fsl.ds;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNullByDefault;

/// One row of the launcher's update section, drawn with the FSL palette: an icon on the left, a
/// bold title with a status line under it, and the row actions pinned to the right.
///
/// The arrangement follows the update entry users are used to, while every colour comes from
/// `fsl-ui.css`, so the row follows this launcher's theme instead of borrowing another one.
@NotNullByDefault
public final class FslUpdateCard extends HBox {

    /// Colour of the status line, so the state of the update check is visible at a glance.
    public enum Tone {
        /// Nothing worth highlighting, for example the description of an option.
        NORMAL("fsl-update-status"),
        /// A check is running.
        BUSY("fsl-update-status-busy"),
        /// The launcher is up to date.
        LATEST("fsl-update-status-latest"),
        /// A newer version is available.
        NEW("fsl-update-status-new"),
        /// The check failed.
        ERROR("fsl-update-status-error");

        private final String styleClass;

        Tone(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    private final Label title = new Label();
    private final Label status = new Label();
    private final HBox trailing = new HBox(8);
    private Tone tone = Tone.NORMAL;

    /// Creates a card.
    /// @param title the bold first line
    /// @param icon the icon shown in the rounded tile on the left
    public FslUpdateCard(String title, Node icon) {
        getStyleClass().add("fsl-update-card");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(12);

        StackPane iconTile = new StackPane(icon);
        iconTile.getStyleClass().add("fsl-update-icon");
        iconTile.setMinSize(36, 36);
        iconTile.setPrefSize(36, 36);
        iconTile.setMaxSize(36, 36);

        this.title.getStyleClass().add("fsl-update-title");
        this.title.setText(title);

        status.getStyleClass().add(Tone.NORMAL.styleClass);
        status.setWrapText(true);

        VBox text = new VBox(3, this.title, status);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);

        trailing.setAlignment(Pos.CENTER_RIGHT);
        trailing.setMinWidth(Region.USE_PREF_SIZE);

        getChildren().addAll(iconTile, text, trailing);
    }

    /// Replaces the bold first line, for example when the version changes.
    public void setTitle(String text) {
        title.setText(text);
    }

    /// Replaces the status line, keeping the current colour.
    public void setStatus(String text) {
        status.setText(text);
    }

    /// Replaces the status line and its colour.
    public void setStatus(String text, Tone tone) {
        if (this.tone != tone) {
            status.getStyleClass().remove(this.tone.styleClass);
            status.getStyleClass().add(tone.styleClass);
            this.tone = tone;
        }
        status.setText(text);
    }

    /// The box holding the row actions, filled by the page.
    public HBox trailing() {
        return trailing;
    }

    /// A small rounded button used for the update channel, matching the card colours.
    public static Button channelButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("fsl-update-channel");
        FslMotion.installPressFeedback(button);
        return button;
    }
}
