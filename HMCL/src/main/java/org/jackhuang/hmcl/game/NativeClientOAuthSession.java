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
package org.jackhuang.hmcl.game;

import org.jackhuang.hmcl.auth.AuthenticationException;
import org.jackhuang.hmcl.auth.OAuth;
import org.jackhuang.hmcl.auth.yggdrasil.RemoteAuthenticationException;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static org.jackhuang.hmcl.util.Lang.mapOf;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// OAuth authorization-code session that uses Microsoft's native client redirect URI.
///
/// Instead of running a local HTTP server, the login page is shown in an embedded web view
/// (see `FslMicrosoftLoginWindow`). When Microsoft redirects that view to [#REDIRECT_URI],
/// the full redirected URL is handed to [#complete(String)], which extracts the authorization code.
@NotNullByDefault
public final class NativeClientOAuthSession implements OAuth.Session {

    /// The redirect URI registered in the Azure app for the "Mobile and desktop applications" platform.
    public static final String REDIRECT_URI = "https://login.microsoftonline.com/common/oauth2/nativeclient";

    /// Random `state` value used to bind the redirect to this session (RFC 6749 §10.12).
    private final String state;

    /// PKCE code verifier for this session (RFC 7636 §4.1).
    private final String codeVerifier;

    /// Completed with the authorization code, or exceptionally when the login fails or is abandoned.
    private final CompletableFuture<String> future = new CompletableFuture<>();

    /// Invoked once when this session is closed, so the owner can forget it.
    private final Consumer<NativeClientOAuthSession> onClose;

    /// Creates a new session with fresh `state` and PKCE values.
    ///
    /// @param onClose called with this session when [#close()] is invoked
    public NativeClientOAuthSession(Consumer<NativeClientOAuthSession> onClose) {
        this.onClose = onClose;

        var encoder = Base64.getUrlEncoder().withoutPadding();
        var random = new SecureRandom();

        byte[] stateBytes = new byte[32];
        random.nextBytes(stateBytes);
        this.state = encoder.encodeToString(stateBytes);

        byte[] verifierBytes = new byte[64];
        random.nextBytes(verifierBytes);
        this.codeVerifier = encoder.encodeToString(verifierBytes);
    }

    /// Returns whether the given URL is a navigation to [#REDIRECT_URI].
    ///
    /// @param url the URL the web view is navigating to
    /// @return `true` if the URL is the native client redirect, with or without query or fragment
    public static boolean isRedirect(String url) {
        return url.equals(REDIRECT_URI)
                || url.startsWith(REDIRECT_URI + "?")
                || url.startsWith(REDIRECT_URI + "#");
    }

    /// Delivers the redirected URL captured by the web view.
    ///
    /// On success the authorization code completes [#waitFor()]; error responses, a missing code
    /// or a mismatched `state` complete it exceptionally instead.
    ///
    /// @param redirectedUrl the full URL Microsoft redirected to
    /// @return `true` if this call settled the session, `false` if it was already settled
    public boolean complete(String redirectedUrl) {
        if (future.isDone()) {
            return false;
        }

        Map<String, String> query;
        try {
            query = mapOf(NetworkUtils.parseQuery(URI.create(redirectedUrl).getRawQuery()));
        } catch (IllegalArgumentException e) {
            LOG.warning("Failed to parse OAuth redirect URL", e);
            return future.completeExceptionally(new AuthenticationException("Failed to authenticate: malformed redirect URL", e));
        }

        @Nullable String error = query.get("error");
        if (error != null) {
            @Nullable String description = query.get("error_description");
            LOG.warning("Microsoft login returned an error: " + error + ": " + description);
            return future.completeExceptionally(new RemoteAuthenticationException(error, description != null ? description : error, ""));
        }

        @Nullable String code = query.get("code");
        if (code == null) {
            LOG.warning("Failed to authenticate: missing authorization code in redirect");
            return future.completeExceptionally(new AuthenticationException("Failed to authenticate: missing authorization code"));
        }

        if (!state.equals(query.get("state"))) {
            LOG.warning("Failed to authenticate: invalid state in redirect");
            return future.completeExceptionally(new AuthenticationException("Failed to authenticate: invalid state"));
        }

        return future.complete(code);
    }

    /// Returns the random `state` sent with the authorization request.
    @Override
    public String getState() {
        return state;
    }

    /// Returns the PKCE code verifier for this session.
    @Override
    public String getCodeVerifier() {
        return codeVerifier;
    }

    /// Returns [#REDIRECT_URI].
    @Override
    public String getRedirectURI() {
        return REDIRECT_URI;
    }

    /// Blocks until the web view delivers the redirect.
    ///
    /// @return the authorization code
    @Override
    public String waitFor() throws InterruptedException, ExecutionException {
        return future.get();
    }

    /// Abandons the session if it is still pending and notifies the owner.
    @Override
    public void close() {
        if (!future.isDone()) {
            future.completeExceptionally(new AuthenticationException("OAuth session is closing"));
        }
        onClose.accept(this);
    }
}
