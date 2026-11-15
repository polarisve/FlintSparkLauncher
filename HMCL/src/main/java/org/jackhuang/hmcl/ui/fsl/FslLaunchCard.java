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

import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.util.Duration;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.task.FetchTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.fsl.ds.Fsl;
import org.jackhuang.hmcl.ui.fsl.ds.FslMotion;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// FSL: like PCL, launching a game shows a launch card in the middle of the left column
/// (pickaxe animation, instance name, progress bar, current step, login method, download speed, cancel).
/// The account card stays at the top, the launch button disappears.
@NotNullByDefault
public final class FslLaunchCard {

    private static final Set<String> DEPENDENCY_STAGES =
            Set.of("hmcl.install.assets", "hmcl.install.libraries", "hmcl.modpack.download");

    private static final BooleanProperty LAUNCHING = new SimpleBooleanProperty(false);
    private static final StringProperty INSTANCE = new SimpleStringProperty("");
    private static final StringProperty STEP = new SimpleStringProperty("");
    private static final StringProperty LOGIN_METHOD = new SimpleStringProperty("");
    private static final DoubleProperty PROGRESS = new SimpleDoubleProperty(0);
    private static final LongProperty SPEED = new SimpleLongProperty(0);
    private static final BooleanProperty FINISHED = new SimpleBooleanProperty(false);

    private static @Nullable TaskExecutor current;
    private static int total;
    private static int done;

    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private static final Consumer<FetchTask.SpeedEvent> SPEED_HANDLER = FetchTask.SPEED_EVENT.registerWeak(
            event -> Platform.runLater(() -> SPEED.set(LAUNCHING.get() ? event.getSpeed() : 0)));

    private FslLaunchCard() {
    }

    /// Shows the launch card for `executor` (not started yet or just started).
    ///
    /// @param executor the launch executor
    /// @param instance the name of the instance being launched
    public static void track(TaskExecutor executor, String instance) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> track(executor, instance));
            return;
        }
        current = executor;
        total = 0;
        done = 0;
        INSTANCE.set(instance);
        STEP.set(i18n("launch.state.java"));
        LOGIN_METHOD.set(Accounts.getLocalizedLoginTypeName(Accounts.getAccountFactory(Accounts.getSelectedAccount())));
        PROGRESS.set(0);
        SPEED.set(0);
        FINISHED.set(false);
        LAUNCHING.set(true);

        executor.addTaskListener(new TaskListener() {
            @Override
            public void onReady(Task<?> task) {
                Platform.runLater(() -> {
                    if (current == executor) {
                        total++;
                        update();
                    }
                });
            }

            @Override
            public void onRunning(Task<?> task) {
                Platform.runLater(() -> {
                    if (current == executor) {
                        @Nullable String name = stepName(task.getInheritedStage());
                        if (name != null) STEP.set(name);
                    }
                });
            }

            @Override
            public void onFinished(Task<?> task) {
                Platform.runLater(() -> {
                    if (current == executor) {
                        done++;
                        update();
                    }
                });
            }

            @Override
            public void onStop(boolean success, TaskExecutor executor) {
                Platform.runLater(() -> {
                    if (current == executor) {
                        if (executor.isCancelled()) {
                            finish(executor);
                        } else {
                            // Game window is up, show "启动完成" for 1.2s then hide
                            FINISHED.set(true);
                            PROGRESS.set(1);
                            STEP.set(i18n("launch.state.done"));
                            PauseTransition pause = new PauseTransition(Duration.millis(1200));
                            pause.setOnFinished(e -> finish(executor));
                            pause.play();
                        }
                    }
                });
            }
        });
    }

    private static void finish(TaskExecutor executor) {
        if (current == executor) {
            current = null;
            processStopper = null;
            LAUNCHING.set(false);
            SPEED.set(0);
        }
    }

    private static @Nullable Runnable processStopper;

    /// Called once the game process exists: cancelling then also stops the game.
    ///
    /// @param stopper stops the game process
    public static void setProcessStopper(Runnable stopper) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setProcessStopper(stopper));
            return;
        }
        processStopper = stopper;
    }

    /// Cancel button: stops the launch (and the game if it already started), hides the card at once.
    private static void cancelLaunch() {
        @Nullable TaskExecutor executor = current;
        @Nullable Runnable stopper = processStopper;
        processStopper = null;
        if (stopper != null) {
            try {
                stopper.run();
            } catch (RuntimeException ignored) {
            }
        }
        if (executor != null) {
            executor.cancel();
            finish(executor);
        }
    }

    /// The PCL-style mining animation: a 60 x 47 box, pickaxe 35 px high, a 25 x 2 ground line.
    private static Node createMiningAnimation() {
        javafx.scene.paint.Color color = javafx.scene.paint.Color.web("#A99BFF");

        SVGPath pickaxe = new SVGPath();
        pickaxe.setContent("M 963.6 858.2 410.816 305.504 C 508.116 213.304 609.204 196.8 711.104 128.6 837.11367 49.573762 879.34045 50.334062 751.5 49.5 611.3 52 471.8 96.2 353.3 182.4 309.8 155.7 252.1 161.2 214.5 198.9 176.9 236.6 171.3 294.2 198 337.7 111.8 456.3 67.6 595.8 65.1 735.9 63.315254 883.82034 65.077966 837.29308 144.2 695.488 212.4 593.588 228.888 492.4 321.088 395.2 L 873.9 948 c 0.60001 0.59999 1.6 0.6 2.2 0 l 87.5 -87.5 c 0.6 -0.7 0.6 -1.6 0 -2.3 z");
        pickaxe.setFill(javafx.scene.paint.Color.TRANSPARENT);
        pickaxe.setStroke(color);
        javafx.geometry.Bounds raw = pickaxe.getLayoutBounds();
        double scale = 35 / Math.max(raw.getWidth(), raw.getHeight());
        pickaxe.setStrokeWidth(2 / scale);
        // Same as PCL: placed at (10, 6), rotated around (30, 30) of the 35 px pickaxe, resting at 55 degrees
        Rotate rotate = new Rotate(55, 30, 30);
        pickaxe.getTransforms().setAll(
                new javafx.scene.transform.Translate(10, 6),
                rotate,
                new Scale(scale, scale, 0, 0),
                new javafx.scene.transform.Translate(-raw.getMinX(), -raw.getMinY()));
        pickaxe.setManaged(false);

        Rectangle ground = new Rectangle(0, 45, 25, 2);
        ground.setFill(color);
        ground.setManaged(false);

        Polygon left = chip(color, 7, 41, -45);
        Polygon right = chip(color, 14, 41, 45);

        Pane box = new Pane(pickaxe, ground, left, right);
        box.setMinSize(60, 47);
        box.setPrefSize(60, 47);
        box.setMaxSize(60, 47);

        Transition loop = new Transition() {
            {
                setCycleDuration(Duration.millis(1500));
                setCycleCount(INDEFINITE);
                setInterpolator(Interpolator.LINEAR);
            }

            @Override
            protected void interpolate(double frac) {
                double ms = frac * 1500;
                double angle;
                if (ms < 250) {
                    angle = 55;
                } else if (ms < 600) {
                    // lift back a little, then strike down to -20 degrees (ease-in-back)
                    double t = (ms - 250) / 350;
                    double back = 0.8;
                    double k = t * t * ((back + 1) * t - back);
                    angle = 55 + (-20 - 55) * k;
                } else {
                    // spring back up to 55 degrees with a small wobble
                    double t = (ms - 600) / 900;
                    double fluent = 1 - Math.pow(1 - t, 3);
                    double elastic = 1 - Math.pow(2, -9 * t) * Math.cos(t * Math.PI * 3);
                    angle = -20 + 50 * fluent + 25 * elastic;
                }
                rotate.setAngle(angle);

                // chips appear on impact, fly up and outwards, fade out
                if (ms >= 600 && ms < 780) {
                    double t = (ms - 600) / 180;
                    double move = 1 - Math.pow(1 - t, 3);
                    left.setTranslateX(-5 * move);
                    left.setTranslateY(-6 * move);
                    right.setTranslateX(5 * move);
                    right.setTranslateY(-6 * move);
                    double fade = ms < 650 ? 1 : Math.max(0, 1 - (ms - 650) / 100);
                    left.setOpacity(fade);
                    right.setOpacity(fade);
                } else {
                    left.setOpacity(0);
                    right.setOpacity(0);
                }
            }
        };
        LAUNCHING.addListener((o, old, launching) -> {
            if (launching) loop.playFromStart();
            else loop.stop();
        });
        if (LAUNCHING.get()) loop.play();
        return box;
    }

    /// A 3 x 5 chip (downward triangle) used by the mining animation.
    private static Polygon chip(javafx.scene.paint.Color color, double x, double y, double angle) {
        Polygon chip = new Polygon(0, 0, 3, 0, 1.5, 5);
        chip.setFill(color);
        chip.setLayoutX(x);
        chip.setLayoutY(y);
        chip.setRotate(angle);
        chip.setOpacity(0);
        chip.setManaged(false);
        return chip;
    }

    private static void update() {
        // Keep a little room at the end: the last step waits for the game window
        PROGRESS.set(total == 0 ? 0 : Math.min(0.95, (double) done / total));
    }

    private static @Nullable String stepName(@Nullable String stage) {
        if (stage == null) return null;
        if (stage.startsWith("launch.state.")) return i18n(stage);
        if (DEPENDENCY_STAGES.contains(stage)) return i18n("launch.state.dependencies");
        return null;
    }

    /// Wraps the account card and launch button: while a game launches, shows the
    /// PCL-style launch card in the middle, account stays at top, button disappears.
    ///
    /// @param accountCard the account card at the top of the left column
    /// @param launchButton the launch button at the bottom
    /// @return the node to put in the home page
    public static Node wrap(Node accountCard, Node launchButton) {
        // PCL-style loading: the pickaxe strikes the ground line, two chips fly off, it springs back
        Node pickaxePane = createMiningAnimation();

        Label title = new Label();
        title.setText(i18n("fsl.launch.title"));
        title.getStyleClass().add("fsl-launch-title");

        Label name = new Label();
        name.getStyleClass().add("fsl-launch-name");
        name.textProperty().bind(INSTANCE);

        // Progress bar (PCL-style: gradient fill)
        ProgressBar bar = new ProgressBar(0);
        bar.progressProperty().bind(PROGRESS);
        bar.getStyleClass().add("fsl-launch-progress");
        bar.setMaxWidth(Double.MAX_VALUE);
        VBox.setMargin(bar, new javafx.geometry.Insets(12, 0, 12, 0));

        // Info grid: 4 rows x 3 columns (like PCL)
        GridPane info = new GridPane();
        info.setHgap(15);
        info.setVgap(5);
        info.setAlignment(Pos.CENTER);

        Label stepLabel = new Label(i18n("fsl.launch.step"));
        stepLabel.getStyleClass().add("fsl-launch-label");
        stepLabel.setOpacity(0.5);
        Label stepValue = new Label();
        stepValue.getStyleClass().add("fsl-launch-value");
        stepValue.textProperty().bind(STEP);

        Label methodLabel = new Label(i18n("fsl.launch.method"));
        methodLabel.getStyleClass().add("fsl-launch-label");
        methodLabel.setOpacity(0.5);
        Label methodValue = new Label();
        methodValue.getStyleClass().add("fsl-launch-value");
        methodValue.textProperty().bind(LOGIN_METHOD);

        Label progressLabel = new Label(i18n("fsl.launch.progress"));
        progressLabel.getStyleClass().add("fsl-launch-label");
        progressLabel.setOpacity(0.5);
        Label progressValue = new Label();
        progressValue.getStyleClass().add("fsl-launch-value");
        progressValue.textProperty().bind(PROGRESS.multiply(100).asString("%.2f%%"));

        Label speedLabel = new Label(i18n("fsl.task.speed"));
        speedLabel.getStyleClass().add("fsl-launch-label");
        speedLabel.setOpacity(0.5);
        Label speedValue = new Label();
        speedValue.getStyleClass().add("fsl-launch-value");
        SPEED.addListener((o, a, b) -> speedValue.setText(I18n.formatSpeed(b.longValue())));
        speedValue.setText(I18n.formatSpeed(0));

        info.add(stepLabel, 0, 0);
        info.add(stepValue, 1, 0);
        info.add(methodLabel, 0, 1);
        info.add(methodValue, 1, 1);
        info.add(progressLabel, 0, 2);
        info.add(progressValue, 1, 2);
        info.add(speedLabel, 0, 3);
        info.add(speedValue, 1, 3);

        GridPane.setHalignment(stepLabel, javafx.geometry.HPos.RIGHT);
        GridPane.setHalignment(methodLabel, javafx.geometry.HPos.RIGHT);
        GridPane.setHalignment(progressLabel, javafx.geometry.HPos.RIGHT);
        GridPane.setHalignment(speedLabel, javafx.geometry.HPos.RIGHT);

        // Speed row: only show when downloading
        speedLabel.visibleProperty().bind(SPEED.greaterThan(0));
        speedValue.visibleProperty().bind(SPEED.greaterThan(0));
        speedLabel.managedProperty().bind(speedLabel.visibleProperty());
        speedValue.managedProperty().bind(speedValue.visibleProperty());

        Button cancel = Fsl.button(i18n("button.cancel"), SVG.CLOSE, Fsl.ButtonKind.SECONDARY);
        cancel.setMaxWidth(Double.MAX_VALUE);
        cancel.setOnAction(e -> cancelLaunch());
        cancel.disableProperty().bind(FINISHED);

        VBox cardContent = new VBox(10, pickaxePane, title, name, bar, info, cancel);
        cardContent.setAlignment(Pos.CENTER);
        cardContent.getStyleClass().addAll("fsl-card", "fsl-launch-card");
        cardContent.setMaxHeight(Region.USE_PREF_SIZE);

        // PCL-style scale transform for animation
        Scale cardScale = new Scale(0.8, 0.8, 0, 0);
        cardContent.getTransforms().add(cardScale);
        cardContent.setOpacity(0);

        Region middleGrow = new Region();
        VBox.setVgrow(middleGrow, Priority.ALWAYS);
        VBox side = new VBox(12, accountCard, middleGrow, launchButton);
        side.getStyleClass().add("fsl-home-side");

        StackPane holder = new StackPane(side, cardContent);
        StackPane.setAlignment(cardContent, Pos.CENTER);

        cardContent.setVisible(false);
        cardContent.managedProperty().bind(cardContent.visibleProperty());
        LAUNCHING.addListener((o, old, launching) -> {
            if (launching) {
                // Show launch card with PCL-style animation: scale 0.8->1 + fade in
                cardContent.setVisible(true);
                launchButton.setVisible(false);
                if (FslMotion.enabled()) {
                    cardContent.setOpacity(0);
                    cardScale.setX(0.8);
                    cardScale.setY(0.8);
                    FadeTransition fadeIn = new FadeTransition(Duration.millis(150), cardContent);
                    fadeIn.setFromValue(0);
                    fadeIn.setToValue(1);
                    Timeline scaleUp = new Timeline(
                            new KeyFrame(Duration.ZERO,
                                    new KeyValue(cardScale.xProperty(), 0.8, javafx.animation.Interpolator.EASE_OUT),
                                    new KeyValue(cardScale.yProperty(), 0.8, javafx.animation.Interpolator.EASE_OUT)
                            ),
                            new KeyFrame(Duration.millis(500),
                                    new KeyValue(cardScale.xProperty(), 1, javafx.animation.Interpolator.EASE_OUT),
                                    new KeyValue(cardScale.yProperty(), 1, javafx.animation.Interpolator.EASE_OUT)
                            )
                    );
                    ParallelTransition show = new ParallelTransition(fadeIn, scaleUp);
                    show.play();
                } else {
                    cardContent.setOpacity(1);
                    cardScale.setX(1);
                    cardScale.setY(1);
                }
            } else {
                // Hide launch card, show launch button with fade
                cardContent.setVisible(false);
                launchButton.setVisible(true);
                if (FslMotion.enabled()) {
                    launchButton.setOpacity(0);
                    FadeTransition fade = new FadeTransition(FslMotion.NORMAL, launchButton);
                    fade.setToValue(1);
                    fade.play();
                } else {
                    launchButton.setOpacity(1);
                }
            }
        });

        return holder;
    }
}
