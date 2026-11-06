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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.game.GameRemoteVersion;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.GetTask;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.glavo.url.WebURL;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// FSL: keeps the official Minecraft Launcher in step with versions installed by FSL.
///
/// When the selected game directory is the official `.minecraft`, every installed version (see
/// [#isInstalled]) that has no profile in `launcher_profiles.json` gets a "custom" profile, so it shows up in the official
/// launcher's installation list. Existing profiles and all other content of the file are left as
/// they are, nothing is ever removed, and a one-time backup is written before the first change.
@NotNullByDefault
public final class FslOfficialLauncherSync {

    /// Name of the official launcher's profile file.
    private static final String PROFILES_FILE = "launcher_profiles.json";

    /// Backup written once, before FSL first edits the profile file.
    private static final String BACKUP_FILE = "launcher_profiles.json.fsl-backup";

    /// Pretty printer matching the official launcher's indented format.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private FslOfficialLauncherSync() {
    }

    /// Starts syncing: runs whenever the selected repository's version list is (re)loaded.
    public static void install() {
        GameDirectoryManager.registerVersionsListener(repository -> {
            Path base = repository.getLayout().getBaseDirectory().toAbsolutePath().normalize();
            if (!base.equals(Metadata.MINECRAFT_DIRECTORY.toAbsolutePath().normalize())) {
                return;
            }
            List<String> ids = repository.getDisplayInstances().map(instance -> instance.getId().id()).toList();
            Schedulers.io().execute(() -> sync(base, ids));
            // FSL: 官方→FSL 反向同步，检测官方启动器已下载但 FSL 缺 json 的版本并自动补全
            Schedulers.io().execute(() -> syncOfficialVersions(repository, base));
        });
    }

    /// FSL: 反向同步官方启动器已下载的版本。
    ///
    /// 官方启动器只下载 `<id>.jar` 而不写 `<id>.json` 时，[DefaultGameRepository#loadInstanceDirectory]
    /// 会把该目录识别为无 json 的 jar-only 版本；这里从 Mojang 官方版本清单拉取对应 json 写入
    /// `versions/<id>/<id>.json`，随后刷新仓库使该版本以完整实例出现。官方启动器删除的版本目录
    /// 会在仓库刷新时自然消失（refreshRepository 按目录扫描），无需额外清理。
    ///
    /// @param repository 当前选中的仓库
    /// @param base       官方 `.minecraft` 目录
    static synchronized void syncOfficialVersions(HMCLGameRepository repository, Path base) {
        Path versionsDir = base.resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return;
        }

        List<String> missingJson = new ArrayList<>();
        try (var stream = Files.list(versionsDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                String id = FileUtils.getName(dir);
                if (Files.isRegularFile(dir.resolve(id + ".jar"))
                        && !Files.isRegularFile(dir.resolve(id + ".json"))) {
                    missingJson.add(id);
                }
            });
        } catch (IOException e) {
            LOG.warning("FSL: failed to scan official versions directory " + versionsDir, e);
            return;
        }
        if (missingJson.isEmpty()) {
            return;
        }

        LOG.info("FSL: fetching missing version json for " + missingJson);
        DownloadProvider provider = DownloadProviders.getDownloadProvider();
        GameRemoteVersion.fetchAsync(provider)
                .whenComplete(Schedulers.io(), (list, error) -> {
                    if (error != null) {
                        LOG.warning("FSL: failed to fetch Mojang version manifest", error);
                        return;
                    }
                    if (list == null) {
                        return;
                    }
                    boolean wroteAny = false;
                    for (String id : missingJson) {
                        GameRemoteVersion remote = list.getRemoteVersion(id);
                        if (remote == null) {
                            continue;
                        }
                        String url = remote.getUrls().get(0);
                        try {
                            String json = new GetTask(WebURL.parse(url)).run();
                            Path target = versionsDir.resolve(id).resolve(id + ".json");
                            Files.writeString(target, json, StandardCharsets.UTF_8);
                            wroteAny = true;
                            LOG.info("FSL: wrote missing version json for " + id + " -> " + target);
                        } catch (Exception e) {
                            LOG.warning("FSL: failed to download json for " + id + " from " + url, e);
                        }
                    }
                    if (wroteAny) {
                        repository.refreshAsync().start();
                    }
                })
                .start();
    }

    /// Adds a profile for each version in `ids` that the official launcher does not list yet.
    ///
    /// @param base the official `.minecraft` directory
    /// @param ids  the installed version IDs
    static synchronized void sync(Path base, List<String> ids) {
        Path file = base.resolve(PROFILES_FILE);
        // The official launcher creates this file; without it there is nothing to show versions in
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                LOG.warning("Not syncing official launcher profiles: unexpected format in " + file);
                return;
            }
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("profiles") || !root.get("profiles").isJsonObject()) {
                root.add("profiles", new JsonObject());
            }
            JsonObject profiles = root.getAsJsonObject("profiles");

            // Versions already reachable from a profile, including the built-in latest release/snapshot
            Set<String> listed = new HashSet<>();
            for (String key : profiles.keySet()) {
                JsonElement profile = profiles.get(key);
                if (profile.isJsonObject() && profile.getAsJsonObject().has("lastVersionId")) {
                    listed.add(profile.getAsJsonObject().get("lastVersionId").getAsString());
                }
            }

            String now = Instant.now().toString();
            int added = 0;
            for (String id : ids) {
                if (listed.contains(id) || !isInstalled(base.resolve("versions").resolve(id), id)) {
                    continue;
                }
                JsonObject profile = new JsonObject();
                profile.addProperty("name", id);
                profile.addProperty("type", "custom");
                profile.addProperty("lastVersionId", id);
                profile.addProperty("icon", "Grass");
                profile.addProperty("created", now);
                profile.addProperty("lastUsed", now);
                profiles.add(UUID.randomUUID().toString().replace("-", ""), profile);
                added++;
            }
            if (added == 0) {
                return;
            }

            Path backup = base.resolve(BACKUP_FILE);
            if (!Files.exists(backup)) {
                Files.copy(file, backup);
            }
            // Write to a temporary file first so a crash never leaves a half-written profile file
            Path temp = base.resolve(PROFILES_FILE + ".fsl-tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOG.info("Added " + added + " FSL version(s) to the official launcher profiles");
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to sync official launcher profiles in " + file, e);
        }
    }

    /// Returns whether the version in `dir` is really installed, not just cached metadata.
    ///
    /// On every start the official launcher writes `<id>.json` for the latest release and snapshot,
    /// without the jar. Turning those into profiles would make an uninstalled version come back, so a
    /// version counts as installed only when it has its own jar or inherits from another version
    /// (mod loaders such as Fabric or Forge, which ship no jar of their own).
    ///
    /// @param dir the version directory
    /// @param id  the version ID
    /// @return `true` if the version has a JSON file and either a jar or an `inheritsFrom` parent
    static boolean isInstalled(Path dir, String id) {
        Path json = dir.resolve(id + ".json");
        if (!Files.isRegularFile(json)) {
            return false;
        }
        if (Files.isRegularFile(dir.resolve(id + ".jar"))) {
            return true;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8));
            return parsed.isJsonObject() && parsed.getAsJsonObject().has("inheritsFrom");
        } catch (IOException | RuntimeException e) {
            // An unreadable version is skipped instead of aborting the whole sync
            return false;
        }
    }
}
