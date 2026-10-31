package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuildLogTest {

    @Test
    void anEntryIsTheCommandAsAShellShowsItAndAllItPrinted() {
        var entry = BuildLog.entry("repo 'app' prime", "cd ~/app &&\n./gradlew dependencies",
                new IncusClient.ExecResult(1, "out\n", "err\n"), Duration.ofMillis(1500));
        assertEquals("""
                ─── repo 'app' prime ───
                $ cd ~/app &&
                > ./gradlew dependencies
                out
                err
                ─── exit 1 after 1.5s ───

                """, entry);
    }

    @Test
    void anEntryCannotDriveTheTerminalOrKeepACredential() {
        // A guest's output and a definition's command are untrusted, and the log is read with cat
        var entry = BuildLog.entry("tool 'x'\033[2J run", "echo \033]8;;https://evil\007hi",
                new IncusClient.ExecResult(0, "\033[31mred\033[0m token ghp_" + "a".repeat(36) + "\n", ""),
                Duration.ZERO);
        assertFalse(entry.contains("\033"), entry);
        assertFalse(entry.contains("\007"), entry);
        assertFalse(entry.contains("ghp_" + "a".repeat(36)), entry);
        assertTrue(entry.contains("red token <isx:redacted:"), entry);
    }

    @Test
    void anEntryKeepsNoneOfTheBuildsOwnProofs() {
        // A prime's login shell sources /run/isx/proof-tokens, so `env` prints the build's proofs
        var gh = "gho_isx_" + "0123456789abcdef".repeat(2);
        var vertex = "ya29.isx_" + "fedcba9876543210".repeat(2);
        var entry = BuildLog.entry("repo 'app' prime", "env",
                new IncusClient.ExecResult(0, "GH_TOKEN=" + gh + "\nCLOUDSDK_AUTH_ACCESS_TOKEN=" + vertex + "\n", ""),
                Duration.ZERO);
        assertFalse(entry.contains(gh) || entry.contains(vertex), entry);
        assertTrue(entry.contains("GH_TOKEN=gho_<isx:redacted:isx-proof>"), entry);
        assertTrue(entry.contains("CLOUDSDK_AUTH_ACCESS_TOKEN=ya29.<isx:redacted:isx-proof>"), entry);
    }

    @Test
    void anEntryKeepsNoSecretWhoseShapeOnlyTheRawOutputShows() {
        // The armour is put together at run time, so a secret scanner does not take this for a key
        var armour = "-----";
        // Made safe to show, a control byte ends the URL password at a space and a carriage return
        // drops the key's BEGIN line: what the raw output shows is redacted too (#1878)
        var url = BuildLog.entry("s", "c",
                new IncusClient.ExecResult(0, "https://u:hunter2\u0001secretpw@host\n", ""), Duration.ZERO);
        assertFalse(url.contains("secretpw") || url.contains("hunter2"), url);
        assertTrue(url.contains("https://u:<isx:redacted:url-credentials>@host"), url);
        var key = BuildLog.entry("s", "c", new IncusClient.ExecResult(1, "",
                armour + "BEGIN PRIVATE KEY" + armour + "\rcloning...\nfake-key-body\n" + armour + "END PRIVATE KEY" + armour + "\n"), Duration.ZERO);
        assertFalse(key.contains("fake-key-body"), key);
        assertTrue(key.contains("<isx:redacted:private-key>"), key);
        assertFalse(key.contains("\r"), key);
    }

    @Test
    void anEntryKeepsNoSecretTheCommandsShownFormHides() {
        // A definition's own command is scrubbed raw as well as shown, as what it printed is (#1886)
        var entry = BuildLog.entry("repo 'app' prime", "git clone https://u:hunter2\u0001secretpw@host/r",
                new IncusClient.ExecResult(1, "", "fatal\n"), Duration.ZERO);
        assertFalse(entry.contains("secretpw") || entry.contains("hunter2"), entry);
        assertTrue(entry.contains("$ git clone https://u:<isx:redacted:url-credentials>@host/r\n"), entry);
    }

    @Test
    void anEntryKeepsNoTokenColourSplitsAfterItsPrefix() {
        // The raw pass alone would stop the token at the escape and keep the digest's second half
        var half = "0123456789abcdef";
        var entry = BuildLog.entry("s", "c", new IncusClient.ExecResult(0,
                "CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-isx_" + half + "\u001b[m\u001b[K" + half + "\n", ""), Duration.ZERO);
        assertFalse(entry.contains(half), entry);
        assertTrue(entry.contains("CLAUDE_CODE_OAUTH_TOKEN=<isx:redacted:"), entry);
    }

    @Test
    void theLogIsWrittenAsEachCommandEndsAndReplacesTheLastBuilds(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("build-logs/tpl-app.log");
        try (var old = BuildLog.open(file, "tpl-app", false)) {
            old.record("tool 'old' run", "echo old", new IncusClient.ExecResult(0, "old\n", ""), Duration.ZERO);
        }
        var log = BuildLog.open(file, "tpl-app", false);
        assertEquals("", log.reportLine(), "nothing ran yet");
        log.record("tool 'x' run", "echo hi", new IncusClient.ExecResult(0, "hi\n", ""), Duration.ZERO);
        var written = Files.readString(file);
        assertTrue(written.startsWith("isx build tpl-app, started "), written);
        assertTrue(written.contains("$ echo hi\nhi\n"), written);
        assertFalse(written.contains("old"), written);
        assertEquals(List.of(), log.lines(), "only --verbose keeps the entries in memory");
        assertEquals(file, log.savedFile());
        assertTrue(log.reportLine().contains(file.toString()), log.reportLine());
        log.close();
    }

    @Test
    void aLogThatCannotBeWrittenSaysSoOnceAndStillKeepsItsEntries(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build-logs"), "a file where the directory goes");
        var log = BuildLog.open(tmp.resolve("build-logs/tpl-app.log"), "tpl-app", true);
        log.record("tool 'x' run", "echo hi", new IncusClient.ExecResult(0, "hi\n", ""), Duration.ZERO);
        var out = RecordingOutput.during(log::close);
        assertEquals(1, out.events().size(), out.events().toString());
        assertTrue(out.events().getFirst().msg().startsWith("Could not save the build log to "), out.events().toString());
        assertTrue(String.join("\n", log.lines()).contains("$ echo hi"));
        assertEquals("", log.reportLine(), "a report must not point at a log that is not there");
        assertNull(log.savedFile());
    }

    @Test
    void onlyItsOwnerReadsTheLog(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("build-logs/tpl-app.log");
        BuildLog.open(file, "tpl-app", false).close();
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        // The last build's, readable by others, is made owner-only again
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        BuildLog.open(file, "tpl-app", false).close();
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void aCommandWhoseExecThrowsIsRecordedWithWhy() {
        // It may be the very command the build failed on
        var incus = org.mockito.Mockito.mock(IncusClient.class);
        org.mockito.Mockito.when(incus.execInContainer("c", "agentuser", "./slow"))
                .thenThrow(new dev.incusspawn.incus.IncusException("exec timed out"));
        var recorded = new java.util.ArrayList<String>();
        var container = new dev.incusspawn.incus.Container(incus, "c", RecordingOutput.QUIET,
                (step, command, result, took) -> recorded.add(command + " | " + result.exitCode() + " | " + result.stderr()));

        assertThrows(dev.incusspawn.incus.IncusException.class,
                () -> container.runDeclaredAsUser("tool 'x' run_as_user", "agentuser", "./slow", "x failed"));
        assertEquals(List.of("./slow | -1 | isx could not run it: exec timed out"), recorded);
    }

    @Test
    void aKeyHeaderACommandPrintsNeverHidesHowItEnded() {
        var armour = "-----";
        var entry = BuildLog.entry("tool 'x' verify", "head -1 id_rsa",
                new IncusClient.ExecResult(3, armour + "BEGIN OPENSSH PRIVATE KEY" + armour + "\n", ""), Duration.ofMillis(200));
        assertTrue(entry.endsWith("─── exit 3 after 0.2s ───\n\n"), entry);
        assertFalse(entry.contains("OPENSSH"), entry);
    }

    @Test
    void aCommandRecordedOnceTheLogIsClosedMakesItPartial(@TempDir Path tmp) {
        // An interrupted build's hook closes the log while a prime may still end
        var log = BuildLog.open(tmp.resolve("build-logs/tpl-app.log"), "tpl-app", false);
        log.record("tool 'x' run", "echo a", new IncusClient.ExecResult(0, "", ""), Duration.ZERO);
        log.close();
        log.record("repo 'app' prime", "./slow", new IncusClient.ExecResult(0, "", ""), Duration.ZERO);
        assertEquals("", log.reportLine(), "it no longer holds every command");
    }
}
