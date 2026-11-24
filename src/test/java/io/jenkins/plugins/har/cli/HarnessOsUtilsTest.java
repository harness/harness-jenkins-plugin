package io.jenkins.plugins.har.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class HarnessOsUtilsTest {

    @Test
    void binaryNamesFollowPlatform() {
        assertEquals("harness.exe", HarnessOsUtils.getBinaryName(true));
        assertEquals("harness", HarnessOsUtils.getBinaryName(false));
        assertEquals("harness-har.exe", HarnessOsUtils.getHarPluginBinaryName(true));
        assertEquals("harness-har", HarnessOsUtils.getHarPluginBinaryName(false));
    }

    @Test
    void osAndArchAreRecognisedSegments() {
        assertTrue(Set.of("linux", "darwin", "windows").contains(HarnessOsUtils.getOs()));
        assertTrue(HarnessOsUtils.isSupportedArch(HarnessOsUtils.getArch()));
    }

    @Test
    void onlyAmd64AndArm64AreSupported() {
        assertTrue(HarnessOsUtils.isSupportedArch("amd64"));
        assertTrue(HarnessOsUtils.isSupportedArch("arm64"));
        assertFalse(HarnessOsUtils.isSupportedArch("i386"));
    }
}
