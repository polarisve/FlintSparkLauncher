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

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/// FSL left navigation rail: a logo, a column of icon+label items, and items pinned to the bottom.
///
/// The selected item gets a highlight and a small bar on its left edge that slides between items.
/// Items are keyboard accessible (Tab to focus, Enter/Space to select).
///
/// @param <K> the key type identifying each destination
@NotNullByDefault
public final class FslRail<K> extends StackPane {

    /// Pseudo class set on the selected item.
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    /// Height of the sliding indicator bar.
    private static final double INDICATOR_HEIGHT = 16;

    /// Column holding the logo, the top items, a spacer and the bottom items.
    private final VBox column = new VBox();

    /// Spacer separating the top items from the bottom-pinned items.
    private final Region spacer = new Region();

    /// Overlay that hosts the indicator, laid out by hand.
    private final Pane overlay = new Pane();

    /// The sliding bar marking the selected item.
    private final Region indicator = new Region();

    /// Item nodes by key, in insertion order.
    private final Map<K, VBox> items = new LinkedHashMap<>();

    /// The selected destination, or `null` when nothing is selected.
    private final ObjectProperty<@Nullable K> selected = new SimpleObjectProperty<>(this, "selected");

    /// Runs the indicator animation.
    private @Nullable Timeline slide;

    /// Called when the user activates an item; when `null`, activating an item just selects it.
    private @Nullable Consumer<K> onActivate;

    /// Creates an empty rail with the FSL logo at the top.
    public FslRail() {
        getStyleClass().add("fsl-rail-root");

        column.getStyleClass().add("fsl-rail");
        StackPane logo = new StackPane(new Label("FSL"));
        logo.getStyleClass().add("fsl-rail-logo");
        VBox.setMargin(logo, new javafx.geometry.Insets(0, 0, 8, 0));
        VBox.setVgrow(spacer, Priority.ALWAYS);
        column.getChildren().addAll(logo, spacer);

        indicator.getStyleClass().add("fsl-rail-indicator");
        indicator.setManaged(false);
        indicator.resize(3, INDICATOR_HEIGHT);
        indicator.setVisible(false);
        overlay.setPickOnBounds(false);
        overlay.setMouseTransparent(true);
        overlay.getChildren().add(indicator);

        getChildren().addAll(column, overlay);

        selected.addListener((o, oldKey, key) -> {
            items.forEach((k, node) -> node.pseudoClassStateChanged(SELECTED, k.equals(key)));
            moveIndicator(true);
        });
        // Keep the indicator aligned when the window is resized
        layoutBoundsProperty().addListener(o -> moveIndicator(false));
    }

    /// Adds a destination above the spacer.
    ///
    /// @param key  the destination key
    /// @param icon the icon
    /// @param text the label under the icon
    public void addItem(K key, SVG icon, String text) {
        column.getChildren().add(column.getChildren().indexOf(spacer), createItem(key, icon, text));
    }

    /// Adds a destination pinned to the bottom of the rail (for example settings).
    ///
    /// @param key  the destination key
    /// @param icon the icon
    /// @param text the label under the icon
    public void addBottomItem(K key, SVG icon, String text) {
        column.getChildren().add(createItem(key, icon, text));
    }

    private VBox createItem(K key, SVG icon, String text) {
        VBox item = new VBox(icon.createIcon(18), new Label(text));
        item.getStyleClass().add("fsl-rail-item");
        item.setFocusTraversable(true);
        item.setAccessibleRole(AccessibleRole.BUTTON);
        item.setAccessibleText(text);
        item.setOnMouseClicked(e -> activate(key));
        item.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                activate(key);
                e.consume();
            }
        });
        FslMotion.installPressFeedback(item);
        items.put(key, item);
        return item;
    }

    /// Sets what happens when the user activates an item.
    ///
    /// The handler is responsible for updating [#selectedProperty()], for example after navigating.
    ///
    /// @param handler called with the activated key
    public void setOnActivate(Consumer<K> handler) {
        this.onActivate = handler;
    }

    private void activate(K key) {
        @Nullable Consumer<K> handler = onActivate;
        if (handler != null) {
            handler.accept(key);
        } else {
            selected.set(key);
        }
    }

    /// Returns the property holding the selected destination.
    public ObjectProperty<@Nullable K> selectedProperty() {
        return selected;
    }

    /// Moves the indicator next to the selected item.
    ///
    /// @param animate whether to slide; `false` jumps straight there
    private void moveIndicator(boolean animate) {
        @Nullable K key = selected.get();
        @Nullable VBox item = key == null ? null : items.get(key);
        if (item == null) {
            indicator.setVisible(false);
            return;
        }
        // Item bounds are only valid after layout; defer until then
        if (item.getHeight() <= 0) {
            javafx.application.Platform.runLater(() -> moveIndicator(false));
            return;
        }

        Bounds bounds = overlay.sceneToLocal(item.localToScene(item.getLayoutBounds()));
        double targetY = bounds.getMinY() + (bounds.getHeight() - INDICATOR_HEIGHT) / 2;
        indicator.setLayoutX(bounds.getMinX() - 6);

        if (slide != null) {
            slide.stop();
        }
        if (!animate || !indicator.isVisible() || !FslMotion.enabled()) {
            indicator.setLayoutY(targetY);
            indicator.setVisible(true);
            return;
        }
        slide = new Timeline(new KeyFrame(FslMotion.NORMAL,
                new KeyValue(indicator.layoutYProperty(), targetY, FslMotion.MOVE)));
        slide.play();
    }
}
