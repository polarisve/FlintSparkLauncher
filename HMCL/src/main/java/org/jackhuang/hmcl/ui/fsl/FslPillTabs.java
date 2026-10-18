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
package org.jackhuang.hmcl.ui.fsl;

import com.jfoenix.controls.JFXButton;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.AnimationUtils;
import org.jackhuang.hmcl.ui.construct.TabControl;
import org.jackhuang.hmcl.ui.construct.TabHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/// FSL horizontal pill tab bar with a sliding highlight.
///
/// It drives an existing [TabHeader]: clicking a pill selects that tab, and selection changes made
/// elsewhere (for example from code) move the highlight too. The highlight slides between pills
/// with a short animation when launcher animations are enabled.
@NotNullByDefault
public final class FslPillTabs extends StackPane {

    /// Pseudo class set on the pill of the selected tab.
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    /// Duration of the highlight slide.
    private static final Duration SLIDE_DURATION = Duration.millis(260);

    /// The tab header whose selection this bar mirrors.
    private final TabHeader header;

    /// Row holding the pills and separators.
    private final HBox pills = new HBox(2);

    /// Highlight drawn behind the selected pill.
    private final Region indicator = new Region();

    /// Pill buttons keyed by the tab they select, in insertion order.
    private final Map<TabControl.Tab<?>, JFXButton> buttons = new LinkedHashMap<>();

    /// Running highlight animation, or `null` when idle.
    private @Nullable Timeline slide;

    /// Creates a pill bar for `header`. Add tabs with [#add] afterwards.
    ///
    /// @param header the tab header that owns the tabs and their content
    public FslPillTabs(TabHeader header) {
        this.header = header;
        getStyleClass().add("fsl-pill-tabs");
        setMaxWidth(Region.USE_PREF_SIZE);

        indicator.getStyleClass().add("fsl-pill-indicator");
        indicator.prefHeightProperty().bind(pills.heightProperty());
        Pane indicatorLayer = new Pane(indicator);
        indicatorLayer.setMouseTransparent(true);

        getChildren().setAll(indicatorLayer, pills);

        header.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            updatePseudoClasses();
            moveIndicator(true);
        });
    }

    /// Adds a pill for `tab`.
    ///
    /// @param tab   the tab selected by this pill
    /// @param title the pill label
    /// @param icon  the pill icon
    /// @return this bar, for chaining
    public FslPillTabs add(TabControl.Tab<?> tab, String title, SVG icon) {
        JFXButton button = new JFXButton(title);
        button.getStyleClass().add("fsl-pill");
        button.setGraphic(icon.createIcon(18));
        button.setGraphicTextGap(8);
        button.setOnAction(e -> header.select(tab));
        // Keep the highlight glued to the pill when the layout changes (first show, window resize)
        button.boundsInParentProperty().addListener((obs, oldBounds, newBounds) -> {
            if (slide == null && header.getSelectionModel().getSelectedItem() == tab) {
                moveIndicator(false);
            }
        });

        buttons.put(tab, button);
        pills.getChildren().add(button);
        updatePseudoClasses();
        return this;
    }

    /// Adds a thin vertical divider between pill groups.
    ///
    /// @return this bar, for chaining
    public FslPillTabs addSeparator() {
        Region separator = new Region();
        separator.getStyleClass().add("fsl-pill-separator");
        HBox.setMargin(separator, new Insets(9, 6, 9, 6));
        pills.getChildren().add(separator);
        return this;
    }

    /// Marks the pill of the selected tab with the `:selected` pseudo class.
    private void updatePseudoClasses() {
        TabControl.Tab<?> selected = header.getSelectionModel().getSelectedItem();
        buttons.forEach((tab, button) -> button.pseudoClassStateChanged(SELECTED, tab == selected));
    }

    /// Moves the highlight under the selected pill.
    ///
    /// @param animate `true` to slide (if animations are enabled), `false` to jump
    private void moveIndicator(boolean animate) {
        @Nullable JFXButton target = buttons.get(header.getSelectionModel().getSelectedItem());
        if (target == null) {
            indicator.setVisible(false);
            return;
        }
        indicator.setVisible(true);
        Bounds bounds = target.getBoundsInParent();

        if (slide != null) {
            slide.stop();
            slide = null;
        }
        if (!animate || !AnimationUtils.isAnimationEnabled() || indicator.getPrefWidth() <= 0) {
            indicator.setTranslateX(bounds.getMinX());
            indicator.setPrefWidth(bounds.getWidth());
            return;
        }

        Timeline timeline = new Timeline(new KeyFrame(SLIDE_DURATION,
                new KeyValue(indicator.translateXProperty(), bounds.getMinX(), Interpolator.EASE_BOTH),
                new KeyValue(indicator.prefWidthProperty(), bounds.getWidth(), Interpolator.EASE_BOTH)));
        timeline.setOnFinished(e -> slide = null);
        slide = timeline;
        timeline.play();
    }
}
