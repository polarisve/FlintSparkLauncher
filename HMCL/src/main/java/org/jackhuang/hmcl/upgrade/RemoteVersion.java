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
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public record RemoteVersion(UpdateChannel channel, String version, List<String> urls, Type type, @Nullable IntegrityCheck integrityCheck,
                            boolean preview, boolean force, @Nullable String changelog, @Nullable String page,
                            String source) {

    /// FSL: what a real SHA-1 looks like; anything else is a placeholder.
    private static final Pattern SHA1_PATTERN = Pattern.compile("[0-9a-fA-F]{40}");

    /// FSL: asks every source (Gitee and GitHub) at the same time on each check.
    /// The newest version found wins; download URLs from every source that offers
    /// that same jar are merged, so the download can use either side.
    public static RemoteVersion fetch(UpdateChannel channel, boolean preview, List<String> sources) throws IOException {
        List<CompletableFuture<RemoteVersion>> futures = sources.stream()
                .map(source -> CompletableFuture.supplyAsync(() -> {
                    // FSL: one network hiccup should not hide an update, so each source is tried twice
                    IOException last = null;
                    for (int attempt = 0; attempt < 2; attempt++) {
                        try {
                            return parse(channel, preview, NetworkUtils.doGet(source), sourceName(source));
                        } catch (IOException e) {
                            if (last != null) e.addSuppressed(last);
                            last = e;
                        }
                    }
                    throw new CompletionException(new IOException("Update source " + source + " failed", last));
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
            if (v.version().equals(newest.version()) && Objects.equals(v.integrityCheck(), newest.integrityCheck())) {
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

    /// Sites ("Gitee", "GitHub", ...) this version can be downloaded from, in download order.
    public List<String> sources() {
        List<String> names = new ArrayList<>();
        for (String u : urls) {
            String name = sourceName(u);
            if (!names.contains(name)) names.add(name);
        }
        return List.copyOf(names);
    }

    /// The same version, downloaded from `chosen` first (the other site is only a backup if it fails).
    public RemoteVersion withSource(String chosen) {
        return new RemoteVersion(channel, version, orderUrls(urls, chosen), type, integrityCheck,
                preview, force, changelog, page, chosen);
    }

    /// FSL: tests the network before downloading. Every site is asked for the first part of the
    /// jar at the same time; the site that delivers it fastest is downloaded from first, and the
    /// other site stays as the backup. If no site answers, the current order is kept.
    public RemoteVersion withFastestSource() {
        List<String> names = sources();
        if (names.size() < 2) return this;
        List<CompletableFuture<Double>> speeds = names.stream()
                .map(name -> CompletableFuture.supplyAsync(() -> probeSpeed(orderUrls(urls, name).get(0)), Schedulers.io()))
                .toList();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROBE_DEADLINE_MILLIS);
        String best = null;
        double bestSpeed = 0;
        for (int i = 0; i < names.size(); i++) {
            // FSL: the whole test waits at most PROBE_DEADLINE_MILLIS; a site still silent by then counts as unreachable
            long remaining = Math.max(deadline - System.nanoTime(), 0);
            double speed;
            try {
                speed = speeds.get(i).get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                speeds.get(i).cancel(true);
                speed = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                speed = 0;
            } catch (ExecutionException e) {
                speed = 0;
            }
            LOG.info("Update source " + names.get(i) + " speed: " + Math.round(speed) + " bytes/s");
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = names.get(i);
            }
        }
        if (best == null) {
            LOG.warning("No update source answered the network test, keeping " + source);
            return this;
        }
        LOG.info("Network test picked update source " + best);
        return withSource(best);
    }

    private static final int PROBE_BYTES = 512 * 1024;
    private static final long PROBE_MILLIS = 5000;
    private static final long PROBE_DEADLINE_MILLIS = 6000;

    /// Bytes per second while reading the start of `url`; 0 if the site cannot be reached.
    private static double probeSpeed(String url) {
        long start = System.nanoTime();
        HttpURLConnection conn = null;
        try {
            conn = NetworkUtils.createHttpConnection(url);
            conn.setConnectTimeout((int) PROBE_MILLIS);
            conn.setReadTimeout((int) PROBE_MILLIS);
            conn = NetworkUtils.resolveConnection(conn);
            conn.setReadTimeout((int) PROBE_MILLIS);
            if (conn.getResponseCode() / 100 != 2) return 0;
            long read = 0;
            byte[] buffer = new byte[8192];
            try (InputStream in = conn.getInputStream()) {
                int n;
                while (read < PROBE_BYTES && (n = in.read(buffer)) != -1) {
                    read += n;
                    if ((System.nanoTime() - start) / 1_000_000 > PROBE_MILLIS) break;
                }
            }
            double seconds = Math.max((System.nanoTime() - start) / 1e9, 0.001);
            return read / seconds;
        } catch (IOException | RuntimeException e) {
            LOG.warning("Network test failed for " + url, e);
            return 0;
        } finally {
            if (conn != null) conn.disconnect();
        }
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
            // FSL: a sha1 that is empty, all zeros or not 40 hex chars is a placeholder left behind by an
            // old release script. Treating it as a real checksum made every download end in
            // ChecksumMismatchException, so such a value is downgraded to "no checksum" instead.
            if (jarHash != null && !isUsableSha1(jarHash)) {
                LOG.warning("Ignoring malformed jarsha1 '" + jarHash + "' from " + source);
                jarHash = null;
            }
            boolean force = Optional.ofNullable(response.get("force")).map(JsonElement::getAsBoolean).orElse(false);
            // FSL: release notes and release page are carried in the update JSON itself
            String changelog = Optional.ofNullable(response.get("changelog")).map(JsonElement::getAsString).orElse(null);
            String page = Optional.ofNullable(response.get("page")).map(JsonElement::getAsString).orElse(null);
            if (jarUrl != null) {
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
                IntegrityCheck check = jarHash == null ? null
                        : new IntegrityCheck("SHA-1", jarHash.toLowerCase(Locale.ROOT));
                return new RemoteVersion(channel, version, List.copyOf(urls), Type.JAR, check, preview, force, changelog, page, source);
            } else {
                throw new IOException("No download url is available");
            }
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            throw new IOException("Malformed response", e);
        }
    }

    /// FSL: SHA-1 values that actually carry information, as opposed to release-script placeholders.
    private static boolean isUsableSha1(String hash) {
        if (!SHA1_PATTERN.matcher(hash).matches()) {
            return false;
        }
        return !hash.chars().allMatch(c -> c == '0');
    }

    @Override
    public @NotNull String toString() {
        return "[" + version + " from " + source + ", " + urls + "]";
    }

    public enum Type {
        JAR
    }
}
