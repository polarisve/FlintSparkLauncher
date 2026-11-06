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
package org.jackhuang.hmcl.upgrade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies that update sources fall back in order and that jar mirrors are parsed.
class RemoteVersionTest {

    private static final String JSON = """
            {
              "version": "9.9",
              "jar": "https://gitee.example/FSL-9.9.jar",
              "mirrors": ["https://github.example/FSL-9.9.jar", "https://gitee.example/FSL-9.9.jar"],
              "jarsha1": "0123456789abcdef0123456789abcdef01234567",
              "changelog": "中文说明"
            }
            """;

    @Test
    void fallsBackToSecondSourceAndParsesMirrors(@TempDir Path dir) throws IOException {
        Path json = dir.resolve("update.json");
        Files.writeString(json, JSON, StandardCharsets.UTF_8);
        String missing = dir.resolve("missing.json").toUri().toString();

        RemoteVersion v = RemoteVersion.fetch(UpdateChannel.STABLE, false, List.of(missing, json.toUri().toString()));

        assertEquals("9.9", v.version());
        // primary first, mirror second, duplicates removed
        assertEquals(List.of("https://gitee.example/FSL-9.9.jar", "https://github.example/FSL-9.9.jar"), v.urls());
        assertEquals("中文说明", v.changelog());
    }

    @Test
    void worksWithoutMirrors(@TempDir Path dir) throws IOException {
        Path json = dir.resolve("update.json");
        Files.writeString(json, JSON.replaceAll("(?m)^.*\"mirrors\".*\\R", ""), StandardCharsets.UTF_8);

        RemoteVersion v = RemoteVersion.fetch(UpdateChannel.STABLE, false, List.of(json.toUri().toString()));

        assertEquals(List.of("https://gitee.example/FSL-9.9.jar"), v.urls());
    }

    @Test
    void failsWhenAllSourcesFail(@TempDir Path dir) {
        String a = dir.resolve("a.json").toUri().toString();
        String b = dir.resolve("b.json").toUri().toString();
        assertThrows(IOException.class, () -> RemoteVersion.fetch(UpdateChannel.STABLE, false, List.of(a, b)));
    }
}
