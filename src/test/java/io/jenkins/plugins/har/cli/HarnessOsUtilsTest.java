package io.jenkins.plugins.har.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class HarnessOsUtilsTest {

    @Test
    void binaryNameFollowsPlatform() {
        assertEquals("hc.exe", HarnessOsUtils.getBinaryName(true));
        assertEquals("hc", HarnessOsUtils.getBinaryName(false));
    }

    @Test
    void osAndArchAreRecognisedSegments() {
        assertTrue(Set.of("linux", "mac-os", "windows").contains(HarnessOsUtils.getOs()));
        assertTrue(Set.of("x86_64", "arm64", "i386").contains(HarnessOsUtils.getArch()));
    }
}
