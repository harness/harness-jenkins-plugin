package io.jenkins.plugins.har.cli;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.console.LineTransformationOutputStream;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.slaves.WorkspaceList;
import hudson.util.ArgumentListBuilder;
import hudson.util.Secret;
import io.jenkins.plugins.har.Constants;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.io.output.TeeOutputStream;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static io.jenkins.plugins.har.cli.HarnessCliInstallation.CLI_HOME_DIR;
import static io.jenkins.plugins.har.cli.HarnessCliInstallation.HARNESS_CLI_HOME;
import static io.jenkins.plugins.har.cli.HarnessCliInstallation.HARNESS_CLI_PATH;

/**
 * Shared logic for running the Harness CLI from the {@code harness} Pipeline step and the
 * Freestyle build step.
 *
 * <p>Credentials are resolved per command, first match wins:
 * <ol>
 *   <li>{@code --profile} in the command, or {@code HARNESS_PROFILE} in the build environment
 *       (for example a profile saved earlier with {@code harness auth login});</li>
 *   <li>{@code HARNESS_API_KEY} in the build environment (for example from {@code withCredentials});</li>
 *   <li>the API token from <b>Manage Jenkins → System → Harness CLI Configuration</b>,
 *       passed as environment variables.</li>
 * </ol>
 * The plugin never runs {@code auth login} or {@code auth logout} itself, so concurrent builds
 * and builds spanning several agents do not share credential state.
 *
 * <p>For installer-managed installations each workspace gets its own CLI home, so a profile saved
 * by {@code harness auth login} in one job is never visible to other jobs on the same agent.
 */
public final class HarnessCliSupport {

    static final String ENV_API_KEY = "HARNESS_API_KEY";
    static final String ENV_API_URL = "HARNESS_API_URL";
    static final String ENV_ORG = "HARNESS_ORG";
    static final String ENV_PROJECT = "HARNESS_PROJECT";
    static final String ENV_PROFILE = "HARNESS_PROFILE";

    static final String API_TOKEN_FLAG = "--api-token";
    static final String PROFILE_FLAG = "--profile";

    /** Per-workspace CLI home, created under the workspace's {@code @tmp} directory. */
    static final String WORKSPACE_CLI_HOME_DIR = "harness-cli-home";

    private static final String MASK = "****";

    private HarnessCliSupport() {}

    /** Exit code and raw (unmasked) standard output of one {@code harness} invocation. */
    public record Result(int exitCode, String output) {}

    /**
     * Runs {@code harness <args>} in {@code workspace}. The command line, standard output and
     * standard error are written to the build log with API tokens masked.
     */
    public static Result run(Launcher launcher, FilePath workspace, EnvVars env, String[] rawArgs,
                             TaskListener listener)
            throws IOException, InterruptedException {
        boolean isWindows = !launcher.isUnix();
        String cliPath = cliPath(env, isWindows);
        listener.getLogger().println("[harness] Using binary: " + cliPath);

        String[] args = stripCliName(rawArgs);
        EnvVars commandEnv = commandEnvironment(env, args, workspace, isWindows, listener);
        List<String> secrets = secrets(commandEnv, args);

        ArgumentListBuilder cmd = command(cliPath, args);
        if (isWindows) {
            cmd = cmd.toWindowsCommand();
        }

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        MaskingOutputStream stdoutLog = new MaskingOutputStream(listener.getLogger(), secrets);
        MaskingOutputStream stderr = new MaskingOutputStream(listener.getLogger(), secrets);
        int exitCode = launcher.launch()
                .envs(commandEnv)
                .pwd(workspace)
                .cmds(cmd)
                .stdout(new TeeOutputStream(stdoutLog, captured))
                .stderr(stderr)
                .join();
        stdoutLog.forceEol();
        stderr.forceEol();

        return new Result(exitCode, captured.toString(StandardCharsets.UTF_8));
    }

    /**
     * Resolves the full path to the harness binary.
     * Uses {@value HarnessCliInstallation#HARNESS_CLI_PATH} when set (via the tool installer),
     * or falls back to just the binary name so the OS PATH is searched.
     */
    public static String cliPath(EnvVars env, boolean isWindows) {
        String binaryName = HarnessOsUtils.getBinaryName(isWindows);
        String cliDir = env.get(HARNESS_CLI_PATH, "");
        if (StringUtils.isBlank(cliDir)) {
            return binaryName; // rely on system PATH
        }
        return joinPath(cliDir, binaryName, isWindows);
    }

    /**
     * Returns the CLI home to use in {@code workspace}, or {@code null} when the installation was not
     * set up by {@link io.jenkins.plugins.har.cli.callables.HarnessCliGitHubInstaller} (e.g. a manually
     * installed binary), so the CLI keeps its default {@code ~/.harness}.
     *
     * <p>The workspace home only holds copies of the plugin specs registered in the installation's
     * shared home; each spec records the absolute path of its plugin binary, so the binaries are not copied.
     */
    public static FilePath cliHome(EnvVars env, FilePath workspace, boolean isWindows)
            throws IOException, InterruptedException {
        String cliDir = env.get(HARNESS_CLI_PATH, "");
        if (StringUtils.isBlank(cliDir)) {
            return null;
        }
        FilePath sharedHome = new FilePath(workspace.getChannel(), joinPath(cliDir, CLI_HOME_DIR, isWindows));
        if (!sharedHome.child("bin").child(HarnessOsUtils.getHarPluginBinaryName(isWindows)).exists()) {
            return null;
        }
        FilePath tempDir = WorkspaceList.tempDir(workspace);
        if (tempDir == null) {
            throw new AbortException("[harness] Cannot create a private CLI home for workspace "
                    + workspace.getRemote() + ": it has no parent directory for temporary files.");
        }
        FilePath home = tempDir.child(WORKSPACE_CLI_HOME_DIR);
        syncSpecs(sharedHome.child("spec"), home.child("spec"));
        return home;
    }

    /** Copies plugin specs that are missing or older than the installation's (e.g. after an upgrade). */
    private static void syncSpecs(FilePath from, FilePath to) throws IOException, InterruptedException {
        to.mkdirs();
        for (FilePath spec : from.list("*.yaml")) {
            FilePath target = to.child(spec.getName());
            if (target.exists() && target.lastModified() >= spec.lastModified()) {
                continue;
            }
            // Parallel branches can share a workspace: stage then rename so the CLI never reads a partial spec.
            FilePath staging = to.child(spec.getName() + "." + UUID.randomUUID() + ".tmp");
            spec.copyTo(staging);
            staging.act(new ReplaceFile(target.getRemote()));
        }
    }

    /** Atomically moves the file onto {@code target}, replacing it; {@link FilePath#renameTo} cannot replace. */
    private static final class ReplaceFile extends MasterToSlaveFileCallable<Void> {

        private static final long serialVersionUID = 1L;

        private final String target;

        ReplaceFile(String target) {
            this.target = target;
        }

        @Override
        public Void invoke(File file, VirtualChannel channel) throws IOException {
            Files.move(file.toPath(), Path.of(target),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return null;
        }
    }

    /**
     * Returns a copy of {@code env} for one {@code harness} invocation: the CLI home plus the
     * credentials chosen as described in the class documentation.
     */
    static EnvVars commandEnvironment(EnvVars env, String[] args, FilePath workspace, boolean isWindows,
                                      TaskListener listener)
            throws IOException, InterruptedException {
        EnvVars commandEnv = new EnvVars(env);

        FilePath cliHome = cliHome(env, workspace, isWindows);
        if (cliHome != null) {
            commandEnv.put(HARNESS_CLI_HOME, cliHome.getRemote());
        }

        if (hasFlag(args, PROFILE_FLAG)) {
            listener.getLogger().println("[harness] Using the profile given by " + PROFILE_FLAG + ".");
            return commandEnv;
        }
        String profile = env.get(ENV_PROFILE);
        if (StringUtils.isNotBlank(profile)) {
            listener.getLogger().println("[harness] Using " + ENV_PROFILE + " '" + profile
                    + "' from the build environment.");
            return commandEnv;
        }
        if (StringUtils.isNotBlank(env.get(ENV_API_KEY))) {
            listener.getLogger().println("[harness] Using " + ENV_API_KEY + " from the build environment.");
            if (StringUtils.isBlank(env.get(ENV_API_URL))) {
                listener.getLogger().println("[harness] WARNING: " + ENV_API_KEY + " is set without " + ENV_API_URL
                        + ", so the CLI sends the token to its default host (app.harness.io). "
                        + "The URL in Harness CLI Configuration is not used with a build token; "
                        + "set " + ENV_API_URL + " if your account is on another domain.");
            }
            return commandEnv;
        }

        String apiToken = configuredToken();
        if (StringUtils.isBlank(apiToken)) {
            listener.getLogger().println(
                    "[harness] WARNING: Harness API Token is not configured. "
                    + "Go to Manage Jenkins → System → Harness CLI Configuration, "
                    + "or set " + ENV_API_KEY + " for this build.");
            return commandEnv;
        }

        HarnessGlobalConfiguration config = HarnessGlobalConfiguration.get();
        String apiUrl = StringUtils.defaultIfBlank(config.getApiUrl(), Constants.DEFAULT_BASE_URL);
        commandEnv.put(ENV_API_KEY, apiToken);
        commandEnv.put(ENV_API_URL, apiUrl);
        if (StringUtils.isBlank(env.get(ENV_ORG))) {
            putIfNotBlank(commandEnv, ENV_ORG, config.getOrgId());
        }
        if (StringUtils.isBlank(env.get(ENV_PROJECT))) {
            putIfNotBlank(commandEnv, ENV_PROJECT, config.getProjectId());
        }
        listener.getLogger().println("[harness] Using API token from Harness CLI Configuration for " + apiUrl);
        return commandEnv;
    }

    /** Builds the command line, marking {@code --api-token} values as masked so Jenkins does not echo them. */
    static ArgumentListBuilder command(String cliPath, String[] args) {
        ArgumentListBuilder cmd = new ArgumentListBuilder(cliPath);
        boolean tokenValueNext = false;
        for (String arg : args) {
            cmd.add(arg, tokenValueNext || arg.startsWith(API_TOKEN_FLAG + "="));
            tokenValueNext = arg.equals(API_TOKEN_FLAG);
        }
        return cmd;
    }

    /** Tokens that must not appear in the build log: the configured token, the command's API key and any {@code --api-token}. */
    static List<String> secrets(EnvVars commandEnv, String[] args) {
        List<String> secrets = new ArrayList<>();
        addIfNotBlank(secrets, configuredToken());
        addIfNotBlank(secrets, commandEnv.get(ENV_API_KEY));
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals(API_TOKEN_FLAG) && i + 1 < args.length) {
                addIfNotBlank(secrets, args[i + 1]);
            } else if (args[i].startsWith(API_TOKEN_FLAG + "=")) {
                addIfNotBlank(secrets, args[i].substring(API_TOKEN_FLAG.length() + 1));
            }
        }
        return secrets;
    }

    static String mask(String text, List<String> secrets) {
        if (text == null) {
            return null;
        }
        String masked = text;
        for (String secret : secrets) {
            masked = masked.replace(secret, MASK);
        }
        return masked;
    }

    /** Drops a leading {@code harness} if the user typed the full command line. */
    public static String[] stripCliName(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("harness")) {
            return Arrays.copyOfRange(args, 1, args.length);
        }
        return args;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag) || arg.startsWith(flag + "=")) {
                return true;
            }
        }
        return false;
    }

    private static String configuredToken() {
        HarnessGlobalConfiguration config = HarnessGlobalConfiguration.get();
        return config == null ? "" : Secret.toString(config.getApiToken());
    }

    private static void addIfNotBlank(List<String> list, String value) {
        if (StringUtils.isNotBlank(value)) {
            list.add(value);
        }
    }

    private static void putIfNotBlank(EnvVars env, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            env.put(key, value);
        }
    }

    private static String joinPath(String dir, String child, boolean isWindows) {
        String sep = isWindows ? "\\" : "/";
        String base = dir.endsWith("/") || dir.endsWith("\\")
                ? dir.substring(0, dir.length() - 1)
                : dir;
        String fullPath = base + sep + child;
        return isWindows ? fullPath.replace('/', '\\') : fullPath.replace('\\', '/');
    }

    /** Masks secrets line by line, so standard error can still stream to the build log. */
    private static final class MaskingOutputStream extends LineTransformationOutputStream.Delegating {

        private final List<String> secrets;

        MaskingOutputStream(OutputStream out, List<String> secrets) {
            super(out);
            this.secrets = secrets;
        }

        @Override
        protected void eol(byte[] b, int len) throws IOException {
            out.write(mask(new String(b, 0, len, StandardCharsets.UTF_8), secrets).getBytes(StandardCharsets.UTF_8));
        }
    }
}
