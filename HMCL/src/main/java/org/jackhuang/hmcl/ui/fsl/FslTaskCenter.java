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

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;
import org.jackhuang.hmcl.task.FetchTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.TaskListPane;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.fsl.ds.Fsl;
import org.jackhuang.hmcl.ui.fsl.ds.FslMotion;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// FSL background tasks: downloads and installs run behind the scenes while the player keeps
/// using the launcher. A round button in the lower right corner shows the overall progress; it
/// opens the task page with every running task and its steps.
@NotNullByDefault
public final class FslTaskCenter {

    /// One running task.
    public static final class Entry {
        private final String title;
        private final TaskExecutor executor;
        private final TaskListPane steps = new TaskListPane();
        private final DoubleProperty progress = new SimpleDoubleProperty(0);
        private int total;
        private int done;

        private final boolean cancellable;

        private Entry(String title, TaskExecutor executor, boolean cancellable) {
            this.title = title;
            this.executor = executor;
            this.cancellable = cancellable;
        }

        private void count(boolean finished) {
            Platform.runLater(() -> {
                if (finished) done++;
                else total++;
                progress.set(total == 0 ? 0 : Math.min(1, (double) done / total));
            });
        }
    }

    private static final ObservableList<Entry> ENTRIES = FXCollections.observableArrayList();
    private static final DoubleProperty TOTAL_PROGRESS = new SimpleDoubleProperty(0);
    private static final DoubleProperty SPEED = new SimpleDoubleProperty(0);

    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private static final Consumer<FetchTask.SpeedEvent> SPEED_HANDLER = FetchTask.SPEED_EVENT.registerWeak(
            event -> Platform.runLater(() -> SPEED.set(ENTRIES.isEmpty() ? 0 : event.getSpeed())));

    private FslTaskCenter() {
    }

    /// Runs `task` in the background and lists it in the task center.
    ///
    /// @param task  the task to run
    /// @param title the name shown in the task list
    /// @return the started executor
    public static TaskExecutor submit(Task<?> task, String title) {
        TaskExecutor executor = task.executor();
        track(executor, title, true);
        executor.start();
        return executor;
    }

    /// Lists an executor that has not been started yet; the caller starts it.
    ///
    /// @param executor the executor to track
    /// @param title    the name shown in the task list
    public static void track(TaskExecutor executor, String title, boolean cancellable) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> track(executor, title, cancellable));
            return;
        }
        Entry entry = new Entry(title, executor, cancellable);
        entry.steps.setExecutor(executor);
        executor.addTaskListener(new TaskListener() {
            @Override
            public void onReady(Task<?> task) {
                entry.count(false);
            }

            @Override
            public void onFinished(Task<?> task) {
                entry.count(true);
            }

            @Override
            public void onFailed(Task<?> task, Throwable throwable) {
                entry.count(true);
            }

            @Override
            public void onStop(boolean success, TaskExecutor executor) {
                Platform.runLater(() -> {
                    ENTRIES.remove(entry);
                    if (Controllers.isStopped()) return;
                    updateTotal();
                    if (success) {
                        Controllers.showToast(i18n("fsl.task.done", title));
                    }
                    if (Controllers.AUTO_TRIM_HEAP) {
                        Platform.runLater(Controllers::trimHeap);
                    }
                });
            }
        });
        entry.progress.addListener(o -> updateTotal());
        ENTRIES.add(entry);
        updateTotal();
        if (!Controllers.isStopped()) Controllers.showToast(i18n("fsl.task.started", title));
    }

    private static void updateTotal() {
        double sum = 0;
        for (Entry entry : ENTRIES) sum += entry.progress.get();
        TOTAL_PROGRESS.set(ENTRIES.isEmpty() ? 0 : sum / ENTRIES.size());
    }

    /// Creates the round button for the lower right corner of the main window. It is only
    /// visible while at least one task runs.
    ///
    /// @return the button
    public static Node createButton() {
        double size = 44;
        Circle face = new Circle(size / 2);
        face.getStyleClass().add("fsl-task-button-face");

        Circle track = new Circle(size / 2 + 3);
        track.getStyleClass().add("fsl-task-button-track");

        Arc ring = new Arc(0, 0, size / 2 + 3, size / 2 + 3, 90, 0);
        ring.setType(ArcType.OPEN);
        ring.setStrokeLineCap(StrokeLineCap.ROUND);
        ring.getStyleClass().add("fsl-task-button-ring");
        ring.setManaged(false);
        Region ringBox = new Region();
        ringBox.setMinSize(size + 10, size + 10);
        ringBox.setMaxSize(size + 10, size + 10);
        StackPane ringHolder = new StackPane(ringBox, ring);
        ringHolder.setMouseTransparent(true);
        ringHolder.layoutBoundsProperty().addListener((o, a, b) -> {
            ring.setCenterX(b.getWidth() / 2);
            ring.setCenterY(b.getHeight() / 2);
        });
        TOTAL_PROGRESS.addListener((o, a, value) -> ring.setLength(-360 * Math.max(0.02, value.doubleValue())));

        Node icon = SVG.DOWNLOAD.createIcon(22);
        icon.getStyleClass().add("fsl-task-button-icon");

        Label badge = new Label();
        badge.getStyleClass().add("fsl-task-button-badge");
        badge.setMouseTransparent(true);
        StackPane.setAlignment(badge, Pos.TOP_RIGHT);

        StackPane button = new StackPane(track, ringHolder, face, icon, badge);
        button.getStyleClass().add("fsl-task-button");
        button.setMaxSize(StackPane.USE_PREF_SIZE, StackPane.USE_PREF_SIZE);
        button.setPickOnBounds(false);
        button.setCursor(Cursor.HAND);
        Tooltip tooltip = new Tooltip();
        Tooltip.install(button, tooltip);
        button.setOnMouseClicked(e -> openPage());

        Runnable refresh = () -> {
            boolean any = !ENTRIES.isEmpty();
            badge.setText(String.valueOf(ENTRIES.size()));
            badge.setVisible(ENTRIES.size() > 1);
            tooltip.setText(i18n("fsl.task.title") + "  " + percent(TOTAL_PROGRESS.get()));
            if (any && !button.isVisible()) {
                button.setVisible(true);
                pop(button);
            } else if (!any) {
                button.setVisible(false);
            }
        };
        button.setVisible(false);
        ENTRIES.addListener((ListChangeListener<Entry>) c -> refresh.run());
        TOTAL_PROGRESS.addListener(o -> tooltip.setText(i18n("fsl.task.title") + "  " + percent(TOTAL_PROGRESS.get())));
        refresh.run();
        return button;
    }

    private static void pop(Node node) {
        if (!FslMotion.enabled()) {
            node.setScaleX(1);
            node.setScaleY(1);
            return;
        }
        // Grows a little past full size and settles back (SPLINE only accepts points in [0,1])
        new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(node.scaleXProperty(), 0.3),
                        new KeyValue(node.scaleYProperty(), 0.3),
                        new KeyValue(node.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(180),
                        new KeyValue(node.scaleXProperty(), 1.12, Interpolator.EASE_OUT),
                        new KeyValue(node.scaleYProperty(), 1.12, Interpolator.EASE_OUT),
                        new KeyValue(node.opacityProperty(), 1)),
                new KeyFrame(Duration.millis(280),
                        new KeyValue(node.scaleXProperty(), 1, Interpolator.EASE_BOTH),
                        new KeyValue(node.scaleYProperty(), 1, Interpolator.EASE_BOTH))
        ).play();
    }

    private static String percent(double value) {
        return Math.round(value * 100) + "%";
    }

    private static @Nullable Page openPage;

    /// Opens the task page, or brings it back if it is already open.
    public static void openPage() {
        @Nullable Page open = openPage;
        if (open != null && open.getScene() != null) {
            return;
        }
        Page page = new Page();
        openPage = page;
        Controllers.navigate(page);
    }

    /// The task page: overall progress and speed on the left, every task with its steps on the right.
    private static final class Page extends HBox implements DecoratorPage {
        private final ReadOnlyObjectWrapper<State> state =
                new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("fsl.task.title")));
        private final ListChangeListener<Entry> listener;

        Page() {
            getStyleClass().addAll("gray-background", "fsl-task-page");
            setSpacing(12);
            setPadding(new Insets(12));

            // Left: summary
            VBox summary = Fsl.card();
            summary.getStyleClass().add("fsl-task-summary");
            summary.setSpacing(6);
            summary.setMinWidth(200);
            summary.setPrefWidth(200);
            summary.setMaxWidth(200);
            Label totalValue = new Label();
            totalValue.getStyleClass().add("fsl-task-big");
            ProgressBar totalBar = Fsl.progress(0);
            totalBar.progressProperty().bind(TOTAL_PROGRESS);
            Label speedValue = new Label();
            speedValue.getStyleClass().add("fsl-task-big");
            Label countValue = new Label();
            countValue.getStyleClass().add("fsl-task-big");
            summary.getChildren().addAll(
                    Fsl.muted(i18n("fsl.task.total")), totalValue, totalBar,
                    gap(), Fsl.muted(i18n("fsl.task.speed")), speedValue,
                    gap(), Fsl.muted(i18n("fsl.task.remaining")), countValue);
            Runnable refreshSummary = () -> {
                totalValue.setText(percent(TOTAL_PROGRESS.get()));
                speedValue.setText(I18n.formatSpeed((long) SPEED.get()));
                countValue.setText(String.valueOf(ENTRIES.size()));
            };
            TOTAL_PROGRESS.addListener(o -> refreshSummary.run());
            SPEED.addListener(o -> refreshSummary.run());
            refreshSummary.run();

            // Right: task cards
            VBox list = new VBox(10);
            list.setFillWidth(true);
            ScrollPane scroll = new ScrollPane(list);
            scroll.setFitToWidth(true);
            scroll.getStyleClass().add("fsl-task-scroll");
            HBox.setHgrow(scroll, Priority.ALWAYS);

            Label empty = Fsl.muted(i18n("fsl.task.empty"));
            StackPane right = new StackPane(scroll, empty);
            HBox.setHgrow(right, Priority.ALWAYS);

            Runnable rebuild = () -> {
                list.getChildren().clear();
                for (Entry entry : ENTRIES) {
                    list.getChildren().add(card(entry));
                }
                empty.setVisible(ENTRIES.isEmpty());
                countValue.setText(String.valueOf(ENTRIES.size()));
            };
            listener = c -> rebuild.run();
            ENTRIES.addListener(new javafx.collections.WeakListChangeListener<>(listener));
            rebuild.run();

            getChildren().addAll(summary, right);
        }

        private static Region gap() {
            Region gap = new Region();
            gap.setMinHeight(8);
            return gap;
        }

        private static Node card(Entry entry) {
            Label title = new Label(entry.title);
            title.getStyleClass().add("fsl-heading");
            title.setMinWidth(0);
            Tooltip.install(title, new Tooltip(entry.title));
            HBox.setHgrow(title, Priority.ALWAYS);
            title.setMaxWidth(Double.MAX_VALUE);

            Label value = new Label();
            value.getStyleClass().add("fsl-task-percent");
            value.setMinWidth(Region.USE_PREF_SIZE);

            Button cancel = Fsl.button(i18n("button.cancel"), SVG.CLOSE, Fsl.ButtonKind.SECONDARY);
            cancel.setMinWidth(Region.USE_PREF_SIZE);
            cancel.setVisible(entry.cancellable);
            cancel.setManaged(entry.cancellable);
            cancel.setOnAction(e -> {
                cancel.setDisable(true);
                entry.executor.cancel();
            });

            HBox header = new HBox(16, title, value, cancel);
            header.setAlignment(Pos.CENTER_LEFT);

            ProgressBar bar = Fsl.progress(0);
            bar.progressProperty().bind(entry.progress);
            value.textProperty().bind(entry.progress.multiply(100).asString("%.0f%%"));

            entry.steps.setMinHeight(120);
            entry.steps.setPrefHeight(180);

            VBox card = Fsl.card();
            card.getStyleClass().add("fsl-task-card");
            card.setSpacing(8);
            card.getChildren().addAll(header, bar, entry.steps);
            return card;
        }

        @Override
        public ReadOnlyObjectProperty<State> stateProperty() {
            return state.getReadOnlyProperty();
        }

        @Nullable
        @Override
        public String toString() {
            return "FslTaskPage";
        }
    }
}
