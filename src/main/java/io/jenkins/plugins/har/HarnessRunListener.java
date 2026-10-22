package io.jenkins.plugins.har;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.har.cli.HarnessCliLoginTracker;
import io.jenkins.plugins.har.cli.HcStep;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Runs {@code hc auth logout} at the end of every build where {@code hc auth login} was called.
 *
 * <p>This covers two scenarios:
 * <ul>
 *   <li><b>Pipeline jobs</b> — the {@link HarnessCliWrapper} Disposer is not available, so this
 *       listener is the only hook that fires after all pipeline steps complete.</li>
 *   <li><b>Freestyle jobs without {@link HarnessCliWrapper}</b> — using only
 *       {@code HarnessCliBuilder} build steps with no build environment wrapper.</li>
 * </ul>
 *
 * <p>For Freestyle jobs that <em>do</em> use {@link HarnessCliWrapper}, the Disposer runs first
 * and calls {@link HarnessCliLoginTracker#markLoggedOut()}, so this listener will find that
 * logout is already done and skip.
 *
 * <p>For pipeline builds that used multiple agents, logout is attempted on every agent that
 * recorded a login in {@link HarnessCliLoginTracker}.
 */
@Extension
public class HarnessRunListener extends RunListener<Run<?, ?>> {

    @Override
    public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
        HarnessCliLoginTracker tracker = run.getAction(HarnessCliLoginTracker.class);
        if (tracker == null || !tracker.markLoggedOut()) {
            return;
        }

        try {
            EnvVars env = run.getEnvironment(listener);

            if (run instanceof AbstractBuild) {
                AbstractBuild<?, ?> build = (AbstractBuild<?, ?>) run;
                FilePath workspace = build.getWorkspace();
                if (workspace == null) {
                    listener.getLogger().println("[hc] WARNING: Workspace not available for logout.");
                    return;
                }
                Node node = build.getBuiltOn();
                if (node == null) {
                    listener.getLogger().println("[hc] WARNING: Agent not available for logout.");
                    return;
                }
                Launcher launcher = node.createLauncher(listener);
                boolean isWindows = !launcher.isUnix();
                String hcBinaryPath = tracker.getSessions().isEmpty()
                        ? null
                        : tracker.getSessions().iterator().next().getHcBinaryPath();
                if (hcBinaryPath == null) {
                    return;
                }
                HcStep.Execution.performLogout(
                        launcher, workspace, env, hcBinaryPath, isWindows, listener);
                return;
            }

            // Pipeline — logout on every agent that logged in during this build.
            for (HarnessCliLoginTracker.AgentSession session : tracker.getSessions()) {
                logoutSession(session, env, listener);
            }

        } catch (Exception e) {
            listener.getLogger().println("[hc] WARNING: Failed to run 'hc auth logout': " + e.getMessage());
        }
    }

    private static void logoutSession(HarnessCliLoginTracker.AgentSession session,
                                      EnvVars env,
                                      TaskListener listener) {
        try {
            String workspacePath = session.getWorkspacePath();
            String nodeName = session.getNodeName();
            if (StringUtils.isBlank(workspacePath)) {
                listener.getLogger().println(
                        "[hc] WARNING: No workspace captured for agent '" + nodeName
                                + "' — skipping logout.");
                return;
            }
            Computer computer;
            if (StringUtils.isBlank(nodeName) || "built-in".equals(nodeName) || "master".equals(nodeName)) {
                computer = Jenkins.get().toComputer();
            } else {
                computer = Jenkins.get().getComputer(nodeName);
            }
            if (computer == null) {
                listener.getLogger().println(
                        "[hc] WARNING: Agent '" + nodeName + "' no longer available — skipping logout.");
                return;
            }
            Node node = computer.getNode();
            if (node == null) {
                listener.getLogger().println(
                        "[hc] WARNING: Agent '" + nodeName + "' no longer available — skipping logout.");
                return;
            }
            Launcher launcher = node.createLauncher(listener);
            FilePath workspace = new FilePath(computer.getChannel(), workspacePath);
            boolean isWindows = !launcher.isUnix();
            listener.getLogger().println("[hc] Logging out on agent '" + nodeName + "'...");
            HcStep.Execution.performLogout(
                    launcher, workspace, env, session.getHcBinaryPath(), isWindows, listener);
        } catch (Exception e) {
            listener.getLogger().println(
                    "[hc] WARNING: Failed to logout on agent '" + session.getNodeName()
                            + "': " + e.getMessage());
        }
    }
}
