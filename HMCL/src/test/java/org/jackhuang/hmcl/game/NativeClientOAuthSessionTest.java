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

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

public class NativeClientOAuthSessionTest {

    private static NativeClientOAuthSession newSession() {
        return new NativeClientOAuthSession(ignored -> {
        });
    }

    @Test
    public void testIsRedirect() {
        assertTrue(NativeClientOAuthSession.isRedirect(NativeClientOAuthSession.REDIRECT_URI));
        assertTrue(NativeClientOAuthSession.isRedirect(NativeClientOAuthSession.REDIRECT_URI + "?code=abc"));
        assertFalse(NativeClientOAuthSession.isRedirect("https://login.live.com/oauth20_authorize.srf"));
        assertFalse(NativeClientOAuthSession.isRedirect(NativeClientOAuthSession.REDIRECT_URI + "evil"));
    }

    @Test
    public void testCodeWithMatchingState() throws Exception {
        NativeClientOAuthSession session = newSession();
        assertTrue(session.complete(NativeClientOAuthSession.REDIRECT_URI + "?code=M.C1_abc&state=" + session.getState()));
        assertEquals("M.C1_abc", session.waitFor());
        assertFalse(session.complete(NativeClientOAuthSession.REDIRECT_URI + "?code=other&state=" + session.getState()));
    }

    @Test
    public void testWrongState() {
        NativeClientOAuthSession session = newSession();
        session.complete(NativeClientOAuthSession.REDIRECT_URI + "?code=abc&state=wrong");
        assertThrows(ExecutionException.class, session::waitFor);
    }

    @Test
    public void testErrorResponse() {
        NativeClientOAuthSession session = newSession();
        session.complete(NativeClientOAuthSession.REDIRECT_URI + "?error=access_denied&error_description=The+user+denied");
        assertThrows(ExecutionException.class, session::waitFor);
    }

    @Test
    public void testCloseUnblocksWaiter() {
        NativeClientOAuthSession session = newSession();
        session.close();
        assertThrows(ExecutionException.class, session::waitFor);
    }
}
