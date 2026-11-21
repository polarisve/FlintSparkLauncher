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
import javafx.beans.binding.Bindings;
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
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.fsl.ds.Fsl;
import org.jackhuang.hmcl.ui.fsl.ds.FslMotion;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// FSL: like PCL, launching a game shows a launch card in the middle of the left column
/// (pickaxe animation, instance name, progress bar, current step, login method, download speed, cancel).
/// The account card stays at the top, the launch button disappears.
@NotNullByDefault
public final class FslLaunchCard {

    private static final BooleanProperty LAUNCHING = new SimpleBooleanProperty(false);
    private static final StringProperty INSTANCE = new SimpleStringProperty("");
    private static final StringProperty TITLE = new SimpleStringProperty("");
    private static final StringProperty STEP = new SimpleStringProperty("");
    private static final StringProperty LOGIN_METHOD = new SimpleStringProperty("");
    private static final DoubleProperty PROGRESS = new SimpleDoubleProperty(0);
    private static final LongProperty SPEED = new SimpleLongProperty(0);
    private static final BooleanProperty FINISHED = new SimpleBooleanProperty(false);

    private static @Nullable TaskExecutor current;

    /// PCL's launch loaders (`McLaunchStart` in ModLaunch.vb), in PCL's order and with PCL's
    /// progress weights. Every step below is an operation the launch pipeline really performs,
    /// not just a label.
    public enum LaunchStep {
        JAVA("fsl.launch.step.java", 4),
        LOGIN("fsl.launch.step.login", 15),
        FIX_FILES("fsl.launch.step.fix_files", 15),
        MEMORY("fsl.launch.step.memory", 30),
        ARGUMENTS("fsl.launch.step.arguments", 2),
        NATIVES("fsl.launch.step.natives", 2),
        PRERUN("fsl.launch.step.prerun", 1),
        CUSTOM("fsl.launch.step.custom", 1),
        PROCESS("fsl.launch.step.process", 2),
        WINDOW("fsl.launch.step.window", 1),
        END("fsl.launch.step.end", 1);

        private final String key;
        private final int weight;

        LaunchStep(String key, int weight) {
            this.key = key;
            this.weight = weight;
        }

        public String key() {
            return key;
        }

        public int weight() {
            return weight;
        }
    }

    /// Sum of every loader weight, the denominator of PCL's weighted progress.
    private static final int TOTAL_WEIGHT;

    static {
        int sum = 0;
        for (LaunchStep s : LaunchStep.values()) sum += s.weight();
        TOTAL_WEIGHT = sum;
    }

    private static LaunchStep step = LaunchStep.JAVA;
    /// Step the pipeline reached before the card was attached (see {@link #enter(LaunchStep)}).
    private static @Nullable LaunchStep pending;
    private static int stepDone;
    private static int stepTotal;
    /// Progress inside the current step for steps that have no tasks of their own (PCL advances
    /// 内存优化 in 100 ms ticks instead of task events).
    private static double stepProgress;

    /// PCL's `IsLaunched`: the game window is being waited for (or the launch is being wrapped up).
    private static final BooleanProperty LAUNCHED = new SimpleBooleanProperty(false);

    /// Real progress reported by the task executor, 0..1. Never moves backwards.
    private static double actualProgress;

    /// PCL drives the bar from its own `ShowProgress`, which crawls towards the real value
    /// instead of jumping: `ShowProgress += (Actual - ShowProgress) * 0.1 + 0.0025`.
    /// HMCL's task graph is expanded on the fly, so the raw ratio may already be at 50 %
    /// when the card shows up; crawling keeps the bar starting from 0 like PCL does.
    private static final AnimationTimer PROGRESS_PUMP = new AnimationTimer() {
        /// Timestamp of the previous frame, 0 until the timer is (re)started.
        private long lastNanos;

        @Override
        public void start() {
            lastNanos = 0;
            super.start();
        }

        @Override
        public void handle(long now) {
            // Frame-rate independent: the crawl has to look the same on 60 Hz and 144 Hz panels,
            // so the increments below are expressed per second instead of per frame.
            double dt = lastNanos == 0 ? 1.0 / 60.0 : (now - lastNanos) / 1_000_000_000.0;
            lastNanos = now;
            dt = Math.max(0.001, Math.min(0.1, dt));

            double shown = PROGRESS.get();
            if (actualProgress >= shown) {
                double diff = actualProgress - shown;
                // PCL nudges `ShowProgress` towards the real value instead of assigning it; keeping a
                // constant floor speed makes even a large step report (e.g. 98 % -> 100 %) read as
                // loading rather than as a jump.
                double step = Math.max(diff * 3.0, 0.10) * dt;
                shown += Math.min(step, diff);
            } else {
                shown = actualProgress;
            }
            if (actualProgress - shown < 0.0005) shown = actualProgress;
            PROGRESS.set(Math.max(0, Math.min(1, shown)));
        }
    };

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
        step = LaunchStep.JAVA;
        stepDone = 0;
        stepTotal = 0;
        stepProgress = 0;
        actualProgress = 0;
        INSTANCE.set(instance);
        TITLE.set(i18n("fsl.launch.title"));
        STEP.set(i18n(LaunchStep.JAVA.key()));
        LAUNCHED.set(false);
        LOGIN_METHOD.set(Accounts.getLocalizedLoginTypeName(Accounts.getAccountFactory(Accounts.getSelectedAccount())));
        PROGRESS.set(0);
        SPEED.set(0);
        if (pending != null) {
            LaunchStep resumed = pending;
            pending = null;
            enter(resumed);
        }
        FINISHED.set(false);
        LAUNCHING.set(true);
        PROGRESS_PUMP.start();

        executor.addTaskListener(new TaskListener() {
            @Override
            public void onReady(Task<?> task) {
                Platform.runLater(() -> {
                    if (current == executor && stepOfStage(task.getInheritedStage()) == step) {
                        stepTotal++;
                    }
                });
            }

            @Override
            public void onFinished(Task<?> task) {
                Platform.runLater(() -> {
                    if (current == executor) {
                        if (stepOfStage(task.getInheritedStage()) == step) {
                            stepDone++;
                        }
                        recompute();
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
                            // PCL jumps to 100 % only once the process is up and the launcher
                            // waits for the game window; the card then goes away.
                            FINISHED.set(true);
                            if (!LAUNCHED.get()) {
                                enter(LaunchStep.WINDOW);
                            }
                            PauseTransition pause = new PauseTransition(Duration.millis(1500));
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
            PROGRESS_PUMP.stop();
            LAUNCHING.set(false);
            LAUNCHED.set(false);
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

    /// PCL tilts the launch title and the instance name by -3 degrees (SkewTransform on X).
    private static void skew(Label label) {
        javafx.scene.transform.Shear shear = new javafx.scene.transform.Shear(-0.0524, 0);
        label.getTransforms().add(shear);
        label.layoutBoundsProperty().addListener((observable, oldBounds, bounds) -> {
            shear.setPivotX(bounds.getWidth() / 2);
            shear.setPivotY(bounds.getHeight() / 2);
        });
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
        // WPF defaults: PenLineCap.Flat / PenLineJoin.Miter
        pickaxe.setStrokeLineCap(StrokeLineCap.BUTT);
        pickaxe.setStrokeLineJoin(StrokeLineJoin.MITER);
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

        // Straight port of PCL MyLoading.AniLoop, honouring the real AniStart queue semantics
        // (ModAnimation.AniTimer: a step marked IsAfter only starts once every previous
        //  non-IsAfter step has finished, and a zero length AaCode fires the moment its turn
        //  comes up — it does NOT wait for the parallel rotations behind it):
        //   0    -> 250 ms  idle at the resting angle 55 deg
        //   250  -> 600     swing the pickaxe up to the top, -20 deg  (AaRotateTransform, 350 ms, delay 250, AniEaseInBack Weak)
        //   600             AaCode: both chips pop out — exactly at the instant the pickaxe hits the top
        //   600  -> 780     AaX / AaY(+-5, -6, 180, AniEaseOutFluent Middle): chips fly apart
        //   650  -> 750     AaOpacity(-1, 100, delay 50): chips fade out
        //   600  -> 1500    strike back down to 55 deg (two rotations run in parallel, both 900 ms:
        //                   +50 with AniEaseOutFluent Middle and +25 with AniEaseOutElastic Weak)
        //   1500            trailing IsAfter AaCode restarts the loop, so one cycle is 1500 ms.
        final double cycle = 1500;
        Transition loop = new Transition() {
            {
                setCycleDuration(Duration.millis(cycle));
                setCycleCount(INDEFINITE);
                setInterpolator(Interpolator.LINEAR);
            }

            @Override
            protected void interpolate(double frac) {
                double ms = frac * cycle;

                double angle;
                if (ms < 250) {
                    angle = 55;
                } else if (ms < 600) {
                    angle = 55 - 75 * easeInBackWeak((ms - 250) / 350);
                } else {
                    double t = Math.min(1, (ms - 600) / 900);
                    angle = -20 + 50 * easeOutFluentMiddle(t) + 25 * easeOutElasticWeak(t);
                }
                rotate.setAngle(angle);

                if (ms < 600) {
                    left.setOpacity(0);
                    right.setOpacity(0);
                    left.setTranslateX(0);
                    left.setTranslateY(0);
                    right.setTranslateX(0);
                    right.setTranslateY(0);
                } else {
                    double move = easeOutFluentMiddle(Math.min(1, (ms - 600) / 180));
                    left.setTranslateX(-5 * move);
                    left.setTranslateY(-6 * move);
                    right.setTranslateX(5 * move);
                    right.setTranslateY(-6 * move);
                    double fade = Math.min(1, Math.max(0, (ms - 650) / 100));
                    left.setOpacity(1 - fade);
                    right.setOpacity(1 - fade);
                }
            }
        };
        LAUNCHING.addListener((o, old, launching) -> {
            if (launching) {
                loop.playFromStart();
            } else {
                loop.stop();
            }
        });
        if (LAUNCHING.get()) loop.play();
        return box;
    }

    /// PCL AniEaseInBack(Weak): p = 3 - 2 * 0.5 = 2.
    private static double easeInBackWeak(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * Math.cos(1.5 * Math.PI * (1 - t));
    }

    /// PCL AniEaseOutFluent(Middle): 1 - (1 - t) ^ 3.
    private static double easeOutFluentMiddle(double t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - t, 3);
    }

    /// PCL AniEaseOutElastic(Weak): p = 2 + 4 = 6.
    /// Source: <c>t = 1 - t.Clamp</c> then <c>1 - t ^ ((p - 1) * 0.25) * cos((p - 3.5) * PI * (1 - t) ^ 1.5)</c>,
    /// where the local <c>t</c> is the reversed fraction, so the cosine takes the forward fraction.
    private static double easeOutElasticWeak(double t) {
        double forward = Math.max(0, Math.min(1, t));
        double reversed = 1 - forward;
        return 1 - Math.pow(reversed, 1.25) * Math.cos(2.5 * Math.PI * Math.pow(forward, 1.5));
    }

    /// The 3 x 5 chip (downward triangle) used by the mining animation.
    /// WPF draws <c>M0,0L2,0L1,4</c> with Stretch=Uniform inside a 3 x 5 box, so the geometry
    /// lands on a 2.5 x 5 area centred in that box: x spans 0.25 .. 2.75.
    private static Polygon chip(javafx.scene.paint.Color color, double x, double y, double angle) {
        Polygon chip = new Polygon(0.25, 0, 2.75, 0, 1.5, 5);
        chip.setFill(color);
        chip.setLayoutX(x);
        chip.setLayoutY(y);
        chip.setRotate(angle);
        chip.setOpacity(0);
        chip.setManaged(false);
        return chip;
    }

    /// Marks the PCL loader the launch pipeline is currently performing. Called by the launch
    /// pipeline itself at the exact point where the matching operation starts.
    ///
    /// @param next the loader that just started; steps never go backwards
    public static void enter(LaunchStep next) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> enter(next));
            return;
        }
        if (current == null) {
            // The card is attached a moment after the executor starts; remember where the pipeline
            // already is so the first frame shows the right step instead of resetting to 获取 Java.
            if (pending == null || next.ordinal() > pending.ordinal()) {
                pending = next;
            }
            return;
        }
        if (next.ordinal() < step.ordinal()) {
            return;
        }
        step = next;
        stepDone = 0;
        stepTotal = 0;
        stepProgress = 0;
        STEP.set(i18n(next.key()));
        if (next == LaunchStep.WINDOW || next == LaunchStep.END) {
            // PCL: `IsLaunched` (等待游戏窗口出现 / 结束处理) is exactly when the bar reads 100 %.
            LAUNCHED.set(true);
            TITLE.set(i18n("fsl.launch.title.launched"));
            // Only the target is raised here: PROGRESS is left to the pump, so the bar climbs to
            // 100 % over a beat instead of snapping there in a single frame.
            actualProgress = 1;
        } else {
            recompute();
        }
    }

    /// PCL's weighted progress: finished loaders plus the finished part of the current one.
    private static void recompute() {
        if (LAUNCHED.get()) {
            return;
        }
        int completed = 0;
        for (LaunchStep s : LaunchStep.values()) {
            if (s.ordinal() < step.ordinal()) {
                completed += s.weight();
            }
        }
        double fraction = Math.max(stepProgress, stepTotal > 0 ? Math.min(1, (double) stepDone / stepTotal) : 0);
        double value = (completed + step.weight() * fraction) / TOTAL_WEIGHT;
        if (value > actualProgress) {
            actualProgress = Math.min(1, value);
        }
    }

    /// Advances the current step by one PCL-style tick (0.7 % of the step every 100 ms), for the
    /// steps PCL drives with a timer instead of task events.
    public static void tick() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(FslLaunchCard::tick);
            return;
        }
        if (current == null || LAUNCHED.get()) {
            return;
        }
        stepProgress = Math.min(0.98, stepProgress + 0.007);
        recompute();
    }

    /// @return the loader a task stage belongs to, or {@code null} when it is not a launch loader
    private static @Nullable LaunchStep stepOfStage(@Nullable String stage) {
        if (stage == null) {
            return null;
        }
        return switch (stage) {
            case "launch.state.java" -> LaunchStep.JAVA;
            case "launch.state.logging_in" -> LaunchStep.LOGIN;
            case "launch.state.dependencies",
                 "hmcl.install.assets",
                 "hmcl.install.libraries",
                 "hmcl.modpack.download" -> LaunchStep.FIX_FILES;
            default -> null;
        };
    }

    /// Wraps the account card and launch button: while a game launches, shows the
    /// PCL-style launch card in the middle, account stays at top, button disappears.
    ///
    /// @param accountCard the account card at the top of the left column
    /// @param launchButton the launch button at the bottom
    /// @return the node to put in the home page
    public static Node wrap(Node accountCard, Node launchButton) {
        // PCL-style loading: the pickaxe strikes the ground line, two chips fly off, it springs back
        // PCL: MyLoading is 50 px high, the 60 x 47 pickaxe box sits inside it
        StackPane pickaxePane = new StackPane(createMiningAnimation());
        pickaxePane.setMinHeight(50);
        pickaxePane.setPrefHeight(50);
        pickaxePane.setMaxHeight(50);

        Label title = new Label();
        title.textProperty().bind(TITLE);
        title.getStyleClass().add("fsl-launch-title");
        skew(title);

        Label name = new Label();
        name.getStyleClass().add("fsl-launch-name");
        name.textProperty().bind(INSTANCE);
        skew(name);

        // Progress bar (PCL-style: flat gradient fill over a translucent track)
        ProgressBar bar = new ProgressBar(0);
        bar.progressProperty().bind(PROGRESS);
        bar.getStyleClass().add("fsl-launch-progress");
        bar.setMaxWidth(Double.MAX_VALUE);

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

        // Speed row: only shown while downloading. The row itself keeps its slot on purpose:
        // hiding it would shrink the info block, and because the card is vertically centred
        // any height change makes the whole card jump to a new position.
        speedLabel.visibleProperty().bind(SPEED.greaterThan(0));
        speedValue.visibleProperty().bind(SPEED.greaterThan(0));

        // The progress row stays visible for the whole lifetime of the launch card, 100 %
        // included: it only goes away together with every other row when the card itself is
        // dismissed once the game has started.

        Button cancel = Fsl.button(i18n("button.cancel"), SVG.CLOSE, Fsl.ButtonKind.SECONDARY);
        cancel.setMaxWidth(Double.MAX_VALUE);
        cancel.setPrefHeight(35);
        cancel.setMinHeight(35);
        cancel.setOnAction(e -> cancelLaunch());
        // PCL keeps the cancel button live until the card is gone: even at 100 % (waiting for
        // the game window) the user must still be able to abort the launch, so never disable it.
        // The button label and icon are centred, not left aligned like JavaFX's default.
        cancel.setAlignment(Pos.CENTER);

        // PCL: the progress bar and the info block share one grid.
        GridPane body = new GridPane();
        body.setAlignment(Pos.CENTER);
        ColumnConstraints bodyColumn = new ColumnConstraints();
        bodyColumn.setPercentWidth(100);
        bodyColumn.setHgrow(Priority.ALWAYS);
        bodyColumn.setFillWidth(true);
        body.getColumnConstraints().add(bodyColumn);
        body.add(bar, 0, 0);
        body.add(info, 0, 1);
        // PCL: progress bar 30 px on both sides, 12 px above, 27 px below
        // Slightly wider than PCL's 30 px side inset, but still inside the 240 px side column.
        GridPane.setMargin(bar, new javafx.geometry.Insets(12, 24, 27, 24));

        // PCL: no card background, no border; content vertically centred, the cancel button
        // pinned to the bottom of the left column (35 px high, 20 px from the sides and bottom).
        // Spacing comes from the individual margins below, exactly like PCL's StackPanel.
        VBox contentBox = new VBox(0, pickaxePane, title, name, body);
        contentBox.setAlignment(Pos.CENTER);
        contentBox.setFillWidth(true);
        title.setMaxWidth(Region.USE_PREF_SIZE);
        name.setMaxWidth(Region.USE_PREF_SIZE);
        info.setMaxWidth(Region.USE_PREF_SIZE);
        // PCL margins: MyLoading (0,10,0,5) / title (15,10,15,0) / name (40,5,40,0)
        VBox.setMargin(pickaxePane, new javafx.geometry.Insets(10, 0, 5, 0));
        VBox.setMargin(title, new javafx.geometry.Insets(10, 15, 0, 15));
        VBox.setMargin(name, new javafx.geometry.Insets(5, 40, 0, 40));

        Region topSpacer = new Region();
        VBox.setVgrow(topSpacer, Priority.ALWAYS);
        Region bottomSpacer = new Region();
        VBox.setVgrow(bottomSpacer, Priority.ALWAYS);

        VBox launchView = new VBox(topSpacer, contentBox, bottomSpacer, cancel);
        launchView.setAlignment(Pos.CENTER);
        launchView.setFillWidth(true);
        launchView.getStyleClass().add("fsl-launch-view");
        VBox.setMargin(cancel, new javafx.geometry.Insets(0, 20, 20, 20));

        Region middleGrow = new Region();
        VBox.setVgrow(middleGrow, Priority.ALWAYS);
        VBox side = new VBox(12, accountCard, middleGrow, launchButton);
        side.getStyleClass().add("fsl-home-side");

        StackPane holder = new StackPane(side, launchView);
        StackPane.setAlignment(side, Pos.TOP_CENTER);
        StackPane.setAlignment(launchView, Pos.CENTER);

        // The bar must already be full width on the first frame. A bare GridPane sizes its single
        // column from the widest child, so the bar used to be as wide as the info block: short
        // while the step text was short, and suddenly longer once a longer step name appeared.
        // Binding the width to the column makes it constant for the whole launch.
        var barWidth = Bindings.createDoubleBinding(
                () -> holder.getWidth() > 120 ? holder.getWidth() - 48 : 192,
                holder.widthProperty());
        bar.prefWidthProperty().bind(barWidth);
        body.maxWidthProperty().bind(barWidth);

        launchView.setVisible(false);
        launchView.setOpacity(0);
        launchView.managedProperty().bind(launchView.visibleProperty());

        Scale launchScale = centeredScale(launchView, 0.8);
        launchView.getTransforms().add(launchScale);
        Scale sideScale = centeredScale(side, 1);
        side.getTransforms().add(sideScale);

        LAUNCHING.addListener((o, old, launching) -> {
            if (!FslMotion.enabled()) {
                side.setVisible(!launching);
                side.setOpacity(launching ? 0 : 1);
                sideScale.setX(1);
                sideScale.setY(1);
                launchView.setVisible(launching);
                launchView.setOpacity(launching ? 1 : 0);
                launchScale.setX(1);
                launchScale.setY(1);
                return;
            }

            if (launching) {
                // PCL: the whole left column (account card + launch button) fades out while zooming to 1.2x
                side.setVisible(true);
                side.setOpacity(1);
                sideScale.setX(1);
                sideScale.setY(1);
                FadeTransition sideFade = new FadeTransition(Duration.millis(220), side);
                sideFade.setToValue(0);
                Timeline sideZoom = new Timeline(
                        new KeyFrame(Duration.ZERO,
                                new KeyValue(sideScale.xProperty(), 1, Interpolator.EASE_IN),
                                new KeyValue(sideScale.yProperty(), 1, Interpolator.EASE_IN)),
                        new KeyFrame(Duration.millis(220),
                                new KeyValue(sideScale.xProperty(), 1.2, Interpolator.EASE_IN),
                                new KeyValue(sideScale.yProperty(), 1.2, Interpolator.EASE_IN)));
                ParallelTransition sideOut = new ParallelTransition(sideFade, sideZoom);
                sideOut.setOnFinished(e -> side.setVisible(false));
                sideOut.play();

                // PCL: the launch view grows from 0.8x to 1x while fading in
                launchView.setVisible(true);
                launchView.setOpacity(0);
                launchScale.setX(0.8);
                launchScale.setY(0.8);
                FadeTransition launchFade = new FadeTransition(Duration.millis(300), launchView);
                launchFade.setToValue(1);
                Timeline launchZoom = new Timeline(
                        new KeyFrame(Duration.ZERO,
                                new KeyValue(launchScale.xProperty(), 0.8, Interpolator.EASE_OUT),
                                new KeyValue(launchScale.yProperty(), 0.8, Interpolator.EASE_OUT)),
                        new KeyFrame(Duration.millis(300),
                                new KeyValue(launchScale.xProperty(), 1, Interpolator.EASE_OUT),
                                new KeyValue(launchScale.yProperty(), 1, Interpolator.EASE_OUT)));
                PauseTransition delay = new PauseTransition(Duration.millis(80));
                delay.setOnFinished(e -> new ParallelTransition(launchFade, launchZoom).play());
                delay.play();
            } else {
                // Reverse animation: the launch view shrinks away, the left column comes back
                FadeTransition launchFade = new FadeTransition(Duration.millis(200), launchView);
                launchFade.setToValue(0);
                Timeline launchZoom = new Timeline(
                        new KeyFrame(Duration.ZERO,
                                new KeyValue(launchScale.xProperty(), 1, Interpolator.EASE_IN),
                                new KeyValue(launchScale.yProperty(), 1, Interpolator.EASE_IN)),
                        new KeyFrame(Duration.millis(200),
                                new KeyValue(launchScale.xProperty(), 0.8, Interpolator.EASE_IN),
                                new KeyValue(launchScale.yProperty(), 0.8, Interpolator.EASE_IN)));
                ParallelTransition launchOut = new ParallelTransition(launchFade, launchZoom);
                launchOut.setOnFinished(e -> launchView.setVisible(false));
                launchOut.play();

                side.setVisible(true);
                side.setOpacity(0);
                sideScale.setX(1.2);
                sideScale.setY(1.2);
                FadeTransition sideFade = new FadeTransition(Duration.millis(300), side);
                sideFade.setToValue(1);
                Timeline sideZoom = new Timeline(
                        new KeyFrame(Duration.ZERO,
                                new KeyValue(sideScale.xProperty(), 1.2, Interpolator.EASE_OUT),
                                new KeyValue(sideScale.yProperty(), 1.2, Interpolator.EASE_OUT)),
                        new KeyFrame(Duration.millis(300),
                                new KeyValue(sideScale.xProperty(), 1, Interpolator.EASE_OUT),
                                new KeyValue(sideScale.yProperty(), 1, Interpolator.EASE_OUT)));
                new ParallelTransition(sideFade, sideZoom).play();
            }
        });

        return holder;
    }

    /// A scale transform pivoting around the centre of `node`, like PCL's render transform.
    private static Scale centeredScale(Node node, double initial) {
        Scale scale = new Scale(initial, initial, 0, 0);
        Runnable update = () -> {
            javafx.geometry.Bounds bounds = node.getLayoutBounds();
            scale.setPivotX(bounds.getWidth() / 2);
            scale.setPivotY(bounds.getHeight() / 2);
        };
        node.layoutBoundsProperty().addListener((o, a, b) -> update.run());
        update.run();
        return scale;
    }
}
