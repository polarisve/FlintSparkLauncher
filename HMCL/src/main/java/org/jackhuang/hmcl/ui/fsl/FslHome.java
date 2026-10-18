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

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.animation.AnimationUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Building blocks of the FSL home page: the hero text block and launch button effects.
@NotNullByDefault
public final class FslHome {

    private FslHome() {
    }

    /// Creates the hero block shown at the bottom-left of the home page.
    ///
    /// It shows the launcher tag, the selected instance as a large title, and a hint line,
    /// and updates (with a short fade) whenever the selected instance changes.
    ///
    /// @param currentGame the currently selected game instance, or `null` when there is none
    /// @return the hero node, already aligned for a [StackPane]
    public static VBox createHero(ObservableValue<@Nullable HMCLGameInstance> currentGame) {
        Label tag = new Label("FLINT SPARK LAUNCHER");
        tag.getStyleClass().add("fsl-hero-tag");

        Label title = new Label();
        title.getStyleClass().add("fsl-hero-title");

        Label subtitle = new Label();
        subtitle.getStyleClass().add("fsl-hero-subtitle");

        VBox hero = new VBox(4, tag, title, subtitle);
        hero.getStyleClass().add("fsl-hero");
        hero.setPickOnBounds(false);
        hero.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(hero, Pos.BOTTOM_LEFT);

        FXUtils.onChangeAndOperate(currentGame, game -> {
            if (game == null) {
                title.setText(i18n("fsl.home.no_instance"));
                subtitle.setText(i18n("fsl.home.no_instance.subtitle"));
            } else {
                title.setText(game.getId().toString());
                subtitle.setText(i18n("fsl.home.ready"));
            }
            if (AnimationUtils.isAnimationEnabled() && hero.getScene() != null) {
                FadeTransition fade = new FadeTransition(Duration.millis(260), title);
                fade.setFromValue(0.2);
                fade.setToValue(1);
                fade.play();
            }
        });

        playEntranceOnce(hero);
        return hero;
    }

    /// Adds the breathing glow and hover zoom to the launch button group.
    ///
    /// Does nothing when launcher animations are disabled. The glow only animates while the
    /// node is in a scene, so it stops when the home page is not displayed.
    ///
    /// @param launchPane the container holding the launch and instance-menu buttons
    public static void installLaunchEffects(Region launchPane) {
        if (!AnimationUtils.isAnimationEnabled()) {
            return;
        }

        DropShadow glow = new DropShadow(BlurType.GAUSSIAN, Color.rgb(110, 95, 255, 0.60), 16, 0.12, 0, 0);
        launchPane.setEffect(glow);

        Timeline breathe = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(glow.radiusProperty(), 14, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.seconds(1.6), new KeyValue(glow.radiusProperty(), 32, Interpolator.EASE_BOTH)));
        breathe.setAutoReverse(true);
        breathe.setCycleCount(Animation.INDEFINITE);
        launchPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                breathe.play();
            } else {
                breathe.stop();
            }
        });
        if (launchPane.getScene() != null) {
            breathe.play();
        }

        ScaleTransition zoom = new ScaleTransition(Duration.millis(160), launchPane);
        zoom.setInterpolator(Interpolator.EASE_OUT);
        launchPane.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> {
            zoom.stop();
            zoom.setToX(1.04);
            zoom.setToY(1.04);
            zoom.playFromStart();
        });
        launchPane.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            zoom.stop();
            zoom.setToX(1);
            zoom.setToY(1);
            zoom.playFromStart();
        });
    }

    /// Slides and fades `node` in from the left the first time it is shown.
    ///
    /// @param node the node to animate
    private static void playEntranceOnce(Node node) {
        if (!AnimationUtils.isAnimationEnabled()) {
            return;
        }
        node.setOpacity(0);
        node.sceneProperty().addListener(new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends javafx.scene.Scene> obs,
                                javafx.scene.@Nullable Scene oldScene, javafx.scene.@Nullable Scene newScene) {
                if (newScene == null) {
                    return;
                }
                obs.removeListener(this);

                FadeTransition fade = new FadeTransition(Duration.millis(520), node);
                fade.setFromValue(0);
                fade.setToValue(1);

                TranslateTransition slide = new TranslateTransition(Duration.millis(520), node);
                slide.setFromX(-36);
                slide.setToX(0);
                slide.setInterpolator(Interpolator.EASE_OUT);

                ParallelTransition entrance = new ParallelTransition(fade, slide);
                entrance.setDelay(Duration.millis(120));
                entrance.play();
            }
        });
    }
}
