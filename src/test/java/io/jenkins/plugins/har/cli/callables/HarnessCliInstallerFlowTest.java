package io.jenkins.plugins.har.cli.callables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import hudson.AbortException;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.tools.InstallSourceProperty;
import hudson.util.StreamTaskListener;
import io.jenkins.plugins.har.cli.HarnessCliInstallation;
import io.jenkins.plugins.har.cli.HarnessOsUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Runs the installer end to end against a local stand-in for GitHub, with a fake CLI bundle. */
@WithJenkins
class HarnessCliInstallerFlowTest {

    private static final String RELEASES_PATH = "/harness/cli/releases/download/";

    @TempDir
    Path tmp;

    private HttpServer server;
    private HttpClient originalClient;
    private final Map<String, byte[]> responses = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startFakeGitHub() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "fake CLI is a shell script");
        server = HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = responses.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        originalClient = HarnessCliGitHubInstaller.httpClient;
        HarnessCliGitHubInstaller.httpClient = new LocalhostClient(server.getAddress().getPort());
    }

    @AfterEach
    void stopFakeGitHub() {
        if (server != null) {
            server.stop(0);
            HarnessCliGitHubInstaller.httpClient = originalClient;
        }
    }

    @Test
    void installsOnceThenReusesAndUpgradesInPlace(JenkinsRule j) throws Exception {
        publish("3.20.0", true);
        publish("3.21.0", true);
        HarnessCliInstallation tool = tool("fake-cli", "3.20.0");

        FilePath home = install(tool, "3.20.0");
        assertEquals("3.20.0", marker(home));
        assertRegistered(home);
        assertNoLeftovers(home);

        int before = requests.get();
        install(tool, "3.20.0");
        assertEquals(before, requests.get(), "a matching pinned version must not touch the network");

        FilePath upgraded = install(tool, "3.21.0");
        assertEquals(home.getRemote(), upgraded.getRemote());
        assertEquals("3.21.0", marker(upgraded));
        assertRegistered(upgraded);
        assertNoLeftovers(upgraded);
    }

    @Test
    void failedInstallLeavesTheWorkingInstallUntouched(JenkinsRule j) throws Exception {
        publish("3.20.0", true);
        publish("3.21.0", false);
        HarnessCliInstallation tool = tool("fake-cli", "3.20.0");
        FilePath home = install(tool, "3.20.0");

        AbortException mismatch = assertThrows(AbortException.class, () -> install(tool, "3.21.0"));
        assertTrue(mismatch.getMessage().contains("SHA-256 mismatch"), mismatch.getMessage());

        AbortException unknown = assertThrows(AbortException.class, () -> install(tool, "3.22.0"));
        assertTrue(unknown.getMessage().contains("was not found"), unknown.getMessage());

        assertEquals("3.20.0", marker(home));
        assertRegistered(home);
        assertNoLeftovers(home);
    }

    @Test
    void latestVersionIsResolvedFromTheReleaseList(JenkinsRule j) throws Exception {
        publish("3.30.0", true);
        responses.put("/repos/harness/cli/releases",
                "[{\"tag_name\":\"har/v1.9.0\"},{\"tag_name\":\"v3.30.0\"},{\"tag_name\":\"v3.29.0\"}]"
                        .getBytes(StandardCharsets.UTF_8));
        HarnessCliInstallation tool = tool("fake-latest", "");

        FilePath home = install(tool, "");
        assertEquals("3.30.0", marker(home));
    }

    private FilePath install(HarnessCliInstallation tool, String version) throws Exception {
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        TaskListener listener = new StreamTaskListener(log, StandardCharsets.UTF_8);
        return new HarnessCliGitHubInstaller(version).performInstallation(tool, Jenkins.get(), listener);
    }

    private HarnessCliInstallation tool(String name, String version) throws IOException {
        return new HarnessCliInstallation(name, "",
                List.of(new InstallSourceProperty(List.of(new HarnessCliGitHubInstaller(version)))));
    }

    private static String marker(FilePath home) throws Exception {
        return home.child(".installed-version").readToString().trim();
    }

    private static void assertRegistered(FilePath home) throws Exception {
        assertTrue(home.child("harness").exists());
        String spec = home.child("cli-home/spec/har.spec.yaml").readToString();
        assertTrue(spec.contains(home.child("cli-home/bin/harness-har").getRemote()), spec);
        assertTrue(home.child("cli-home/bin/harness-har").exists());
    }

    private static void assertNoLeftovers(FilePath home) throws Exception {
        for (FilePath sibling : home.getParent().list()) {
            assertFalse(sibling.getName().startsWith(".staging-") || sibling.getName().startsWith(".old-"),
                    sibling.getName());
        }
    }

    /** Publishes a bundle for {@code version}; a bad checksum simulates a corrupted download. */
    private void publish(String version, boolean validChecksum) throws Exception {
        String os = HarnessOsUtils.getOs();
        String arch = HarnessOsUtils.getArch();
        String archive = "harness-bundle_" + version + "_" + os + "_" + arch + ".tar.gz";
        byte[] bundle = bundle(version);
        String sha = validChecksum
                ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bundle))
                : "0".repeat(64);
        responses.put(RELEASES_PATH + "v" + version + "/" + archive, bundle);
        responses.put(RELEASES_PATH + "v" + version + "/harness_" + version + "_checksums.txt",
                (sha + "  " + archive + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** A bundle whose {@code harness} registers {@code harness-har} like {@code harness install plugin} does. */
    private byte[] bundle(String version) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("bundle-" + version));
        String padding = "#" + "x".repeat(1_100_000) + "\n";
        Files.writeString(dir.resolve("harness"), "#!/bin/sh\n"
                + "if [ \"$1\" = install ] && [ \"$2\" = plugin ]; then\n"
                + "  mkdir -p \"$HARNESS_CLI_HOME/bin\" \"$HARNESS_CLI_HOME/spec\"\n"
                + "  cp \"$3\" \"$HARNESS_CLI_HOME/bin/harness-har\"\n"
                + "  echo \"binary_path: $HARNESS_CLI_HOME/bin/harness-har\" > \"$HARNESS_CLI_HOME/spec/har.spec.yaml\"\n"
                + "  exit 0\n"
                + "fi\n"
                + "echo harness " + version + "\n"
                + "exit 0\n" + padding);
        Files.writeString(dir.resolve("harness-har"), "#!/bin/sh\nexit 0\n" + padding);
        for (String name : new String[] {"harness", "harness-har"}) {
            Files.setPosixFilePermissions(dir.resolve(name), PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Path archive = tmp.resolve("bundle-" + version + ".tar.gz");
        Process tar = new ProcessBuilder("tar", "czf", archive.toString(), "-C", dir.toString(),
                "harness", "harness-har").inheritIO().start();
        assertEquals(0, tar.waitFor());
        return Files.readAllBytes(archive);
    }

    /** Sends every request to the local server, whatever host the installer asked for. */
    private static final class LocalhostClient extends HttpClient {
        private final HttpClient delegate = HttpClient.newHttpClient();
        private final int port;

        LocalhostClient(int port) {
            this.port = port;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            URI original = request.uri();
            URI local = URI.create("http://localhost:" + port + original.getRawPath());
            return delegate.send(HttpRequest.newBuilder(local).GET().build(), handler);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                 HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                 HttpResponse.BodyHandler<T> handler,
                                                                 HttpResponse.PushPromiseHandler<T> push) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }
    }
}
