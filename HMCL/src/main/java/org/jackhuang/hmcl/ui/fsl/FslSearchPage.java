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

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.collections.ObservableList;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.addon.AddonProjectInfo;
import org.jackhuang.hmcl.addon.AddonProjectInfoRegistry;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.download.ComponentRemoteVersion;
import org.jackhuang.hmcl.download.DownloadCandidates;
import org.jackhuang.hmcl.download.game.GameRemoteVersion;
import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.GameInstanceIconType;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.ImageContainer;
import org.jackhuang.hmcl.ui.construct.TwoLineListItem;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.download.DownloadPage;
import org.jackhuang.hmcl.ui.fsl.ds.Fsl;
import org.jackhuang.hmcl.ui.instances.DownloadListPage;
import org.jackhuang.hmcl.ui.instances.GameItem;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jackhuang.hmcl.util.RemoteImageLoader;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// FSL aggregated search: one page with every kind of result for a keyword.
///
/// Opened from the title-bar search box. Each section (installed instances, game versions,
/// modpacks, mods, resource packs, shaders, data packs, worlds) searches on its own and fills in when its
/// results arrive, so a slow source never blocks the others. Clicking a result opens it directly;
/// "查看全部" opens the matching download tab with the keyword already searched.
@NotNullByDefault
public final class FslSearchPage extends StackPane implements DecoratorPage {

    /// Results loaded per section; only the ones that fit in the card are shown, "查看全部" shows the rest.
    private static final int MAX_ROWS = 6;
    /// Sections per row of the grid.
    private static final int COLUMNS = 2;

    /// How many results each online search asks for.
    private static final int SEARCH_SIZE = 10;

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>();

    /// The trimmed search text.
    private final String keyword;

    /// Loads the icons of online results.
    private final RemoteImageLoader iconLoader = new RemoteImageLoader(DownloadProviders.getDownloadProvider()) {
        @Override
        protected Task<Image> createLoadTask(DownloadCandidates urls) {
            return FXUtils.getRemoteImageTask(urls, 64, 64, true, true);
        }
    };

    /// Opens the search page for `keyword`, replacing whatever page was open.
    ///
    /// @param keyword the trimmed search text, not empty
    /// @param goHome  pops every page above the home page
    public static void open(String keyword, Runnable goHome) {
        goHome.run();
        Controllers.navigate(new FslSearchPage(keyword));
    }

    private FslSearchPage(String keyword) {
        this.keyword = keyword;
        state.set(State.fromTitle(i18n("fsl.search.title", keyword)));
        getStyleClass().add("gray-background");

        DownloadPage downloads = Controllers.getDownloadPage();

        Section instances = new Section(SVG.DEPLOYED_CODE, i18n("fsl.search.instances"),
                () -> Controllers.navigate(Controllers.getGameListPage()));
        Section versions = new Section(SVG.STADIA_CONTROLLER, i18n("fsl.search.versions"), () -> {
            Controllers.navigate(downloads);
            downloads.showGameDownloads().searchFor(keyword);
        });
        Section modpacks = addonSection(downloads, RemoteAddon.Type.MODPACK, SVG.PACKAGE2, i18n("modpack"));
        Section mods = addonSection(downloads, RemoteAddon.Type.MOD, SVG.EXTENSION, i18n("mods"));
        Section resourcePacks = addonSection(downloads, RemoteAddon.Type.RESOURCE_PACK, SVG.TEXTURE, i18n("resourcepack"));
        Section shaders = addonSection(downloads, RemoteAddon.Type.SHADER_PACK, SVG.WB_SUNNY, i18n("download.shader"));
        Section worlds = addonSection(downloads, RemoteAddon.Type.WORLD, SVG.PUBLIC, i18n("world"));
        Section dataPacks = addonSection(downloads, RemoteAddon.Type.DATAPACK, SVG.SCHEMA, i18n("datapack"));

        // FSL: every section gets an equal share of the window and nothing scrolls; each one shows
        // as many results as fit and "查看全部" opens the rest
        Section[] sections = {instances, mods, versions, resourcePacks, modpacks, dataPacks, shaders, worlds};
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 12, 12, 12));
        for (int c = 0; c < COLUMNS; c++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / COLUMNS);
            column.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(column);
        }
        int rowCount = (sections.length + COLUMNS - 1) / COLUMNS;
        for (int r = 0; r < rowCount; r++) {
            RowConstraints row = new RowConstraints();
            row.setPercentHeight(100.0 / rowCount);
            row.setVgrow(Priority.ALWAYS);
            row.setFillHeight(true);
            grid.getRowConstraints().add(row);
        }
        for (int i = 0; i < sections.length; i++) {
            grid.add(sections[i].card, i % COLUMNS, i / COLUMNS);
        }
        grid.setMinSize(0, 0);
        getChildren().setAll(grid);

        searchInstances(instances);
        searchVersions(versions, downloads);
        searchAddons(modpacks, downloads, RemoteAddon.Type.MODPACK);
        searchAddons(mods, downloads, RemoteAddon.Type.MOD);
        searchAddons(resourcePacks, downloads, RemoteAddon.Type.RESOURCE_PACK);
        searchAddons(shaders, downloads, RemoteAddon.Type.SHADER_PACK);
        searchAddons(worlds, downloads, RemoteAddon.Type.WORLD);
        searchAddons(dataPacks, downloads, RemoteAddon.Type.DATAPACK);
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Creates the section for one kind of online resource.
    private Section addonSection(DownloadPage downloads, RemoteAddon.Type type, SVG icon, String name) {
        return new Section(icon, name, () -> {
            Controllers.navigate(downloads);
            downloads.showAddonDownloads(type).searchFor(keyword);
        });
    }

    /// Lists installed instances in the selected game directory whose name contains the keyword.
    private void searchInstances(Section section) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        List<Node> rows = new ArrayList<>();
        try {
            GameDirectoryManager.getSelectedRepository().getDisplayInstances()
                    .filter(it -> it.getId().toString().toLowerCase(Locale.ROOT).contains(needle))
                    .limit(MAX_ROWS)
                    .forEach(instance -> rows.add(instanceRow(instance)));
        } catch (RuntimeException e) {
            LOG.warning("Search could not read instances", e);
        }
        section.show(rows);
    }

    /// Filters the game version list by the keyword.
    private void searchVersions(Section section, DownloadPage downloads) {
        section.loading();
        String needle = keyword.toLowerCase(Locale.ROOT);
        DownloadProviders.getDownloadProvider().getVersionsAsync(GameComponentType.GAME, null, false)
                .whenComplete(Schedulers.javafx(), (list, exception) -> {
                    if (exception != null || list == null) {
                        LOG.warning("Search could not load game versions", exception);
                        section.failed(() -> searchVersions(section, downloads));
                        return;
                    }
                    List<Node> rows = new ArrayList<>();
                    for (ComponentRemoteVersion version : list) {
                        if (rows.size() >= MAX_ROWS) {
                            break;
                        }
                        if (version instanceof GameRemoteVersion gameVersion
                                && (I18n.getDisplayVersion(version).toLowerCase(Locale.ROOT).contains(needle)
                                || version.getSelfVersion().toLowerCase(Locale.ROOT).contains(needle))) {
                            rows.add(versionRow(downloads, gameVersion));
                        }
                    }
                    section.show(rows);
                }).start();
    }

    /// Searches one kind of online resource with the same sources as its download tab.
    private void searchAddons(Section section, DownloadPage downloads, RemoteAddon.Type type) {
        section.loading();
        DownloadListPage list = downloads.addonListPage(type);
        Task.supplyAsync(() -> list.searchOnce(keyword, SEARCH_SIZE))
                .whenComplete(Schedulers.javafx(), (result, exception) -> {
                    if (exception != null || result == null) {
                        LOG.warning("Search failed for " + type, exception);
                        section.failed(() -> searchAddons(section, downloads, type));
                        return;
                    }
                    section.show(result.stream()
                            .limit(MAX_ROWS)
                            .map(addon -> addonRow(list, addon))
                            .toList());
                }).start();
    }

    private Node instanceRow(HMCLGameInstance instance) {
        GameItem item = new GameItem(instance);
        TwoLineListItem content = new TwoLineListItem();
        content.setTitle(instance.getId().toString());
        content.subtitleProperty().bind(item.subtitleProperty());
        ImageContainer image = new ImageContainer(32);
        image.imageProperty().bind(item.imageProperty());
        return row(image, content, () -> Instances.modifyGameSettings(instance));
    }

    private Node versionRow(DownloadPage downloads, GameRemoteVersion version) {
        TwoLineListItem content = new TwoLineListItem();
        content.setTitle(I18n.getDisplayVersion(version));
        if (version.getReleaseDate() != null) {
            content.setSubtitle(I18n.formatDateTime(version.getReleaseDate()));
        }
        ImageContainer image = new ImageContainer(32);
        switch (version.getVersionType()) {
            case RELEASE -> {
                content.addTag(i18n("instance.game.release"));
                image.imageProperty().set(GameInstanceIconType.GRASS.getIcon());
            }
            case SNAPSHOT, PENDING, UNOBFUSCATED -> {
                content.addTag(i18n("instance.game.snapshot"));
                image.imageProperty().set(GameInstanceIconType.COMMAND.getIcon());
            }
            default -> {
                content.addTag(i18n("instance.game.old"));
                image.imageProperty().set(GameInstanceIconType.CRAFT_TABLE.getIcon());
            }
        }
        return row(image, content, () -> downloads.installGame(version));
    }

    private Node addonRow(DownloadListPage list, RemoteAddon addon) {
        TwoLineListItem content = new TwoLineListItem();
        @Nullable AddonProjectInfoRegistry<?> registry = AddonProjectInfoRegistry.getByAddonType(list.getAddonType());
        @Nullable AddonProjectInfo info = registry != null ? registry.getInfoByCurseForgeId(addon.slug()) : null;
        content.setTitle(info != null && I18n.isUseChinese() ? info.displayName() : addon.title());
        @Nullable String description = addon.description();
        content.setSubtitle(description != null ? description.replaceAll("\\R", " ") : null);
        RemoteAddon.@Nullable Source source = FslMergedAddonRepository.sourceOf(addon);
        if (source != null) {
            content.addTag(i18n(source == RemoteAddon.Source.MODRINTH ? "addon.modrinth" : "addon.curseforge"));
        }
        long downloads = FslMergedAddonRepository.downloadsOf(addon);
        if (downloads >= 0) {
            content.addTag(i18n("fsl.addon.downloads", FslFormat.compactCount(downloads)));
        }
        ImageContainer image = new ImageContainer(32);
        iconLoader.load(image.imageProperty(), addon.iconUrl());
        return row(image, content, () -> list.openAddon(addon));
    }

    /// Builds one clickable result row: icon, one-line title and one-line subtitle.
    private static Node row(ImageContainer image, TwoLineListItem content, Runnable action) {
        image.setMouseTransparent(true);
        content.setMinWidth(0);
        content.getFirstLine().setMinWidth(0);
        content.getSubtitleLabel().setMinWidth(0);
        HBox.setHgrow(content, Priority.ALWAYS);

        HBox row = new HBox(10, image, content);
        row.getStyleClass().add("fsl-search-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(0);
        row.setCursor(Cursor.HAND);
        row.setFocusTraversable(true);
        row.setAccessibleRole(AccessibleRole.BUTTON);
        row.setAccessibleText(content.getTitle());
        FXUtils.onClicked(row, action);
        row.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                action.run();
                e.consume();
            }
        });
        return row;
    }

    /// A vertical list that never grows its parent: it lays out children top to bottom and hides the
    /// ones that do not fit completely, so a card shows one, two or more rows depending on its height.
    private static final class FitList extends Region {
        private final double spacing;

        FitList(double spacing) {
            this.spacing = spacing;
            setMinSize(0, 0);
            setPrefSize(0, 0);
            setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        }

        ObservableList<Node> rows() {
            return getChildren();
        }

        @Override
        protected void layoutChildren() {
            Insets insets = getInsets();
            double x = insets.getLeft();
            double width = Math.max(0, getWidth() - insets.getLeft() - insets.getRight());
            double bottom = getHeight() - insets.getBottom();
            double y = insets.getTop();
            boolean full = false;
            for (Node child : getChildren()) {
                double height = child.prefHeight(width);
                if (!full && y + height <= bottom + 0.5) {
                    child.setVisible(true);
                    child.resizeRelocate(x, y, width, height);
                    y += height + spacing;
                } else {
                    // Hidden rows are not clickable either
                    full = true;
                    child.setVisible(false);
                    child.resizeRelocate(x, y, width, 0);
                }
            }
        }
    }

    /// One result group: a card with a header and a list that shows loading, results, or an error.
    private static final class Section {
        private final VBox card = Fsl.card();
        private final FitList body = new FitList(2);

        Section(SVG icon, String name, @Nullable Runnable viewAll) {
            Node headerIcon = icon.createIcon(16);
            headerIcon.getStyleClass().add("fsl-search-section-icon");
            Label title = Fsl.title(name);
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            HBox header = new HBox(8, headerIcon, title, grow);
            header.setAlignment(Pos.CENTER_LEFT);
            if (viewAll != null) {
                Button more = Fsl.button(i18n("fsl.search.view_all"), Fsl.ButtonKind.GHOST);
                more.setOnAction(e -> viewAll.run());
                header.getChildren().add(more);
            }
            card.getChildren().setAll(header, body);
            VBox.setVgrow(body, Priority.ALWAYS);
            card.setMinSize(0, 0);
            card.setPrefSize(0, 0);
            card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        }

        void loading() {
            body.rows().setAll(Fsl.muted(i18n("fsl.search.loading")), Fsl.progress(-1));
        }

        void show(List<Node> rows) {
            if (rows.isEmpty()) {
                body.rows().setAll(Fsl.muted(i18n("search.no_results_found")));
            } else {
                body.rows().setAll(rows);
            }
        }

        void failed(Runnable retry) {
            Label label = Fsl.muted(i18n("fsl.search.failed"));
            label.setCursor(Cursor.HAND);
            FXUtils.onClicked(label, retry);
            body.rows().setAll(label);
        }
    }
}
