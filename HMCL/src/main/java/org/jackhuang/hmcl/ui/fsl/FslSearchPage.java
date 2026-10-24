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
import javafx.scene.input.KeyCode;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.download.ComponentRemoteVersion;
import org.jackhuang.hmcl.download.game.GameRemoteVersion;
import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.download.DownloadPage;
import org.jackhuang.hmcl.ui.fsl.ds.Fsl;
import org.jackhuang.hmcl.ui.instances.DownloadListPage;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;

import java.text.NumberFormat;
import java.util.Locale;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// FSL aggregated search: how many results a keyword has in every kind of content.
///
/// Opened from the title-bar search box. The page always fits the window (nothing scrolls): one
/// card per kind (installed instances, game versions, modpacks, mods, resource packs, shaders, data
/// packs, worlds) shows only the number of matches. Clicking a card or its "查看全部" opens that
/// kind's list already searched for the keyword. Each card counts on its own, so a slow source
/// never holds up the others.
@NotNullByDefault
public final class FslSearchPage extends StackPane implements DecoratorPage {

    /// Cards per row of the grid.
    private static final int COLUMNS = 2;

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>();

    /// The trimmed search text.
    private final String keyword;

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

        Card instances = new Card(SVG.DEPLOYED_CODE, i18n("fsl.search.instances"), () -> {
            Controllers.navigate(Controllers.getGameListPage());
            Controllers.getGameListPage().searchFor(keyword);
        });
        Card versions = new Card(SVG.STADIA_CONTROLLER, i18n("fsl.search.versions"), () -> {
            Controllers.navigate(downloads);
            downloads.showGameDownloads().searchFor(keyword);
        });
        Card modpacks = addonCard(downloads, RemoteAddon.Type.MODPACK, SVG.PACKAGE2, i18n("modpack"));
        Card mods = addonCard(downloads, RemoteAddon.Type.MOD, SVG.EXTENSION, i18n("mods"));
        Card resourcePacks = addonCard(downloads, RemoteAddon.Type.RESOURCE_PACK, SVG.TEXTURE, i18n("resourcepack"));
        Card shaders = addonCard(downloads, RemoteAddon.Type.SHADER_PACK, SVG.WB_SUNNY, i18n("download.shader"));
        Card dataPacks = addonCard(downloads, RemoteAddon.Type.DATAPACK, SVG.SCHEMA, i18n("datapack"));
        Card worlds = addonCard(downloads, RemoteAddon.Type.WORLD, SVG.PUBLIC, i18n("world"));

        // Every card gets an equal share of the window; nothing scrolls
        Card[] cards = {instances, mods, versions, resourcePacks, modpacks, dataPacks, shaders, worlds};
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10, 12, 12, 12));
        grid.setMinSize(0, 0);
        for (int c = 0; c < COLUMNS; c++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / COLUMNS);
            column.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(column);
        }
        int rowCount = (cards.length + COLUMNS - 1) / COLUMNS;
        for (int r = 0; r < rowCount; r++) {
            RowConstraints row = new RowConstraints();
            row.setPercentHeight(100.0 / rowCount);
            row.setVgrow(Priority.ALWAYS);
            grid.getRowConstraints().add(row);
        }
        for (int i = 0; i < cards.length; i++) {
            grid.add(cards[i].root, i % COLUMNS, i / COLUMNS);
        }
        getChildren().setAll(grid);

        countInstances(instances);
        countVersions(versions);
        countAddons(modpacks, downloads, RemoteAddon.Type.MODPACK);
        countAddons(mods, downloads, RemoteAddon.Type.MOD);
        countAddons(resourcePacks, downloads, RemoteAddon.Type.RESOURCE_PACK);
        countAddons(shaders, downloads, RemoteAddon.Type.SHADER_PACK);
        countAddons(dataPacks, downloads, RemoteAddon.Type.DATAPACK);
        countAddons(worlds, downloads, RemoteAddon.Type.WORLD);
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// Creates the card for one kind of online resource.
    private Card addonCard(DownloadPage downloads, RemoteAddon.Type type, SVG icon, String name) {
        return new Card(icon, name, () -> {
            Controllers.navigate(downloads);
            downloads.showAddonDownloads(type).searchFor(keyword);
        });
    }

    /// Counts installed instances whose name contains the keyword, like the instance list's search.
    private void countInstances(Card card) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        try {
            card.show(GameDirectoryManager.getSelectedRepository().getDisplayInstances()
                    .filter(it -> it.getId().toString().toLowerCase(Locale.ROOT).contains(needle))
                    .count());
        } catch (RuntimeException e) {
            LOG.warning("Search could not read instances", e);
            card.failed(() -> countInstances(card));
        }
    }

    /// Counts game versions (every type) whose ID contains the keyword, like the game tab's search.
    private void countVersions(Card card) {
        card.loading();
        String needle = keyword.toLowerCase(Locale.ROOT);
        DownloadProviders.getDownloadProvider().getVersionsAsync(GameComponentType.GAME, null, false)
                .whenComplete(Schedulers.javafx(), (list, exception) -> {
                    if (exception != null || list == null) {
                        LOG.warning("Search could not load game versions", exception);
                        card.failed(() -> countVersions(card));
                        return;
                    }
                    long count = 0;
                    for (ComponentRemoteVersion version : list) {
                        if (version instanceof GameRemoteVersion
                                && version.getSelfVersion().toLowerCase(Locale.ROOT).contains(needle)) {
                            count++;
                        }
                    }
                    card.show(count);
                }).start();
    }

    /// Asks the same sources as the download tab how many results the keyword has.
    private void countAddons(Card card, DownloadPage downloads, RemoteAddon.Type type) {
        card.loading();
        DownloadListPage list = downloads.addonListPage(type);
        Task.supplyAsync(() -> list.countOnce(keyword))
                .whenComplete(Schedulers.javafx(), (count, exception) -> {
                    if (exception != null || count == null || count < 0) {
                        LOG.warning("Search could not count " + type, exception);
                        card.failed(() -> countAddons(card, downloads, type));
                        return;
                    }
                    card.show(count);
                }).start();
    }

    /// One kind of content: a header with "查看全部", and in the middle the number of matches.
    /// The whole card opens the full list.
    private final class Card {
        private final VBox root = Fsl.card();
        private final Label count = new Label();
        private final Label caption;
        private final String name;

        Card(SVG icon, String name, Runnable viewAll) {
            this.name = name;
            Node headerIcon = icon.createIcon(16);
            headerIcon.getStyleClass().add("fsl-search-section-icon");
            Label title = Fsl.title(name);
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            Button more = Fsl.button(i18n("fsl.search.view_all"), Fsl.ButtonKind.GHOST);
            more.setOnAction(e -> viewAll.run());
            // The card itself is clickable too; don't open the list twice
            more.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, javafx.event.Event::consume);
            HBox header = new HBox(8, headerIcon, title, grow, more);
            header.setAlignment(Pos.CENTER_LEFT);

            count.getStyleClass().add("fsl-search-count");
            count.setMinWidth(0);
            caption = Fsl.muted("");
            caption.setWrapText(false);
            caption.setMinWidth(0);
            VBox middle = new VBox(2, count, caption);
            middle.setAlignment(Pos.CENTER);
            middle.setMinSize(0, 0);
            VBox.setVgrow(middle, Priority.ALWAYS);

            root.getChildren().setAll(header, middle);
            root.getStyleClass().add("fsl-search-count-card");
            root.setMinSize(0, 0);
            root.setPrefSize(0, 0);
            root.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            root.setCursor(Cursor.HAND);
            root.setFocusTraversable(true);
            root.setAccessibleRole(AccessibleRole.BUTTON);
            root.setAccessibleText(name);
            FXUtils.onClicked(root, viewAll);
            root.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                    viewAll.run();
                    e.consume();
                }
            });
        }

        void loading() {
            count.setText("…");
            caption.setText(i18n("fsl.search.loading"));
            caption.setOnMouseClicked(null);
        }

        void show(long total) {
            count.setText(i18n("fsl.search.count", NumberFormat.getIntegerInstance(I18n.getLocale().getLocale()).format(total)));
            caption.setText(i18n("fsl.search.about", keyword, name));
            caption.setOnMouseClicked(null);
            root.setAccessibleText(name + " " + count.getText());
        }

        void failed(Runnable retry) {
            count.setText("—");
            caption.setText(i18n("fsl.search.failed"));
            // Retry instead of opening the list
            caption.setOnMouseClicked(e -> {
                e.consume();
                retry.run();
            });
        }
    }
}
