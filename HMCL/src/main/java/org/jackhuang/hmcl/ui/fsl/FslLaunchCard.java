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
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.task.FetchTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.fsl.ds.Fsl;
import org.jackhuang.hmcl.ui.fsl.ds.FslMotion;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// FSL: like PCL, launching a game turns the left column of the home page into a launch card
/// (instance name, progress, current step, download speed, cancel). Nothing blocks the window and
/// nothing shows up in the download tasks.
@NotNullByDefault
public final class FslLaunchCard {

    private static final Set<String> DEPENDENCY_STAGES =
            Set.of("hmcl.install.assets", "hmcl.install.libraries", "hmcl.modpack.download");

    private static final BooleanProperty LAUNCHING = new SimpleBooleanProperty(false);
    private static final StringProperty INSTANCE = new SimpleStringProperty("");
    private static final StringProperty STEP = new SimpleStringProperty("");
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
        PROGRESS.set(0);
        SPEED.set(0);
        FINISHED.set(false);
        LAUNCHING.set(true);

        executor.addTaskListener(new TaskListener() {
            @Override
            public void onReady(Task<?> task) {
                Platform.runLater(() -> {
                    if (current != executor) return;
                    total++;
                    update();
                });
            }

            @Override
            public void onRunning(Task<?> task) {
                @Nullable String step = stepName(task.getInheritedStage());
                if (step != null) {
                    Platform.runLater(() -> {
                        if (current == executor) STEP.set(step);
                    });
                }
            }

            @Override
            public void onFinished(Task<?> task) {
                Platform.runLater(() -> {
                    if (current != executor) return;
                    done++;
                    update();
                });
            }

            @Override
            public void onFailed(Task<?> task, Throwable throwable) {
                onFinished(task);
            }

            @Override
            public void onStop(boolean success, TaskExecutor executor) {
                Platform.runLater(() -> {
                    if (current != executor) return;
                    if (success) {
                        PROGRESS.set(1);
                        STEP.set(i18n("launch.state.done"));
                        FINISHED.set(true);
                        PauseTransition wait = new PauseTransition(Duration.millis(1200));
                        wait.setOnFinished(e -> finish(executor));
                        wait.play();
                    } else {
                        finish(executor);
                    }
                });
            }
        });
    }

    private static void finish(TaskExecutor executor) {
        if (current == executor) {
            current = null;
            LAUNCHING.set(false);
            SPEED.set(0);
        }
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

    /// Wraps the normal left column of the home page: while a game launches, the column is
    /// replaced by the launch card.
    ///
    /// @param normal the usual left column (account card and launch button)
    /// @return the node to put in the home page
    public static Node wrap(Node normal) {
        VBox card = new VBox(8);
        card.getStyleClass().addAll("fsl-card", "fsl-launch-card");
        card.setMaxHeight(Region.USE_PREF_SIZE);

        Label heading = Fsl.muted(i18n("fsl.launch.title"));
        Label name = new Label();
        name.getStyleClass().add("fsl-launch-name");
        name.textProperty().bind(INSTANCE);
        name.setMaxWidth(Double.MAX_VALUE);

        ProgressBar bar = Fsl.progress(0);
        bar.progressProperty().bind(PROGRESS);
        Label percent = new Label();
        percent.getStyleClass().add("fsl-task-percent");
        percent.textProperty().bind(PROGRESS.multiply(100).asString("%.0f%%"));
        percent.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(bar, Priority.ALWAYS);
        HBox progressRow = new HBox(10, bar, percent);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        Label step = new Label();
        step.getStyleClass().add("fsl-text");
        step.textProperty().bind(STEP);
        step.setMaxWidth(Double.MAX_VALUE);

        Label speedTitle = Fsl.muted(i18n("fsl.task.speed"));
        Label speed = new Label();
        speed.getStyleClass().add("fsl-text");
        SPEED.addListener((o, a, b) -> speed.setText(I18n.formatSpeed(b.longValue())));
        speed.setText(I18n.formatSpeed(0));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox speedRow = new HBox(8, speedTitle, spacer, speed);
        speedRow.setAlignment(Pos.CENTER_LEFT);

        Button cancel = Fsl.button(i18n("button.cancel"), SVG.CLOSE, Fsl.ButtonKind.SECONDARY);
        cancel.setMaxWidth(Double.MAX_VALUE);
        cancel.setOnAction(e -> {
            @Nullable TaskExecutor executor = current;
            if (executor != null) executor.cancel();
        });
        cancel.disableProperty().bind(FINISHED);

        card.getChildren().addAll(heading, name, progressRow, step, speedRow, cancel);

        StackPane holder = new StackPane(normal, card);
        StackPane.setAlignment(card, Pos.CENTER);
        Runnable apply = () -> show(normal, card, LAUNCHING.get());
        card.setVisible(false);
        LAUNCHING.addListener(o -> apply.run());
        if (LAUNCHING.get()) {
            card.setVisible(true);
            normal.setVisible(false);
        }
        return holder;
    }

    private static void show(Node normal, Node card, boolean launching) {
        Node in = launching ? card : normal;
        Node out = launching ? normal : card;
        out.setVisible(false);
        in.setVisible(true);
        if (FslMotion.enabled()) {
            in.setOpacity(0);
            FadeTransition fade = new FadeTransition(FslMotion.NORMAL, in);
            fade.setToValue(1);
            fade.play();
        } else {
            in.setOpacity(1);
        }
    }
}
