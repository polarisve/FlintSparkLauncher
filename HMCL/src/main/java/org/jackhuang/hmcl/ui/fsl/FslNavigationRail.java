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

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.terracotta.TerracottaMetadata;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.fsl.ds.FslRail;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// FSL main navigation: the left rail of the main window, mapping each item to a top-level page.
///
/// Items act like tabs: choosing one returns to the home page first and then opens the target,
/// so the back stack never piles up across sections. The highlight follows navigation done
/// elsewhere too (for example a button on the home page that opens the download page).
@NotNullByDefault
public final class FslNavigationRail extends StackPane {

    /// Top-level destinations of the launcher.
    public enum Destination {
        /// Home page.
        HOME,
        /// Game, mod and modpack downloads.
        DOWNLOAD,
        /// Installed instances.
        INSTANCES,
        /// Accounts.
        ACCOUNT,
        /// Multiplayer (Terracotta).
        ONLINE,
        /// Launcher and game settings.
        SETTINGS,
    }

    /// The rail control.
    private final FslRail<Destination> rail = new FslRail<>();

    /// Pops every page above the home page.
    private final Runnable goHome;

    /// Pages opened from the rail so far, so [#sync(Node)] never has to create a page.
    private final Map<Destination, Node> opened = new EnumMap<>(Destination.class);

    /// Creates the rail.
    ///
    /// @param goHome returns the main window to the home page
    public FslNavigationRail(Runnable goHome) {
        this.goHome = goHome;

        rail.addItem(Destination.HOME, SVG.HOME, i18n("fsl.nav.home"));
        rail.addItem(Destination.DOWNLOAD, SVG.DOWNLOAD, i18n("download"));
        rail.addItem(Destination.INSTANCES, SVG.DEPLOYED_CODE, i18n("fsl.nav.instances"));
        rail.addItem(Destination.ACCOUNT, SVG.PERSON, i18n("account"));
        rail.addItem(Destination.ONLINE, SVG.STADIA_CONTROLLER, i18n("fsl.nav.online"));
        rail.addBottomItem(Destination.SETTINGS, SVG.SETTINGS, i18n("settings"));
        rail.setOnActivate(this::open);
        rail.selectedProperty().set(Destination.HOME);

        getChildren().add(rail);
    }

    /// Opens a destination.
    private void open(Destination destination) {
        if (destination == Destination.ONLINE && TerracottaMetadata.PROVIDER == null) {
            Controllers.dialog(i18n("terracotta.unsupported"), null, MessageDialogPane.MessageType.WARNING);
            return;
        }

        @Nullable Node target = pageOf(destination);
        goHome.run();
        if (target != null) {
            opened.put(destination, target);
            if (destination == Destination.SETTINGS) {
                Controllers.getSettingsPage().showGameSettings(GameDirectoryManager.getSelectedRepository());
            }
            Controllers.navigate(target);
        }
        rail.selectedProperty().set(destination);
    }

    /// Returns the page for a destination, or `null` for the home page.
    private static @Nullable Node pageOf(Destination destination) {
        return switch (destination) {
            case HOME -> null;
            case DOWNLOAD -> Controllers.getDownloadPage();
            case INSTANCES -> Controllers.getGameListPage();
            case ACCOUNT -> Controllers.getAccountListPage();
            case ONLINE -> Controllers.getTerracottaPage();
            case SETTINGS -> Controllers.getSettingsPage();
        };
    }

    /// Updates the highlight after the main window navigated.
    ///
    /// Sub-pages (for example an instance's settings) keep the section that opened them highlighted.
    ///
    /// @param page the page now shown
    public void sync(Node page) {
        if (page == Controllers.getRootPage()) {
            rail.selectedProperty().set(Destination.HOME);
            return;
        }
        for (Map.Entry<Destination, Node> entry : opened.entrySet()) {
            if (entry.getValue() == page) {
                rail.selectedProperty().set(entry.getKey());
                return;
            }
        }
    }
}
