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

import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.task.Schedulers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// FSL: searches Modrinth and CurseForge at the same time and merges the results into one list.
///
/// Results are interleaved (one from each source in turn) and de-duplicated by title and author, so
/// a project published on both platforms shows up once. If one source fails, the other one's
/// results are still returned. CurseForge results are never dropped for being slow: a list page
/// gets them later through [#withLateResults(Consumer, Callable)], any other caller waits for them. Per-project calls are routed by ID: CurseForge project IDs are numeric,
/// Modrinth IDs are base62 strings.
@NotNullByDefault
public final class FslMergedAddonRepository implements RemoteAddonRepository {

    /// How long a search waits for CurseForge after Modrinth has answered.
    private static final long CURSEFORGE_WAIT_NANOS = TimeUnit.SECONDS.toNanos(4);


    /// Where CurseForge results that arrive after the deadline go, for the search running on this thread.
    private static final ThreadLocal<@Nullable Consumer<List<RemoteAddon>>> LATE_RESULTS = new ThreadLocal<>();

    /// Runs `search` so that CurseForge results arriving after Modrinth's are handed to `onLate`
    /// (on a background thread) instead of being dropped or holding up the first results.
    ///
    /// @param onLate receives the extra results, already de-duplicated against the first ones
    /// @param search the search to run on this thread
    /// @return what `search` returned
    public static <T> T withLateResults(Consumer<List<RemoteAddon>> onLate, Callable<T> search) throws Exception {
        LATE_RESULTS.set(onLate);
        try {
            return search.call();
        } finally {
            LATE_RESULTS.remove();
        }
    }

    /// Returns the key used to tell whether two results are the same project: normalized title and
    /// author, or the slug when the title is missing.
    public static String dedupeKey(RemoteAddon addon) {
        String title = addon.title() == null ? "" : normalize(addon.title());
        if (title.isEmpty()) {
            return "#" + addon.slug();
        }
        return title + "|" + (addon.author() == null ? "" : normalize(addon.author()));
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /// Shared instance; the repository is stateless.
    public static final FslMergedAddonRepository INSTANCE = new FslMergedAddonRepository();

    private FslMergedAddonRepository() {
    }

    /// Returns the Modrinth backend.
    private static RemoteAddonRepository modrinth() {
        return ModrinthRemoteAddonRepository.getInstance();
    }

    /// Returns the CurseForge backend.
    private static RemoteAddonRepository curseForge() {
        return CurseForgeRemoteAddonRepository.getInstance();
    }

    /// Picks the backend that owns a project ID.
    ///
    /// @param id a CurseForge (numeric) or Modrinth (base62) project ID
    /// @return the repository that can resolve `id`
    private static RemoteAddonRepository byId(String id) {
        return !id.isEmpty() && id.chars().allMatch(Character::isDigit) ? curseForge() : modrinth();
    }

    /// Returns which platform an addon came from, based on its data record.
    ///
    /// @param addon a search result
    /// @return the source platform, or `null` when unknown
    public static RemoteAddon.@Nullable Source sourceOf(RemoteAddon addon) {
        Object data = addon.data();
        if (data instanceof CurseForgeRemoteAddonRepository.CurseAddon) {
            return RemoteAddon.Source.CURSEFORGE;
        }
        if (data instanceof ModrinthRemoteAddonRepository.Project
                || data instanceof ModrinthRemoteAddonRepository.ProjectSearchResult) {
            return RemoteAddon.Source.MODRINTH;
        }
        return null;
    }

    /// Returns the total download count reported by the source platform.
    ///
    /// @param addon a search result
    /// @return the download count, or `-1` when the platform does not report one
    public static long downloadsOf(RemoteAddon addon) {
        Object data = addon.data();
        if (data instanceof CurseForgeRemoteAddonRepository.CurseAddon curse) {
            return curse.downloadCount();
        }
        if (data instanceof ModrinthRemoteAddonRepository.ProjectSearchResult result) {
            return result.downloads();
        }
        if (data instanceof ModrinthRemoteAddonRepository.Project project) {
            return project.downloads();
        }
        return -1;
    }

    @Override
    public String getApiBaseUrl() {
        return modrinth().getApiBaseUrl();
    }

    @Override
    public String getBaseUrl() {
        return modrinth().getBaseUrl();
    }

    @Override
    public SearchResult search(DownloadProvider downloadProvider, RemoteAddon.Type type, String gameVersion,
                               @Nullable Category category, int pageOffset, int pageSize, String searchFilter,
                               SortType sortType, SortOrder sortOrder) throws IOException {
        // Categories are platform specific, so the merged view always searches all categories
        int half = Math.max(1, (pageSize + 1) / 2);
        long deadline = System.nanoTime() + CURSEFORGE_WAIT_NANOS;

        CompletableFuture<SearchResult> modrinthSearch = CompletableFuture.supplyAsync(
                () -> searchQuietly(modrinth(), downloadProvider, type, gameVersion, pageOffset, half, searchFilter, sortType, sortOrder),
                Schedulers.io());
        CompletableFuture<SearchResult> curseSearch = CompletableFuture.supplyAsync(
                () -> searchQuietly(curseForge(), downloadProvider, type, gameVersion, pageOffset, half, searchFilter, sortType, sortOrder),
                Schedulers.io());

        @Nullable SearchResult modrinthResult = join(modrinthSearch, "Modrinth");
        // The CurseForge mirror can take ~20 s. A list page that accepts late results shows
        // Modrinth's after the deadline and gets CurseForge's added quietly when they arrive.
        // Everyone else waits for both, so nothing is missing.
        @Nullable Consumer<List<RemoteAddon>> onLate = LATE_RESULTS.get();
        @Nullable SearchResult curseResult;
        boolean curseLate = false;
        if (modrinthResult != null && onLate != null) {
            curseResult = joinBefore(curseSearch, "CurseForge", deadline);
            curseLate = curseResult == null && !curseSearch.isDone();
        } else {
            curseResult = join(curseSearch, "CurseForge");
        }
        if (modrinthResult == null && curseResult == null) {
            throw new IOException("Both Modrinth and CurseForge searches failed");
        }

        List<RemoteAddon> a = modrinthResult != null ? modrinthResult.results().toList() : List.of();
        List<RemoteAddon> b = curseResult != null ? curseResult.results().toList() : List.of();

        List<RemoteAddon> merged = new ArrayList<>(a.size() + b.size());
        Set<String> seenKeys = new HashSet<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) addIfNew(merged, seenKeys, a.get(i));
            if (i < b.size()) addIfNew(merged, seenKeys, b.get(i));
        }

        if (curseLate && onLate != null) {
            Set<String> shown = new HashSet<>(seenKeys);
            curseSearch.thenAccept(late -> {
                List<RemoteAddon> extra = new ArrayList<>();
                late.results().forEach(addon -> addIfNew(extra, shown, addon));
                if (!extra.isEmpty()) {
                    onLate.accept(extra);
                }
            }).exceptionally(e -> {
                LOG.warning("FSL merged search: late CurseForge results failed", e);
                return null;
            });
        }

        int totalPages = Math.max(
                modrinthResult != null ? modrinthResult.totalPages() : 0,
                curseResult != null ? curseResult.totalPages() : 0);
        return new SearchResult(merged.stream(), totalPages);
    }

    /// Runs one backend search, rethrowing I/O errors unchecked so it can run in a future.
    private static SearchResult searchQuietly(RemoteAddonRepository repository, DownloadProvider downloadProvider,
                                              RemoteAddon.Type type, String gameVersion, int pageOffset, int pageSize,
                                              String searchFilter, SortType sortType, SortOrder sortOrder) {
        try {
            return repository.search(downloadProvider, type, gameVersion, null, pageOffset, pageSize, searchFilter, sortType, sortOrder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Waits for a backend search and logs (instead of propagating) its failure.
    ///
    /// @return the result, or `null` if that backend failed
    private static @Nullable SearchResult join(CompletableFuture<SearchResult> future, String name) {
        try {
            return future.join();
        } catch (CompletionException e) {
            LOG.warning("FSL merged search: " + name + " failed", e.getCause() != null ? e.getCause() : e);
            return null;
        }
    }

    /// Waits for a backend search until `deadline` (a [System#nanoTime()] value).
    ///
    /// @return the result, or `null` if that backend failed or was still running at the deadline
    private static @Nullable SearchResult joinBefore(CompletableFuture<SearchResult> future, String name, long deadline) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            LOG.info("FSL merged search: " + name + " is slow, its results will be added when they arrive");
            return null;
        } catch (ExecutionException e) {
            LOG.warning("FSL merged search: " + name + " failed", e.getCause() != null ? e.getCause() : e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /// Adds `addon` unless the same project (see [#dedupeKey(RemoteAddon)]) is already in the list.
    private static void addIfNew(List<RemoteAddon> target, Set<String> seenKeys, RemoteAddon addon) {
        if (seenKeys.add(dedupeKey(addon))) {
            target.add(addon);
        }
    }

    @Override
    public Optional<RemoteAddon.Version> getRemoteVersionByLocalFile(Path file) throws IOException {
        Optional<RemoteAddon.Version> version = modrinth().getRemoteVersionByLocalFile(file);
        return version.isPresent() ? version : curseForge().getRemoteVersionByLocalFile(file);
    }

    @Override
    public RemoteAddon getAddonById(DownloadProvider downloadProvider, String id) throws IOException {
        return byId(id).getAddonById(downloadProvider, id);
    }

    @Override
    public RemoteAddon resolveDependency(DownloadProvider downloadProvider, String id) throws IOException {
        return byId(id).resolveDependency(downloadProvider, id);
    }

    @Override
    public RemoteAddon.File getAddonFile(String projectId, String fileId) throws IOException {
        return byId(projectId).getAddonFile(projectId, fileId);
    }

    @Override
    public Stream<RemoteAddon.Version> getRemoteVersionsById(DownloadProvider downloadProvider, String id) throws IOException {
        return byId(id).getRemoteVersionsById(downloadProvider, id);
    }

    @Override
    public @Nullable String getAddonChangelog(DownloadProvider downloadProvider, String addonId, String versionId) throws IOException {
        return byId(addonId).getAddonChangelog(downloadProvider, addonId, versionId);
    }

    @Override
    public @NotNull String getVersionPageUrl(RemoteAddon.Version version) throws IOException {
        return version.source().getRepository().getVersionPageUrl(version);
    }

    @Override
    public Stream<Category> getCategories(RemoteAddon.Type type) {
        // No shared category tree between the two platforms; the merged view only offers "All"
        return Stream.empty();
    }
}
