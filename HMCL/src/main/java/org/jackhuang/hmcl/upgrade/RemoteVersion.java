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
import org.jackhuang.hmcl.task.FileDownloadTask.IntegrityCheck;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public record RemoteVersion(UpdateChannel channel, String version, List<String> urls, Type type, IntegrityCheck integrityCheck,
                            boolean preview, boolean force, @Nullable String changelog, @Nullable String page,
                            String source) {

    /// FSL: asks every source (Gitee and GitHub) at the same time on each check.
    /// The newest version found wins; download URLs from every source that offers
    /// that same jar are merged, so the download can use either side.
    public static RemoteVersion fetch(UpdateChannel channel, boolean preview, List<String> sources) throws IOException {
        List<CompletableFuture<RemoteVersion>> futures = sources.stream()
                .map(source -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return parse(channel, preview, NetworkUtils.doGet(source), sourceName(source));
                    } catch (IOException e) {
                        throw new CompletionException(new IOException("Update source " + source + " failed", e));
                    }
                }, Schedulers.io()))
                .toList();

        List<RemoteVersion> found = new ArrayList<>();
        IOException failure = null;
        for (CompletableFuture<RemoteVersion> future : futures) {
            try {
                found.add(future.join());
            } catch (CompletionException e) {
                Throwable cause = e.getCause() instanceof IOException ? e.getCause() : e;
                LOG.warning("Failed to check one update source", cause);
                if (failure == null) failure = new IOException("All update sources failed");
                failure.addSuppressed(cause);
            }
        }
        if (found.isEmpty()) {
            throw failure != null ? failure : new IOException("No update source");
        }

        RemoteVersion newest = found.get(0);
        for (RemoteVersion v : found) {
            if (VersionNumber.compare(v.version(), newest.version()) > 0) newest = v;
        }
        List<String> urls = new ArrayList<>(newest.urls());
        for (RemoteVersion v : found) {
            if (v.version().equals(newest.version()) && v.integrityCheck().equals(newest.integrityCheck())) {
                for (String u : v.urls()) {
                    if (!urls.contains(u)) urls.add(u);
                }
            }
        }
        // FSL: download from the site that answered the check (Gitee if it answered, since it is
        // checked first); the update dialog shows that one site as the update source
        List<String> ordered = orderUrls(urls, newest.source());
        return new RemoteVersion(newest.channel(), newest.version(), ordered, newest.type(), newest.integrityCheck(),
                newest.preview(), newest.force(), newest.changelog(), newest.page(), sourceName(ordered.get(0)));
    }

    /// Moves the download URLs hosted on `preferred` (e.g. "GitHub") to the front, keeping the rest in order.
    static List<String> orderUrls(List<String> urls, String preferred) {
        List<String> ordered = new ArrayList<>(urls.size());
        for (String u : urls) {
            if (sourceName(u).equals(preferred)) ordered.add(u);
        }
        for (String u : urls) {
            if (!sourceName(u).equals(preferred)) ordered.add(u);
        }
        return List.copyOf(ordered);
    }

    /// Readable name of an update source: "Gitee", "GitHub", or the host.
    static String sourceName(String url) {
        String host;
        try {
            host = Objects.requireNonNullElse(URI.create(url).getHost(), "");
        } catch (IllegalArgumentException e) {
            host = "";
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("gitee.com") || lower.endsWith(".gitee.com")) return "Gitee";
        if (lower.equals("github.com") || lower.endsWith(".github.com")) return "GitHub";
        return host.isEmpty() ? url : host;
    }

    private static RemoteVersion parse(UpdateChannel channel, boolean preview, String text, String source) throws IOException {
        try {
            JsonObject response = JsonUtils.fromNonNullJson(text, JsonObject.class);
            String version = Optional.ofNullable(response.get("version")).map(JsonElement::getAsString).orElseThrow(() -> new IOException("version is missing"));
            String jarUrl = Optional.ofNullable(response.get("jar")).map(JsonElement::getAsString).orElse(null);
            String jarHash = Optional.ofNullable(response.get("jarsha1")).map(JsonElement::getAsString).orElse(null);
            boolean force = Optional.ofNullable(response.get("force")).map(JsonElement::getAsBoolean).orElse(false);
            // FSL: release notes and release page are carried in the update JSON itself
            String changelog = Optional.ofNullable(response.get("changelog")).map(JsonElement::getAsString).orElse(null);
            String page = Optional.ofNullable(response.get("page")).map(JsonElement::getAsString).orElse(null);
            if (jarUrl != null && jarHash != null) {
                // FSL: "mirrors" lists other download URLs for the same jar
                List<String> urls = new ArrayList<>();
                urls.add(jarUrl);
                JsonElement mirrors = response.get("mirrors");
                if (mirrors != null && mirrors.isJsonArray()) {
                    for (JsonElement mirror : mirrors.getAsJsonArray()) {
                        String m = mirror.getAsString();
                        if (!urls.contains(m)) urls.add(m);
                    }
                }
                return new RemoteVersion(channel, version, List.copyOf(urls), Type.JAR, new IntegrityCheck("SHA-1", jarHash.toLowerCase(Locale.ROOT)), preview, force, changelog, page, source);
            } else {
                throw new IOException("No download url is available");
            }
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            throw new IOException("Malformed response", e);
        }
    }

    @Override
    public @NotNull String toString() {
        return "[" + version + " from " + source + ", " + urls + "]";
    }

    public enum Type {
        JAR
    }
}
