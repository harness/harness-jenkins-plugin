package io.jenkins.plugins.har.cli;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentSpecific;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolInstaller;
import hudson.tools.ToolProperty;
import io.jenkins.plugins.har.cli.callables.HarnessCliGitHubInstaller;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a Harness CLI ({@code harness}) tool installation in Jenkins.
 * <p>
 * Admins configure this under Manage Jenkins → Tools → Harness CLI installations.
 * The installation directory is exposed as the {@value #HARNESS_CLI_PATH} environment variable
 * so that {@link HarnessStep} can locate the binary at runtime.
 */
public class HarnessCliInstallation extends ToolInstallation
        implements NodeSpecific<HarnessCliInstallation>, EnvironmentSpecific<HarnessCliInstallation> {

    /** Environment variable set to the directory that contains the harness binary. */
    public static final String HARNESS_CLI_PATH = "HARNESS_CLI_PATH";

    /**
     * Environment variable the harness CLI reads to locate its home directory
     * (registered plugins under {@code bin/} and their command specs under {@code spec/}).
     */
    public static final String HARNESS_CLI_HOME = "HARNESS_CLI_HOME";

    /** Directory inside the tool home that the installer uses as {@value #HARNESS_CLI_HOME}. */
    public static final String CLI_HOME_DIR = "cli-home";

    @DataBoundConstructor
    public HarnessCliInstallation(String name, String home, List<? extends ToolProperty<?>> properties) {
        super(name, home, properties);
    }

    @Override
    public HarnessCliInstallation forEnvironment(EnvVars environment) {
        return new HarnessCliInstallation(getName(), environment.expand(getHome()), getProperties().toList());
    }

    @Override
    public HarnessCliInstallation forNode(@NonNull Node node, TaskListener log) throws IOException, InterruptedException {
        return new HarnessCliInstallation(getName(), translateFor(node, log), getProperties().toList());
    }

    @Override
    public void buildEnvVars(EnvVars env) {
        String home = getHome();
        if (home == null) {
            return;
        }
        env.put(HARNESS_CLI_PATH, home);
    }

    @Symbol("harnessCli")
    @Extension
    public static final class DescriptorImpl extends ToolDescriptor<HarnessCliInstallation> {

        public DescriptorImpl() {
            super(HarnessCliInstallation.class);
            load();
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Harness CLI";
        }

        @Override
        public List<? extends ToolInstaller> getDefaultInstallers() {
            List<ToolInstaller> list = new ArrayList<>();
            list.add(new HarnessCliGitHubInstaller(""));
            return list;
        }

        @Override
        public boolean configure(StaplerRequest2 req, JSONObject o) throws FormException {
            super.configure(req, o);
            save();
            return true;
        }
    }
}
