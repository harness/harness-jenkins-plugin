package io.jenkins.plugins.har.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class HarnessCliSupportTest {

    private static final String GLOBAL_TOKEN = "pat.GLOBAL.global.secret";

    @TempDir
    Path tmp;

    @Test
    void commandLineHidesApiTokenValues(JenkinsRule j) {
        String shown = HarnessCliSupport.command("harness", new String[] {
            "auth", "login", "--api-url", "https://example.invalid", "--api-token", "pat.A.spaced.secret"
        }).toString();
        assertFalse(shown.contains("pat.A.spaced.secret"), shown);
        assertTrue(shown.contains("https://example.invalid"), shown);

        String inline = HarnessCliSupport.command("harness", new String[] {"auth", "login", "--api-token=pat.B.inline.secret"})
                .toString();
        assertFalse(inline.contains("pat.B.inline.secret"), inline);
    }

    @Test
    void outputMasksConfiguredEnvironmentAndCommandTokens(JenkinsRule j) {
        configureGlobalToken();
        EnvVars env = new EnvVars("HARNESS_API_KEY", "pat.ENV.env.secret");
        List<String> secrets = HarnessCliSupport.secrets(env, new String[] {
            "auth", "login", "--api-token", "pat.A.spaced.secret", "--api-token=pat.B.inline.secret"
        });

        String masked = HarnessCliSupport.mask(
                GLOBAL_TOKEN + " pat.ENV.env.secret pat.A.spaced.secret pat.B.inline.secret", secrets);

        assertEquals("**** **** **** ****", masked);
    }

    @Test
    void globalTokenIsUsedWhenTheBuildSetsNoCredentials(JenkinsRule j) throws Exception {
        configureGlobalToken();
        EnvVars result = commandEnvironment(new EnvVars(), "push", "artifact:generic");
        assertEquals(GLOBAL_TOKEN, result.get("HARNESS_API_KEY"));
        assertEquals("https://example.invalid", result.get("HARNESS_API_URL"));
        assertEquals("my-org", result.get("HARNESS_ORG"));

        EnvVars jobScope = commandEnvironment(new EnvVars("HARNESS_ORG", "job-org", "HARNESS_PROJECT", "job-project"),
                "push", "artifact:generic");
        assertEquals(GLOBAL_TOKEN, jobScope.get("HARNESS_API_KEY"));
        assertEquals("job-org", jobScope.get("HARNESS_ORG"));
        assertEquals("job-project", jobScope.get("HARNESS_PROJECT"));
    }

    @Test
    void credentialsFromTheBuildWinOverTheGlobalToken(JenkinsRule j) throws Exception {
        configureGlobalToken();

        EnvVars withKey = commandEnvironment(new EnvVars("HARNESS_API_KEY", "pat.ENV.env.secret"), "version");
        assertEquals("pat.ENV.env.secret", withKey.get("HARNESS_API_KEY"));
        assertNull(withKey.get("HARNESS_ORG"));

        EnvVars withProfile = commandEnvironment(new EnvVars("HARNESS_PROFILE", "ci"), "version");
        assertEquals("ci", withProfile.get("HARNESS_PROFILE"));
        assertNull(withProfile.get("HARNESS_API_KEY"));

        EnvVars withFlag = commandEnvironment(new EnvVars(), "version", "--profile=ci");
        assertNull(withFlag.get("HARNESS_API_KEY"));
    }

    @Test
    void buildApiKeyWithoutApiUrlWarnsThatTheDefaultHostIsUsed(JenkinsRule j) throws Exception {
        configureGlobalToken();
        FilePath ws = new FilePath(tmp.resolve("ws").toFile());

        ByteArrayOutputStream withoutUrl = new ByteArrayOutputStream();
        HarnessCliSupport.commandEnvironment(new EnvVars("HARNESS_API_KEY", "pat.ENV.env.secret"),
                new String[] {"version"}, ws, false, new StreamTaskListener(withoutUrl, StandardCharsets.UTF_8));
        assertTrue(withoutUrl.toString(StandardCharsets.UTF_8).contains("without HARNESS_API_URL"));

        ByteArrayOutputStream withUrl = new ByteArrayOutputStream();
        HarnessCliSupport.commandEnvironment(
                new EnvVars("HARNESS_API_KEY", "pat.ENV.env.secret", "HARNESS_API_URL", "https://example.invalid"),
                new String[] {"version"}, ws, false, new StreamTaskListener(withUrl, StandardCharsets.UTF_8));
        assertFalse(withUrl.toString(StandardCharsets.UTF_8).contains("WARNING"));
    }

    @Test
    void eachWorkspaceGetsItsOwnCliHomeWithCurrentSpecs(JenkinsRule j) throws Exception {
        FilePath tool = new FilePath(tmp.resolve("tool").toFile());
        FilePath sharedSpec = tool.child("cli-home/spec/har.spec.yaml");
        tool.child("cli-home/bin/harness-har").write("binary", "UTF-8");
        sharedSpec.write("version: 1", "UTF-8");
        EnvVars env = new EnvVars("HARNESS_CLI_PATH", tool.getRemote());

        FilePath ws1 = new FilePath(tmp.resolve("ws1").toFile());
        FilePath ws2 = new FilePath(tmp.resolve("ws2").toFile());
        FilePath home1 = HarnessCliSupport.cliHome(env, ws1, false);
        FilePath home2 = HarnessCliSupport.cliHome(env, ws2, false);

        assertEquals(new File(tmp.toFile(), "ws1@tmp/harness-cli-home").getPath(), home1.getRemote());
        assertFalse(home1.getRemote().equals(home2.getRemote()));
        assertEquals("version: 1", home1.child("spec/har.spec.yaml").readToString());

        sharedSpec.write("version: 2", "UTF-8");
        sharedSpec.touch(home1.child("spec/har.spec.yaml").lastModified() + 10_000);
        HarnessCliSupport.cliHome(env, ws1, false);
        assertEquals("version: 2", home1.child("spec/har.spec.yaml").readToString());
        assertEquals(1, home1.child("spec").list().size());
    }

    @Test
    void workspaceWithoutTempDirFailsInsteadOfSharingTheToolHome(JenkinsRule j) throws Exception {
        FilePath tool = new FilePath(tmp.resolve("tool").toFile());
        tool.child("cli-home/bin/harness-har").write("binary", "UTF-8");
        EnvVars env = new EnvVars("HARNESS_CLI_PATH", tool.getRemote());

        assertThrows(AbortException.class,
                () -> HarnessCliSupport.cliHome(env, new FilePath(new File(File.separator)), false));
    }

    @Test
    void manuallyInstalledCliKeepsItsDefaultHome(JenkinsRule j) throws Exception {
        FilePath ws = new FilePath(tmp.resolve("ws").toFile());
        assertNull(HarnessCliSupport.cliHome(new EnvVars(), ws, false));

        FilePath toolWithoutPlugin = new FilePath(tmp.resolve("bare-tool").toFile());
        toolWithoutPlugin.mkdirs();
        assertNull(HarnessCliSupport.cliHome(new EnvVars("HARNESS_CLI_PATH", toolWithoutPlugin.getRemote()), ws, false));
    }

    private void configureGlobalToken() {
        HarnessGlobalConfiguration config = HarnessGlobalConfiguration.get();
        config.setApiUrl("https://example.invalid");
        config.setApiToken(Secret.fromString(GLOBAL_TOKEN));
        config.setOrgId("my-org");
        config.setProjectId("");
    }

    private EnvVars commandEnvironment(EnvVars env, String... args) throws Exception {
        FilePath ws = new FilePath(tmp.resolve("ws").toFile());
        return HarnessCliSupport.commandEnvironment(env, args, ws, false, TaskListener.NULL);
    }
}
