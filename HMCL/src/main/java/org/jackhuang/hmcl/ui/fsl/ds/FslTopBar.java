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

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.decorator.Decorator;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.BiConsumer;

/// FSL top bar: page title on the left, global search and trailing actions on the right.
///
/// Only the magnifier button hands the query to the search handler. Enter does nothing (it used to
/// reach the home page's launch button and start the game); Esc clears the box.
@NotNullByDefault
public final class FslTopBar extends HBox {

    /// The page title.
    private final Label title = new Label();

    /// The global search box.
    private final TextField search = new TextField();

    /// Slot for trailing actions such as the account chip.
    private final HBox trailing = new HBox(10);

    /// Creates a top bar.
    ///
    /// @param searchPrompt placeholder text for the search box
    /// @param onSearch     called with the trimmed query and the search box (to place a popup under)
    ///                     when the magnifier is pressed
    public FslTopBar(String searchPrompt, BiConsumer<String, Node> onSearch) {
        getStyleClass().add("fsl-topbar");

        title.getStyleClass().add("fsl-page-title");

        search.getStyleClass().add("fsl-search");
        search.setPromptText(searchPrompt);
        search.setAccessibleText(searchPrompt);
        // Clicking still focuses it; this only stops it grabbing focus when the window opens
        search.setFocusTraversable(false);

        // Magnifier inside the right end of the box; the only way to start a search
        JFXButton magnifier = new JFXButton();
        magnifier.getStyleClass().add("fsl-search-button");
        magnifier.setGraphic(SVG.SEARCH.createIcon(14));
        magnifier.setFocusTraversable(false);
        magnifier.setAccessibleText(searchPrompt);
        StackPane box = new StackPane(search, magnifier);
        box.getStyleClass().add("fsl-search-box");
        StackPane.setAlignment(magnifier, Pos.CENTER_RIGHT);

        Runnable submit = () -> {
            String query = search.getText().trim();
            if (!query.isEmpty()) {
                onSearch.accept(query, box);
            } else {
                search.requestFocus();
            }
        };
        magnifier.setOnAction(e -> submit.run());

        // Keep the box's own keys (Backspace, Delete, arrows...) instead of rerouting them to the page
        search.getProperties().put(Decorator.KEEP_KEY_EVENTS, Boolean.TRUE);
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                // Swallow Enter so it never triggers the default (launch) button
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                search.clear();
                e.consume();
            }
        });

        trailing.setAlignment(Pos.CENTER_RIGHT);

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        getChildren().addAll(title, grow, box, trailing);
    }

    /// Sets the page title.
    public void setTitle(String text) {
        title.setText(text);
    }

    /// Replaces the trailing actions.
    public void setTrailing(Node... nodes) {
        trailing.getChildren().setAll(nodes);
    }

    /// Moves keyboard focus to the search box (for the Ctrl+K shortcut).
    public void focusSearch() {
        search.requestFocus();
        search.selectAll();
    }
}
