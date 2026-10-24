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

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.animation.FadeTransition;
import javafx.scene.Node;
import javafx.util.Duration;
import org.jackhuang.hmcl.ui.animation.AnimationUtils;
import org.jetbrains.annotations.NotNullByDefault;

/// Shared motion rules for the FSL design system.
///
/// All animations are short (120–320 ms) and use decelerating curves so the UI feels quick, and
/// every helper falls back to an instant change when the user turned animations off.
@NotNullByDefault
public final class FslMotion {

    /// Duration of hover and press feedback.
    public static final Duration FAST = Duration.millis(120);

    /// Duration of small layout changes, such as the navigation indicator sliding.
    public static final Duration NORMAL = Duration.millis(220);

    /// Duration of page transitions.
    public static final Duration PAGE = Duration.millis(320);

    /// Fast-out, slow-in curve used for things entering the screen.
    public static final Interpolator ENTER = Interpolator.SPLINE(0.05, 0.7, 0.1, 1.0);

    /// Standard curve used for things moving within the screen.
    public static final Interpolator MOVE = Interpolator.SPLINE(0.2, 0.0, 0.0, 1.0);

    private FslMotion() {
    }

    /// Returns whether animations should play.
    public static boolean enabled() {
        return AnimationUtils.isAnimationEnabled();
    }

    /// Adds a subtle scale-down on press to a control.
    ///
    /// There is no zoom on hover: a button left at 102% draws its text stretched and blurry.
    ///
    /// @param node the node to animate
    public static void installPressFeedback(Node node) {
        ScaleTransition scale = new ScaleTransition(FAST, node);
        scale.setInterpolator(MOVE);
        node.setOnMousePressed(e -> scaleTo(scale, 0.97));
        node.setOnMouseReleased(e -> scaleTo(scale, 1.0));
    }

    private static void scaleTo(ScaleTransition scale, double value) {
        if (!enabled() || value == 1.0) {
            // Snap back exactly to 100% so text is drawn on whole pixels again
            scale.stop();
            scale.getNode().setScaleX(value);
            scale.getNode().setScaleY(value);
            return;
        }
        scale.stop();
        scale.setToX(value);
        scale.setToY(value);
        scale.playFromStart();
    }

    /// Plays the entrance animation for a newly shown page: fade in while rising slightly.
    ///
    /// @param page the page node that was just attached
    public static void enterPage(Node page) {
        if (!enabled()) {
            page.setOpacity(1);
            page.setTranslateY(0);
            return;
        }
        page.setOpacity(0);
        page.setTranslateY(14);

        FadeTransition fade = new FadeTransition(PAGE, page);
        fade.setToValue(1);
        fade.setInterpolator(ENTER);

        TranslateTransition rise = new TranslateTransition(PAGE, page);
        rise.setToY(0);
        rise.setInterpolator(ENTER);

        new ParallelTransition(fade, rise).play();
    }

    /// Plays a staggered entrance for a group of nodes, such as cards on a page.
    ///
    /// @param nodes the nodes, animated in order
    public static void stagger(Iterable<? extends Node> nodes) {
        if (!enabled()) {
            return;
        }
        int index = 0;
        for (Node node : nodes) {
            node.setOpacity(0);
            node.setTranslateY(10);
            Duration delay = Duration.millis(40L * index++);
            Timeline timeline = new Timeline(
                    new KeyFrame(delay, new KeyValue(node.opacityProperty(), 0), new KeyValue(node.translateYProperty(), 10)),
                    new KeyFrame(delay.add(NORMAL),
                            new KeyValue(node.opacityProperty(), 1, ENTER),
                            new KeyValue(node.translateYProperty(), 0, ENTER)));
            timeline.play();
        }
    }
}
