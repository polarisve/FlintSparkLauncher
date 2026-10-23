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
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.layout.Priority;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// Building blocks of the FSL home page: side panel cards, right-side content and launch button effects.
@NotNullByDefault
public final class FslHome {

    private FslHome() {
    }

    /// Creates the account card at the top of the home side panel: large avatar, name and login type.
    ///
    /// Clicking it opens the account page.
    public static VBox createAccountCard() {
        Canvas avatar = new Canvas(64, 64);
        Label name = Fsl.title("");
        name.setStyle("-fx-font-size: 15px;");
        Label type = Fsl.muted("");

        VBox card = new VBox(8, avatar, name, type);
        card.getStyleClass().add("fsl-account-card");
        card.setAlignment(Pos.CENTER);
        card.setOnMouseClicked(e -> Controllers.navigate(Controllers.getAccountListPage()));

        FXUtils.onChangeAndOperate(Accounts.selectedAccountProperty(), account -> {
            if (account == null) {
                TexturesLoader.unbindAvatar(avatar);
                TexturesLoader.drawAvatar(avatar, TexturesLoader.getDefaultSkinImage());
                name.setText(i18n("account.missing"));
                type.setText(i18n("fsl.home.account.add"));
            } else {
                TexturesLoader.bindAvatar(avatar, account);
                String profileName = account.getProfileName();
                name.setText(StringUtils.isBlank(profileName) ? account.getProfileID().toString() : profileName);
                type.setText(Accounts.getLocalizedLoginTypeName(Accounts.getAccountFactory(account)));
            }
        });
        return card;
    }

    /// Creates the small "version" line above the launch button; clicking it opens the instance list.
    ///
    /// @param currentGame the currently selected game instance, or `null` when there is none
    /// @return the line node
    public static HBox createInstanceLine(ObservableValue<@Nullable HMCLGameInstance> currentGame) {
        Label caption = Fsl.muted(i18n("fsl.home.version"));
        Label value = new Label();
        value.getStyleClass().add("fsl-text");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox line = new HBox(6, caption, grow, value, SVG.ARROW_DROP_DOWN.createIcon(16));
        line.getStyleClass().add("fsl-instance-line");
        line.setAlignment(Pos.CENTER_LEFT);
        line.setOnMouseClicked(e -> Controllers.navigate(Controllers.getGameListPage()));
        FXUtils.onChangeAndOperate(currentGame, game ->
                value.setText(game == null ? i18n("fsl.home.version.none") : game.getId().toString()));
        return line;
    }

    /// Creates the right side of the home page: the current instance, download shortcuts and a tip.
    ///
    /// @param currentGame the currently selected game instance, or `null` when there is none
    /// @return the content node, already aligned for a [StackPane]
    public static VBox createHero(ObservableValue<@Nullable HMCLGameInstance> currentGame) {
        Label instanceName = Fsl.title("");
        instanceName.setStyle("-fx-font-size: 16px;");
        Label state = Fsl.chip(i18n("fsl.home.ready.chip"));
        HBox nameRow = new HBox(8, instanceName, state);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        Label subtitle = Fsl.muted("");

        Button settings = Fsl.button(i18n("fsl.home.action.settings"), SVG.SETTINGS, Fsl.ButtonKind.SECONDARY);
        settings.setOnAction(e -> {
            @Nullable HMCLGameInstance game = currentGame.getValue();
            if (game != null) {
                Instances.modifyGameSettings(game);
            }
        });
        Button manage = Fsl.button(i18n("fsl.home.action.instances"), SVG.DEPLOYED_CODE, Fsl.ButtonKind.GHOST);
        manage.setOnAction(e -> Controllers.navigate(Controllers.getGameListPage()));
        HBox actions = new HBox(8, settings, manage);

        VBox instanceCard = Fsl.card();
        instanceCard.getStyleClass().add("fsl-card-hero");
        instanceCard.getChildren().addAll(Fsl.muted(i18n("fsl.home.current")), nameRow, subtitle, actions);

        HBox shortcuts = new HBox(10,
                shortcut(SVG.DOWNLOAD, "fsl.home.tile.game", "fsl.home.tile.game.sub",
                        () -> Controllers.getDownloadPage().showGameDownloads()),
                shortcut(SVG.EXTENSION, "fsl.home.tile.mod", "fsl.home.tile.mod.sub",
                        () -> Controllers.getDownloadPage().showModDownloads()),
                shortcut(SVG.FOLDER, "fsl.home.tile.modpack", "fsl.home.tile.modpack.sub",
                        () -> Controllers.getDownloadPage().showModpackDownloads()));

        VBox tip = Fsl.card(i18n("fsl.home.tip.title"));
        tip.getChildren().add(Fsl.muted(i18n("fsl.home.tip")));

        VBox hero = new VBox(12, instanceCard, shortcuts, tip);
        hero.setPadding(new Insets(4));
        hero.setPickOnBounds(false);
        StackPane.setAlignment(hero, Pos.TOP_LEFT);

        FXUtils.onChangeAndOperate(currentGame, game -> {
            boolean has = game != null;
            instanceName.setText(has ? game.getId().toString() : i18n("fsl.home.no_instance"));
            subtitle.setText(i18n(has ? "fsl.home.ready" : "fsl.home.no_instance.subtitle"));
            state.setVisible(has);
            settings.setDisable(!has);
        });

        playEntranceOnce(hero);
        return hero;
    }

    /// Creates a clickable shortcut tile that opens a download section.
    ///
    /// @param icon        the tile icon
    /// @param titleKey    i18n key of the title
    /// @param subtitleKey i18n key of the subtitle
    /// @param selectTab   selects the matching tab on the download page
    /// @return the tile
    private static VBox shortcut(SVG icon, String titleKey, String subtitleKey, Runnable selectTab) {
        VBox tile = Fsl.card();
        tile.getStyleClass().add("fsl-card-clickable");
        tile.getChildren().addAll(icon.createIcon(20), Fsl.title(i18n(titleKey)), Fsl.muted(i18n(subtitleKey)));
        tile.setOnMouseClicked(e -> {
            Controllers.navigate(Controllers.getDownloadPage());
            selectTab.run();
        });
        HBox.setHgrow(tile, Priority.ALWAYS);
        tile.setMaxWidth(Double.MAX_VALUE);
        return tile;
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
