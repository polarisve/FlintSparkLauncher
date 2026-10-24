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

import com.jfoenix.controls.JFXPopup;
import javafx.scene.Node;
import javafx.scene.control.Label;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.IconedMenuItem;
import org.jackhuang.hmcl.ui.construct.MenuSeparator;
import org.jackhuang.hmcl.ui.construct.PopupMenu;
import org.jackhuang.hmcl.ui.download.DownloadPage;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// FSL global search: the menu that opens under the title-bar search box.
///
/// It lists installed instances whose name matches (click to open its settings), followed by one
/// entry per searchable place (game versions, modpacks, mods, resource packs, shaders, worlds).
/// Picking a place opens that download tab with the keyword already searched.
@NotNullByDefault
public final class FslGlobalSearch {

    /// At most this many matching instances are listed.
    private static final int MAX_INSTANCES = 5;

    private FslGlobalSearch() {
    }

    /// Shows the result menu.
    ///
    /// @param anchor  the search box; the menu opens under it
    /// @param keyword the trimmed search text, not empty
    /// @param goHome  pops every page above the home page before opening a result
    public static void show(Node anchor, String keyword, Runnable goHome) {
        PopupMenu menu = new PopupMenu();
        menu.getStyleClass().add("fsl-search-menu");
        JFXPopup popup = new JFXPopup(menu);

        List<HMCLGameInstance> instances = findInstances(keyword);
        if (!instances.isEmpty()) {
            Label header = new Label(i18n("fsl.search.instances"));
            header.getStyleClass().add("fsl-search-menu-title");
            menu.getContent().add(header);
            for (HMCLGameInstance instance : instances) {
                menu.getContent().add(new IconedMenuItem(SVG.DEPLOYED_CODE, instance.getId().toString(), () -> {
                    goHome.run();
                    Instances.modifyGameSettings(instance);
                }, popup));
            }
            menu.getContent().add(new MenuSeparator());
        }

        addPlace(menu, popup, keyword, goHome, SVG.STADIA_CONTROLLER, i18n("fsl.search.versions"),
                (page, text) -> page.showGameDownloads().searchFor(text));
        addPlace(menu, popup, keyword, goHome, SVG.PACKAGE2, i18n("modpack"),
                (page, text) -> page.showModpackDownloads().searchFor(text));
        addPlace(menu, popup, keyword, goHome, SVG.EXTENSION, i18n("mods"),
                (page, text) -> page.showModDownloads().searchFor(text));
        addPlace(menu, popup, keyword, goHome, SVG.TEXTURE, i18n("resourcepack"),
                (page, text) -> page.showResourcePackDownloads().searchFor(text));
        addPlace(menu, popup, keyword, goHome, SVG.WB_SUNNY, i18n("download.shader"),
                (page, text) -> page.showShaderDownloads().searchFor(text));
        addPlace(menu, popup, keyword, goHome, SVG.PUBLIC, i18n("world"),
                (page, text) -> page.showWorldDownloads().searchFor(text));

        popup.show(anchor, JFXPopup.PopupVPosition.TOP, JFXPopup.PopupHPosition.RIGHT,
                0, anchor.getLayoutBounds().getHeight() + 4);
    }

    /// Adds a "search 'keyword' in <place>" entry that opens the download page and searches there.
    private static void addPlace(PopupMenu menu, JFXPopup popup, String keyword, Runnable goHome,
                                 SVG icon, String place, BiConsumer<DownloadPage, String> searchIn) {
        menu.getContent().add(new IconedMenuItem(icon, i18n("fsl.search.in", keyword, place), () -> {
            goHome.run();
            DownloadPage page = Controllers.getDownloadPage();
            Controllers.navigate(page);
            searchIn.accept(page, keyword);
        }, popup));
    }

    /// Returns installed instances in the selected game directory whose name contains `keyword`.
    private static List<HMCLGameInstance> findInstances(String keyword) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        try {
            return GameDirectoryManager.getSelectedRepository().getDisplayInstances()
                    .filter(it -> it.getId().toString().toLowerCase(Locale.ROOT).contains(needle))
                    .limit(MAX_INSTANCES)
                    .toList();
        } catch (RuntimeException e) {
            LOG.warning("Global search could not read instances", e);
            return List.of();
        }
    }
}
