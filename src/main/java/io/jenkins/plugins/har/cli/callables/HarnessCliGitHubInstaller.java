package io.jenkins.plugins.har.cli.callables;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.ProxyConfiguration;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolInstallerDescriptor;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import io.jenkins.plugins.har.Constants;
import io.jenkins.plugins.har.cli.HarnessCliInstallation;
import io.jenkins.plugins.har.cli.HarnessOsUtils;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads the Harness CLI from harness/cli GitHub releases and installs it on Jenkins agents.
 *
 * <p>Each release publishes a bundle containing the core {@code harness} binary and the
 * {@code harness-har} Artifact Registry plugin, plus a SHA-256 checksums file:
 * <pre>
 *   harness-bundle_{version}_{os}_{arch}.tar.gz   (linux, darwin)
 *   harness-bundle_{version}_windows_{arch}.zip
 *   harness_{version}_checksums.txt
 *
 *   OS   : linux | darwin | windows
 *   Arch : amd64 | arm64
 * </pre>
 *
 * <p>After extraction the installer registers {@code harness-har} with
 * {@code harness install plugin}, using {@code <tool home>/cli-home} as the CLI home so the
 * installation does not depend on, or modify, the agent user's {@code ~/.harness}.
 *
 * When version is left blank the installer queries the GitHub Releases API
 * to determine the latest version tag, at most once an hour per controller.
 */
public class HarnessCliGitHubInstaller extends ToolInstaller {

    private static final long serialVersionUID = 1L;

    /** Minimum expected binary size – anything smaller is considered a failed download. */
    private static final long MIN_BINARY_SIZE_BYTES = 1024L * 1024L; // 1 MB

    /** Marker file holding the version currently installed in the tool home. */
    private static final String VERSION_MARKER_FILE = ".installed-version";

    /** POSIX mode applied to the downloaded binaries: rwxr-xr-x. */
    private static final int EXECUTABLE_MODE = 0755;

    /** harness/cli also hosts plugin tags (har/v1.3.0) and v0.x tags; only v3+ tags carry the CLI bundle. */
    private static final Pattern RELEASE_TAG = Pattern.compile("^v(\\d+)\\.\\d+\\.\\d+$");
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$");
    private static final int MIN_MAJOR_VERSION = 3;

    /** Total send() attempts on connect/request timeout only (not HTTP 4xx/5xx). */
    private static final int HTTP_MAX_ATTEMPTS = 8;
    private static final long HTTP_RETRY_INITIAL_DELAY_MS = 1_000L;
    private static final long HTTP_RETRY_MAX_DELAY_MS = 16_000L;
    static HttpClient httpClient = ProxyConfiguration.newHttpClientBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private static final long LATEST_VERSION_TTL_MS = TimeUnit.HOURS.toMillis(1);

    private record CachedVersion(String version, long fetchedAtMs) {}

    private static final AtomicReference<CachedVersion> latestVersionCache = new AtomicReference<>();

    private String version;

    @DataBoundConstructor
    public HarnessCliGitHubInstaller(String version) {
        super(null);
        this.version = StringUtils.trimToEmpty(version);
    }

    public String getVersion() {
        return version;
    }

    @DataBoundSetter
    public void setVersion(String version) {
        this.version = StringUtils.trimToEmpty(version);
    }

    // -------------------------------------------------------------------------

    @Override
    public FilePath performInstallation(ToolInstallation tool, Node node, TaskListener log)
            throws IOException, InterruptedException {

        FilePath toolHome = preferredLocation(tool, node);
        if (!toolHome.exists()) {
            toolHome.mkdirs();
        }

        boolean isWindows = !node.createLauncher(log).isUnix();
        FilePath cliPath = toolHome.child(HarnessOsUtils.getBinaryName(isWindows));
        FilePath cliHome = toolHome.child(HarnessCliInstallation.CLI_HOME_DIR);
        FilePath registeredHarPlugin = cliHome.child("bin").child(HarnessOsUtils.getHarPluginBinaryName(isWindows));
        FilePath versionMarker = toolHome.child(VERSION_MARKER_FILE);

        // Cheapest check first: is a usable installation already on disk?
        String installedVersion = cliPath.exists()
                && cliPath.length() > MIN_BINARY_SIZE_BYTES
                && registeredHarPlugin.exists()
                && versionMarker.exists()
                ? versionMarker.readToString().trim()
                : null;

        // Only hit the GitHub API when no version is pinned in the configuration.
        String resolvedVersion = StringUtils.isBlank(version)
                ? resolveLatestVersion(log)
                : normalizeVersion(version);

        if (resolvedVersion.equals(installedVersion)) {
            log.getLogger().println("[Harness CLI] Already installed at " + cliPath.getRemote()
                    + " (v" + resolvedVersion + ") — skipping download.");
            return toolHome;
        }

        // Detect OS + arch on the actual agent node (only needed for a download)
        String osInfo = toolHome.act(new OsArchDetectorCallable());
        String[] osArch = OsArchDetectorCallable.parseOsInfo(osInfo);
        String os = osArch[0];
        String arch = osArch[1];

        log.getLogger().println("[Harness CLI] Agent detected: os=" + os + " arch=" + arch);
        if (!HarnessOsUtils.isSupportedArch(arch)) {
            throw new AbortException("[Harness CLI] Unsupported agent architecture '" + arch
                    + "'. Harness CLI releases are published for amd64 and arm64.");
        }

        boolean zip = "windows".equals(os);
        String archiveName = "harness-bundle_" + resolvedVersion + "_" + os + "_" + arch
                + (zip ? ".zip" : ".tar.gz");
        String downloadUrl = releaseAssetUrl(resolvedVersion, archiveName);

        // Look the release up before deleting anything: a mistyped version must not wipe a working installation.
        String expectedSha256 = fetchExpectedSha256(resolvedVersion, archiveName, log);

        // Build the new installation next to the live one and swap it in only after every step has succeeded,
        // so a failed download, checksum, extract or plugin registration leaves the working install untouched.
        FilePath toolsDir = toolHome.getParent();
        if (toolsDir == null) {
            throw new AbortException("[Harness CLI] Tool home has no parent directory: " + toolHome.getRemote());
        }
        FilePath staging = toolsDir.child(".staging-" + UUID.randomUUID());
        try {
            staging.mkdirs();
            installInto(staging, node, isWindows, zip, archiveName, downloadUrl, expectedSha256, resolvedVersion,
                    cliHome, log);
            String backup = toolHome.act(new SwapDirectories(staging.getRemote()));
            deleteQuietly(toolsDir.child(backup), log);
        } finally {
            deleteQuietly(staging, log);
        }

        log.getLogger().println("[Harness CLI] Installation complete: " + cliPath.getRemote()
                + " (v" + resolvedVersion + ")");

        return toolHome;
    }

    private static void installInto(FilePath staging, Node node, boolean isWindows, boolean zip, String archiveName,
                                    String downloadUrl, String expectedSha256, String resolvedVersion,
                                    FilePath finalCliHome, TaskListener log)
            throws IOException, InterruptedException {
        log.getLogger().println("[Harness CLI] Downloading v" + resolvedVersion + ": " + downloadUrl);
        FilePath archive = staging.child(archiveName);
        String actualSha256 = download(downloadUrl, archive, log);
        if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
            throw new AbortException("[Harness CLI] SHA-256 mismatch for " + archiveName
                    + ": expected " + expectedSha256 + ", got " + actualSha256);
        }
        log.getLogger().println("[Harness CLI] SHA-256 verified for " + archiveName);

        if (zip) {
            archive.unzip(staging);
        } else {
            archive.untar(staging, FilePath.TarCompression.GZIP);
        }
        archive.delete();

        FilePath stagedCli = staging.child(HarnessOsUtils.getBinaryName(isWindows));
        FilePath extractedHarPlugin = staging.child(HarnessOsUtils.getHarPluginBinaryName(isWindows));
        for (FilePath binary : new FilePath[] {stagedCli, extractedHarPlugin}) {
            if (!binary.exists() || binary.length() <= MIN_BINARY_SIZE_BYTES) {
                throw new IOException("[Harness CLI] Could not find '" + binary.getName()
                        + "' after extracting " + archiveName + ". Contents of "
                        + staging.getRemote() + ": " + staging.list());
            }
            if (!isWindows) {
                binary.chmod(EXECUTABLE_MODE);
            }
        }

        FilePath stagedCliHome = staging.child(HarnessCliInstallation.CLI_HOME_DIR);
        registerHarPlugin(node, stagedCli, extractedHarPlugin, stagedCliHome, log);
        extractedHarPlugin.delete();
        repointSpecs(stagedCliHome, finalCliHome);

        staging.child(VERSION_MARKER_FILE).write(resolvedVersion, "UTF-8");
    }

    /**
     * The spec written by {@code harness install plugin} records the absolute path of the plugin binary,
     * which at that point lives in the staging directory.
     */
    private static void repointSpecs(FilePath stagedCliHome, FilePath finalCliHome)
            throws IOException, InterruptedException {
        FilePath specDir = stagedCliHome.child("spec");
        int rewritten = 0;
        for (FilePath spec : specDir.list()) {
            String text = spec.readToString();
            if (text.contains(stagedCliHome.getRemote())) {
                spec.write(text.replace(stagedCliHome.getRemote(), finalCliHome.getRemote()), "UTF-8");
                rewritten++;
            }
        }
        if (rewritten == 0) {
            throw new IOException("[Harness CLI] 'harness install plugin' wrote no spec referencing "
                    + stagedCliHome.getRemote() + " in " + specDir.getRemote());
        }
    }

    private static void deleteQuietly(FilePath dir, TaskListener log) {
        try {
            if (dir.exists()) {
                dir.deleteRecursive();
            }
        } catch (IOException | InterruptedException e) {
            log.getLogger().println("[Harness CLI] Could not remove " + dir.getRemote() + ": " + e.getMessage());
        }
    }

    /**
     * Renames the live tool home aside and the staged directory into its place, restoring the old one
     * if the second rename fails. Returns the name of the old directory, a sibling of the tool home.
     */
    static final class SwapDirectories extends MasterToSlaveFileCallable<String> {
        private static final long serialVersionUID = 1L;
        private final String stagingPath;

        SwapDirectories(String stagingPath) {
            this.stagingPath = stagingPath;
        }

        @Override
        public String invoke(File toolHome, VirtualChannel channel) throws IOException {
            Path target = toolHome.toPath();
            String backupName = ".old-" + UUID.randomUUID();
            Path backup = target.resolveSibling(backupName);
            Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
            try {
                Files.move(Path.of(stagingPath), target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
                throw e;
            }
            return backupName;
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Returns the newest stable CLI release without the leading 'v' (e.g. "v3.13.0" → "3.13.0"),
     * reusing the last answer for {@link #LATEST_VERSION_TTL_MS}: Jenkins resolves a tool several
     * times per build, and unauthenticated GitHub API calls are limited to 60 per hour per IP.
     */
    private static String resolveLatestVersion(TaskListener log)
            throws IOException, InterruptedException {
        CachedVersion cached = latestVersionCache.get();
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs() < LATEST_VERSION_TTL_MS) {
            return cached.version();
        }
        String ver = fetchLatestVersion(log);
        latestVersionCache.set(new CachedVersion(ver, System.currentTimeMillis()));
        return ver;
    }

    /** Lists releases from the GitHub Releases API and returns the newest stable CLI release. */
    private static String fetchLatestVersion(TaskListener log)
            throws IOException, InterruptedException {
        log.getLogger().println("[Harness CLI] Querying GitHub API for latest release...");
        HttpRequest request = ProxyConfiguration.newHttpRequestBuilder(URI.create(Constants.GITHUB_API_RELEASES))
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "harness-jenkins-plugin")
                .GET()
                .build();

        HttpResponse<String> response = sendWithTimeoutRetry(
                request, HttpResponse.BodyHandlers.ofString(), log);
        int status = response.statusCode();
        if (status != 200) {
            throw new IOException("GitHub API returned HTTP " + status
                    + ". Set a specific version in the tool configuration to avoid this call.");
        }

        String ver = latestReleaseVersion(response.body());
        log.getLogger().println("[Harness CLI] Latest version resolved: " + ver);
        return ver;
    }

    /** GitHub lists releases newest first. */
    static String latestReleaseVersion(String releasesJson) throws IOException {
        JSONArray releases;
        try {
            releases = JSONArray.fromObject(releasesJson);
        } catch (JSONException e) {
            throw new IOException("Failed to parse GitHub releases response: " + e.getMessage(), e);
        }
        for (Object entry : releases) {
            if (!(entry instanceof JSONObject)) {
                continue;
            }
            JSONObject release = (JSONObject) entry;
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
                continue;
            }
            Matcher m = RELEASE_TAG.matcher(release.optString("tag_name"));
            if (m.matches() && Integer.parseInt(m.group(1)) >= MIN_MAJOR_VERSION) {
                return stripLeadingV(release.getString("tag_name"));
            }
        }
        throw new IOException("No Harness CLI release found in the GitHub releases response.");
    }

    private static String releaseAssetUrl(String version, String assetName) {
        return Constants.GITHUB_RELEASES_BASE + "/download/v" + version + "/" + assetName;
    }

    /** Streams {@code url} into {@code target} and returns the SHA-256 of the downloaded bytes (hex). */
    private static String download(String url, FilePath target, TaskListener log)
            throws IOException, InterruptedException {

        HttpRequest request = ProxyConfiguration.newHttpRequestBuilder(URI.create(url))
                .header("Accept", "application/octet-stream")
                .GET()
                .build();

        HttpResponse<InputStream> response = sendWithTimeoutRetry(
                request, HttpResponse.BodyHandlers.ofInputStream(), log);
        MessageDigest sha256 = newSha256();
        try (InputStream in = new DigestInputStream(response.body(), sha256)) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException(
                        "Failed to download Harness CLI — HTTP " + status + " from: " + url);
            }
            target.copyFrom(in);
        }
        return HexFormat.of().formatHex(sha256.digest());
    }

    private static String fetchExpectedSha256(String version, String archiveName, TaskListener log)
            throws IOException, InterruptedException {
        String checksumsUrl = releaseAssetUrl(version, "harness_" + version + "_checksums.txt");
        HttpRequest request = ProxyConfiguration.newHttpRequestBuilder(URI.create(checksumsUrl))
                .GET()
                .build();
        HttpResponse<String> response = sendWithTimeoutRetry(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), log);
        if (response.statusCode() == 404) {
            throw new AbortException("[Harness CLI] Version " + version + " was not found. "
                    + "Pick a version listed at " + Constants.GITHUB_RELEASES_BASE
                    + ", or leave the version field empty for the latest.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download checksums — HTTP " + response.statusCode()
                    + " from: " + checksumsUrl);
        }
        String expected = expectedSha256(response.body(), archiveName);
        if (expected == null) {
            throw new IOException("No checksum entry for " + archiveName + " in " + checksumsUrl);
        }
        return expected;
    }

    /** Parses a {@code sha256sum}-format file ("{@code <hash>  <file name>}" per line). */
    static String expectedSha256(String checksums, String fileName) {
        for (String line : checksums.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[1].equals(fileName)) {
                return parts[0];
            }
        }
        return null;
    }

    /** Runs {@code harness install plugin <harness-har>} on the agent with {@code cliHome} as the CLI home. */
    private static void registerHarPlugin(Node node, FilePath cli, FilePath harPlugin, FilePath cliHome,
                                          TaskListener log)
            throws IOException, InterruptedException {
        Launcher launcher = node.createLauncher(log);
        ArgumentListBuilder cmd = new ArgumentListBuilder(
                cli.getRemote(), "install", "plugin", harPlugin.getRemote());
        int exitCode = launcher.launch()
                .cmds(cmd)
                .envs(Map.of(HarnessCliInstallation.HARNESS_CLI_HOME, cliHome.getRemote()))
                .pwd(cli.getParent())
                .stdout(log)
                .stderr(log.getLogger())
                .join();
        if (exitCode != 0) {
            throw new IOException("[Harness CLI] 'harness install plugin' failed with exit code " + exitCode);
        }
    }

    /**
     * Retries {@link HttpClient#send} only on {@link HttpTimeoutException} (including
     * {@code HttpConnectTimeoutException}). HTTP status errors are not retried.
     * Backoff starts at {@link #HTTP_RETRY_INITIAL_DELAY_MS} and doubles up to
     * {@link #HTTP_RETRY_MAX_DELAY_MS}.
     */
    private static <T> HttpResponse<T> sendWithTimeoutRetry(
            HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler,
            TaskListener log)
            throws IOException, InterruptedException {

        HttpTimeoutException lastTimeout = null;
        long delayMs = HTTP_RETRY_INITIAL_DELAY_MS;
        for (int attempt = 1; attempt <= HTTP_MAX_ATTEMPTS; attempt++) {
            try {
                return httpClient.send(request, bodyHandler);
            } catch (HttpTimeoutException e) {
                lastTimeout = e;
                if (attempt == HTTP_MAX_ATTEMPTS) {
                    break;
                }
                log.getLogger().println("[Harness CLI] HTTP timed out (attempt " + attempt
                        + "/" + HTTP_MAX_ATTEMPTS + "): " + e.getMessage()
                        + " — retrying in " + delayMs + "ms");
                TimeUnit.MILLISECONDS.sleep(delayMs);
                delayMs = Math.min(delayMs * 2, HTTP_RETRY_MAX_DELAY_MS);
            }
        }
        throw lastTimeout;
    }

    // -------------------------------------------------------------------------

    /** Validates a pinned version and strips a leading 'v'. "v3.13.0" → "3.13.0" */
    static String normalizeVersion(String ver) throws IOException {
        String error = versionError(ver);
        if (error != null) {
            throw new AbortException("[Harness CLI] " + error);
        }
        return stripLeadingV(ver.trim());
    }

    /** Returns a user-facing problem with the pinned version, or {@code null} if it is usable. */
    static String versionError(String ver) {
        Matcher m = VERSION.matcher(stripLeadingV(StringUtils.trimToEmpty(ver)));
        if (!m.matches()) {
            return "Invalid version '" + ver + "'. Expected a release version such as 3.13.0.";
        }
        if (Integer.parseInt(m.group(1)) < MIN_MAJOR_VERSION) {
            return "Version '" + ver + "' cannot be installed. This installer downloads releases from "
                    + "https://github.com/harness/cli/releases; enter a version listed there, "
                    + "for example 3.13.0, or leave the field empty for the latest.";
        }
        return null;
    }

    /** Strips a leading 'v' from a version string. "v3.13.0" → "3.13.0", "3.13.0" → "3.13.0" */
    private static String stripLeadingV(String ver) {
        return ver != null && ver.startsWith("v") ? ver.substring(1) : ver;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }

    // -------------------------------------------------------------------------

    @Extension
    public static final class DescriptorImpl extends ToolInstallerDescriptor<HarnessCliGitHubInstaller> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Install from GitHub releases (harness/cli)";
        }

        @Override
        public boolean isApplicable(Class<? extends ToolInstallation> toolType) {
            return toolType == HarnessCliInstallation.class;
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckVersion(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (StringUtils.isBlank(value)) {
                return FormValidation.ok();
            }
            String error = versionError(value);
            return error == null ? FormValidation.ok() : FormValidation.error(error);
        }
    }
}
