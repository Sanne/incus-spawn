package dev.incusspawn;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A runner's apt reads Azure's Ubuntu mirror first, and when that mirror crawls a plain
 * {@code apt-get update && apt-get install} sits silent until the job's timeout: {@code macos-role}
 * lost its whole 20 minutes that way, and every apt step of that afternoon took 4 to 19 (#1993).
 * apt's own {@code Acquire::http::Timeout} cannot see a mirror that is slow rather than silent.
 * So every workflow installs through {@code .github/scripts/apt-install.sh}, which bounds the update
 * and the download, moves to the next mirror when either stalls, and runs dpkg only once every
 * package is downloaded.
 */
class WorkflowAptInstallTest {

    private static final Path WORKFLOWS = Path.of("../.github/workflows");
    private static final Path SCRIPT = Path.of("../.github/scripts/apt-install.sh");
    private static final String INVOCATION = "sudo .github/scripts/apt-install.sh ";
    private static final Pattern RAW_APT = Pattern.compile(
            "\\bapt(?:-get)?\\s[^\\n]*\\b(?:update|install|upgrade|dist-upgrade|full-upgrade|build-dep)\\b");
    private static final Pattern WORKFLOW_FILE = Pattern.compile(".*\\.ya?ml");

    @TempDir
    Path dir;

    @Test
    void everyWorkflowInstallsThroughTheBoundedScript() throws IOException {
        var offenders = new ArrayList<String>();
        int bounded = 0;
        try (var files = Files.list(WORKFLOWS)) {
            for (var file : files.filter(f -> WORKFLOW_FILE.matcher(f.getFileName().toString()).matches()).sorted().toList()) {
                var jobs = JqYaml.parse(Files.readString(file)).path("jobs");
                for (var job : jobs.asMap().entrySet()) {
                    boolean checkedOut = false;
                    for (var step : job.getValue().path("steps").asList()) {
                        // release.yml checks out with a `git clone` step of its own
                        checkedOut |= step.pathText("uses", "").startsWith("actions/checkout@")
                                || step.pathText("name", "").equals("Checkout") && step.pathText("run", "").startsWith("git clone");
                        var run = step.pathText("run", "");
                        var where = file.getFileName() + " " + job.getKey() + ": " + step.pathText("name", "");
                        if (RAW_APT.matcher(run).find()) offenders.add(where + " runs apt directly");
                        if (run.contains("apt-install.sh")) {
                            bounded++;
                            if (!run.contains(INVOCATION)) offenders.add(where + " must run it as `" + INVOCATION.strip() + "`");
                            if (!checkedOut) offenders.add(where + " runs it before the checkout");
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "install packages with `" + INVOCATION + "<package>...` after the checkout (#1993):\n"
                + String.join("\n", offenders));
        assertTrue(bounded > 0, "no workflow uses " + SCRIPT);
    }

    private static boolean onPath(String tool) {
        for (var d : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (!d.isEmpty() && Files.isExecutable(Path.of(d, tool))) return true;
        }
        return false;
    }

    private record Run(int exit, String out, List<String> apt, List<String> mirrors) {}

    /**
     * Runs the script against a stand-in apt-get that logs each call and, while a mirror named
     * {@code slow} is still listed (or there is no list), never finishes the phase named by {@code stallOn}.
     */
    private Run run(String stallOn, String mirrorList, String... packages) throws Exception {
        return runWithFallback("", stallOn, mirrorList, packages);
    }

    /**
     * As {@link #run(String, String, String...)}, with the given fallback mirror and a stand-in
     * resolver that knows every host but those named {@code unresolvable...}.
     */
    private Run runWithFallback(String fallback, String stallOn, String mirrorList, String... packages) throws Exception {
        for (var tool : List.of("bash", "timeout", "awk", "cat", "mktemp", "tail")) assumeTrue(onPath(tool), tool + " is not installed");
        var bin = Files.createDirectories(dir.resolve("bin"));
        var apt = bin.resolve("apt-get");
        Files.writeString(apt, """
                #!/bin/bash
                printf '%s\\n' "$*" >> "$STATE/apt.log"
                case " $* " in *" $STALL_ON "*) { [ ! -f "$APT_MIRRORS" ] || grep -q slow "$APT_MIRRORS"; } && exec sleep 60 ;; esac
                exit 0
                """);
        Files.setPosixFilePermissions(apt, PosixFilePermissions.fromString("rwxr-xr-x"));
        var getent = bin.resolve("getent");
        Files.writeString(getent, """
                #!/bin/bash
                case "$2" in unresolvable*) exit 2 ;; esac
                echo "192.0.2.1 STREAM $2"
                """);
        Files.setPosixFilePermissions(getent, PosixFilePermissions.fromString("rwxr-xr-x"));
        var mirrors = dir.resolve("apt-mirrors.txt");
        if (mirrorList != null) Files.writeString(mirrors, mirrorList);
        var cmd = new ArrayList<>(List.of("bash", SCRIPT.toString()));
        cmd.addAll(List.of(packages));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(dir.resolve("out").toFile());
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().putAll(Map.of("STATE", dir.toString(), "STALL_ON", stallOn,
                "APT_MIRRORS", mirrors.toString(), "APT_PHASE_TIMEOUT", "1", "APT_ATTEMPTS", "3",
                "APT_FALLBACK_MIRROR", fallback));
        var p = pb.start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the script must not wait for a stalled apt");
        var log = dir.resolve("apt.log");
        return new Run(p.exitValue(), Files.readString(dir.resolve("out")),
                Files.exists(log) ? Files.readAllLines(log) : List.of(),
                Files.exists(mirrors) ? Files.readAllLines(mirrors) : List.of());
    }

    private static final String RUNNER_MIRRORS = """
            http://slow.archive.ubuntu.com/ubuntu/\tpriority:1
            https://archive.ubuntu.com/ubuntu/\tpriority:2
            https://security.ubuntu.com/ubuntu/\tpriority:3
            """;

    private static void assertInstalledOnceFromDownloads(Run run, String packages) {
        var installs = run.apt().stream().filter(c -> c.contains("install") && !c.contains("--download-only")).toList();
        assertEquals(List.of("-qq install -y --no-download " + packages), installs,
                "dpkg runs once, after the download, and never fetches");
    }

    @Test
    void anUpdateStalledOnTheFirstMirrorMovesToTheNext() throws Exception {
        var run = run("update", RUNNER_MIRRORS, "qemu-system-x86", "socat");
        assertEquals(0, run.exit(), run.out());
        assertEquals(2, run.apt().stream().filter(c -> c.endsWith(" update")).count(), run.apt().toString());
        assertEquals(List.of("https://archive.ubuntu.com/ubuntu/\tpriority:2", "https://security.ubuntu.com/ubuntu/\tpriority:3"),
                run.mirrors());
        assertTrue(run.out().contains("did not finish within 1s"), run.out());
        assertInstalledOnceFromDownloads(run, "qemu-system-x86 socat");
    }

    @Test
    void aDownloadStalledOnTheFirstMirrorMovesToTheNext() throws Exception {
        var run = run("--download-only", RUNNER_MIRRORS, "zsh");
        assertEquals(0, run.exit(), run.out());
        assertEquals(2, run.apt().stream().filter(c -> c.contains("--download-only")).count(), run.apt().toString());
        assertFalse(run.mirrors().getFirst().contains("slow"), run.mirrors().toString());
        assertInstalledOnceFromDownloads(run, "zsh");
    }

    @Test
    void theMirrorDroppedIsTheLowestPriorityNotTheFirstLine() throws Exception {
        var run = run("update", """
                https://archive.ubuntu.com/ubuntu/\tpriority:2
                http://slow.archive.ubuntu.com/ubuntu/\tpriority:1
                """, "zsh");
        assertEquals(0, run.exit(), run.out());
        assertEquals(List.of("https://archive.ubuntu.com/ubuntu/\tpriority:2"), run.mirrors());
        assertInstalledOnceFromDownloads(run, "zsh");
    }

    @Test
    void stallingOnEveryAttemptFailsWithoutRunningDpkgOrDroppingTheLastMirror() throws Exception {
        var run = run("update", "http://slow.archive.ubuntu.com/ubuntu/\tpriority:1\n", "zsh");
        assertEquals(1, run.exit(), run.out());
        assertEquals(3, run.apt().size(), "three bounded attempts: " + run.apt());
        assertEquals(List.of("http://slow.archive.ubuntu.com/ubuntu/\tpriority:1"), run.mirrors(),
                "the only mirror stays: apt with no mirror at all could not even try");
        assertTrue(run.out().contains("::error::apt-install: no attempt of 3 installed zsh"), run.out());
    }

    @Test
    void withoutAMirrorListAStallIsStillBoundedAndRetried() throws Exception {
        var run = run("update", null, "zsh");
        assertEquals(1, run.exit(), run.out());
        assertEquals(3, run.apt().size(), run.apt().toString());
        assertTrue(run.apt().stream().noneMatch(c -> c.contains("--no-download")), run.apt().toString());
    }

    private static final String FALLBACK = "https://mirrors.edge.kernel.org/ubuntu/";

    @Test
    void mirrorsWhoseHostDoesNotResolveAreDroppedForTheFallback() throws Exception {
        // #2146: the runner's resolver failed every ubuntu.com name for hours, and each index then
        // waited out the resolver on every mirror until all three attempts ran out
        var run = runWithFallback(FALLBACK, "none", """
                http://unresolvable.azure.archive.ubuntu.com/ubuntu/\tpriority:1
                https://unresolvable.archive.ubuntu.com/ubuntu/\tpriority:2
                https://unresolvable.security.ubuntu.com/ubuntu/\tpriority:3
                """, "zsh");
        assertEquals(0, run.exit(), run.out());
        assertEquals(List.of(FALLBACK + "\tpriority:4"), run.mirrors());
        assertEquals(1, run.apt().stream().filter(c -> c.endsWith(" update")).count(), run.apt().toString());
        assertTrue(run.out().contains("dropping mirrors whose host does not resolve"), run.out());
        assertInstalledOnceFromDownloads(run, "zsh");
    }

    @Test
    void theFallbackIsListedLastAndResolvingMirrorsStay() throws Exception {
        var run = runWithFallback(FALLBACK, "none", RUNNER_MIRRORS.replace("https://security", "https://unresolvable.security").strip(), "zsh");
        assertEquals(0, run.exit(), run.out());
        assertEquals(List.of("http://slow.archive.ubuntu.com/ubuntu/\tpriority:1", "https://archive.ubuntu.com/ubuntu/\tpriority:2",
                FALLBACK + "\tpriority:4"), run.mirrors());
    }

    @Test
    void theFallbackIsNotListedTwice() throws Exception {
        var run = runWithFallback(FALLBACK, "none", RUNNER_MIRRORS + FALLBACK + "\tpriority:9\n", "zsh");
        assertEquals(0, run.exit(), run.out());
        assertEquals(List.of("http://slow.archive.ubuntu.com/ubuntu/\tpriority:1", "https://archive.ubuntu.com/ubuntu/\tpriority:2",
                "https://security.ubuntu.com/ubuntu/\tpriority:3", FALLBACK + "\tpriority:9"), run.mirrors());
    }

    @Test
    void theFallbackStaysLastInAListWithoutPriorities() throws Exception {
        // apt tries an entry without priority:N after every tagged one, so a tagged fallback would go first
        var run = runWithFallback(FALLBACK, "none", "https://archive.ubuntu.com/ubuntu/\n", "zsh");
        assertEquals(0, run.exit(), run.out());
        assertEquals(List.of("https://archive.ubuntu.com/ubuntu/", FALLBACK), run.mirrors());
    }

    @Test
    void aPortsListGetsNoFallback() throws Exception {
        // the fallback carries amd64's archive only, and an arm64 runner reads ubuntu-ports
        var list = "http://azure.ports.ubuntu.com/ubuntu-ports/\tpriority:1\nhttp://ports.ubuntu.com/ubuntu-ports/\tpriority:2\n";
        var run = runWithFallback(FALLBACK, "none", list, "zsh");
        assertEquals(0, run.exit(), run.out());
        assertEquals(List.of("http://azure.ports.ubuntu.com/ubuntu-ports/\tpriority:1", "http://ports.ubuntu.com/ubuntu-ports/\tpriority:2"),
                run.mirrors());
    }

    @Test
    void whenNoHostResolvesEveryMirrorStays() throws Exception {
        var list = "http://unresolvable.a/ubuntu/\tpriority:1\nhttp://unresolvable.b/ubuntu/\tpriority:2\n";
        var run = run("none", list, "zsh");
        assertEquals(0, run.exit(), run.out());
        assertEquals(List.of("http://unresolvable.a/ubuntu/\tpriority:1", "http://unresolvable.b/ubuntu/\tpriority:2"), run.mirrors());
        assertTrue(run.out().contains("no mirror's host resolves"), run.out());
    }
}
