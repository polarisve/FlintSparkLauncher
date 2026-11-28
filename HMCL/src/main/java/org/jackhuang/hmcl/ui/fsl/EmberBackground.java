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

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import org.jackhuang.hmcl.ui.animation.AnimationUtils;
import org.jetbrains.annotations.NotNullByDefault;

/// Animated backdrop for FSL: a dark night-blue gradient with a slowly breathing violet-blue glow.
///
/// The backdrop deliberately contains no starfield or spark particles. The animation only runs
/// while the node is attached to a scene, and falls back to a single static frame when launcher
/// animations are disabled, so it costs nothing on other pages.
@NotNullByDefault
public final class EmberBackground extends Pane {

    /// Canvas the backdrop is painted on; resized with this pane.
    private final Canvas canvas = new Canvas();

    /// Drives the per-frame repaint while the pane is showing.
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            step(now / 1e9);
        }
    };

    /// Creates the backdrop. It is mouse-transparent so it never steals clicks from the page.
    public EmberBackground() {
        getStyleClass().add("fsl-ember-background");
        setMouseTransparent(true);
        getChildren().add(canvas);

        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null && AnimationUtils.isAnimationEnabled()) {
                timer.start();
            } else {
                timer.stop();
            }
        });
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
            // Paint once immediately so a static frame exists even with animations off
            step(System.nanoTime() / 1e9);
        }
    }

    /// Repaints the whole canvas.
    ///
    /// @param time absolute time in seconds, used for the glow pulse
    private void step(double time) {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();

        // Base: deep night-blue vertical gradient
        g.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#0C0B18")),
                new Stop(0.65, Color.web("#121027")),
                new Stop(1, Color.web("#1A1640"))));
        g.fillRect(0, 0, w, h);

        // Breathing violet glow rising from the bottom-right, where the launch button sits
        double pulse = 0.5 + 0.5 * Math.sin(time * 0.8);
        g.setFill(new RadialGradient(0, 0, w * 0.78, h * 1.05, Math.max(w, h) * (0.55 + 0.05 * pulse),
                false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(120, 100, 255, 0.30 + 0.10 * pulse)),
                new Stop(0.45, Color.rgb(60, 90, 230, 0.12)),
                new Stop(1, Color.TRANSPARENT)));
        g.fillRect(0, 0, w, h);

        // Soft cyan counter-light top-left keeps the dark from looking flat
        g.setFill(new RadialGradient(0, 0, w * 0.1, -h * 0.1, Math.max(w, h) * 0.6,
                false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(80, 180, 255, 0.08)),
                new Stop(1, Color.TRANSPARENT)));
        g.fillRect(0, 0, w, h);
    }
}
