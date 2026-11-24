package io.jenkins.plugins.har;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Node;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.har.cli.HarnessCliInstallation;
import io.jenkins.plugins.har.cli.HarnessCliSupport;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.verb.POST;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;

/**
 * Build step for running Harness CLI ({@code harness}) commands in Freestyle jobs.
 *
 * <p>Mirrors what the {@code harness(...)} Pipeline step does for Declarative/Scripted pipelines,
 * authenticating with the credentials from
 * <b>Manage Jenkins → System → Harness CLI Configuration</b>.
 *
 * <p>Example commands:
 * <pre>
 *   version
 *   push artifact:rpm my-repo /path/to/file.rpm
 *   auth status
 * </pre>
 */
public class HarnessCliBuilder extends Builder {

    private String command;
    private String harnessCliInstallation;

    @DataBoundConstructor
    public HarnessCliBuilder(String command) {
        this.command = command;
    }

    public String getCommand() {
        return command;
    }

    public String getHarnessCliInstallation() {
        return harnessCliInstallation;
    }

    @DataBoundSetter
    public void setCommand(String command) {
        this.command = command;
    }

    @DataBoundSetter
    public void setHarnessCliInstallation(String harnessCliInstallation) {
        this.harnessCliInstallation = harnessCliInstallation;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {

        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            listener.error("[harness] Workspace is null");
            return false;
        }

        EnvVars env = build.getEnvironment(listener);

        // Apply tool installation env vars (sets HARNESS_CLI_PATH) if an installation is selected
        if (StringUtils.isNotBlank(harnessCliInstallation)) {
            HarnessCliInstallation installation = getInstallation();
            if (installation != null) {
                Node node = build.getBuiltOn();
                if (node != null) {
                    installation = installation.forNode(node, listener);
                }
                installation = installation.forEnvironment(env);
                installation.buildEnvVars(env);
            }
        }

        workspace.mkdirs();

        if (StringUtils.isBlank(command)) {
            listener.error("[harness] No command provided");
            return false;
        }

        String cliPath = HarnessCliSupport.cliPath(env, !launcher.isUnix());
        try {
            HarnessCliSupport.Result result =
                    HarnessCliSupport.run(launcher, workspace, env, StringUtils.split(command.trim()), listener);
            if (result.exitCode() != 0) {
                listener.error("[harness] Command failed with exit code " + result.exitCode());
                return false;
            }
            return true;

        } catch (IOException e) {
            if (e.getMessage() != null && (e.getMessage().contains("No such file or directory")
                    || e.getMessage().contains("Cannot run program"))) {
                listener.error("[harness] Harness CLI binary not found at: " + cliPath);
                listener.error("     → Go to Manage Jenkins → Tools → Harness CLI installations and add an installation.");
                listener.error("     → Or install the harness binary on the system PATH of the agent.");
                listener.error("Error: " + ExceptionUtils.getRootCauseMessage(e));
            } else {
                listener.error("[harness] Failed to execute command: " + ExceptionUtils.getRootCauseMessage(e));
            }
            return false;
        }
    }

    private HarnessCliInstallation getInstallation() {
        if (harnessCliInstallation == null) {
            return null;
        }
        HarnessCliInstallation[] installations = ((DescriptorImpl) getDescriptor()).getInstallations();
        if (installations == null) {
            return null;
        }
        for (HarnessCliInstallation inst : installations) {
            if (inst != null && harnessCliInstallation.equals(inst.getName())) {
                return inst;
            }
        }
        return null;
    }

    @Extension
    @Symbol("harnessCliRun")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Run Harness CLI command";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public HarnessCliInstallation[] getInstallations() {
            return jenkins.model.Jenkins.get()
                    .getDescriptorByType(HarnessCliInstallation.DescriptorImpl.class)
                    .getInstallations();
        }

        @POST
        public ListBoxModel doFillHarnessCliInstallationItems(@AncestorInPath Job<?, ?> job) {
            job.checkPermission(Item.CONFIGURE);
            ListBoxModel items = new ListBoxModel();
            items.add("(Use harness from system PATH)", "");
            for (HarnessCliInstallation inst : getInstallations()) {
                items.add(inst.getName(), inst.getName());
            }
            return items;
        }
    }
}
