/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui.decorator;

import org.jackhuang.hmcl.ui.construct.Navigator;
import org.jackhuang.hmcl.ui.fsl.FslNavigationTabs;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.fsl.ds.FslTopBar;
import org.jackhuang.hmcl.ui.download.DownloadPage;

import com.jfoenix.controls.JFXButton;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.glavo.monetfx.ColorRole;
import org.glavo.monetfx.ColorScheme;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.WeakListenerHolder;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.animation.Motion;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.wizard.Navigation;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Renders the clipped background, title bar, and navigation content owned by a [Decorator].
@NotNullByDefault
final class MainWindowPane extends StackPane {
    /// The diameter, in pixels, of the rounded window corners.
    private static final double ARC = 8.0;

    /// The decorator whose state and actions are represented by this pane.
    private final Decorator decorator;

    /// The content clip, rounded only for normal windows with custom decoration.
    private final Rectangle clip = new Rectangle();

    /// The frame containing the title bar and current navigation page.
    private final BorderPane frame;

    /// The title bar containing page navigation state.
    private final BorderPane titleBar;

    /// The custom help, minimize, and close buttons shared by both decoration styles.
    private final HBox windowButtons;

    /// The transition container used when the title-bar state changes.
    private final TransitionPane navBarPane;

    /// FSL: tabs in the middle of the title bar, used to size the page title.
    private @Nullable Node titleTabs;

    /// FSL: search box and window buttons, used to size the page title.
    private @Nullable Node titleRight;

    /// Retains listener delegates that are registered through weak listener wrappers.
    @SuppressWarnings("FieldCanBeLocal")
    private final WeakListenerHolder holder = new WeakListenerHolder();

    /// Creates and wires the main-window content for `decorator`.
    ///
    /// @param decorator the owning window decorator
    MainWindowPane(Decorator decorator) {
        this.decorator = decorator;

        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(ARC);
        clip.setArcHeight(ARC);
        setClip(clip);

        backgroundProperty().bind(Bindings.createObjectBinding(
                () -> Themes.windowTransparentProperty().get()
                        ? null
                        : new Background(new BackgroundFill(
                        Themes.getColorScheme().getColor(ColorRole.SURFACE_CONTAINER),
                        CornerRadii.EMPTY,
                        Insets.EMPTY)),
                Themes.windowTransparentProperty(),
                Themes.colorSchemeProperty()));

        Region backgroundNode = createBackgroundNode();

        frame = new BorderPane();
        frame.getStyleClass().add("jfx-decorator");
        frame.setMinWidth(0);
        setMinWidth(0);

        StackPane center = new StackPane();
        center.getStyleClass().add("jfx-decorator-content-container");
        FXUtils.setOverflowHidden(center);
        center.getChildren().setAll(decorator.getNavigator());
        frame.setCenter(center);

        windowButtons = createWindowButtons();
        titleBar = new BorderPane() {
            /// FSL: the page title gets every pixel left of the tabs. It used to be capped at 180px,
            /// which cut "安装新游戏 - 26.3" off. The tabs keep the same size on every page and this
            /// width only depends on the window width, so the tabs never move when switching pages.
            @Override
            protected void layoutChildren() {
                if (titleTabs != null && titleRight != null) {
                    double room = Math.max(0, getWidth() - snappedLeftInset() - snappedRightInset()
                            - titleTabs.prefWidth(-1) - titleRight.prefWidth(-1));
                    navBarPane.setPrefWidth(room);
                    navBarPane.setMaxWidth(room);
                }
                super.layoutChildren();
            }
        };
        titleBar.setPickOnBounds(false);
        titleBar.getStyleClass().add("jfx-tool-bar");
        // FSL: BorderPane asks for the sum of its parts' preferred widths as its minimum. With a long
        // page title that exceeded the window, so the whole window content was laid out wider than
        // the window, centered, and cut off on both sides. Let everything shrink to the window.
        titleBar.setMinWidth(0);
        // FSL: global search left of the window buttons; Enter searches mods on the download page
        FslTopBar search = new FslTopBar(i18n("fsl.search.prompt"), keyword -> {
            decorator.getNavigator().clear();
            DownloadPage page = Controllers.getDownloadPage();
            Controllers.navigate(page);
            page.showModDownloads().searchFor(keyword);
        });
        search.setPickOnBounds(false);
        search.setStyle("-fx-padding: 0 8 0 0;");
        HBox right = new HBox(search, windowButtons);
        right.setAlignment(Pos.CENTER_RIGHT);
        titleBar.setRight(right);
        titleRight = right;

        navBarPane = new TransitionPane();
        navBarPane.setMinWidth(0);
        FXUtils.setOverflowHidden(navBarPane);
        titleBar.setLeft(navBarPane);
        decorator.capableDraggingWindow(navBarPane);

        // FSL: top-level pages as tabs in the middle of the title bar
        FslNavigationTabs tabs = new FslNavigationTabs(() -> decorator.getNavigator().clear());
        decorator.getNavigator().addEventHandler(Navigator.NavigationEvent.NAVIGATED, event -> tabs.sync(event.getNode()));
        decorator.forbidDraggingWindow(tabs);
        decorator.forbidDraggingWindow(search);
        titleBar.setCenter(tabs);
        titleTabs = tabs;

        frame.setTop(titleBar);

        updateTitleBarBackground();
        InvalidationListener titleBarBackgroundListener = observable -> updateTitleBarBackground();
        Themes.colorSchemeProperty().addListener(holder.weak(titleBarBackgroundListener));
        Themes.titleBarTransparentProperty().addListener(holder.weak(titleBarBackgroundListener));

        InvalidationListener navBarListener = observable -> updateNavBar();
        decorator.stateProperty().addListener(holder.weak(navBarListener));
        updateNavBar();

        decorator.capableDraggingWindow(titleBar);

        getChildren().setAll(backgroundNode, frame);
    }

    /// Moves the custom title bar into or out of the native dragging header.
    ///
    /// @param decoration the native header support, or `null` for custom decoration
    void setNativeDecoration(@Nullable NativeWindowDecoration decoration) {
        frame.setTop(null);
        if (decoration == null) {
            frame.setTop(titleBar);
        } else {
            decoration.setContent(titleBar);
            decoration.headerBar.backgroundProperty().bind(titleBar.backgroundProperty());
            frame.setTop(decoration.headerBar);
        }
    }

    /// Enables or disables custom rounding of the content corners.
    ///
    /// Native decoration uses square content bounds so the platform can shape the window outline.
    ///
    /// @param rounded whether to round the content corners
    void setWindowCornersRounded(boolean rounded) {
        double arc = rounded ? ARC : 0.0;
        clip.setArcWidth(arc);
        clip.setArcHeight(arc);
    }

    /// Creates the launcher-background layer and keeps it synchronized with the active theme.
    ///
    /// @return the mouse-transparent background node
    private Region createBackgroundNode() {
        Region backgroundNode = new Region();
        backgroundNode.setMouseTransparent(true);
        StackPane.setAlignment(backgroundNode, Pos.BOTTOM_CENTER);
        holder.onWeakChangeAndOperate(Themes.backgroundProperty(), background -> {
            double opacity = background.opacity();
            if (opacity >= 0.001) {
                backgroundNode.setBackground(background.background());
                backgroundNode.setOpacity(opacity);
            } else {
                backgroundNode.setBackground(null);
                backgroundNode.setOpacity(0);
            }
        });
        return backgroundNode;
    }

    /// Creates the help, minimize, and application-close buttons.
    ///
    /// @return a size-limited top-right button row
    private HBox createWindowButtons() {
        HBox buttons = new HBox();
        buttons.setAlignment(Pos.TOP_RIGHT);
        decorator.capableDraggingWindow(buttons);
        buttons.setMaxSize(Region.USE_PREF_SIZE, 40);

        JFXButton helpButton = new JFXButton();
        helpButton.setFocusTraversable(false);
        helpButton.setGraphic(SVG.HELP.createIcon(Themes.titleFillProperty()));
        helpButton.getStyleClass().add("jfx-decorator-button");
        helpButton.setOnAction(event -> FXUtils.openLink(Metadata.CONTACT_URL));
        decorator.forbidDraggingWindow(helpButton);

        JFXButton minimizeButton = new JFXButton();
        minimizeButton.setFocusTraversable(false);
        minimizeButton.setGraphic(SVG.MINIMIZE_CENTER.createIcon(Themes.titleFillProperty()));
        minimizeButton.getStyleClass().add("jfx-decorator-button");
        minimizeButton.setOnAction(event -> decorator.minimizeWindow());
        decorator.forbidDraggingWindow(minimizeButton);

        // FSL: maximize/restore; the icon follows the window state
        JFXButton maximizeButton = new JFXButton();
        maximizeButton.setFocusTraversable(false);
        maximizeButton.getStyleClass().add("jfx-decorator-button");
        maximizeButton.setOnAction(event -> decorator.toggleMaximizeWindow());
        FXUtils.onChangeAndOperate(decorator.windowMaximizedProperty(), maximized -> maximizeButton.setGraphic(
                (maximized ? SVG.RESTORE_WINDOW : SVG.MAXIMIZE_WINDOW).createIcon(Themes.titleFillProperty())));
        decorator.forbidDraggingWindow(maximizeButton);

        JFXButton closeButton = new JFXButton();
        closeButton.setFocusTraversable(false);
        closeButton.setGraphic(SVG.CLOSE.createIcon(Themes.titleFillProperty()));
        closeButton.getStyleClass().add("jfx-decorator-button");
        closeButton.setOnAction(event -> decorator.closeWindow());
        decorator.forbidDraggingWindow(closeButton);

        buttons.getChildren().setAll(helpButton, minimizeButton, maximizeButton, closeButton);
        return buttons;
    }

    /// Recomputes the title-bar background for the current theme and transparency preference.
    private void updateTitleBarBackground() {
        @Nullable ColorScheme colorScheme = Themes.getColorScheme();
        if (colorScheme == null) {
            titleBar.setBackground(null);
        } else if (Themes.titleBarTransparentProperty().get()) {
            Color surface = colorScheme.getSurface();
            titleBar.setBackground(new Background(new BackgroundFill(
                    new Color(surface.getRed(), surface.getGreen(), surface.getBlue(), 0.5),
                    CornerRadii.EMPTY,
                    Insets.EMPTY)));
        } else {
            // FSL: dark glass title bar instead of the upstream primary-container fill
            titleBar.setBackground(new Background(new BackgroundFill(
                    Themes.FSL_TITLE_BAR_COLOR,
                    CornerRadii.EMPTY,
                    Insets.EMPTY)));
        }
    }

    /// Rebuilds the navigation bar from the current page state.
    private void updateNavBar() {
        DecoratorPage.@Nullable State state = decorator.getState();
        if (state == null) {
            navBarPane.getChildren().clear();
            return;
        }

        Node navBar = createNavBar(state);

        if (state.animate()) {
            // FSL: plain fade. The upstream sideways slide drew the old and new titles on top of
            // each other for a moment, which looked like the title bar jumping out of place.
            decorator.setNavigationDirection(Navigation.NavigationDirection.START);
            navBarPane.setContent(navBar, ContainerAnimations.FADE, Motion.SHORT4);
        } else {
            navBarPane.getChildren().setAll(navBar);
        }
    }

    /// Creates the page-specific navigation controls and title.
    ///
    /// @param state the current page state
    /// @return a newly constructed navigation bar
    private Node createNavBar(DecoratorPage.State state) {
        HBox navBar = new HBox();
        navBar.setAlignment(Pos.CENTER_LEFT);
        decorator.capableDraggingWindow(navBar);

        // Left navigation buttons
        if (state.backable()) {
            JFXButton backButton = new JFXButton();
            backButton.setFocusTraversable(false);
            backButton.setGraphic(SVG.ARROW_BACK.createIcon(Themes.titleFillProperty()));
            backButton.getStyleClass().add("jfx-decorator-button");
            backButton.setOnAction(event -> decorator.back());
            decorator.forbidDraggingWindow(backButton);
            navBar.getChildren().add(backButton);
        }

        if (decorator.canCloseProperty().get()) {
            boolean showCloseAsHome = decorator.showCloseAsHomeProperty().get();

            JFXButton closeButton = new JFXButton();
            closeButton.setFocusTraversable(false);
            closeButton.setGraphic((showCloseAsHome ? SVG.HOME : SVG.CLOSE).createIcon(Themes.titleFillProperty()));
            closeButton.getStyleClass().add("jfx-decorator-button");
            closeButton.setOnAction(event -> decorator.closeCurrentPage());
            decorator.forbidDraggingWindow(closeButton);
            navBar.getChildren().add(closeButton);
        }

        // Center title area
        StackPane titleArea = new StackPane();
        titleArea.setAlignment(Pos.CENTER_LEFT);
        titleArea.setMinWidth(0);
        decorator.capableDraggingWindow(titleArea);
        HBox.setHgrow(titleArea, Priority.ALWAYS);
        if (state.titleNode() != null) {
            titleArea.getChildren().setAll(state.titleNode());
            StackPane.setMargin(state.titleNode(), new Insets(0, 0, 0, 8));
        } else if (state.title() != null) {
            Label titleLabel = new Label(state.title());
            titleLabel.textFillProperty().bind(Themes.titleFillProperty());
            titleLabel.getStyleClass().add("jfx-decorator-title");
            titleLabel.setMinWidth(0);
            titleLabel.setMouseTransparent(true);
            titleArea.getChildren().setAll(titleLabel);
            // FSL: full title on hover, in case a very long one still had to be shortened
            FXUtils.installFastTooltip(titleArea, state.title());
        }

        decorator.registerTitleBar(titleArea);

        if (!navBar.getChildren().isEmpty()) {
            Insets padding = new Insets(0, 0, 0, 5);
            navBar.setPadding(padding);
            titleArea.setPadding(padding);
        }

        navBar.getChildren().add(titleArea);

        // Right refresh button
        if (state.refreshable()) {
            JFXButton refreshButton = new JFXButton();
            refreshButton.setFocusTraversable(false);
            refreshButton.setGraphic(SVG.REFRESH.createIcon(Themes.titleFillProperty()));
            refreshButton.getStyleClass().add("jfx-decorator-button");
            refreshButton.setOnAction(event -> decorator.refresh());
            decorator.forbidDraggingWindow(refreshButton);

            navBar.getChildren().add(refreshButton);
        }

        return navBar;
    }

    /// Produces directional transitions for page-title changes.
    private enum NavBarAnimations implements TransitionPane.AnimationProducer {
        /// Moves the next title in from the right.
        NEXT {
            /// {@inheritDoc}
            @Override
            public void init(TransitionPane container, Node previousNode, Node nextNode) {
                super.init(container, previousNode, nextNode);
                nextNode.setTranslateX(container.getWidth());
            }

            /// {@inheritDoc}
            @Override
            public Timeline animate(
                    Pane container,
                    Node previousNode,
                    Node nextNode,
                    Duration duration,
                    Interpolator interpolator) {
                return createTimeline(previousNode, nextNode, 50, -50, duration, interpolator);
            }

            /// {@inheritDoc}
            @Override
            public TransitionPane.AnimationProducer opposite() {
                return PREVIOUS;
            }
        },

        /// Moves the previous title in from the left.
        PREVIOUS {
            /// {@inheritDoc}
            @Override
            public void init(TransitionPane container, Node previousNode, Node nextNode) {
                super.init(container, previousNode, nextNode);
                nextNode.setTranslateX(-container.getWidth());
            }

            /// {@inheritDoc}
            @Override
            public Timeline animate(
                    Pane container,
                    Node previousNode,
                    Node nextNode,
                    Duration duration,
                    Interpolator interpolator) {
                return createTimeline(previousNode, nextNode, -50, 50, duration, interpolator);
            }

            /// {@inheritDoc}
            @Override
            public TransitionPane.AnimationProducer opposite() {
                return NEXT;
            }
        };

        /// Creates a title transition with the supplied horizontal offsets.
        ///
        /// @param previousNode   the title being removed
        /// @param nextNode       the title being shown
        /// @param nextOffset     the initial offset of the next title
        /// @param previousOffset the final offset of the previous title
        /// @param duration       the transition duration
        /// @param interpolator   the transition interpolator
        /// @return the unstarted transition timeline
        private static Timeline createTimeline(
                Node previousNode,
                Node nextNode,
                double nextOffset,
                double previousOffset,
                Duration duration,
                Interpolator interpolator) {
            return new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(nextNode.translateXProperty(), nextOffset, interpolator),
                            new KeyValue(previousNode.translateXProperty(), 0, interpolator),
                            new KeyValue(nextNode.opacityProperty(), 0, interpolator),
                            new KeyValue(previousNode.opacityProperty(), 1, interpolator)),
                    new KeyFrame(duration,
                            new KeyValue(nextNode.translateXProperty(), 0, interpolator),
                            new KeyValue(previousNode.translateXProperty(), previousOffset, interpolator),
                            new KeyValue(nextNode.opacityProperty(), 1, interpolator),
                            new KeyValue(previousNode.opacityProperty(), 0, interpolator)));
        }
    }
}
