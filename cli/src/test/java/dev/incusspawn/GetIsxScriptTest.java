package dev.incusspawn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code get-isx.sh}, what {@code curl -fsSL https://isx.run | sh} runs, verifies every download
 * against the release's {@code SHA256SUMS} before anything reaches {@code INSTALL_DIR} (#2080).
 * Run against a stand-in {@code curl} serving a fake release, whose sums are written by the release
 * job's own {@code .github/scripts/release-sha256sums.sh}, so the two halves are tested together.
 */
class GetIsxScriptTest {

    private static final Path SCRIPT = Path.of("..", "get-isx.sh");
    private static final Path SUMS_SCRIPT = Path.of("..", ".github", "scripts", "release-sha256sums.sh");
    private static final Path RELEASE_WORKFLOW = Path.of("..", ".github", "workflows", "release.yml");
    private static final String VERSION = "v9.9.9";
    private static final List<String> INSTALLED = List.of("isx", "isx-proxy", "git-remote-isx");

    @TempDir
    Path dir;
    Path release;
    Path installDir;

    private record Run(int exit, String out) {}

    @BeforeEach
    void fakeRelease() throws Exception {
        for (var tool : List.of("sh", "bash", "sha256sum", "awk")) assumeTrue(onPath(tool), tool + " is not installed");
        release = Files.createDirectories(dir.resolve("release"));
        installDir = dir.resolve("install");
        var assets = new ArrayList<String>();
        for (var name : List.of("incus-spawn-linux-amd64", "isx-proxy-linux-amd64",
                "incus-spawn-linux-aarch64", "isx-proxy-linux-aarch64", "git-remote-isx")) {
            Files.writeString(release.resolve(name), "#!/bin/sh\necho " + name + "\n");
            assets.add(release.resolve(name).toString());
        }
        assertEquals(0, sums(release.resolve("SHA256SUMS"), assets).exit());

        var bin = Files.createDirectories(dir.resolve("bin"));
        // The stand-in curl: the API names VERSION, a release download copies the fake asset, or
        // writes a few bytes and fails as an interrupted transfer does when cut-<name> exists.
        script(bin.resolve("curl"), """
                #!/bin/sh
                out=""; url=""
                while [ $# -gt 0 ]; do
                  case "$1" in -o) out=$2; shift ;; https://*) url=$1 ;; esac
                  shift
                done
                case "$url" in
                  https://api.github.com/repos/Sanne/incus-spawn/releases/latest)
                    printf '{\\n  "tag_name": "%s",\\n}\\n' "$VERSION"; exit 0 ;;
                  https://github.com/Sanne/incus-spawn/releases/download/$VERSION/*)
                    name=${url##*/}; echo "$name" >> "$STATE/requested"
                    [ -f "$RELEASE/$name" ] || exit 22
                    if [ -f "$STATE/cut-$name" ]; then head -c 5 "$RELEASE/$name" > "$out"; exit 18; fi
                    cp "$RELEASE/$name" "$out"; exit 0 ;;
                esac
                exit 6
                """);
        script(bin.resolve("uname"), """
                #!/bin/sh
                case "$1" in -s) echo Linux ;; -m) echo "$FAKE_ARCH" ;; esac
                """);
    }

    private static boolean onPath(String tool) {
        for (var d : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (!d.isEmpty() && Files.isExecutable(Path.of(d, tool))) return true;
        }
        return false;
    }

    private static void script(Path path, String body) throws IOException {
        Files.writeString(path, body);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Run exec(List<String> cmd, Map<String, String> env) throws Exception {
        var log = dir.resolve("log-" + System.nanoTime());
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().putAll(env);
        var p = pb.start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        return new Run(p.exitValue(), Files.readString(log));
    }

    private Run sums(Path out, List<String> assets) throws Exception {
        var cmd = new ArrayList<>(List.of("bash", SUMS_SCRIPT.toString(), out.toString()));
        cmd.addAll(assets);
        return exec(cmd, Map.of());
    }

    private Run install(String arch) throws Exception {
        return exec(List.of("sh", SCRIPT.toString()), Map.of(
                "PATH", dir.resolve("bin") + ":" + System.getenv("PATH"),
                "HOME", dir.toString(),
                "INSTALL_DIR", installDir.toString(),
                "STATE", dir.toString(),
                "RELEASE", release.toString(),
                "VERSION", VERSION,
                "FAKE_ARCH", arch));
    }

    private void assertNothingInstalled(Run run) throws IOException {
        assertNotEquals(0, run.exit(), run.out());
        assertTrue(run.out().contains("nothing was installed"), run.out());
        if (Files.isDirectory(installDir)) {
            try (var files = Files.list(installDir)) {
                assertEquals(List.of(), files.map(f -> f.getFileName().toString()).toList(),
                        "a refused download must leave INSTALL_DIR as it was");
            }
        }
    }

    @Test
    void installsWhatMatchesTheReleasesSums() throws Exception {
        var run = install("x86_64");

        assertEquals(0, run.exit(), run.out());
        assertEquals("#!/bin/sh\necho incus-spawn-linux-amd64\n", Files.readString(installDir.resolve("isx")));
        assertEquals("#!/bin/sh\necho isx-proxy-linux-amd64\n", Files.readString(installDir.resolve("isx-proxy")));
        for (var name : INSTALLED) assertTrue(Files.isExecutable(installDir.resolve(name)), name);
        try (var files = Files.list(installDir)) {
            assertEquals(Set.copyOf(INSTALLED), Set.copyOf(files.map(f -> f.getFileName().toString()).toList()),
                    "no temporary file stays behind");
        }
    }

    @Test
    void aTamperedDownloadInstallsNothing() throws Exception {
        Files.writeString(release.resolve("isx-proxy-linux-amd64"), "#!/bin/sh\necho evil\n");

        var run = install("x86_64");

        assertNothingInstalled(run);
        assertTrue(run.out().contains("isx-proxy-linux-amd64 does not match"), run.out());
    }

    @Test
    void aTamperedDownloadLeavesThePreviousInstallInPlace() throws Exception {
        Files.createDirectories(installDir);
        Files.writeString(installDir.resolve("isx"), "previous isx");
        Files.writeString(release.resolve("git-remote-isx"), "#!/bin/sh\necho evil\n");

        var run = install("x86_64");

        assertNotEquals(0, run.exit(), run.out());
        assertEquals("previous isx", Files.readString(installDir.resolve("isx")),
                "an earlier file passing its check must not be installed when a later one fails");
        try (var files = Files.list(installDir)) {
            assertEquals(List.of("isx"), files.map(f -> f.getFileName().toString()).toList());
        }
    }

    @Test
    void anInterruptedDownloadInstallsNothing() throws Exception {
        Files.writeString(dir.resolve("cut-incus-spawn-linux-aarch64"), "");

        assertNothingInstalled(install("aarch64"));
    }

    @Test
    void aReleaseWithoutSumsInstallsNothing() throws Exception {
        Files.delete(release.resolve("SHA256SUMS"));

        var run = install("x86_64");

        assertNothingInstalled(run);
        assertTrue(run.out().contains("publishes no SHA256SUMS"), run.out());
    }

    @Test
    void anAssetTheSumsDoNotListInstallsNothing() throws Exception {
        var sums = release.resolve("SHA256SUMS");
        Files.write(sums, Files.readAllLines(sums).stream().filter(l -> !l.endsWith("  git-remote-isx")).toList());

        var run = install("x86_64");

        assertNothingInstalled(run);
        assertTrue(run.out().contains("does not list git-remote-isx"), run.out());
    }

    /**
     * What the script fetches, on every architecture it installs on, the release uploads, and
     * through the one list its SHA256SUMS is written from: an asset the sums did not cover would make
     * every install fail, and one the release does not upload a 404.
     */
    @Test
    void everyAssetTheScriptFetchesIsInTheReleasesSums() throws Exception {
        var uploaded = releaseAssets();
        for (var arch : List.of("x86_64", "aarch64")) {
            Files.deleteIfExists(dir.resolve("requested"));
            assertEquals(0, install(arch).exit(), arch);
            var requested = new LinkedHashSet<>(Files.readAllLines(dir.resolve("requested")));
            assertTrue(requested.remove("SHA256SUMS"), arch + ": the script must fetch the sums");
            assertEquals(3, requested.size(), arch + ": " + requested);
            for (var name : requested) {
                assertTrue(uploaded.contains(name), arch + ": get-isx.sh fetches " + name
                        + ", which release.yml neither sums nor uploads (" + uploaded + ")");
            }
        }
    }

    /** The basenames listed in the "Create GitHub Release" step, which feed both the sums and the upload. */
    private static List<String> releaseAssets() throws IOException {
        var workflow = Files.readString(RELEASE_WORKFLOW);
        var step = workflow.indexOf("- name: Create GitHub Release");
        assertTrue(step >= 0, "release.yml must create the GitHub release");
        var start = workflow.indexOf("set -- \\\n", step);
        var sums = workflow.indexOf(".github/scripts/release-sha256sums.sh SHA256SUMS \"$@\"", start);
        var create = workflow.indexOf("gh release create $GITHUB_REF_NAME \"$@\" SHA256SUMS", sums);
        assertTrue(start > step && sums > start && create > sums,
                "the release step must list its assets once, write their SHA256SUMS, and upload both");
        return workflow.substring(start + "set -- \\\n".length(), sums).lines()
                .map(l -> l.strip().replace(" \\", ""))
                .filter(l -> !l.isEmpty())
                .map(l -> l.substring(l.lastIndexOf('/') + 1))
                .toList();
    }

    @Test
    void theReleaseRefusesAnArtifactThatChangedSinceItsBuild() throws Exception {
        var built = Files.createDirectories(dir.resolve("artifacts/native-linux-amd64"));
        var binary = Files.writeString(built.resolve("incus-spawn-linux-amd64"), "built");
        assertEquals(0, sums(built.resolve("SHA256SUMS"), List.of(binary.toString())).exit());
        var out = dir.resolve("SHA256SUMS");
        assertEquals(0, sums(out, List.of(binary.toString())).exit());

        Files.writeString(binary, "changed");
        var run = sums(out, List.of(binary.toString()));

        assertNotEquals(0, run.exit());
        assertTrue(run.out().contains("the job that built it recorded"), run.out());
    }

    @Test
    void theReleaseRefusesAnArtifactItsBuildDidNotList() throws Exception {
        var built = Files.createDirectories(dir.resolve("artifacts/native-linux-amd64"));
        var listed = Files.writeString(built.resolve("isx-proxy-linux-amd64"), "proxy");
        assertEquals(0, sums(built.resolve("SHA256SUMS"), List.of(listed.toString())).exit());
        var other = Files.writeString(built.resolve("incus-spawn-linux-amd64"), "cli");

        var run = sums(dir.resolve("SHA256SUMS"), List.of(other.toString()));

        assertNotEquals(0, run.exit());
        assertTrue(run.out().contains("does not list it"), run.out());
    }

    @Test
    void theReleaseRefusesTwoAssetsOfOneName() throws Exception {
        var a = Files.writeString(Files.createDirectories(dir.resolve("a")).resolve("git-remote-isx"), "a");
        var b = Files.writeString(Files.createDirectories(dir.resolve("b")).resolve("git-remote-isx"), "b");

        var run = sums(dir.resolve("SHA256SUMS"), List.of(a.toString(), b.toString()));

        assertNotEquals(0, run.exit());
        assertTrue(run.out().contains("named twice"), run.out());
    }
}
