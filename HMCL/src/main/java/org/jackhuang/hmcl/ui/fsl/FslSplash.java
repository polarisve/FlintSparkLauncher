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

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.jackhuang.hmcl.ui.fsl.ds.FslMotion;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// FSL start-up splash, like PCL: the launcher icon appears alone in the middle of the screen
/// while the main window is being built, then fades away once the main window is shown.
@NotNullByDefault
public final class FslSplash {
    /// Size of the icon on screen, in logical pixels.
    private static final double SIZE = 80;
    /// The icon stays at least this long, so a fast start does not just flash it.
    private static final Duration MIN_SHOWN = Duration.millis(600);

    private static @Nullable Stage stage;
    private static long shownAt;

    private FslSplash() {
    }

    /// Shows the icon in the middle of the primary screen. Call on the FX thread.
    public static void show() {
        try {
            Image image = new Image(FslSplash.class.getResource("/assets/img/icon@8x.png").toExternalForm(), SIZE * 2, SIZE * 2, true, true);
            ImageView icon = new ImageView(image);
            icon.setFitWidth(SIZE);
            icon.setFitHeight(SIZE);
            icon.setSmooth(true);

            StackPane root = new StackPane(icon);
            root.setStyle("-fx-background-color: transparent;");
            Scene scene = new Scene(root, SIZE + 40, SIZE + 40, Color.TRANSPARENT);

            Stage splash = new Stage(StageStyle.TRANSPARENT);
            splash.setScene(scene);
            splash.setAlwaysOnTop(true);
            splash.getIcons().add(image);
            splash.setTitle("Flint Spark Launcher");
            // Exact middle of the screen (centerOnScreen() puts windows at one third of the height)
            Rectangle2D screen = Screen.getPrimary().getBounds();
            splash.setX(screen.getMinX() + (screen.getWidth() - scene.getWidth()) / 2);
            splash.setY(screen.getMinY() + (screen.getHeight() - scene.getHeight()) / 2);
            splash.show();
            stage = splash;
            shownAt = System.nanoTime();

            if (FslMotion.enabled()) {
                // Pop in: grow from 80% while fading in
                icon.setOpacity(0);
                icon.setScaleX(0.8);
                icon.setScaleY(0.8);
                FadeTransition fade = new FadeTransition(Duration.millis(260), icon);
                fade.setToValue(1);
                ScaleTransition scale = new ScaleTransition(Duration.millis(320), icon);
                scale.setToX(1);
                scale.setToY(1);
                scale.setInterpolator(FslMotion.ENTER);
                new ParallelTransition(fade, scale).play();
            }
        } catch (RuntimeException e) {
            // A missing splash must never stop the launcher from starting
            LOG.warning("Failed to show the splash", e);
            stage = null;
        }
    }

    /// Runs `showMain` once the icon has been up for a moment, then fades the icon away.
    /// Call on the FX thread when the main window is ready to be shown.
    ///
    /// @param showMain shows the main window
    public static void hide(Runnable showMain) {
        @Nullable Stage splash = stage;
        if (splash == null) {
            showMain.run();
            return;
        }
        stage = null;

        double shownMillis = (System.nanoTime() - shownAt) / 1_000_000.0;
        PauseTransition wait = new PauseTransition(Duration.millis(Math.max(1, MIN_SHOWN.toMillis() - shownMillis)));
        wait.setOnFinished(e -> {
            showMain.run();
            if (!FslMotion.enabled()) {
                splash.close();
                return;
            }
            FadeTransition fade = new FadeTransition(Duration.millis(220), splash.getScene().getRoot());
            fade.setToValue(0);
            fade.setOnFinished(e2 -> splash.close());
            fade.play();
        });
        wait.play();
    }
}
