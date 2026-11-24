package io.jenkins.plugins.har.cli;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;

/**
 * Pipeline step {@code harness(...)} that runs the Harness CLI ({@code harness}) binary.
 *
 * <p>Each invocation authenticates with {@code HARNESS_API_KEY} from the build environment
 * (e.g. {@code withCredentials}) or, by default, the credentials configured under
 * <b>Manage Jenkins → System → Harness CLI Configuration</b> (see {@link HarnessCliSupport}).
 *
 * <p>Example Declarative Pipeline:
 * <pre>{@code
 * pipeline {
 *     agent any
 *     tools { harnessCli 'harness-cli' }
 *     stages {
 *         stage('Deploy') {
 *             steps {
 *                 harness 'push artifact:generic my-repo/output build/output.jar --version 1.0.0'
 *             }
 *         }
 *     }
 * }
 * }</pre>
 */
public class HarnessStep extends Step {

    private final String[] args;

    @DataBoundConstructor
    public HarnessStep(Object args) {
        if (args instanceof List) {
            //noinspection unchecked
            this.args = ((List<String>) args).toArray(String[]::new);
            return;
        }
        this.args = StringUtils.split(args.toString());
    }

    public String[] getArgs() {
        return args;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(args, context);
    }

    // -------------------------------------------------------------------------

    public static class Execution extends SynchronousNonBlockingStepExecution<String> {

        private static final long serialVersionUID = 1L;

        private final String[] args;

        protected Execution(String[] args, @NonNull StepContext context) {
            super(context);
            this.args = args;
        }

        @Override
        protected String run() throws Exception {
            Launcher     launcher  = getContext().get(Launcher.class);
            FilePath     workspace = getContext().get(FilePath.class);
            TaskListener listener  = getContext().get(TaskListener.class);
            EnvVars      env       = getContext().get(EnvVars.class);

            workspace.mkdirs();

            HarnessCliSupport.Result result = HarnessCliSupport.run(launcher, workspace, env, args, listener);
            if (result.exitCode() != 0) {
                throw new AbortException("'harness' command failed with exit code " + result.exitCode());
            }
            return result.output();
        }
    }

    // -------------------------------------------------------------------------

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "harness";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Run Harness CLI command";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Launcher.class, FilePath.class, TaskListener.class, EnvVars.class);
        }
    }
}
