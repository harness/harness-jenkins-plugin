package io.jenkins.plugins.har.cli;

import java.util.Locale;

public class HarnessOsUtils {

    private HarnessOsUtils() {}

    /**
     * Returns the OS segment used in harness/cli release asset names:
     *   linux | darwin | windows
     *
     * Designed to run on the agent node (inside a MasterToSlaveCallable).
     */
    public static String getOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        return "linux";
    }

    /**
     * Returns the architecture segment used in harness/cli release asset names:
     *   amd64 | arm64
     * Any other architecture is returned as reported by the JVM and rejected by the installer.
     *
     * Designed to run on the agent node (inside a MasterToSlaveCallable).
     */
    public static String getArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) return "arm64";
        if (arch.equals("amd64") || arch.equals("x86_64")) return "amd64";
        return arch;
    }

    public static boolean isSupportedArch(String arch) {
        return "amd64".equals(arch) || "arm64".equals(arch);
    }

    public static String getBinaryName(boolean isWindows) {
        return isWindows ? "harness.exe" : "harness";
    }

    public static String getHarPluginBinaryName(boolean isWindows) {
        return isWindows ? "harness-har.exe" : "harness-har";
    }
}
