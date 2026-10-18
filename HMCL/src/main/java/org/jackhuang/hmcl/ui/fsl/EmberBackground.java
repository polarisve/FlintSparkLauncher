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

import java.util.Random;

/// Animated home page backdrop for FSL: a dark gradient with a slowly breathing violet-blue glow
/// and small spark particles drifting upwards.
///
/// The animation only runs while the node is attached to a scene, and falls back to a single
/// static frame when launcher animations are disabled, so it costs nothing on other pages.
@NotNullByDefault
public final class EmberBackground extends Pane {

    /// Number of spark particles kept alive at once.
    private static final int PARTICLE_COUNT = 70;

    /// Canvas the backdrop and particles are painted on; resized with this pane.
    private final Canvas canvas = new Canvas();

    /// Source of randomness for particle spawning.
    private final Random random = new Random();

    /// Horizontal particle positions, relative to the pane width (0..1).
    private final double[] px = new double[PARTICLE_COUNT];
    /// Vertical particle positions, relative to the pane height (0..1, 1 = bottom).
    private final double[] py = new double[PARTICLE_COUNT];
    /// Upward speed of each particle, in pane heights per second.
    private final double[] speed = new double[PARTICLE_COUNT];
    /// Radius of each particle in pixels.
    private final double[] size = new double[PARTICLE_COUNT];
    /// Phase offset used for sideways sway and flicker.
    private final double[] phase = new double[PARTICLE_COUNT];

    /// Drives the per-frame update while the pane is showing.
    private final AnimationTimer timer = new AnimationTimer() {
        /// Timestamp of the previous frame in nanoseconds, or 0 before the first frame.
        private long last;

        @Override
        public void handle(long now) {
            double dt = last == 0 ? 0 : Math.min((now - last) / 1e9, 0.05);
            last = now;
            step(dt, now / 1e9);
        }

        @Override
        public void stop() {
            super.stop();
            last = 0;
        }
    };

    /// Creates the backdrop. It is mouse-transparent so it never steals clicks from the page.
    public EmberBackground() {
        getStyleClass().add("fsl-ember-background");
        setMouseTransparent(true);
        getChildren().add(canvas);
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            spawn(i, true);
        }

        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null && AnimationUtils.isAnimationEnabled()) {
                timer.start();
            } else {
                timer.stop();
            }
        });
    }

    /// Places particle `i` at a fresh random position.
    ///
    /// @param i       particle index
    /// @param anywhere `true` to scatter it over the whole height (initial fill),
    ///                 `false` to start it just below the bottom edge
    private void spawn(int i, boolean anywhere) {
        px[i] = random.nextDouble();
        py[i] = anywhere ? random.nextDouble() : 1.02 + random.nextDouble() * 0.1;
        speed[i] = 0.035 + random.nextDouble() * 0.08;
        size[i] = 1.0 + random.nextDouble() * 2.4;
        phase[i] = random.nextDouble() * Math.PI * 2;
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
            // Paint once immediately so a static frame exists even with animations off
            step(0, System.nanoTime() / 1e9);
        }
    }

    /// Advances particles by `dt` seconds and repaints the whole canvas.
    ///
    /// @param dt   elapsed time since the previous frame, in seconds
    /// @param time absolute time in seconds, used for the glow pulse and flicker
    private void step(double dt, double time) {
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

        // Sparks: drift up, sway sideways, flicker, and fade near the top
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            py[i] -= speed[i] * dt;
            if (py[i] < -0.05) {
                spawn(i, false);
            }
            double x = (px[i] + 0.015 * Math.sin(time * 1.3 + phase[i])) * w;
            double y = py[i] * h;
            double fade = Math.min(1, Math.max(0, py[i] * 1.4));
            double flicker = 0.65 + 0.35 * Math.sin(time * 6 + phase[i] * 3);
            double alpha = fade * flicker;
            double r = size[i];

            g.setFill(Color.rgb(130, 120, 255, 0.20 * alpha));
            g.fillOval(x - r * 3, y - r * 3, r * 6, r * 6);
            g.setFill(Color.rgb(215, 220, 255, 0.85 * alpha));
            g.fillOval(x - r, y - r, r * 2, r * 2);
        }
    }
}
