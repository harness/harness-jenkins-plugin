package io.jenkins.plugins.har.cli.callables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarnessCliGitHubInstallerTest {

    @Test
    void latestReleaseSkipsPluginTagsPreReleasesDraftsAndPre3Versions() throws IOException {
        String releases = "["
                + "{\"tag_name\":\"har/v1.4.0\"},"
                + "{\"tag_name\":\"v3.14.0\",\"prerelease\":true},"
                + "{\"tag_name\":\"v3.14.0-rc1\"},"
                + "{\"tag_name\":\"v3.13.1\",\"draft\":true},"
                + "{\"tag_name\":\"v3.13.0\"},"
                + "{\"tag_name\":\"v3.12.0\"}"
                + "]";
        assertEquals("3.13.0", HarnessCliGitHubInstaller.latestReleaseVersion(releases));

        assertThrows(IOException.class,
                () -> HarnessCliGitHubInstaller.latestReleaseVersion("[{\"tag_name\":\"v0.9.0\"}]"));
    }

    @Test
    void pinnedVersionMustBeAHarnessCliRelease() throws IOException {
        assertEquals("3.13.0", HarnessCliGitHubInstaller.normalizeVersion("v3.13.0"));
        assertNull(HarnessCliGitHubInstaller.versionError("3.14.0-rc1"));

        String notACliRelease = HarnessCliGitHubInstaller.versionError("1.3.47");
        assertNotNull(notACliRelease);
        assertTrue(notACliRelease.contains("https://github.com/harness/cli/releases"), notACliRelease);
        assertNotNull(HarnessCliGitHubInstaller.versionError("1.3.0"));

        assertNotNull(HarnessCliGitHubInstaller.versionError("latest"));
    }

    @Test
    void checksumIsMatchedByExactFileName() {
        String checksums = "aaa  harness-bundle_3.13.0_linux_amd64.tar.gz.sbom\n"
                + "bbb  harness-bundle_3.13.0_linux_amd64.tar.gz\n";
        assertEquals("bbb", HarnessCliGitHubInstaller.expectedSha256(checksums, "harness-bundle_3.13.0_linux_amd64.tar.gz"));
        assertNull(HarnessCliGitHubInstaller.expectedSha256(checksums, "harness-bundle_3.13.0_darwin_arm64.tar.gz"));
    }

    @Test
    void swapReplacesToolHomeAndKeepsItWhenTheStagedDirectoryIsMissing(@TempDir Path root) throws IOException {
        Path toolHome = Files.createDirectory(root.resolve("harness-cli"));
        Files.writeString(toolHome.resolve("marker"), "old");
        Path staging = Files.createDirectory(root.resolve(".staging"));
        Files.writeString(staging.resolve("marker"), "new");

        String backup = new HarnessCliGitHubInstaller.SwapDirectories(staging.toString()).invoke(toolHome.toFile(), null);
        assertEquals("new", Files.readString(toolHome.resolve("marker")));
        assertEquals("old", Files.readString(root.resolve(backup).resolve("marker")));

        assertThrows(IOException.class,
                () -> new HarnessCliGitHubInstaller.SwapDirectories(root.resolve("gone").toString())
                        .invoke(toolHome.toFile(), null));
        assertEquals("new", Files.readString(toolHome.resolve("marker")));
    }
}
