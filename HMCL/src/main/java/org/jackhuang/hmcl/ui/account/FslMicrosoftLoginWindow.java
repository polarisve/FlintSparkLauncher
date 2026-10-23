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
package org.jackhuang.hmcl.ui.account;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.jackhuang.hmcl.game.NativeClientOAuthSession;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// FSL: small window that shows the Microsoft login page in an embedded web view.
///
/// It watches the page location; once Microsoft redirects to
/// [NativeClientOAuthSession#REDIRECT_URI], the redirected URL is handed to the session and the
/// window closes. The user's password is typed into Microsoft's own page and never reaches the
/// launcher. Closing the window early leaves the session pending, so the login can be reopened.
///
/// Must only be used on the JavaFX application thread, and only after `javafx.web` is loaded.
@NotNullByDefault
public final class FslMicrosoftLoginWindow {

    /// The window currently showing, or `null` when none is open.
    private static @Nullable Stage current;

    private FslMicrosoftLoginWindow() {
    }

    /// Opens the login window for the given session, replacing any window already open.
    ///
    /// @param session the session that receives the redirect
    /// @param url     the authorization URL to load
    public static void show(NativeClientOAuthSession session, String url) {
        show(session, url, () -> {
        });
    }

    /// Opens the login window for the given session, replacing any window already open.
    ///
    /// @param session     the session that receives the redirect
    /// @param url         the authorization URL to load
    /// @param onAbandoned called if the user closes the window before signing in
    public static void show(NativeClientOAuthSession session, String url, Runnable onAbandoned) {
        close();

        boolean[] redirected = {false};

        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();

        ProgressBar progress = new ProgressBar();
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.progressProperty().bind(engine.getLoadWorker().progressProperty());
        progress.visibleProperty().bind(engine.getLoadWorker().stateProperty().isEqualTo(Worker.State.RUNNING));
        progress.managedProperty().bind(progress.visibleProperty());

        BorderPane root = new BorderPane(webView);
        root.setTop(progress);

        Stage stage = new Stage();
        stage.setTitle(i18n("account.methods.microsoft.methods.embedded.title"));
        stage.setScene(new Scene(root, 520, 680));
        FXUtils.setIcon(stage);

        @Nullable Stage owner = Controllers.getStage();
        if (owner != null) {
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
        }

        engine.locationProperty().addListener((observable, oldLocation, location) -> {
            if (location != null && NativeClientOAuthSession.isRedirect(location)) {
                // The code is in the URL itself. This listener runs inside WebKit's redirect
                // callback; cancelling or unloading the page here frees the request WebKit is still
                // handling and crashes the JVM, so close the window only after the callback returns.
                redirected[0] = true;
                session.complete(location);
                Platform.runLater(stage::close);
            }
        });

        engine.getLoadWorker().exceptionProperty().addListener((observable, oldException, exception) -> {
            if (exception != null) {
                LOG.warning("Embedded Microsoft login page failed to load: " + engine.getLocation(), exception);
            }
        });

        stage.setOnHidden(event -> {
            if (current == stage) {
                current = null;
            }
            // Release the page, again outside any WebKit callback
            Platform.runLater(() -> engine.load(null));
            if (!redirected[0]) {
                onAbandoned.run();
            }
        });

        current = stage;
        stage.show();
        engine.load(url);
    }

    /// Closes the login window if one is open.
    public static void close() {
        @Nullable Stage stage = current;
        current = null;
        if (stage != null) {
            stage.close();
        }
    }
}
