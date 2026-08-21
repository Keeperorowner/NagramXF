package com.exteragram.messenger.plugins.pip;

import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import tw.nekomimi.nekogram.utils.HttpClient;

public class PipController {
    public interface InstallerDelegate {
        boolean isCancelled();
        void onProgress(String text);
    }

    private static final String ENV_SYS_PLATFORM = "linux";
    private static final String ENV_PLATFORM_SYSTEM = "Linux";
    private static final String ENV_OS_NAME = "posix";
    private static final String ENV_PYTHON_VERSION = "3.11";

    private static final long MAX_WHEEL_BYTES = 262144000L;
    private static final long MAX_METADATA_BYTES = 4194304L;
    private static final long MAX_REGISTRY_BYTES = 4194304L;
    private static final long MAX_ARCHIVE_ENTRIES = 50000L;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 524288000L;
    private static final long MAX_SINGLE_FILE_BYTES = MAX_WHEEL_BYTES;
    private static final double MAX_COMPRESSION_RATIO = 500.0;

    private static final Pattern NORMALIZE = Pattern.compile("[-_.]+");
    private static final Pattern SPEC_PATTERN = Pattern.compile("^(~=|==|!=|>=|<=|>|<)\\s*(.+)$");
    private static final Pattern MARKER_ATOM = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*(==|!=|>=|<=|>|<)\\s*['\"]([^'\"]+)['\"]$");

    private static final Set<String> PRE_RELEASE_TAGS = new HashSet<>(Arrays.asList(
            "a", "alpha", "b", "beta", "c", "rc", "pre", "preview", "dev"));
    // Packages already bundled with the Chaquopy runtime (assets/chaquopy/requirements-*.imy);
    // a plugin __requirements__ on one of these must not trigger a PyPI download.
    private static final Set<String> PREINSTALLED_PACKAGES = new HashSet<>(Arrays.asList(
            "requests", "urllib3", "certifi", "charset-normalizer", "idna",
            "lxml", "pillow", "beautifulsoup4", "soupsieve", "pyyaml",
            "packaging", "debugpy", "typing-extensions"));
    private static final int MAX_HTTP_RETRIES = 3;

    public static final PipController INSTANCE = new PipController();

    private final OkHttpClient client = HttpClient.INSTANCE.getInstance();
    private final Gson gson = new Gson();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<String>>> registry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> installLocks = new ConcurrentHashMap<>();

    private PipController() {
        loadRegistry();
    }

    public List<String> installDependencies(List<String> requirements, String pluginId, InstallerDelegate delegate) throws Exception {
        LinkedHashSet<PackageRef> resolved = new LinkedHashSet<>();
        LinkedHashSet<PackageRef> sessionInstalled = new LinkedHashSet<>();
        try {
            notifyProgress(delegate, LocaleController.getString(R.string.PluginDependencyPreparing));
            if (requirements != null) {
                for (String requirementLine : requirements) {
                    Requirement requirement = parseRequirement(requirementLine);
                    if (requirement == null || TextUtils.isEmpty(requirement.name)) {
                        continue;
                    }
                    if (PREINSTALLED_PACKAGES.contains(requirement.name)) {
                        continue;
                    }
                    resolveAndInstall(requirement, resolved, pluginId, delegate, sessionInstalled);
                }
            }
            updateRegistryForPlugin(pluginId, resolved);
            saveRegistry();
        } catch (Throwable t) {
            rollbackSession(sessionInstalled);
            throw t;
        } finally {
            installLocks.clear();
        }
        ArrayList<String> result = new ArrayList<>();
        for (PackageRef ref : resolved) {
            result.add(getVersionDir(ref.name, ref.version).getAbsolutePath());
        }
        return result;
    }

    public void uninstallDependencies(String pluginId) {
        boolean changed = false;
        for (Map.Entry<String, ConcurrentHashMap<String, Set<String>>> packageEntry : registry.entrySet()) {
            ConcurrentHashMap<String, Set<String>> versions = packageEntry.getValue();
            for (Map.Entry<String, Set<String>> versionEntry : versions.entrySet()) {
                if (versionEntry.getValue().remove(pluginId)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            cleanupInternal();
            saveRegistry();
        }
    }

    public void cleanup() {
        cleanupInternal();
        saveRegistry();
    }

    private synchronized void loadRegistry() {
        registry.clear();
        File file = getRegistryFile();
        if (!file.exists()) {
            return;
        }
        if (file.length() > MAX_REGISTRY_BYTES) {
            FileLog.e("Pip registry is too large to read: " + file.length() + " bytes");
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonObject root = gson.fromJson(reader, JsonObject.class);
            if (root == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> pkgEntry : root.entrySet()) {
                ConcurrentHashMap<String, Set<String>> versions = new ConcurrentHashMap<>();
                JsonObject versionsObject = pkgEntry.getValue().getAsJsonObject();
                for (Map.Entry<String, JsonElement> versionEntry : versionsObject.entrySet()) {
                    HashSet<String> plugins = new HashSet<>();
                    JsonArray pluginsArray = versionEntry.getValue().getAsJsonArray();
                    for (JsonElement element : pluginsArray) {
                        plugins.add(element.getAsString());
                    }
                    versions.put(versionEntry.getKey(), plugins);
                }
                registry.put(pkgEntry.getKey(), versions);
            }
        } catch (Throwable t) {
            FileLog.e("Failed to load pip registry", t);
        }
    }

    private synchronized void saveRegistry() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, ConcurrentHashMap<String, Set<String>>> pkgEntry : registry.entrySet()) {
                JsonObject versionsObject = new JsonObject();
                for (Map.Entry<String, Set<String>> versionEntry : pkgEntry.getValue().entrySet()) {
                    JsonArray pluginsArray = new JsonArray();
                    for (String pluginId : versionEntry.getValue()) {
                        pluginsArray.add(pluginId);
                    }
                    versionsObject.add(versionEntry.getKey(), pluginsArray);
                }
                root.add(pkgEntry.getKey(), versionsObject);
            }
            File file = getRegistryFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
                outputStream.write(gson.toJson(root).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            FileLog.e("Failed to save pip registry", t);
        }
    }

    private void resolveAndInstall(Requirement requirement, LinkedHashSet<PackageRef> resolved,
                                   String pluginId, InstallerDelegate delegate,
                                   LinkedHashSet<PackageRef> sessionInstalled) throws Exception {
        checkCancelled(delegate);
        notifyProgress(delegate, LocaleController.formatString(R.string.PluginDependencyResolving, requirement.name));
        Object lock = installLocks.computeIfAbsent(requirement.name, key -> new Object());
        synchronized (lock) {
            String selectedVersion = findUsableVersion(requirement.name, requirement.specs);
            if (selectedVersion == null) {
                selectedVersion = installPackage(requirement, delegate);
                sessionInstalled.add(new PackageRef(requirement.name, selectedVersion));
            }
            PackageRef ref = new PackageRef(requirement.name, selectedVersion);
            resolved.removeIf(existing -> existing.name.equals(ref.name));
            List<Requirement> dependencies = readInstalledDependencies(ref.name, ref.version);
            for (Requirement dependency : dependencies) {
                resolveAndInstall(dependency, resolved, pluginId, delegate, sessionInstalled);
            }
            resolved.add(ref);
        }
    }
    private boolean isPreReleaseVersion(String version) {
        if (TextUtils.isEmpty(version)) {
            return false;
        }
        String lower = version.toLowerCase(Locale.US);
        for (String part : lower.split("[._\\-]+")) {
            String token = part.replaceAll("\\d", "");
            if (PRE_RELEASE_TAGS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private List<String> filterPreReleases(List<String> versions, List<VersionSpec> specs) {
        if (versions == null || versions.isEmpty() || specsAllowPreRelease(specs)) {
            return versions;
        }
        ArrayList<String> result = new ArrayList<>();
        for (String version : versions) {
            if (!isPreReleaseVersion(version)) {
                result.add(version);
            }
        }
        return result.isEmpty() ? versions : result;
    }

    private boolean specsAllowPreRelease(List<VersionSpec> specs) {
        if (specs == null) {
            return false;
        }
        for (VersionSpec spec : specs) {
            if (spec != null && isPreReleaseVersion(spec.version)) {
                return true;
            }
        }
        return false;
    }

    private String findUsableVersion(String name, List<VersionSpec> specs) throws IOException {
        ArrayList<String> candidates = new ArrayList<>();
        ConcurrentHashMap<String, Set<String>> versions = registry.get(name);
        if (versions != null) {
            for (String version : versions.keySet()) {
                if (checkVersionSatisfies(version, specs) && getVersionDir(name, version).exists()) {
                    candidates.add(version);
                }
            }
        }
        File packageDir = getPackageDir(name);
        File[] children = packageDir.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                String version = child.getName();
                if (checkVersionSatisfies(version, specs) && !candidates.contains(version)) {
                    candidates.add(version);
                }
            }
        }
        candidates = new ArrayList<>(filterPreReleases(candidates, specs));
        candidates.sort((left, right) -> compareVersions(right, left));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private String installPackage(Requirement requirement, InstallerDelegate delegate) throws Exception {
        checkCancelled(delegate);
        JsonObject root = getJson("https://pypi.org/pypi/" + URLEncoder.encode(requirement.name, StandardCharsets.UTF_8) + "/json");
        if (root == null || !root.has("releases")) {
            throw new IOException("Failed to fetch metadata for package: " + requirement.name);
        }
        JsonObject releases = root.getAsJsonObject("releases");
        ArrayList<String> candidateVersions = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : releases.entrySet()) {
            if (checkVersionSatisfies(entry.getKey(), requirement.specs)) {
                candidateVersions.add(entry.getKey());
            }
        }
        candidateVersions = new ArrayList<>(filterPreReleases(candidateVersions, requirement.specs));
        candidateVersions.sort((left, right) -> compareVersions(right, left));
        UnsupportedWheelIssue bestUnsupportedIssue = null;
        for (String version : candidateVersions) {
            JsonArray files = releases.getAsJsonArray(version);
            if (files == null) {
                continue;
            }
            WheelSelection selection = pickWheelFile(files);
            WheelFile wheel = selection != null ? selection.supportedWheel : null;
            if (wheel == null) {
                if (selection != null && selection.issue != null && (bestUnsupportedIssue == null || compareVersions(version, bestUnsupportedIssue.version) > 0)) {
                    bestUnsupportedIssue = selection.issue.withVersion(version);
                }
                continue;
            }
            downloadAndExtract(requirement.name, version, wheel, delegate);
            return version;
        }
        if (bestUnsupportedIssue != null) {
            throw new UnsupportedDependencyException(requirement.name, buildUnsupportedRequirementMessage(requirement, bestUnsupportedIssue));
        }
        throw new IOException(LocaleController.formatString(R.string.PluginDependencyNoCompatibleWheel, requirement.raw));
    }

    private WheelSelection pickWheelFile(JsonArray files) {
        WheelFile selected = null;
        UnsupportedWheelIssue issue = new UnsupportedWheelIssue();
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            if (!file.has("packagetype")) {
                continue;
            }
            String packageType = file.get("packagetype").getAsString();
            if ("sdist".equals(packageType)) {
                issue.hasSourceDistribution = true;
                if (TextUtils.isEmpty(issue.exampleFilename) && file.has("filename")) {
                    issue.exampleFilename = file.get("filename").getAsString();
                }
                continue;
            }
            if (!"bdist_wheel".equals(packageType)) {
                continue;
            }
            if (file.has("yanked") && file.get("yanked").getAsBoolean()) {
                continue;
            }
            issue.hasWheel = true;
            String filename = file.get("filename").getAsString();
            if (!isSupportedWheel(filename)) {
                issue.hasOnlyNativeOrPlatformWheel = true;
                if (TextUtils.isEmpty(issue.exampleFilename)) {
                    issue.exampleFilename = filename;
                }
                continue;
            }
            String url = file.get("url").getAsString();
            String sha256 = null;
            if (file.has("digests") && file.get("digests").isJsonObject()) {
                JsonObject digests = file.getAsJsonObject("digests");
                if (digests.has("sha256")) {
                    sha256 = digests.get("sha256").getAsString();
                }
            }
            selected = new WheelFile(filename, url, sha256);
            if (filename.contains("py3-none-any")) {
                break;
            }
        }
        if (selected != null) {
            return new WheelSelection(selected, null);
        }
        return new WheelSelection(null, issue.isMeaningful() ? issue : null);
    }

    private boolean isSupportedWheel(String filename) {
        if (TextUtils.isEmpty(filename)) {
            return false;
        }
        String lower = filename.toLowerCase(Locale.US);
        return lower.endsWith(".whl")
                && (lower.contains("py3-none-any") || lower.contains("py2.py3-none-any") || lower.contains("py311-none-any"));
    }

    private void downloadAndExtract(String name, String version, WheelFile wheel, InstallerDelegate delegate) throws Exception {
        File versionDir = getVersionDir(name, version);
        File stagingDir = new File(getLibsDir(), ".staging-" + normalizePackageName(name) + "-" + System.currentTimeMillis());
        File tempFile = new File(getTempDir(), normalizePackageName(name) + "-" + version + ".whl");
        try {
            if (!stagingDir.mkdirs()) {
                throw new IOException("Failed to create staging directory for " + name + " " + version);
            }
            File extractDir = new File(stagingDir, "extract");
            if (!extractDir.mkdirs()) {
                throw new IOException("Failed to create staging directory for " + name + " " + version);
            }
            Request request = new Request.Builder().url(wheel.url).build();
            try (Response response = executeWithRetry(request, delegate)) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("Failed to download wheel: " + wheel.url);
                }
                long totalBytes = response.body().contentLength();
                if (totalBytes > MAX_WHEEL_BYTES) {
                    throw new IOException("Wheel for " + name + " is too large: " + totalBytes + " bytes");
                }
                long downloadedBytes = 0L;
                long lastProgressTimestamp = 0L;
                try (InputStream inputStream = response.body().byteStream();
                     FileOutputStream outputStream = new FileOutputStream(tempFile, false)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = inputStream.read(buffer)) != -1) {
                        checkCancelled(delegate);
                        downloadedBytes += read;
                        if (downloadedBytes > MAX_WHEEL_BYTES) {
                            throw new IOException("Wheel for " + name + " exceeds the maximum allowed size of " + MAX_WHEEL_BYTES + " bytes");
                        }
                        outputStream.write(buffer, 0, read);
                        long now = System.currentTimeMillis();
                        if (delegate != null && totalBytes > 0 && (now - lastProgressTimestamp >= 250L || downloadedBytes == totalBytes)) {
                            lastProgressTimestamp = now;
                            int percent = (int) Math.max(0L, Math.min(100L, (downloadedBytes * 100L) / totalBytes));
                            delegate.onProgress(LocaleController.formatString(R.string.PluginDependencyDownloadingProgress, name + " " + version, percent));
                        }
                    }
                }
            }
            if (!TextUtils.isEmpty(wheel.sha256)) {
                String actual = sha256(tempFile);
                if (!wheel.sha256.equalsIgnoreCase(actual)) {
                    throw new IOException("Wheel checksum mismatch for " + wheel.filename);
                }
            }
            notifyProgress(delegate, LocaleController.formatString(R.string.PluginDependencyInstalling, name, version));
            checkCancelled(delegate);
            unzip(tempFile, extractDir);
            checkCancelled(delegate);
            if (versionDir.exists()) {
                deleteRecursive(versionDir);
            }
            File parent = versionDir.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!extractDir.renameTo(versionDir)) {
                deleteRecursive(versionDir);
                throw new IOException("Failed to install " + name + " " + version);
            }
        } finally {
            tempFile.delete();
            deleteRecursive(stagingDir);
        }
    }

    private List<Requirement> readInstalledDependencies(String name, String version) throws IOException {
        File versionDir = getVersionDir(name, version);
        File[] distInfos = versionDir.listFiles(file -> file.isDirectory() && file.getName().endsWith(".dist-info"));
        if (distInfos == null) {
            return Collections.emptyList();
        }
        ArrayList<Requirement> result = new ArrayList<>();
        for (File distInfo : distInfos) {
            File metadata = new File(distInfo, "METADATA");
            if (!metadata.exists()) {
                continue;
            }
            try {
                if (metadata.length() > MAX_METADATA_BYTES) {
                    throw new IOException("METADATA is too large to read: " + metadata.length() + " bytes");
                }
                List<String> lines = java.nio.file.Files.readAllLines(metadata.toPath(), StandardCharsets.UTF_8);
                StringBuilder continuation = new StringBuilder();
                for (String line : lines) {
                    if (line.startsWith(" ") && continuation.length() > 0) {
                        continuation.append(line.trim());
                        continue;
                    }
                    if (continuation.length() > 0) {
                        appendRequirementFromMetadata(continuation.toString(), result);
                        continuation.setLength(0);
                    }
                    if (line.startsWith("Requires-Dist:")) {
                        continuation.append(line.substring("Requires-Dist:".length()).trim());
                    }
                }
                if (continuation.length() > 0) {
                    appendRequirementFromMetadata(continuation.toString(), result);
                }
            } catch (Throwable t) {
                FileLog.e("Failed to parse wheel metadata for " + name + ' ' + version, t);
            }
        }
        return result;
    }

    private void appendRequirementFromMetadata(String line, List<Requirement> result) {
        Requirement requirement = parseRequirement(line);
        if (requirement != null && !TextUtils.isEmpty(requirement.name)) {
            result.add(requirement);
        }
    }
    private Requirement parseRequirement(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        String line = raw.trim();
        String marker = null;
        int markerIndex = line.indexOf(';');
        if (markerIndex >= 0) {
            marker = line.substring(markerIndex + 1).trim();
            line = line.substring(0, markerIndex).trim();
        }
        if (!markerMatches(marker)) {
            return null;
        }
        int bracket = line.indexOf('[');
        if (bracket >= 0) {
            int endBracket = line.indexOf(']', bracket);
            if (endBracket > bracket) {
                line = line.substring(0, bracket) + line.substring(endBracket + 1);
            }
        }
        String name = extractPackageName(line);
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        String specsPart = line.substring(name.length()).trim();
        if (specsPart.startsWith("(") && specsPart.endsWith(")")) {
            specsPart = specsPart.substring(1, specsPart.length() - 1).trim();
        }
        ArrayList<VersionSpec> specs = new ArrayList<>();
        if (!TextUtils.isEmpty(specsPart)) {
            for (String part : specsPart.split(",")) {
                String piece = part.trim();
                if (piece.isEmpty()) {
                    continue;
                }
                Matcher matcher = SPEC_PATTERN.matcher(piece);
                if (matcher.find()) {
                    String op = matcher.group(1);
                    String version = matcher.group(2).trim();
                    if ("~=".equals(op)) {
                        specs.add(new VersionSpec(">=", version));
                        String upper = compatibleUpperBound(version);
                        if (!TextUtils.isEmpty(upper)) {
                            specs.add(new VersionSpec("<", upper));
                        }
                    } else {
                        specs.add(new VersionSpec(op, version));
                    }
                }
            }
        }
        return new Requirement(normalizePackageName(name), specs, raw);
    }

    private String extractPackageName(String line) {
        int index = 0;
        while (index < line.length()) {
            char ch = line.charAt(index);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '.') {
                index++;
            } else {
                break;
            }
        }
        return index > 0 ? line.substring(0, index) : null;
    }

    private boolean markerMatches(String marker) {
        if (TextUtils.isEmpty(marker)) {
            return true;
        }
        for (String orPart : marker.split("\\s+or\\s+")) {
            boolean ok = true;
            for (String andPart : orPart.split("\\s+and\\s+")) {
                if (!evaluateMarkerAtom(andPart.trim())) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }

    private boolean evaluateMarkerAtom(String atom) {
        Matcher matcher = MARKER_ATOM.matcher(atom);
        if (!matcher.find()) {
            return false;
        }
        String key = matcher.group(1);
        String op = matcher.group(2);
        String expected = matcher.group(3);
        String actual;
        switch (key) {
            case "python_version":
            case "python_full_version":
                actual = ENV_PYTHON_VERSION;
                break;
            case "sys_platform":
                actual = ENV_SYS_PLATFORM;
                break;
            case "platform_system":
                actual = ENV_PLATFORM_SYSTEM;
                break;
            case "os_name":
                actual = ENV_OS_NAME;
                break;
            case "extra":
                actual = "";
                break;
            default:
                return false;
        }
        if ("==".equals(op)) return compareVersions(actual, expected) == 0;
        if ("!=".equals(op)) return compareVersions(actual, expected) != 0;
        if (">=".equals(op)) return compareVersions(actual, expected) >= 0;
        if ("<=".equals(op)) return compareVersions(actual, expected) <= 0;
        if (">".equals(op)) return compareVersions(actual, expected) > 0;
        if ("<".equals(op)) return compareVersions(actual, expected) < 0;
        return false;
    }

    private boolean checkVersionSatisfies(String version, List<VersionSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return true;
        }
        for (VersionSpec spec : specs) {
            int cmp = compareVersions(version, spec.version);
            switch (spec.operator) {
                case "==": if (cmp != 0) return false; break;
                case "!=": if (cmp == 0) return false; break;
                case ">=": if (cmp < 0) return false; break;
                case "<=": if (cmp > 0) return false; break;
                case ">": if (cmp <= 0) return false; break;
                case "<": if (cmp >= 0) return false; break;
            }
        }
        return true;
    }

    private int compareVersions(String left, String right) {
        List<String> leftParts = splitVersion(left);
        List<String> rightParts = splitVersion(right);
        int max = Math.max(leftParts.size(), rightParts.size());
        for (int i = 0; i < max; i++) {
            String a = i < leftParts.size() ? leftParts.get(i) : "0";
            String b = i < rightParts.size() ? rightParts.get(i) : "0";
            boolean aNum = a.matches("\\d+");
            boolean bNum = b.matches("\\d+");
            int cmp;
            if (aNum && bNum) {
                cmp = Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            } else {
                cmp = a.compareToIgnoreCase(b);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private List<String> splitVersion(String version) {
        ArrayList<String> result = new ArrayList<>();
        if (TextUtils.isEmpty(version)) {
            result.add("0");
            return result;
        }
        for (String part : version.replace('-', '.').split("\\.")) {
            if (!part.isEmpty()) {
                result.add(part.toLowerCase(Locale.US));
            }
        }
        return result;
    }

    private String compatibleUpperBound(String version) {
        List<String> parts = splitVersion(version);
        if (parts.isEmpty()) {
            return null;
        }
        int pivot = parts.size() > 1 ? parts.size() - 2 : 0;
        ArrayList<String> copy = new ArrayList<>(parts.subList(0, pivot + 1));
        String current = copy.get(pivot);
        if (!current.matches("\\d+")) {
            return null;
        }
        copy.set(pivot, String.valueOf(Integer.parseInt(current) + 1));
        for (int i = pivot + 1; i < parts.size(); i++) {
            copy.add("0");
        }
        return TextUtils.join(".", copy);
    }

    private String normalizePackageName(String value) {
        return NORMALIZE.matcher(value.toLowerCase(Locale.US)).replaceAll("-");
    }
    private void rollbackSession(Set<PackageRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        for (PackageRef ref : refs) {
            ConcurrentHashMap<String, Set<String>> versions = registry.get(ref.name);
            if (versions == null || !versions.containsKey(ref.version)) {
                try {
                    deleteRecursive(getVersionDir(ref.name, ref.version));
                } catch (IOException e) {
                    FileLog.e("Skipped rollback of invalid version path: " + ref.version, e);
                }
            }
        }
    }

    private void checkCancelled(InstallerDelegate delegate) throws InstallationCancelledException {
        if (delegate != null && delegate.isCancelled()) {
            throw new InstallationCancelledException();
        }
    }

    private void notifyProgress(InstallerDelegate delegate, String text) {
        if (delegate != null && !TextUtils.isEmpty(text)) {
            delegate.onProgress(text);
        }
    }

    private String buildUnsupportedRequirementMessage(Requirement requirement, UnsupportedWheelIssue issue) {
        String displayName = requirement.raw != null ? requirement.raw : requirement.name;
        if (issue.hasOnlyNativeOrPlatformWheel) {
            return LocaleController.formatString(R.string.PluginDependencyNativeUnsupported, displayName);
        }
        if (issue.hasSourceDistribution) {
            return LocaleController.formatString(R.string.PluginDependencySourceUnsupported, displayName);
        }
        return LocaleController.formatString(R.string.PluginDependencyNoCompatibleWheel, displayName);
    }

    private void updateRegistryForPlugin(String pluginId, Set<PackageRef> refs) {
        for (ConcurrentHashMap<String, Set<String>> versions : registry.values()) {
            for (Set<String> plugins : versions.values()) {
                plugins.remove(pluginId);
            }
        }
        for (PackageRef ref : refs) {
            ConcurrentHashMap<String, Set<String>> versions = registry.computeIfAbsent(ref.name, key -> new ConcurrentHashMap<>());
            Set<String> plugins = versions.computeIfAbsent(ref.version, key -> ConcurrentHashMap.newKeySet());
            plugins.add(pluginId);
        }
        cleanupInternal();
    }

    private void cleanupInternal() {
        for (Map.Entry<String, ConcurrentHashMap<String, Set<String>>> packageEntry : new ArrayList<>(registry.entrySet())) {
            String packageName = packageEntry.getKey();
            if (PREINSTALLED_PACKAGES.contains(packageName)) {
                continue;
            }
            ConcurrentHashMap<String, Set<String>> versions = packageEntry.getValue();
            for (Map.Entry<String, Set<String>> versionEntry : new ArrayList<>(versions.entrySet())) {
                String version = versionEntry.getKey();
                Set<String> plugins = versionEntry.getValue();
                if (plugins == null || plugins.isEmpty()) {
                    versions.remove(version);
                    try {
                        deleteRecursive(getVersionDir(packageName, version));
                    } catch (IOException e) {
                        FileLog.e("Skipped cleanup of invalid version path: " + version, e);
                    }
                }
            }
            if (versions.isEmpty()) {
                registry.remove(packageName);
                deleteRecursive(getPackageDir(packageName));
            }
        }
    }

    private JsonObject getJson(String url) throws IOException {
        Request request = new Request.Builder().url(url).build();
        try (Response response = executeWithRetry(request, null)) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Unexpected HTTP " + response.code() + " for " + url);
            }
            try (InputStreamReader reader = new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8)) {
                return gson.fromJson(reader, JsonObject.class);
            }
        }
    }

    private Response executeWithRetry(Request request, InstallerDelegate delegate) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < MAX_HTTP_RETRIES; attempt++) {
            checkCancelled(delegate);
            try {
                return client.newCall(request).execute();
            } catch (IOException e) {
                if (e instanceof InstallationCancelledException) {
                    throw e;
                }
                lastError = e;
                if (attempt < MAX_HTTP_RETRIES - 1) {
                    FileLog.w("Pip download attempt " + (attempt + 1) + " failed: " + e.getMessage());
                    try {
                        Thread.sleep((long) (attempt + 1) * 1000L);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("Request failed after retries: " + request.url());
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private void unzip(File archive, File targetDir) throws IOException {
        String targetCanonical = targetDir.getCanonicalPath() + File.separator;
        long entryCount = 0;
        long totalUncompressed = 0;
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zipInputStream.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("Archive exceeds maximum entry count of " + MAX_ARCHIVE_ENTRIES);
                }
                File outFile = new File(targetDir, entry.getName()).getCanonicalFile();
                String outCanonical = outFile.getCanonicalPath() + File.separator;
                if (!outCanonical.startsWith(targetCanonical)) {
                    throw new IOException("Zip entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw new IOException("Failed to create directory for zip entry: " + entry.getName());
                    }
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Failed to create parent directory for zip entry: " + entry.getName());
                    }
                    long entryBytes = 0;
                    long compressedSize = entry.getCompressedSize();
                    try (FileOutputStream outputStream = new FileOutputStream(outFile, false)) {
                        int read;
                        while ((read = zipInputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, read);
                            entryBytes += read;
                            totalUncompressed += read;
                            if (entryBytes > MAX_SINGLE_FILE_BYTES) {
                                throw new IOException("Zip entry exceeds maximum single file size of " + MAX_SINGLE_FILE_BYTES + " bytes: " + entry.getName());
                            }
                            if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                throw new IOException("Archive exceeds maximum total uncompressed size of " + MAX_TOTAL_UNCOMPRESSED_BYTES + " bytes");
                            }
                        }
                    }
                    if (compressedSize >= 0 && entryBytes > 0) {
                        double ratio = (double) entryBytes / (double) compressedSize;
                        if (ratio > MAX_COMPRESSION_RATIO) {
                            throw new IOException("Zip entry exceeds maximum compression ratio of " + MAX_COMPRESSION_RATIO + ": " + entry.getName());
                        }
                    }
                }
                zipInputStream.closeEntry();
            }
        }
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        file.delete();
    }

    private File getRootDir() {
        File root = new File(ApplicationLoader.getFilesDirFixed(), "plugins_pip");
        if (!root.exists()) {
            root.mkdirs();
        }
        return root;
    }

    private File getLibsDir() {
        File libs = new File(getRootDir(), "libs");
        if (!libs.exists()) {
            libs.mkdirs();
        }
        return libs;
    }

    private File getTempDir() {
        File temp = new File(getRootDir(), "temp");
        if (!temp.exists()) {
            temp.mkdirs();
        }
        return temp;
    }

    private File getRegistryFile() {
        return new File(getRootDir(), "registry.json");
    }

    private File getPackageDir(String packageName) {
        return new File(getLibsDir(), normalizePackageName(packageName));
    }

    private File getVersionDir(String packageName, String version) throws IOException {
        validatePathSegment(version);
        return new File(getPackageDir(packageName), version);
    }

    private static void validatePathSegment(String segment) throws IOException {
        if (segment == null || segment.isEmpty()) {
            throw new IOException("Invalid path segment: empty");
        }
        if (segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0 || segment.equals("..")) {
            throw new IOException("Invalid path segment: " + segment);
        }
    }

    private static final class Requirement {
        final String name;
        final List<VersionSpec> specs;
        final String raw;

        Requirement(String name, List<VersionSpec> specs, String raw) {
            this.name = name;
            this.specs = specs;
            this.raw = raw;
        }
    }

    private static final class VersionSpec {
        final String operator;
        final String version;

        VersionSpec(String operator, String version) {
            this.operator = operator;
            this.version = version;
        }
    }

    private static final class PackageRef {
        final String name;
        final String version;

        PackageRef(String name, String version) {
            this.name = name;
            this.version = version;
        }
    }

    private static final class WheelFile {
        final String filename;
        final String url;
        final String sha256;

        WheelFile(String filename, String url, String sha256) {
            this.filename = filename;
            this.url = url;
            this.sha256 = sha256;
        }
    }

    private static final class WheelSelection {
        final WheelFile supportedWheel;
        final UnsupportedWheelIssue issue;

        WheelSelection(WheelFile supportedWheel, UnsupportedWheelIssue issue) {
            this.supportedWheel = supportedWheel;
            this.issue = issue;
        }
    }

    private static final class UnsupportedWheelIssue {
        String version;
        boolean hasWheel;
        boolean hasOnlyNativeOrPlatformWheel;
        boolean hasSourceDistribution;
        String exampleFilename;

        boolean isMeaningful() {
            return hasWheel || hasSourceDistribution;
        }

        UnsupportedWheelIssue withVersion(String version) {
            this.version = version;
            return this;
        }
    }

    public static class InstallationCancelledException extends IOException {
        public InstallationCancelledException() {
            super("Plugin installation cancelled");
        }
    }

    public static class UnsupportedDependencyException extends IOException {
        public final String dependencyName;

        public UnsupportedDependencyException(String dependencyName, String message) {
            super(message);
            this.dependencyName = dependencyName;
        }
    }

}
