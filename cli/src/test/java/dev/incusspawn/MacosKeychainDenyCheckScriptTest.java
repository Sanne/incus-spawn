package dev.incusspawn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@code scripts/macos-keychain-deny-check.sh}, the guided check of a user-domain "Never
 * Trust" for a person at a Mac (#1631), against stub commands. The person's two changes in
 * Keychain Access are played by the stub {@code security}: each {@code dump-trust-settings} call
 * moves to the next state (before, denied, reset), and the stub {@code isx} and {@code ssh} answer
 * from that state.
 *
 * <p>The script is run rarely and by hand, so what matters is that it never reports a pass that
 * means nothing: an isx that ignores the deny fails it, and a premise that does not hold (the
 * guest not served by the proxy, a deny that did not land, a chain without E46's cross-signed
 * copy) stops it before it concludes anything.
 */
@EnabledOnOs(OS.LINUX)
class MacosKeychainDenyCheckScriptTest {

    private static final Path SCRIPT = Path.of("../scripts/macos-keychain-deny-check.sh").toAbsolutePath();

    private static final String CHAIN = """
            Certificate chain
             0 s:CN = *.github.com
               i:C = GB, O = Sectigo Limited, CN = Sectigo Public Server Authentication CA DV E36
             1 s:C = GB, O = Sectigo Limited, CN = Sectigo Public Server Authentication CA DV E36
               i:C = GB, O = Sectigo Limited, CN = Sectigo Public Server Authentication Root E46
             2 s:C = GB, O = Sectigo Limited, CN = Sectigo Public Server Authentication Root E46
               i:C = US, ST = New Jersey, L = Jersey City, O = The USERTRUST Network, CN = USERTrust ECC Certification Authority
            """;

    @TempDir
    Path bin;

    @Test
    void anIsxThatRefusesTheDenyAndRecoversPasses() throws Exception {
        var run = run(Map.of());
        assertEquals(0, run.exit(), run.output());
        var summary = run.output().substring(run.output().indexOf("Summary:"));
        assertEquals(6, summary.split("\\n  PASS: ", -1).length - 1, run.output());
        assertTrue(run.output().contains("Every check passed."), run.output());
        assertFalse(run.output().contains("may still be marked"), run.output());
    }

    @Test
    void withoutSshThePersonPastesWhatTheGuestsCurlPrinted() throws Exception {
        var proxied = "*  issuer: CN=incus-spawn MITM CA\n";
        var run = run(Map.of("NO_SSH", "1"), proxied + "isx-status=200\n\n" + "\n" + proxied + "isx-status=502\n\n"
                + "\n" + proxied + "isx-status=200\n\n");
        assertEquals(0, run.exit(), run.output());
        assertTrue(run.output().contains("run `isx shell dev`"), run.output());
        assertTrue(run.output().contains("PASS: the guest's curl gets 502 from the proxy"), run.output());
    }

    @Test
    void anIsxThatIgnoresTheDenyFails() throws Exception {
        var run = run(Map.of("IGNORES_DENY", "1"));
        assertEquals(1, run.exit(), run.output());
        assertTrue(run.output().contains("FAIL: isx update-base --list read GitHub's release list through a denied root"), run.output());
        assertTrue(run.output().contains("FAIL: isx doctor does not count the deny"), run.output());
        assertTrue(run.output().contains("FAIL: the guest's curl was not refused by the proxy (issuer: proxy, HTTP 200"), run.output());
    }

    @Test
    void aGuestTheProxyDoesNotServeStopsBeforeTheDeny() throws Exception {
        var run = run(Map.of("NOT_INTERCEPTED", "1"));
        assertEquals(2, run.exit(), run.output());
        assertTrue(run.output().contains("was not served by isx's proxy"), run.output());
        assertFalse(run.output().contains("Never Trust\" in your login"), run.output());
    }

    @Test
    void aDenyThatDidNotLandStopsAndSaysHowToUndoIt() throws Exception {
        var run = run(Map.of("DENY_MISSES", "1"));
        assertEquals(2, run.exit(), run.output());
        assertTrue(run.output().contains("the change did not land"), run.output());
        assertFalse(run.output().contains("PASS:"), run.output());
        assertTrue(run.output().contains("may still be marked \"Never Trust\""), run.output());
    }

    @Test
    void aChainWithoutTheCrossSignedE46Stops() throws Exception {
        var run = run(Map.of("CHAIN", CHAIN.replace("USERTrust ECC Certification Authority", "Some Inspecting Proxy")));
        assertEquals(2, run.exit(), run.output());
        assertTrue(run.output().contains("its chain changed, or something on this network inspects TLS"), run.output());
    }

    @Test
    void itRunsOnlyOnMacOs() throws Exception {
        var run = run(Map.of("OS_NAME", "Linux"));
        assertEquals(2, run.exit(), run.output());
        assertTrue(run.output().contains("this check is for macOS"), run.output());
    }

    private record Run(int exit, String output) {}

    private Run run(Map<String, String> env) throws IOException, InterruptedException {
        // The person pressing Enter at both prompts
        return run(env, "\n\n");
    }

    private Run run(Map<String, String> env, String input) throws IOException, InterruptedException {
        var state = bin.resolve("state");
        // The person's two changes: dump-trust-settings reads before, after the deny, after the reset
        stub("security", """
                n=$(cat "$STATE.calls" 2>/dev/null || echo 0); n=$((n + 1)); echo $n > "$STATE.calls"
                case $n in 2) [ -n "${DENY_MISSES:-}" ] && s=default || s=denied ;; *) s=default ;; esac
                echo $s > "$STATE"
                [ $s = denied ] || { echo "SecTrustSettingsCopyCertificates: No Trust Settings were found." >&2; exit 1; }
                printf 'Number of trusted certs = 1\\nCert 0: Sectigo Public Server Authentication Root E46\\n'
                printf '   Number of trust settings : 1\\n   Trust Setting 0:\\n      Result Type           : kSecTrustSettingsResultDeny\\n'
                """);
        // What a deny means to a working isx, unless IGNORES_DENY says it reads none
        var denied = "{ [ \"$(cat \"$STATE\" 2>/dev/null)\" = denied ] && [ -z \"${IGNORES_DENY:-}\" ]; }";
        stub("isx", """
                case "$1" in
                  --version) echo "isx 0.0.0-test" ;;
                  update-base) if %1$s; then echo "certificate with the subject and key of one denied in the Keychain's trust settings" >&2; exit 1; fi; echo "fedora-44 2026-01-01" ;;
                  doctor) if %1$s; then n=3; else n=2; fi
                          printf 'ok\\tKeychain CAs trusted for TLS\\t(CN=Corp sha256:ab; %%s denied (security dump-trust-settings, with -d for an administrator'"'"'s))\\t\\n' $n
                          exit 1 ;;
                  proxy) echo "Proxy restarted" ;;
                esac
                """.formatted(denied));
        stub("ssh", """
                [ -n "${NO_SSH:-}" ] && exit 255
                for last; do :; done
                [ "$last" = true ] && exit 0
                if [ -n "${NOT_INTERCEPTED:-}" ]; then echo "*  issuer: C=GB; O=Sectigo Limited; CN=Sectigo Public Server Authentication CA DV E36"
                else echo "*  issuer: CN=incus-spawn MITM CA"; fi
                if %s; then echo isx-status=502; else echo isx-status=200; fi
                """.formatted(denied));
        stub("openssl", "printf '%s' \"$CHAIN\"\n");
        stub("uname", "echo \"${OS_NAME:-Darwin}\"\n");
        stub("file", "echo 'Mach-O 64-bit executable arm64'\n");

        var output = bin.resolve("output.log");
        var pb = new ProcessBuilder("/bin/bash", SCRIPT.toString(), "dev")
                .redirectErrorStream(true).redirectOutput(output.toFile());
        pb.environment().putAll(Map.of("PATH", bin + ":/usr/bin:/bin", "STATE", state.toString(), "CHAIN", CHAIN));
        pb.environment().putAll(env);
        var process = pb.start();
        try (var in = process.getOutputStream()) {
            in.write(input.getBytes());
        } catch (IOException e) {
            // The script stopped before reading what the person would type: a broken pipe, not a failure
        }
        var finished = process.waitFor(60, TimeUnit.SECONDS);
        process.destroyForcibly();
        var out = Files.readString(output);
        assertTrue(finished, "the script hung:\n" + out);
        return new Run(process.exitValue(), out);
    }

    private void stub(String name, String body) throws IOException {
        var file = bin.resolve(name);
        Files.writeString(file, "#!/bin/bash\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
