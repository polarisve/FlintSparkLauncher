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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies that every update source is checked together and the results are merged.
class RemoteVersionTest {

    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";

    /// Writes an update.json shaped like the one release.ps1 produces.
    private static String source(Path dir, String name, String version, String jar, String mirror, String sha1) throws IOException {
        String mirrors = mirror == null ? "" : "\"mirrors\": [\"" + mirror + "\"],";
        Path file = dir.resolve(name);
        Files.writeString(file, """
                {
                  "version": "%s",
                  "jar": "%s",
                  %s
                  "jarsha1": "%s",
                  "changelog": "中文说明 %s"
                }
                """.formatted(version, jar, mirrors, sha1, version), StandardCharsets.UTF_8);
        return file.toUri().toString();
    }

    @Test
    void mergesDownloadUrlsFromBothSources(@TempDir Path dir) throws IOException {
        String gitee = source(dir, "gitee.json", "9.9", "https://gitee.example/FSL-9.9.jar", "https://github.example/FSL-9.9.jar", SHA1);
        String github = source(dir, "github.json", "9.9", "https://github.example/FSL-9.9.jar", null, SHA1.toUpperCase(Locale.ROOT));

        RemoteVersion v = RemoteVersion.fetch(UpdateChannel.STABLE, false, List.of(gitee, github));

        assertEquals("9.9", v.version());
        assertEquals(List.of("https://gitee.example/FSL-9.9.jar", "https://github.example/FSL-9.9.jar"), v.urls());
        assertEquals("中文说明 9.9", v.changelog());
        // one site is shown: the one the download starts from
        assertEquals("gitee.example", v.source());
    }

    @Test
    void namesGiteeAndGitHubSources() {
        assertEquals("Gitee", RemoteVersion.sourceName("https://gitee.com/polarisve/FlintSparkLauncher/releases/download/latest/update.json?version=1.2"));
        assertEquals("GitHub", RemoteVersion.sourceName("https://github.com/polarisve/FlintSparkLauncher/releases/latest/download/update.json"));
        assertEquals("example.com", RemoteVersion.sourceName("https://example.com/update.json"));
    }

    @Test
    void downloadsFromTheSiteThatAnswered() {
        // update.json always lists Gitee first; if only GitHub answered, GitHub must come first
        List<String> urls = List.of("https://gitee.com/a/FSL-9.9.jar", "https://github.com/a/FSL-9.9.jar");
        assertEquals(List.of("https://github.com/a/FSL-9.9.jar", "https://gitee.com/a/FSL-9.9.jar"), RemoteVersion.orderUrls(urls, "GitHub"));
        assertEquals(urls, RemoteVersion.orderUrls(urls, "Gitee"));
    }

    @Test
    void picksTheNewestVersionWhicheverSourceHasIt(@TempDir Path dir) throws IOException {
        // e.g. GitHub already has 2.0 while Gitee still serves 1.9
        String gitee = source(dir, "gitee.json", "1.9", "https://gitee.example/FSL-1.9.jar", null, SHA1);
        String github = source(dir, "github.json", "2.0", "https://github.example/FSL-2.0.jar", null, "ffffffffffffffffffffffffffffffffffffffff");

        RemoteVersion v = RemoteVersion.fetch(UpdateChannel.STABLE, false, List.of(gitee, github));

        assertEquals("2.0", v.version());
        // the old jar from Gitee must not be mixed into the 2.0 download
        assertEquals(List.of("https://github.example/FSL-2.0.jar"), v.urls());
        // only the site that has 2.0 can be the source
        assertEquals("github.example", v.source());
    }

    @Test
    void stillWorksWhenOneSourceFails(@TempDir Path dir) throws IOException {
        String broken = dir.resolve("missing.json").toUri().toString();
        String github = source(dir, "github.json", "9.9", "https://github.example/FSL-9.9.jar", null, SHA1);

        RemoteVersion v = RemoteVersion.fetch(UpdateChannel.STABLE, false, List.of(broken, github));

        assertEquals("9.9", v.version());
        assertEquals(List.of("https://github.example/FSL-9.9.jar"), v.urls());
    }

    @Test
    void failsWhenAllSourcesFail(@TempDir Path dir) {
        String a = dir.resolve("a.json").toUri().toString();
        String b = dir.resolve("b.json").toUri().toString();
        assertThrows(IOException.class, () -> RemoteVersion.fetch(UpdateChannel.STABLE, false, List.of(a, b)));
    }
}
