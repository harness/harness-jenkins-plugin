package io.jenkins.plugins.har;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.Shell;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import io.jenkins.plugins.har.cli.HarnessCliInstallation;
import io.jenkins.plugins.har.cli.HarnessGlobalConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

/** Runs the build step, the environment wrapper and the pipeline step against a fake {@code harness}. */
@WithJenkins
class HarnessCliJobsTest {

    @TempDir
    Path tmp;

    private Path toolDir;
    private JenkinsRule j;

    @BeforeEach
    void installFakeCli(JenkinsRule rule) throws Exception {
        j = rule;
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "fake CLI is a shell script");
        toolDir = Files.createDirectories(tmp.resolve("tool"));
        Path cli = toolDir.resolve("harness");
        Files.writeString(cli, "#!/bin/sh\n"
                + "echo \"ARGS:$*\"\n"
                + "echo \"KEY:${HARNESS_API_KEY:+set}\"\n"
                + "echo \"HOME:${HARNESS_CLI_HOME:-}\"\n"
                + "[ \"$1\" = fail ] && exit 3\n"
                + "exit 0\n");
        Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwxr-xr-x"));

        j.jenkins.getDescriptorByType(HarnessCliInstallation.DescriptorImpl.class)
                .setInstallations(new HarnessCliInstallation("fake", toolDir.toString(), List.of()));

        HarnessGlobalConfiguration config = HarnessGlobalConfiguration.get();
        config.setApiUrl("https://example.invalid");
        config.setApiToken(Secret.fromString("pat.GLOBAL.global.secret"));
    }

    private static HarnessCliWrapper wrapper(String installation) {
        HarnessCliWrapper wrapper = new HarnessCliWrapper();
        wrapper.setHarnessCliInstallation(installation);
        return wrapper;
    }

    @Test
    void buildStepRunsTheCommandWithTheGlobalTokenAndStripsTheCliName() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        HarnessCliBuilder step = new HarnessCliBuilder("harness hello world");
        step.setHarnessCliInstallation("fake");
        project.getBuildersList().add(step);

        FreeStyleBuild build = j.buildAndAssertSuccess(project);
        j.assertLogContains("ARGS:hello world", build);
        j.assertLogContains("KEY:set", build);
        j.assertLogNotContains("pat.GLOBAL.global.secret", build);
    }

    @Test
    void buildStepFailsTheBuildOnANonZeroExit() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        HarnessCliBuilder step = new HarnessCliBuilder("fail now");
        step.setHarnessCliInstallation("fake");
        project.getBuildersList().add(step);

        FreeStyleBuild build = j.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0).get());
        j.assertLogContains("Command failed with exit code 3", build);
    }

    @Test
    void buildStepExplainsMissingCommandAndMissingBinary() throws Exception {
        FreeStyleProject noCommand = j.createFreeStyleProject();
        noCommand.getBuildersList().add(new HarnessCliBuilder("  "));
        j.assertLogContains("No command provided",
                j.assertBuildStatus(Result.FAILURE, noCommand.scheduleBuild2(0).get()));

        j.jenkins.getDescriptorByType(HarnessCliInstallation.DescriptorImpl.class).setInstallations(
                new HarnessCliInstallation("missing", tmp.resolve("nowhere").toString(), List.of()));
        FreeStyleProject noBinary = j.createFreeStyleProject();
        HarnessCliBuilder step = new HarnessCliBuilder("version");
        step.setHarnessCliInstallation("missing");
        noBinary.getBuildersList().add(step);
        j.assertLogContains("Harness CLI binary not found",
                j.assertBuildStatus(Result.FAILURE, noBinary.scheduleBuild2(0).get()));
    }

    @Test
    void wrapperExposesTheInstallationToEveryBuildStep() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildWrappersList().add(wrapper("fake"));
        project.getBuildersList().add(new Shell("echo \"CLI_PATH=$HARNESS_CLI_PATH\"; \"$HARNESS_CLI_PATH/harness\" version"));

        FreeStyleBuild build = j.buildAndAssertSuccess(project);
        j.assertLogContains("CLI_PATH=" + toolDir, build);
        j.assertLogContains("ARGS:version", build);
    }

    @Test
    void wrapperWarnsAboutAnUnknownInstallationAndBuildContinues() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildWrappersList().add(wrapper("does-not-exist"));
        project.getBuildersList().add(new Shell("true"));

        j.assertLogContains("Installation 'does-not-exist' not found", j.buildAndAssertSuccess(project));
    }

    @Test
    void installerManagedToolGetsAPrivateCliHomePerWorkspace() throws Exception {
        Files.createDirectories(toolDir.resolve("cli-home/bin"));
        Files.createDirectories(toolDir.resolve("cli-home/spec"));
        Files.writeString(toolDir.resolve("cli-home/bin/harness-har"), "plugin");
        Files.writeString(toolDir.resolve("cli-home/spec/har.spec.yaml"), "binary_path: x");

        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildWrappersList().add(wrapper("fake"));
        HarnessCliBuilder step = new HarnessCliBuilder("version");
        step.setHarnessCliInstallation("fake");
        project.getBuildersList().add(step);
        project.getBuildersList().add(new Shell("echo \"WRAPPER_HOME=$HARNESS_CLI_HOME\""));

        FreeStyleBuild build = j.buildAndAssertSuccess(project);
        String home = build.getWorkspace().getRemote() + "@tmp/harness-cli-home";
        j.assertLogContains("HOME:" + home, build);
        j.assertLogContains("WRAPPER_HOME=" + home, build);
    }

    @Test
    void buildStepWarnsWhenNoTokenIsConfiguredAnywhere() throws Exception {
        HarnessGlobalConfiguration.get().setApiToken(Secret.fromString(""));
        FreeStyleProject project = j.createFreeStyleProject();
        HarnessCliBuilder step = new HarnessCliBuilder("version");
        step.setHarnessCliInstallation("fake");
        project.getBuildersList().add(step);

        FreeStyleBuild build = j.buildAndAssertSuccess(project);
        j.assertLogContains("Harness API Token is not configured", build);
        j.assertLogContains("KEY:", build);
        j.assertLogNotContains("KEY:set", build);
    }

    @Test
    void installationDropdownsListThePathOptionAndEveryInstallation() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();

        ListBoxModel builder = j.jenkins.getDescriptorByType(HarnessCliBuilder.DescriptorImpl.class)
                .doFillHarnessCliInstallationItems(project);
        ListBoxModel wrapper = j.jenkins.getDescriptorByType(HarnessCliWrapper.DescriptorImpl.class)
                .doFillHarnessCliInstallationItems(project);

        assertEquals(List.of("", "fake"), builder.stream().map(o -> o.value).toList());
        assertEquals(List.of("", "fake"), wrapper.stream().map(o -> o.value).toList());
    }

    @Test
    void pipelineStepRunsTheCommandAndReturnsItsOutput() throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withEnv(['HARNESS_CLI_PATH=" + toolDir + "']) {\n"
                        + "    def out = harness 'hello pipeline'\n"
                        + "    echo \"GOT[${out.trim()}]\"\n"
                        + "    harness(['two words', 'x'])\n"
                        + "  }\n"
                        + "}", true));

        WorkflowRun run = j.buildAndAssertSuccess(job);
        j.assertLogContains("ARGS:hello pipeline", run);
        j.assertLogContains("GOT[ARGS:hello pipeline", run);
        j.assertLogContains("ARGS:two words x", run);
    }

    @Test
    void pipelineStepFailsWithOneLineErrorOnNonZeroExit() throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class);
        job.setDefinition(new CpsFlowDefinition(
                "node { withEnv(['HARNESS_CLI_PATH=" + toolDir + "']) { harness 'fail please' } }", true));

        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, job.scheduleBuild2(0).get());
        j.assertLogContains("'harness' command failed with exit code 3", run);
    }
}
