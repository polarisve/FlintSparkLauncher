/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2025 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// Locates, downloads and relaunches FSL with a Java runtime that satisfies the launcher.
///
/// This class lives in the boot module and therefore must stay Java 8 compatible: it is used both by
/// [Main] (which runs on the very old Java the user happened to double-click with) and by the
/// launcher itself once it is running.
///
/// The whole point is that FSL never asks the user to install Java by hand:
/// <ul>
///     <li>an already present runtime (bundled `jre-x64`, previously downloaded `jre-*`, `JAVA_HOME`) is reused;</li>
///     <li>otherwise a complete runtime is downloaded from a public mirror and unpacked next to the launcher;</li>
///     <li>then FSL is restarted with that runtime.</li>
/// </ul>
public final class JavaRuntimeBootstrap {

    /// The minimum feature version FSL can run on.
    public static final int MINIMUM_FEATURE_VERSION = 17;

    /// The feature version FSL prefers, it matches the JavaFX build shipped with releases.
    public static final int PREFERRED_FEATURE_VERSION = 21;

    /// Environment variable carrying the number of recovery attempts across restarts, used to break loops.
    public static final String ENV_ATTEMPTS = "FSL_JAVA_RECOVERY_ATTEMPTS";

    /// Upper bound of automatic recovery attempts. Beyond it, the user is asked instead of looping forever.
    public static final int MAX_ATTEMPTS = 3;

    private static final String MICROSOFT_JDK_URL = "https://aka.ms/download-jdk/microsoft-jdk-%d-%s.zip";
    private static final String ADOPTIUM_URL = "https://api.adoptium.net/v3/binary/latest/%d/ga/%s/%s/jre/hotspot/normal/eclipse?project=jdk";

    private JavaRuntimeBootstrap() {
    }

    /// Receives download/extraction progress so the caller can drive a progress window.
    public interface ProgressListener {
        void onMessage(String message);

        void onProgress(long downloaded, long total);
    }

    /// A Java runtime found on disk (or just downloaded) that FSL can be started with.
    public static final class Candidate {
        private final Path home;
        private final int featureVersion;
        private final String version;
        private final boolean bundled;

        Candidate(Path home, int featureVersion, String version, boolean bundled) {
            this.home = home;
            this.featureVersion = featureVersion;
            this.version = version;
            this.bundled = bundled;
        }

        public Path getHome() {
            return home;
        }

        public int getFeatureVersion() {
            return featureVersion;
        }

        public String getVersion() {
            return version;
        }

        /// Whether the runtime ships inside the release package (a `jre-*` directory next to the launcher).
        public boolean isBundled() {
            return bundled;
        }

        /// Returns `bin/javaw.exe` (`bin/java` on other platforms), or `null` when it is not executable.
        public Path getJavaExecutable() {
            return findJavaExecutable(home);
        }

        public boolean isUsable() {
            return getJavaExecutable() != null;
        }

        @Override
        public String toString() {
            return version + " (" + home + ")";
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Locations
    // ---------------------------------------------------------------------------------------------

    /// Directory holding the launcher jar/executable.
    public static Path launcherDirectory() {
        String jar = thisJarPath();
        if (jar != null) {
            Path path = Paths.get(jar);
            Path parent = Files.isDirectory(path) ? path : path.getParent();
            if (parent != null)
                return parent.toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private static String thisJarPath() {
        try {
            if (JavaRuntimeBootstrap.class.getProtectionDomain() == null
                    || JavaRuntimeBootstrap.class.getProtectionDomain().getCodeSource() == null
                    || JavaRuntimeBootstrap.class.getProtectionDomain().getCodeSource().getLocation() == null)
                return null;
            return Paths.get(JavaRuntimeBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize().toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /// Portable per-installation directory (`<launcher>/.fsl`), same as `Metadata.HMCL_LOCAL_HOME`.
    public static Path localHome() {
        String configured = System.getProperty("hmcl.dir", System.getenv("HMCL_LOCAL_HOME"));
        if (configured != null && !configured.trim().isEmpty())
            return Paths.get(configured).toAbsolutePath().normalize();
        return launcherDirectory().resolve(".fsl");
    }

    /// Per-user directory (`%APPDATA%\.fsl`), same as `Metadata.HMCL_USER_HOME`.
    public static Path userHome() {
        String configured = System.getProperty("hmcl.home", System.getenv("HMCL_USER_HOME"));
        if (configured != null && !configured.trim().isEmpty())
            return Paths.get(configured).toAbsolutePath().normalize();

        String os = osName();
        String home = System.getProperty("user.home", ".");
        if (os.contains("windows")) {
            String appdata = System.getenv("APPDATA");
            return Paths.get(appdata == null ? home : appdata, ".fsl").toAbsolutePath().normalize();
        } else if (os.contains("mac")) {
            return Paths.get(home, "Library", "Application Support", "fsl").toAbsolutePath().normalize();
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            return xdg != null && !xdg.trim().isEmpty()
                    ? Paths.get(xdg, "fsl").toAbsolutePath().normalize()
                    : Paths.get(home, ".local", "share", "fsl").toAbsolutePath().normalize();
        }
    }

    /// Where downloaded runtimes are stored: `java` sub-directory of [`localHome`], falling back to
    /// [`userHome`] when the launcher directory is read-only (for example `C:\Program Files`).
    public static Path installRoot() {
        Path local = localHome().resolve("java");
        if (canWrite(local))
            return local;
        return userHome().resolve("java");
    }

    private static boolean canWrite(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = dir.resolve(".fsl-write-probe-" + UUID.randomUUID());
            Files.createFile(probe);
            Files.deleteIfExists(probe);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------------------------------

    /// All runtimes FSL could use, best first.
    public static List<Candidate> findAll() {
        Map<String, Candidate> found = new LinkedHashMap<>();

        // 1. runtimes bundled in the release package
        Path launcher = launcherDirectory();
        for (String name : bundledDirectoryNames()) {
            addCandidate(found, launcher.resolve(name), true);
            Path parent = launcher.getParent();
            if (parent != null)
                addCandidate(found, parent.resolve(name), true);
        }

        // 2. runtimes downloaded earlier by FSL itself
        addChildren(found, localHome().resolve("java"));
        addChildren(found, userHome().resolve("java"));

        // 3. runtimes the launcher itself installed under `java` directories of the cache
        addChildren(found, localHome().resolve("cache").resolve("java"));

        // 4. explicitly configured / current runtimes
        addCandidate(found, pathOrNull(System.getenv("HMCL_JAVA_HOME")), false);
        addCandidate(found, pathOrNull(System.getenv("JAVA_HOME")), false);
        addCandidate(found, pathOrNull(System.getProperty("java.home")), false);

        List<Candidate> result = new ArrayList<>(found.values());
        result.sort(Comparator
                .comparing(Candidate::isBundled).reversed()
                .thenComparing(candidate -> -candidate.getFeatureVersion()));
        return result;
    }

    private static List<String> bundledDirectoryNames() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            names.add("jre-arm64");
        } else if (arch.equals("x86") || arch.equals("i386") || arch.equals("i686")) {
            names.add("jre-x86");
        } else {
            names.add("jre-x64");
        }
        names.add("jre");
        names.add("runtime");
        return names;
    }

    private static void addChildren(Map<String, Candidate> found, Path parent) {
        if (parent == null || !Files.isDirectory(parent))
            return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path child : stream)
                addCandidate(found, child, false);
        } catch (IOException ignored) {
        }
    }

    private static void addCandidate(Map<String, Candidate> found, Path home, boolean bundled) {
        Candidate candidate = inspect(home, bundled);
        if (candidate == null)
            return;
        String key = candidate.getHome().toString().toLowerCase(Locale.ROOT);
        Candidate previous = found.get(key);
        if (previous == null || (bundled && !previous.isBundled()))
            found.put(key, candidate);
    }

    private static Path pathOrNull(String value) {
        if (value == null || value.trim().isEmpty())
            return null;
        try {
            return Paths.get(value.trim());
        } catch (Throwable e) {
            return null;
        }
    }

    /// Inspects a directory and returns a [`Candidate`] when it looks like a Java runtime.
    public static Candidate inspect(Path home) {
        return inspect(home, false);
    }

    private static Candidate inspect(Path home, boolean bundled) {
        if (home == null)
            return null;
        Path normalized;
        try {
            normalized = home.toAbsolutePath().normalize();
        } catch (Throwable e) {
            return null;
        }
        if (!Files.isDirectory(normalized))
            return null;

        Path executable = findJavaExecutable(normalized);
        if (executable == null) {
            // macOS bundle layout: <home>/Contents/Home/bin/java
            Path macHome = normalized.resolve("Contents").resolve("Home");
            if (findJavaExecutable(macHome) != null) {
                normalized = macHome;
            } else {
                return null;
            }
        }

        String version = readReleaseVersion(normalized);
        int feature = featureVersionOf(version);
        if (feature < 0)
            return null;

        return new Candidate(normalized, feature, version, bundled);
    }

    private static Path findJavaExecutable(Path home) {
        if (home == null)
            return null;
        boolean windows = osName().contains("windows");
        Path candidate = home.resolve("bin").resolve(windows ? "javaw.exe" : "java");
        if (Files.isRegularFile(candidate))
            return candidate;
        candidate = home.resolve("bin").resolve(windows ? "java.exe" : "java");
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    /// Reads `JAVA_VERSION` from the `release` file of a runtime, e.g. `21.0.12`.
    public static String readReleaseVersion(Path home) {
        return readReleaseEntry(home, "JAVA_VERSION");
    }

    /// Reads one `KEY="value"` entry from the `release` file of a runtime.
    public static String readReleaseEntry(Path home, String key) {
        if (home == null)
            return null;
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release))
            return null;
        try (java.io.BufferedReader reader = Files.newBufferedReader(release, java.nio.charset.StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int index = line.indexOf('=');
                if (index <= 0)
                    continue;
                if (!key.equals(line.substring(0, index).trim()))
                    continue;
                String value = line.substring(index + 1).trim();
                if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                    value = value.substring(1, value.length() - 1);
                return value;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /// Module names shipped by a runtime, as recorded in its `release` file (jlink images, JDK and JRE
    /// images all write a `MODULES` entry). Returns `null` when the runtime does not report them.
    public static java.util.Set<String> readModules(Path home) {
        String modules = readReleaseEntry(home, "MODULES");
        if (modules == null || modules.trim().isEmpty())
            return null;
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        for (String module : modules.trim().split("\\s+")) {
            if (!module.isEmpty())
                result.add(module);
        }
        return result;
    }

    /// Checks whether a runtime on disk provides all `required` modules.
    ///
    /// Runtimes that do not report their module list are considered acceptable, the caller can only
    /// inspect what the runtime exposes.
    public static boolean hasModules(Path home, java.util.Collection<String> required) {
        java.util.Set<String> modules = readModules(home);
        if (modules == null)
            return true;
        return modules.containsAll(required);
    }

    /// Converts a Java version string into its feature version (`21.0.12` to `21`, `1.8.0_402` to `8`).
    public static int featureVersionOf(String version) {
        if (version == null)
            return -1;
        int end = 0;
        while (end < version.length() && Character.isDigit(version.charAt(end)))
            end++;
        if (end == 0)
            return -1;
        int major;
        try {
            major = Integer.parseInt(version.substring(0, end));
        } catch (NumberFormatException e) {
            return -1;
        }
        if (major > 1)
            return major;
        int start = end + 1;
        end = start;
        while (end < version.length() && Character.isDigit(version.charAt(end)))
            end++;
        if (end == start)
            return -1;
        return Integer.parseInt(version.substring(start, end));
    }

    /// Feature version of the JVM FSL is currently running on.
    public static int currentFeatureVersion() {
        return featureVersionOf(System.getProperty("java.version"));
    }

    /// The runtime FSL is currently running on, or `null` when it cannot be determined.
    public static Path currentJavaHome() {
        try {
            return Paths.get(System.getProperty("java.home")).toAbsolutePath().normalize();
        } catch (Throwable e) {
            return null;
        }
    }

    /// Picks the most suitable runtime that is at least [`MINIMUM_FEATURE_VERSION`] and is not `exclude`.
    public static Candidate findBest(Path exclude) {
        return findBest(MINIMUM_FEATURE_VERSION, Integer.MAX_VALUE, exclude);
    }

    /// Picks the most suitable runtime within `[minFeature, maxFeature]` that is not `exclude`.
    public static Candidate findBest(int minFeature, int maxFeature, Path exclude) {
        return findBest(minFeature, maxFeature, null, exclude);
    }

    /// Picks the most suitable runtime within `[minFeature, maxFeature]` that is not `exclude` and
    /// provides all `requiredModules` (when they are reported by the runtime).
    public static Candidate findBest(int minFeature, int maxFeature, java.util.Collection<String> requiredModules,
                                     Path exclude) {
        Path excluded = exclude == null ? null : exclude.toAbsolutePath().normalize();
        for (Candidate candidate : findAll()) {
            if (excluded != null && excluded.equals(candidate.getHome()))
                continue;
            if (candidate.getFeatureVersion() < minFeature || candidate.getFeatureVersion() > maxFeature)
                continue;
            if (!candidate.isUsable())
                continue;
            if (requiredModules != null && !hasModules(candidate.getHome(), requiredModules))
                continue;
            return candidate;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Download
    // ---------------------------------------------------------------------------------------------

    /// Downloads a complete runtime of `featureVersion` into [`installRoot`] and returns it.
    ///
    /// Mirrors are tried in order; every failure is collected so the caller can show a reason.
    public static Candidate download(int featureVersion, ProgressListener listener) throws IOException {
        Path root = installRoot();
        String arch = architectureName();

        Path target = root.resolve("jre-" + featureVersion + "-" + arch);
        Candidate existing = inspect(target);
        if (existing != null && existing.getFeatureVersion() >= featureVersion) {
            return existing;
        }

        List<String> failures = new ArrayList<>();
        for (String url : downloadUrls(featureVersion, arch)) {
            Path archive = root.resolve(".fsl-jre-" + featureVersion + "-" + arch + ".zip");
            Path staging = root.resolve(".fsl-extract-" + UUID.randomUUID());
            try {
                notifyMessage(listener, url);
                Files.createDirectories(root);
                downloadFile(url, archive, listener);

                notifyMessage(listener, "extracting");
                extract(archive, staging, listener);

                Path home = findRuntimeHome(staging);
                if (home == null)
                    throw new IOException("The downloaded archive does not contain a Java runtime");

                deleteRecursively(target);
                Files.createDirectories(target);
                moveContents(home, target);
                deleteRecursively(staging);
                Files.deleteIfExists(archive);

                Candidate candidate = inspect(target);
                if (candidate == null)
                    throw new IOException("The downloaded runtime is incomplete");
                return candidate;
            } catch (IOException e) {
                failures.add(url + " -> " + e.getMessage());
                deleteQuietly(staging);
                deleteQuietly(archive);
            }
        }

        StringBuilder message = new StringBuilder();
        for (String failure : failures)
            message.append(failure).append('\n');
        throw new IOException(message.toString().trim());
    }

    private static void notifyMessage(ProgressListener listener, String message) {
        if (listener != null) {
            try {
                listener.onMessage(message);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void notifyProgress(ProgressListener listener, long downloaded, long total) {
        if (listener != null) {
            try {
                listener.onProgress(downloaded, total);
            } catch (Throwable ignored) {
            }
        }
    }

    private static List<String> downloadUrls(int featureVersion, String arch) {
        List<String> urls = new ArrayList<>();
        String os = osName();

        if (os.contains("windows")) {
            if ("x86".equals(arch)) {
                urls.add(String.format(ADOPTIUM_URL, featureVersion, "windows", "x86"));
            } else if ("arm64".equals(arch)) {
                urls.add(String.format(MICROSOFT_JDK_URL, featureVersion, "windows-aarch64"));
                urls.add(String.format(ADOPTIUM_URL, featureVersion, "windows", "aarch64"));
            } else {
                urls.add(String.format(MICROSOFT_JDK_URL, featureVersion, "windows-x64"));
                urls.add(String.format(ADOPTIUM_URL, featureVersion, "windows", "x64"));
            }
        } else if (os.contains("mac")) {
            String adoptiumArch = "arm64".equals(arch) ? "aarch64" : "x64";
            urls.add(String.format(ADOPTIUM_URL, featureVersion, "mac", adoptiumArch));
        } else {
            String adoptiumArch;
            switch (arch) {
                case "arm64":
                    adoptiumArch = "aarch64";
                    break;
                case "x86":
                    adoptiumArch = "x86";
                    break;
                default:
                    adoptiumArch = "x64";
                    break;
            }
            urls.add(String.format(ADOPTIUM_URL, featureVersion, "linux", adoptiumArch));
        }

        return urls;
    }

    private static String architectureName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64"))
            return "arm64";
        if (arch.equals("x86") || arch.equals("i386") || arch.equals("i686"))
            return "x86";
        return "x64";
    }

    private static String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    private static void downloadFile(String url, Path target, ProgressListener listener) throws IOException {
        URL current = new URL(url);
        for (int redirects = 0; redirects < 6; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "FSL/" + launcherVersion());

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null)
                    throw new IOException("Redirect without location from " + current);
                String resolved = new URL(current, location).toString();
                if (resolved.startsWith("http://") && current.toString().startsWith("https://"))
                    resolved = "https://" + resolved.substring("http://".length());
                current = new URL(resolved);
                continue;
            }

            if (code != HttpURLConnection.HTTP_OK)
                throw new IOException("HTTP " + code + " from " + current);

            long total = connection.getContentLengthLong();
            if (total <= 0)
                total = -1;

            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long downloaded = 0;
                int read;
                long lastReport = 0;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                    downloaded += read;
                    if (downloaded - lastReport >= 256 * 1024) {
                        lastReport = downloaded;
                        notifyProgress(listener, downloaded, total);
                    }
                }
                notifyProgress(listener, downloaded, total);
            } finally {
                connection.disconnect();
            }

            if (total > 0) {
                long size = Files.size(target);
                if (size != total)
                    throw new IOException("Truncated download: " + size + " of " + total + " bytes");
            }
            return;
        }

        throw new IOException("Too many redirects for " + url);
    }

    private static String launcherVersion() {
        // The boot module has no access to the launcher metadata, a stable user agent is enough here.
        return "FSL";
    }

    private static void extract(Path archive, Path destination, ProgressListener listener) throws IOException {
        Files.createDirectories(destination);
        byte[] buffer = new byte[64 * 1024];
        long total = Files.size(archive);
        long processed = 0;

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination))
                    throw new IOException("Illegal archive entry: " + entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }

                Path parent = target.getParent();
                if (parent != null)
                    Files.createDirectories(parent);

                try (OutputStream output = Files.newOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        output.write(buffer, 0, read);
                        processed += read;
                    }
                }
                notifyProgress(listener, Math.min(processed, total), total);
            }
        }
    }

    private static Path findRuntimeHome(Path root) {
        if (findJavaExecutable(root) != null)
            return root;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child))
                    continue;
                if (findJavaExecutable(child) != null)
                    return child;
                if (findJavaExecutable(child.resolve("Contents").resolve("Home")) != null)
                    return child;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static void moveContents(Path source, Path destination) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
            for (Path child : stream) {
                Path target = destination.resolve(child.getFileName().toString());
                Files.move(child, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path))
            return;
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
    }

    private static void deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Relaunch
    // ---------------------------------------------------------------------------------------------

    /// Number of recovery attempts already performed, read from the environment of this process.
    public static int currentAttempts() {
        String value = System.getenv(ENV_ATTEMPTS);
        if (value == null)
            return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /// Restarts FSL with `candidate` and terminates the current JVM.
    ///
    /// The restart keeps the JVM options FSL was started with and forwards the original arguments,
    /// so the launcher ends up in exactly the state the user asked for.
    public static void restart(Candidate candidate, String[] args) throws IOException {
        if (candidate == null || !candidate.isUsable())
            throw new IOException("The selected Java runtime is not usable");

        String jarPath = thisJarPath();
        if (jarPath == null)
            throw new IOException("Unable to locate the launcher jar");

        List<String> command = new ArrayList<>();
        command.add(candidate.getJavaExecutable().toString());
        for (String argument : preservedJvmArguments())
            command.add(argument);
        command.add("-jar");
        command.add(jarPath);
        if (args != null) {
            for (String argument : args)
                command.add(argument);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(launcherDirectory().toFile());
        builder.environment().put("HMCL_JAVA_HOME", candidate.getHome().toString());
        builder.environment().put("JAVA_HOME", candidate.getHome().toString());
        builder.environment().put(ENV_ATTEMPTS, Integer.toString(currentAttempts() + 1));
        builder.inheritIO();

        System.out.println("Restarting FSL with Java " + candidate.getVersion() + " (" + candidate.getHome() + ")");
        builder.start();
        System.exit(0);
    }

    private static List<String> preservedJvmArguments() {
        List<String> result = new ArrayList<>();
        try {
            for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                String lower = argument.toLowerCase(Locale.ROOT);
                // debugger / agent attach options cannot be reused safely in a new process
                if (lower.startsWith("-agentlib") || lower.startsWith("-javaagent")
                        || lower.startsWith("-xrunjdwp") || lower.startsWith("-xdebug")
                        || lower.startsWith("-dsa") || lower.startsWith("-djava.class.path")
                        || lower.contains(ENV_ATTEMPTS.toLowerCase(Locale.ROOT)))
                    continue;
                result.add(argument);
            }
        } catch (Throwable ignored) {
        }
        return result;
    }
}
