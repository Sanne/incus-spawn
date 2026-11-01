package dev.incusspawn;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@code appliance/kernel/update-kernel-pin.sh} offline: a copy of it sits beside a fixture
 * {@code build-kernel.sh} and a throwaway signing key, and a stub {@code curl} serves fixture
 * {@code sha256sums.asc} and {@code releases.json}. The copy's pinned fingerprint is the throwaway
 * key's; the shipped script has no switch to change either.
 *
 * <p>Pins #1355: a kernel pin is only ever taken from the part of kernel.org's
 * {@code sha256sums.asc} that verifies against the committed autosigner key, moves only forward
 * within its series, and {@code check} fails a pin that does not match.
 */
@EnabledOnOs(value = OS.LINUX, architectures = {"amd64", "x86_64", "aarch64"})
class KernelPinUpdateTest {

    private static final Path KERNEL_DIR = Path.of("../appliance/kernel").toAbsolutePath();
    private static final Path SCRIPT = KERNEL_DIR.resolve("update-kernel-pin.sh");

    private static String script;
    private static String shippedFpr;
    private static Path keys;
    private static String testFpr;

    @TempDir
    Path dir;

    private Path served;

    @BeforeAll
    static void generateKeys() throws Exception {
        script = Files.readString(SCRIPT);
        var m = Pattern.compile("(?m)^AUTOSIGNER_FPR=\"([0-9A-F]{40})\"$").matcher(script);
        assertTrue(m.find(), "AUTOSIGNER_FPR is not set in " + SCRIPT);
        shippedFpr = m.group(1);
        // gpg-agent's socket path must stay short, so not under the build directory.
        keys = Files.createTempDirectory(Path.of("/tmp"), "kpin");
        Files.setPosixFilePermissions(keys, PosixFilePermissions.fromString("rwx------"));
        for (var name : List.of("signer", "stranger")) {
            gpg("--quick-gen-key", name + "@test.invalid", "ed25519", "sign", "never");
        }
        testFpr = fingerprint("signer@test.invalid");
        Files.writeString(keys.resolve("signer.asc"), gpg("--armor", "--export", "signer@test.invalid"));
    }

    @AfterAll
    static void stopAgent() throws Exception {
        if (keys == null) return;
        run(new ProcessBuilder("gpgconf", "--homedir", keys.toString(), "--kill", "all"));
        try (var walk = Files.walk(keys)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        served = Files.createDirectory(dir.resolve("served"));
        var bin = Files.createDirectory(dir.resolve("bin"));
        // Serves the file named like the URL's last segment to curl's -o.
        Files.writeString(bin.resolve("curl"), """
                #!/bin/bash
                out=""; url=""
                while [ $# -gt 0 ]; do case "$1" in -o) out=$2; shift ;; https://*) url=$1 ;; esac; shift; done
                [ -f "$SERVED/${url##*/}" ] || exit 22
                cp "$SERVED/${url##*/}" "$out"
                """);
        Files.setPosixFilePermissions(bin.resolve("curl"), PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void bumpTakesTheNewestPointReleaseOfThePinnedSeriesFromTheSignedFile() throws Exception {
        var copy = install("7.2.9", sum(9), testFpr);
        serveSigned("signer", sums("7.2.2", "7.2.9", "7.2.10", "7.3", "7.20.1", "6.18.56")
                + sum(11) + "  patch-7.2.11.xz\n" + sum(12) + "  ChangeLog-7.2.12\n");

        var result = script(copy, "bump");

        assertEquals(0, result.exit, result.output);
        assertEquals("7.2.10 " + sumOf("7.2.10"), result.output.strip());
        assertPin(copy, "7.2.10", sumOf("7.2.10"));
    }

    @Test
    void bumpLeavesACurrentPinAlone() throws Exception {
        var copy = install("7.2.10", sumOf("7.2.10"), testFpr);
        serveSigned("signer", sums("7.2.9", "7.2.10"));

        var result = script(copy, "bump");

        assertEquals(0, result.exit, result.output);
        assertEquals("", result.output.strip());
        assertPin(copy, "7.2.10", sumOf("7.2.10"));
    }

    @Test
    void bumpNeverMovesThePinBack() throws Exception {
        var copy = install("7.2.10", sumOf("7.2.10"), testFpr);
        serveSigned("signer", sums("7.2.8", "7.2.9"));

        var result = script(copy, "bump");

        assertEquals(0, result.exit, result.output);
        assertEquals("", result.output.strip());
        assertPin(copy, "7.2.10", sumOf("7.2.10"));
    }

    @Test
    void bumpReadsNothingOutsideTheSignedBlock() throws Exception {
        var copy = install("7.2.9", sum(9), testFpr);
        serveSigned("signer", sums("7.2.10"));
        Files.writeString(served.resolve("sha256sums.asc"), sum(99) + "  linux-7.2.99.tar.xz\n",
                java.nio.file.StandardOpenOption.APPEND);

        var result = script(copy, "bump");

        assertEquals(0, result.exit, result.output);
        assertPin(copy, "7.2.10", sumOf("7.2.10"));
    }

    @Test
    void aTamperedSumIsRejected() throws Exception {
        var copy = install("7.2.9", sum(9), testFpr);
        serveSigned("signer", sums("7.2.10"));
        var asc = served.resolve("sha256sums.asc");
        Files.writeString(asc, Files.readString(asc).replace(sumOf("7.2.10"), sum(66)));

        var result = script(copy, "bump");

        assertNotEquals(0, result.exit, result.output);
        assertTrue(result.output.contains("failed signature verification"), result.output);
        assertPin(copy, "7.2.9", sum(9));
    }

    @Test
    void aSignatureByAnyOtherKeyIsRejected() throws Exception {
        // The stranger's key is not in the keyring the script trusts.
        var copy = install("7.2.9", sum(9), testFpr);
        serveSigned("stranger", sums("7.2.10"));

        var result = script(copy, "bump");

        assertNotEquals(0, result.exit, result.output);
        assertPin(copy, "7.2.9", sum(9));
    }

    @Test
    void aGoodSignatureFromAKeyThatIsNotThePinnedFingerprintIsRejected() throws Exception {
        // The keyring holds the signer, but the copy keeps the shipped fingerprint.
        var copy = install("7.2.9", sum(9), shippedFpr);
        serveSigned("signer", sums("7.2.10"));

        var result = script(copy, "bump");

        assertNotEquals(0, result.exit, result.output);
        assertTrue(result.output.contains("is not signed by " + shippedFpr), result.output);
        assertPin(copy, "7.2.9", sum(9));
    }

    @Test
    void checkPassesOnlyAPinThatMatchesTheSignedSum() throws Exception {
        serveSigned("signer", sums("7.2.9", "7.2.10"));

        var good = script(install("7.2.10", sumOf("7.2.10"), testFpr), "check");
        assertEquals(0, good.exit, good.output);

        var wrong = script(install("7.2.10", sum(1), testFpr), "check");
        assertNotEquals(0, wrong.exit, wrong.output);
        assertTrue(wrong.output.contains("kernel.org's signed sum is " + sumOf("7.2.10")), wrong.output);

        var absent = script(install("7.2.11", sum(1), testFpr), "check");
        assertNotEquals(0, absent.exit, absent.output);
        assertTrue(absent.output.contains("not in kernel.org's signed sha256sums.asc"), absent.output);
    }

    @Test
    void checkOfAnotherFileVerifiesThatFilesPinWithThisCopysKey() throws Exception {
        // As kernel-pin-signed.yml runs it: main's copy, a PR's build-kernel.sh.
        var main = install("7.2.9", sum(9), testFpr);
        var pr = Files.createDirectory(dir.resolve("pr")).resolve("build-kernel.sh");
        serveSigned("signer", sums("7.2.9", "7.2.10"));

        Files.writeString(pr, "KERNEL_VERSION=\"7.2.10\"\nKERNEL_SHA256=\"" + sumOf("7.2.10") + "\"\n");
        var good = script(main, "check", pr.toString());
        assertEquals(0, good.exit, good.output);
        assertTrue(good.output.contains("for 7.2.10 matches"), good.output);

        Files.writeString(pr, "KERNEL_VERSION=\"7.2.10\"\nKERNEL_SHA256=\"" + sum(66) + "\"\n");
        var wrong = script(main, "check", pr.toString());
        assertNotEquals(0, wrong.exit, wrong.output);
        assertPin(main, "7.2.9", sum(9));
    }

    @Test
    void aSecondAssignmentOfThePinFailsTheCheckWhateverItsForm() throws Exception {
        // build-kernel.sh would use the last assignment; the check would read the canonical one.
        var main = install("7.2.9", sum(9), testFpr);
        var pr = Files.createDirectory(dir.resolve("pr")).resolve("build-kernel.sh");
        serveSigned("signer", sums("7.2.10"));
        var good = "KERNEL_VERSION=\"7.2.10\"\nKERNEL_SHA256=\"" + sumOf("7.2.10") + "\"\n";
        for (var extra : List.of("KERNEL_SHA256='" + sum(66) + "'", "export KERNEL_SHA256=" + sum(66),
                "  KERNEL_SHA256=\"" + sum(66) + "\"", "[ -n x ] && KERNEL_VERSION=7.2.11", "KERNEL_SHA256+=x")) {
            Files.writeString(pr, good + extra + "\n");
            var result = script(main, "check", pr.toString());
            assertNotEquals(0, result.exit, extra + ":\n" + result.output);
            assertTrue(result.output.contains("once each"), extra + ":\n" + result.output);
        }
        Files.writeString(pr, good + "echo \"$KERNEL_SHA256\" \"${KERNEL_VERSION%%.*}\"\n# override with KERNEL_VERSION=7.2.x to try one\n");
        var reads = script(main, "check", pr.toString());
        assertEquals(0, reads.exit, "reading the pin, or a comment naming it, is not setting it:\n" + reads.output);
    }

    @Test
    void aPinFromSumsSignedByAnotherKeyFailsMainsCheckWhateverThePrsCopyTrusts() throws Exception {
        // A PR that swaps key, fingerprint and pin together passes its own copy; main's does not.
        var main = install("7.2.9", sum(9), testFpr);
        var pr = Files.createDirectory(dir.resolve("pr")).resolve("build-kernel.sh");
        Files.writeString(pr, "KERNEL_VERSION=\"7.2.10\"\nKERNEL_SHA256=\"" + sum(66) + "\"\n");
        serveSigned("stranger", sum(66) + "  linux-7.2.10.tar.xz\n");

        var result = script(main, "check", pr.toString());

        assertNotEquals(0, result.exit, result.output);
        assertTrue(result.output.contains("failed signature verification"), result.output);
    }

    @Test
    void twoDifferentSignedSumsForOneTarballFailRatherThanPickOne() throws Exception {
        var copy = install("7.2.9", sum(9), testFpr);
        serveSigned("signer", sums("7.2.9", "7.2.10") + sum(66) + "  linux-7.2.10.tar.xz\n");

        var result = script(copy, "bump");

        assertNotEquals(0, result.exit, result.output);
        assertTrue(result.output.contains("more than one sum"), result.output);
        assertPin(copy, "7.2.9", sum(9));
    }

    @Test
    void pinnedPrintsTheVersionTheScriptReads() throws Exception {
        assertEquals("7.2.10", script(install("7.2.10", sumOf("7.2.10"), testFpr), "pinned").output.strip());
    }

    @Test
    void eolNamesASeriesKernelOrgEndedOrNoLongerLists() throws Exception {
        var copy = install("7.2.10", sumOf("7.2.10"), testFpr);

        Files.writeString(served.resolve("releases.json"), releases("7.2.10", false));
        assertEquals("", script(copy, "eol").output.strip());

        Files.writeString(served.resolve("releases.json"), releases("7.2.11", true));
        assertEquals("7.2", script(copy, "eol").output.strip());

        Files.writeString(served.resolve("releases.json"), releases("7.20.1", false));
        assertEquals("7.2", script(copy, "eol").output.strip());
    }

    @Test
    void theCommittedKeyIsThePinnedFingerprintAndNothingElse() throws Exception {
        var shown = gpg("--with-colons", "--import-options", "show-only", "--import",
                KERNEL_DIR.resolve("kernel.org-autosigner.asc").toString());
        var primaries = shown.lines().filter(l -> l.startsWith("pub:")).count();
        var fprs = shown.lines().filter(l -> l.startsWith("fpr:")).map(l -> l.split(":")[9]).toList();

        assertEquals(1, primaries, "kernel.org-autosigner.asc must hold exactly one key:\n" + shown);
        assertEquals(List.of(shippedFpr), fprs,
                "kernel.org-autosigner.asc and AUTOSIGNER_FPR must name the same key, with no subkeys");
    }

    // --- fixtures ---

    private record Result(int exit, String output) {}

    /** A copy of the script with its own build-kernel.sh, key and pinned fingerprint. */
    private Path install(String version, String sha256, String fpr) throws IOException {
        var copy = Files.createTempDirectory(dir, "kernel");
        Files.writeString(copy.resolve("build-kernel.sh"),
                "#!/bin/bash\nKERNEL_VERSION=\"" + version + "\"\n# comment\nKERNEL_SHA256=\"" + sha256 + "\"\n");
        Files.copy(keys.resolve("signer.asc"), copy.resolve("kernel.org-autosigner.asc"));
        Files.writeString(copy.resolve("update-kernel-pin.sh"), script.replace(shippedFpr, fpr));
        return copy;
    }

    private Result script(Path copy, String... args) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of("/bin/bash", copy.resolve("update-kernel-pin.sh").toString()));
        cmd.addAll(List.of(args));
        var pb = new ProcessBuilder(cmd);
        pb.environment().put("PATH", dir.resolve("bin") + ":" + System.getenv("PATH"));
        pb.environment().put("SERVED", served.toString());
        return run(pb);
    }

    private void serveSigned(String signer, String body) throws Exception {
        var plain = dir.resolve("sums.txt");
        Files.writeString(plain, body);
        var asc = served.resolve("sha256sums.asc");
        Files.deleteIfExists(asc);
        gpg("--local-user", signer + "@test.invalid", "--clearsign", "--output", asc.toString(), plain.toString());
    }

    private static String sums(String... versions) {
        var out = new StringBuilder();
        for (var v : versions) out.append(sumOf(v)).append("  linux-").append(v).append(".tar.xz\n");
        return out.toString();
    }

    /** A distinct, well-formed sum per version. */
    private static String sumOf(String version) {
        return sum(version.hashCode() & 0x7fffffff);
    }

    private static String sum(long n) {
        return String.format("%064x", n);
    }

    private static String releases(String stable, boolean eol) {
        return """
                {"releases": [
                  {"moniker": "mainline", "version": "7.3-rc7", "iseol": false},
                  {"moniker": "stable", "version": "%s", "iseol": %s},
                  {"moniker": "longterm", "version": "6.18.56", "iseol": false}
                ]}""".formatted(stable, eol);
    }

    private static void assertPin(Path copy, String version, String sha256) throws IOException {
        var text = Files.readString(copy.resolve("build-kernel.sh"));
        assertTrue(text.contains("KERNEL_VERSION=\"" + version + "\"\n"), text);
        assertTrue(text.contains("KERNEL_SHA256=\"" + sha256 + "\"\n"), text);
    }

    private static String fingerprint(String uid) throws Exception {
        return gpg("--with-colons", "--list-keys", uid).lines()
                .filter(l -> l.startsWith("fpr:")).findFirst().orElseThrow().split(":")[9];
    }

    private static String gpg(String... args) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of("gpg", "--homedir", keys.toString(), "--batch",
                "--quiet", "--pinentry-mode", "loopback", "--passphrase", ""));
        cmd.addAll(List.of(args));
        var result = run(new ProcessBuilder(cmd));
        assertEquals(0, result.exit, String.join(" ", cmd) + "\n" + result.output);
        return result.output;
    }

    /** Output goes to a file, so a process that never closes it cannot outlast the timeout. */
    private static Result run(ProcessBuilder pb) throws Exception {
        var log = Files.createTempFile(keys, "out", ".log");
        var process = pb.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        var finished = process.waitFor(60, TimeUnit.SECONDS);
        process.destroyForcibly();
        var output = Files.readString(log);
        Files.delete(log);
        assertTrue(finished, "hung: " + pb.command() + "\n" + output);
        return new Result(process.exitValue(), output);
    }
}
