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
import org.jackhuang.hmcl.ui.account.AccountListPage;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.download.DownloadPage;
import org.jackhuang.hmcl.ui.fsl.ds.FslTabs;
import org.jackhuang.hmcl.ui.instances.GameListPage;
import org.jackhuang.hmcl.ui.main.LauncherSettingsPage;
import org.jackhuang.hmcl.ui.terracotta.TerracottaPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

/// FSL main navigation: tabs in the title bar, mapping each tab to a top-level page.
///
/// Tabs act like sections: choosing one returns to the home page first and then opens the target,
/// so the back stack never piles up. The highlight also follows navigation done elsewhere (for
/// example a shortcut on the home page that opens the download page).
@NotNullByDefault
public final class FslNavigationTabs extends StackPane {

    /// Top-level destinations of the launcher.
    public enum Destination {
        /// Home page with the launch button.
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

    /// The tab bar.
    private final FslTabs<Destination> tabs = new FslTabs<>();

    /// Pops every page above the home page.
    private final Runnable goHome;

    /// Creates the navigation tabs.
    ///
    /// @param goHome returns the main window to the home page
    public FslNavigationTabs(Runnable goHome) {
        this.goHome = goHome;

        tabs.addItem(Destination.HOME, SVG.HOME, i18n("fsl.nav.home"));
        tabs.addItem(Destination.DOWNLOAD, SVG.DOWNLOAD, i18n("download"));
        tabs.addItem(Destination.INSTANCES, SVG.DEPLOYED_CODE, i18n("fsl.nav.instances"));
        tabs.addItem(Destination.ACCOUNT, SVG.PERSON, i18n("account"));
        tabs.addItem(Destination.ONLINE, SVG.STADIA_CONTROLLER, i18n("fsl.nav.online"));
        tabs.addItem(Destination.SETTINGS, SVG.SETTINGS, i18n("settings"));
        tabs.setOnActivate(this::open);
        tabs.selectedProperty().set(Destination.HOME);

        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        setMinWidth(USE_PREF_SIZE);
        getChildren().add(tabs);
    }

    /// Opens a destination.
    private void open(Destination destination) {
        if (destination == Destination.ONLINE && TerracottaMetadata.PROVIDER == null) {
            Controllers.dialog(i18n("terracotta.unsupported"), null, MessageDialogPane.MessageType.WARNING);
            return;
        }

        goHome.run();
        switch (destination) {
            case HOME -> {
            }
            case DOWNLOAD -> Controllers.navigate(Controllers.getDownloadPage());
            case INSTANCES -> Controllers.navigate(Controllers.getGameListPage());
            case ACCOUNT -> Controllers.navigate(Controllers.getAccountListPage());
            case ONLINE -> Controllers.navigate(Controllers.getTerracottaPage());
            case SETTINGS -> {
                Controllers.getSettingsPage().showGameSettings(GameDirectoryManager.getSelectedRepository());
                Controllers.navigate(Controllers.getSettingsPage());
            }
        }
        tabs.selectedProperty().set(destination);
    }

    /// Updates the highlight after the main window navigated.
    ///
    /// Sub-pages (for example an instance's settings) keep the section that opened them highlighted.
    ///
    /// @param page the page now shown
    public void sync(Node page) {
        @Nullable Destination destination =
                page == Controllers.getRootPage() ? Destination.HOME
                        : page instanceof DownloadPage ? Destination.DOWNLOAD
                        : page instanceof GameListPage ? Destination.INSTANCES
                        : page instanceof AccountListPage ? Destination.ACCOUNT
                        : page instanceof LauncherSettingsPage ? Destination.SETTINGS
                        : page instanceof TerracottaPage ? Destination.ONLINE
                        : null;
        if (destination != null) {
            tabs.selectedProperty().set(destination);
        }
    }
}
