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

import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import org.jetbrains.annotations.NotNullByDefault;

/// FSL on/off switch with a sliding thumb.
///
/// Keyboard accessible: it takes focus and toggles with Space or Enter, and reports itself to
/// screen readers as a toggle button.
@NotNullByDefault
public final class FslToggle extends StackPane {

    /// Pseudo class set while the switch is on.
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    /// Track width in pixels.
    private static final double WIDTH = 44;

    /// Track height in pixels.
    private static final double HEIGHT = 24;

    /// How far the thumb travels between off and on.
    private static final double TRAVEL = WIDTH - HEIGHT;

    /// Whether the switch is on.
    private final BooleanProperty selected = new SimpleBooleanProperty(this, "selected", false);

    /// Creates a switch that starts in the given state.
    ///
    /// @param initial whether the switch starts on
    public FslToggle(boolean initial) {
        getStyleClass().add("fsl-toggle-track");
        setMinSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.TOGGLE_BUTTON);

        Circle thumb = new Circle(8);
        thumb.getStyleClass().add("fsl-toggle-thumb");
        thumb.setTranslateX(-TRAVEL / 2);
        getChildren().add(thumb);

        TranslateTransition slide = new TranslateTransition(FslMotion.FAST, thumb);
        slide.setInterpolator(FslMotion.MOVE);

        selected.addListener((o, wasOn, on) -> {
            pseudoClassStateChanged(SELECTED, on);
            notifyAccessibleAttributeChanged(AccessibleAttribute.SELECTED);
            double target = on ? TRAVEL / 2 : -TRAVEL / 2;
            if (FslMotion.enabled()) {
                slide.stop();
                slide.setToX(target);
                slide.playFromStart();
            } else {
                thumb.setTranslateX(target);
            }
        });

        setOnMouseClicked(e -> toggle());
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) {
                toggle();
                e.consume();
            }
        });

        selected.set(initial);
        if (initial) {
            thumb.setTranslateX(TRAVEL / 2);
            pseudoClassStateChanged(SELECTED, true);
        }
    }

    /// Flips the switch.
    public void toggle() {
        selected.set(!selected.get());
    }

    /// Returns the property holding whether the switch is on.
    public BooleanProperty selectedProperty() {
        return selected;
    }

    /// Reports the on/off state to accessibility tools.
    @Override
    public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        if (attribute == AccessibleAttribute.SELECTED) {
            return selected.get();
        }
        return super.queryAccessibleAttribute(attribute, parameters);
    }
}
