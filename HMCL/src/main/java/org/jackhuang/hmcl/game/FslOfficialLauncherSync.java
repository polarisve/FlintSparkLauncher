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
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// FSL: keeps the official Minecraft Launcher in step with versions installed by FSL.
///
/// When the selected game directory is the official `.minecraft`, every installed version that has
/// no profile in `launcher_profiles.json` gets a "custom" profile, so it shows up in the official
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
        });
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
                if (listed.contains(id) || !Files.isRegularFile(base.resolve("versions").resolve(id).resolve(id + ".json"))) {
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
}
