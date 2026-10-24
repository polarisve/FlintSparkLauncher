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
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/// FSL horizontal tab bar: icon+label tabs with a purple pill that slides behind the selected tab.
///
/// Tabs are keyboard accessible (Tab to focus, Enter/Space to activate).
///
/// @param <K> the key type identifying each tab
@NotNullByDefault
public final class FslTabs<K> extends StackPane {

    /// Pseudo class set on the selected tab.
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    /// Row holding the tabs.
    private final HBox row = new HBox(2);

    /// Layer behind the row that hosts the sliding pill.
    private final Pane overlay = new Pane();

    /// The pill marking the selected tab.
    private final Region indicator = new Region();

    /// Animated width of the pill; the pill is unmanaged, so it is resized by hand.
    private final DoubleProperty indicatorWidth = new SimpleDoubleProperty(0);

    /// Tab nodes by key, in insertion order.
    private final Map<K, HBox> items = new LinkedHashMap<>();

    /// The selected tab, or `null` when none is selected.
    private final ObjectProperty<@Nullable K> selected = new SimpleObjectProperty<>(this, "selected");

    /// Whether tabs show only their icons (used when the title bar is short of space).
    private final BooleanProperty compact = new SimpleBooleanProperty(this, "compact", false);

    /// Runs the pill animation.
    private @Nullable Timeline slide;

    /// Called when the user activates a tab; when `null`, activating a tab just selects it.
    private @Nullable Consumer<K> onActivate;

    /// Creates an empty tab bar.
    public FslTabs() {
        getStyleClass().add("fsl-tabs");
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setMinWidth(USE_PREF_SIZE);
        row.setAlignment(Pos.CENTER);
        row.setMinWidth(Region.USE_PREF_SIZE);

        indicator.getStyleClass().add("fsl-tab-indicator");
        indicator.setManaged(false);
        indicator.setVisible(false);
        indicatorWidth.addListener((o, a, width) -> indicator.resize(width.doubleValue(), indicator.getHeight()));
        overlay.setMouseTransparent(true);
        overlay.getChildren().add(indicator);

        getChildren().addAll(overlay, row);

        selected.addListener((o, oldKey, key) -> {
            items.forEach((k, node) -> node.pseudoClassStateChanged(SELECTED, k.equals(key)));
            moveIndicator(true);
        });
        row.layoutBoundsProperty().addListener(o -> moveIndicator(false));
        // Labels appear/disappear: jump the pill to the new tab size once layout has run
        compact.addListener(o -> {
            if (slide != null) {
                slide.stop();
            }
            Platform.runLater(() -> jumpIndicator());
        });
    }

    /// Adds a tab.
    ///
    /// @param key  the tab key
    /// @param icon the icon shown before the label
    /// @param text the label
    public void addItem(K key, SVG icon, String text) {
        Label label = new Label(text);
        // Never shrink a tab: squeezed labels turned into "..." when the title bar got crowded
        label.setMinWidth(Region.USE_PREF_SIZE);
        label.visibleProperty().bind(compact.not());
        label.managedProperty().bind(compact.not());
        HBox item = new HBox(5, icon.createIcon(15), label);
        item.setMinWidth(Region.USE_PREF_SIZE);
        // Icon-only tabs still tell their name when pointed at
        Tooltip tooltip = new Tooltip(text);
        compact.addListener((o, was, isCompact) -> {
            if (isCompact) {
                Tooltip.install(item, tooltip);
            } else {
                Tooltip.uninstall(item, tooltip);
            }
        });
        item.getStyleClass().add("fsl-tab");
        // Re-align the highlight whenever this tab moves (title bar re-layout, window resize)
        item.boundsInParentProperty().addListener(o -> {
            if (key.equals(selected.get())) {
                moveIndicator(false);
            }
        });
        item.setAlignment(Pos.CENTER);
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
        items.put(key, item);
        row.getChildren().add(item);
    }

    /// Sets what happens when the user activates a tab.
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

    /// Returns whether tabs show only their icons.
    public boolean isCompact() {
        return compact.get();
    }

    /// Shows only the icons (`true`) or icons with labels (`false`).
    public void setCompact(boolean value) {
        compact.set(value);
    }

    /// Returns the property holding the selected tab.
    public ObjectProperty<@Nullable K> selectedProperty() {
        return selected;
    }

    /// Places the pill behind the selected tab without animating.
    private void jumpIndicator() {
        @Nullable K key = selected.get();
        @Nullable HBox item = key == null ? null : items.get(key);
        if (item == null || item.getWidth() <= 0) {
            moveIndicator(false);
            return;
        }
        if (slide != null) {
            slide.stop();
        }
        Bounds bounds = overlay.sceneToLocal(item.localToScene(item.getLayoutBounds()));
        indicator.setLayoutX(bounds.getMinX());
        indicator.setLayoutY(bounds.getMinY());
        indicatorWidth.set(bounds.getWidth());
        indicator.resize(bounds.getWidth(), bounds.getHeight());
        indicator.setVisible(true);
    }

    /// Moves the pill behind the selected tab.
    ///
    /// @param animate whether to slide; `false` jumps straight there
    private void moveIndicator(boolean animate) {
        @Nullable K key = selected.get();
        @Nullable HBox item = key == null ? null : items.get(key);
        if (item == null) {
            indicator.setVisible(false);
            return;
        }
        // Tab bounds are only valid after layout; defer until then
        if (item.getWidth() <= 0) {
            Platform.runLater(() -> moveIndicator(false));
            return;
        }

        Bounds bounds = overlay.sceneToLocal(item.localToScene(item.getLayoutBounds()));
        indicator.setLayoutY(bounds.getMinY());
        indicator.resize(indicator.getWidth(), bounds.getHeight());

        if (slide != null) {
            if (!animate && slide.getStatus() == javafx.animation.Animation.Status.RUNNING) {
                // A layout change during the slide: restart it toward the new position
                animate = true;
            }
            slide.stop();
        }
        if (!animate || !indicator.isVisible() || !FslMotion.enabled()) {
            indicator.setLayoutX(bounds.getMinX());
            indicatorWidth.set(bounds.getWidth());
            indicator.resize(bounds.getWidth(), bounds.getHeight());
            indicator.setVisible(true);
            return;
        }
        slide = new Timeline(new KeyFrame(FslMotion.NORMAL,
                new KeyValue(indicator.layoutXProperty(), bounds.getMinX(), FslMotion.MOVE),
                new KeyValue(indicatorWidth, bounds.getWidth(), FslMotion.MOVE)));
        slide.play();
    }
}
