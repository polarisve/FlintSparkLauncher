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
package org.jackhuang.hmcl.ui.fsl.ds;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Factory for the FSL design-system base controls.
///
/// Controls are plain JavaFX nodes styled by `fsl-ui.css`, so pages can be built and restyled
/// without custom skins. Every button gets press/hover motion from [FslMotion].
@NotNullByDefault
public final class Fsl {

    /// Visual weight of a button.
    public enum ButtonKind {
        /// Solid light purple: the one main action on a screen.
        PRIMARY("fsl-btn-primary"),
        /// Outlined: other actions.
        SECONDARY("fsl-btn-secondary"),
        /// Text only: low-emphasis actions.
        GHOST("fsl-btn-ghost"),
        /// Red outline: destructive actions.
        DANGER("fsl-btn-danger");

        /// CSS class applied for this kind.
        private final String styleClass;

        ButtonKind(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    private Fsl() {
    }

    /// Creates a button.
    ///
    /// @param text the label
    /// @param kind the visual weight
    /// @return the button
    public static Button button(String text, ButtonKind kind) {
        return button(text, null, kind);
    }

    /// Creates a button with an optional leading icon.
    ///
    /// @param text the label
    /// @param icon the icon, or `null` for none
    /// @param kind the visual weight
    /// @return the button
    public static Button button(String text, @Nullable SVG icon, ButtonKind kind) {
        Button button = new Button(text);
        button.getStyleClass().setAll("fsl-btn", kind.styleClass);
        if (icon != null) {
            button.setGraphic(icon.createIcon(15));
            button.setGraphicTextGap(6);
        }
        FslMotion.installPressFeedback(button);
        return button;
    }

    /// Makes a button larger, for the main call to action of a page (such as "Launch").
    ///
    /// @param button the button to enlarge
    /// @return the same button
    public static Button large(Button button) {
        button.getStyleClass().add("fsl-btn-large");
        return button;
    }

    /// Creates an empty card container.
    ///
    /// @return the card
    public static VBox card() {
        VBox card = new VBox();
        card.getStyleClass().add("fsl-card");
        return card;
    }

    /// Creates a card with a bold title.
    ///
    /// @param title the card title
    /// @return the card, with the title as its first child
    public static VBox card(String title) {
        VBox card = card();
        card.getChildren().add(title(title));
        return card;
    }

    /// Creates a bold section title.
    public static Label title(String text) {
        return styled(new Label(text), "fsl-title");
    }

    /// Creates a large page heading.
    public static Label heading(String text) {
        return styled(new Label(text), "fsl-heading");
    }

    /// Creates secondary, lower-contrast text.
    public static Label muted(String text) {
        Label label = styled(new Label(text), "fsl-muted");
        label.setWrapText(true);
        return label;
    }

    /// Creates a small rounded tag, such as a loader name.
    public static Label chip(String text) {
        return styled(new Label(text), "fsl-chip");
    }

    /// Creates a slim rounded progress bar.
    ///
    /// @param progress initial progress from 0 to 1, or a negative value for indeterminate
    public static ProgressBar progress(double progress) {
        ProgressBar bar = new ProgressBar(progress);
        bar.getStyleClass().add("fsl-progress");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(4);
        return bar;
    }

    private static Label styled(Label label, String styleClass) {
        label.getStyleClass().add(styleClass);
        return label;
    }
}
