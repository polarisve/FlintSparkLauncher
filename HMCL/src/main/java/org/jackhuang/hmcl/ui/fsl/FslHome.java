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
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.fsl.ds.Fsl;
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
        int hour = java.time.LocalTime.now().getHour();
        String greeting = i18n(hour < 6 ? "fsl.home.greet.night" : hour < 12 ? "fsl.home.greet.morning"
                : hour < 18 ? "fsl.home.greet.afternoon" : "fsl.home.greet.evening");
        Label heading = Fsl.heading(greeting);
        Label subtitle = Fsl.muted("");

        Label instanceLabel = Fsl.muted(i18n("fsl.home.current"));
        Label instanceName = Fsl.title("");
        instanceName.setStyle("-fx-font-size: 15px;");
        Label state = Fsl.chip(i18n("fsl.home.ready.chip"));
        HBox nameRow = new HBox(8, instanceName, state);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        Button download = Fsl.button(i18n("fsl.home.action.download"), SVG.DOWNLOAD, Fsl.ButtonKind.SECONDARY);
        download.setOnAction(e -> Controllers.navigate(Controllers.getDownloadPage()));
        Button instances = Fsl.button(i18n("fsl.home.action.instances"), SVG.DEPLOYED_CODE, Fsl.ButtonKind.SECONDARY);
        instances.setOnAction(e -> Controllers.navigate(Controllers.getGameListPage()));
        Button folder = Fsl.button(i18n("fsl.home.action.settings"), SVG.SETTINGS, Fsl.ButtonKind.GHOST);
        folder.setOnAction(e -> {
            @Nullable HMCLGameInstance game = currentGame.getValue();
            if (game != null) {
                org.jackhuang.hmcl.ui.instances.Instances.modifyGameSettings(game);
            } else {
                Controllers.navigate(Controllers.getGameListPage());
            }
        });
        HBox actions = new HBox(8, download, instances, folder);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox card = Fsl.card();
        card.getStyleClass().add("fsl-card-hero");
        card.getChildren().addAll(instanceLabel, nameRow, subtitle, actions);
        card.setMaxWidth(440);

        VBox hero = new VBox(10, heading, card);
        hero.setPickOnBounds(false);
        hero.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(hero, Pos.TOP_LEFT);

        FXUtils.onChangeAndOperate(currentGame, game -> {
            boolean has = game != null;
            instanceName.setText(has ? game.getId().toString() : i18n("fsl.home.no_instance"));
            subtitle.setText(i18n(has ? "fsl.home.ready" : "fsl.home.no_instance.subtitle"));
            state.setVisible(has);
            folder.setDisable(!has);
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
