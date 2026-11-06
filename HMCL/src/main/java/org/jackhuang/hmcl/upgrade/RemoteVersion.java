/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.glavo.url.WebURL;
import org.jackhuang.hmcl.task.FileDownloadTask.IntegrityCheck;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record RemoteVersion(UpdateChannel channel, String version, List<String> urls, Type type, IntegrityCheck integrityCheck,
                            boolean preview, boolean force, @Nullable String changelog, @Nullable String page) {

    /// Fetches the update JSON from the first source that answers.
    public static RemoteVersion fetch(UpdateChannel channel, boolean preview, List<String> sources) throws IOException {
        try {
            JsonObject response = JsonUtils.fromNonNullJson(
                    NetworkUtils.doGet(sources.stream().map(WebURL::parse).toList()), JsonObject.class);
            String version = Optional.ofNullable(response.get("version")).map(JsonElement::getAsString).orElseThrow(() -> new IOException("version is missing"));
            String jarUrl = Optional.ofNullable(response.get("jar")).map(JsonElement::getAsString).orElse(null);
            String jarHash = Optional.ofNullable(response.get("jarsha1")).map(JsonElement::getAsString).orElse(null);
            boolean force = Optional.ofNullable(response.get("force")).map(JsonElement::getAsBoolean).orElse(false);
            // FSL: release notes and release page are carried in the update JSON itself
            String changelog = Optional.ofNullable(response.get("changelog")).map(JsonElement::getAsString).orElse(null);
            String page = Optional.ofNullable(response.get("page")).map(JsonElement::getAsString).orElse(null);
            if (jarUrl != null && jarHash != null) {
                // FSL: "mirrors" lists fallback download URLs for the same jar
                List<String> urls = new ArrayList<>();
                urls.add(jarUrl);
                JsonElement mirrors = response.get("mirrors");
                if (mirrors != null && mirrors.isJsonArray()) {
                    for (JsonElement mirror : mirrors.getAsJsonArray()) {
                        String m = mirror.getAsString();
                        if (!urls.contains(m)) urls.add(m);
                    }
                }
                return new RemoteVersion(channel, version, List.copyOf(urls), Type.JAR, new IntegrityCheck("SHA-1", jarHash), preview, force, changelog, page);
            } else {
                throw new IOException("No download url is available");
            }
        } catch (JsonParseException e) {
            throw new IOException("Malformed response", e);
        }
    }

    @Override
    public @NotNull String toString() {
        return "[" + version + " from " + urls + "]";
    }

    public enum Type {
        JAR
    }
}
