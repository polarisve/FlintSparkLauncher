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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FslOfficialLauncherSyncTest {

    private static void version(Path base, String id, String json, boolean jar) throws IOException {
        Path dir = Files.createDirectories(base.resolve("versions").resolve(id));
        Files.writeString(dir.resolve(id + ".json"), json, StandardCharsets.UTF_8);
        if (jar) {
            Files.write(dir.resolve(id + ".jar"), new byte[0]);
        }
    }

    @Test
    public void testOnlyInstalledVersionsGetProfiles(@TempDir Path base) throws IOException {
        Files.writeString(base.resolve("launcher_profiles.json"), "{\"profiles\":{}}", StandardCharsets.UTF_8);
        version(base, "26.2", "{\"id\":\"26.2\"}", true);
        // Metadata the official launcher caches for the latest release, without a jar
        version(base, "26.3", "{\"id\":\"26.3\"}", false);
        version(base, "fabric-26.2", "{\"id\":\"fabric-26.2\",\"inheritsFrom\":\"26.2\"}", false);
        version(base, "broken", "not json", false);

        FslOfficialLauncherSync.sync(base, List.of("26.2", "26.3", "fabric-26.2", "broken"));

        String profiles = Files.readString(base.resolve("launcher_profiles.json"), StandardCharsets.UTF_8);
        assertTrue(profiles.contains("\"lastVersionId\": \"26.2\""));
        assertTrue(profiles.contains("\"lastVersionId\": \"fabric-26.2\""));
        assertFalse(profiles.contains("\"26.3\""));
        assertFalse(profiles.contains("broken"));
    }
}
