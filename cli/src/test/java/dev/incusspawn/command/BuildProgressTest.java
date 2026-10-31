package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuildProgressTest {

    // --- StepProgress ---

    @Test
    void guestOutputInAStepCannotDriveTheTerminal() {
        // What a guest command printed, such as a repository's prime, is shown as text (#1588):
        // control sequences are dropped, and every other control a terminal acts on becomes a space.
        var p = BuildProgress.StepProgress.failed("\033[31mboom\033[0m\033]52;c;eA==\007\u009b2J\u0007end",
                "\033[31mred\033[0m\r\n\033]0;pwned\007tab\there\n");
        assertEquals("boom end", p.detail());
        assertEquals("red\ntab\there\n", p.log());
        var running = BuildProgress.StepProgress.running("Installing", "1/2  \033]8;;x\007pkg");
        assertEquals("1/2  pkg", running.detail());
    }

    @Test
    void aFailedStepKeepsNoSecretOnlyItsRawOutputShows() {
        // The armour is put together at run time, so a secret scanner does not take this for a key
        var armour = "-----";
        // Its detail and log become the failure report's: scrubbed as printed and as shown (#1878)
        var p = BuildProgress.StepProgress.failed("https://u:hunter2\u0001secretpw@host",
                armour + "BEGIN PRIVATE KEY" + armour + "\rcloning...\nfake-key-body\n" + armour + "END PRIVATE KEY" + armour + "\nhttps://u:hunter2\u0001secretpw@host\n");
        assertEquals("https://u:<isx:redacted:url-credentials>@host", p.detail());
        assertEquals("<isx:redacted:private-key>\nhttps://u:<isx:redacted:url-credentials>@host\n", p.log());
    }

    @Test
    void aFailedStepsDetailFromItsLogIsScrubbedAsTheLogIs() {
        // A key cut short ends the log: its last body line alone has no BEGIN header to match
        // The armour is put together at run time, so a secret scanner does not take this for a key
        var armour = "-----";
        var log = "fetching\n" + armour + "BEGIN PRIVATE KEY" + armour + "\nMIIEfirst\nMIIElast-body\n";
        var p = BuildProgress.StepProgress.failed(BuildProgress.lastNonEmptyLine(log), log);
        assertEquals("<isx:redacted:private-key>", p.detail());
        assertFalse(p.log().contains("MIIElast-body"), p.log());
        // A prime's detail names its command before the log's last line
        var prime = BuildProgress.StepProgress.failed("prime", "make",
                "prime `make` failed: " + BuildProgress.lastNonEmptyLine(log), log);
        assertEquals("prime `make` failed: <isx:redacted:private-key>", prime.detail());
        // Any other detail is scrubbed on its own
        assertEquals("exit 1", BuildProgress.StepProgress.failed("exit 1", log).detail());
    }

    @Test
    void aStepsLabelCannotDriveTheTerminal() {
        // A label can hold definition text, such as a COPR repository's name (#1610).
        var recorded = dev.incusspawn.util.RecordingOutput.during(() -> assertThrows(dev.incusspawn.incus.IncusException.class,
                () -> BuildProgress.runWithSpinner("Enabling COPR repo \033]0;pwned\007x", "Failed",
                        state -> state.set(0, BuildProgress.StepProgress.failed("boom", "log")))));
        var shown = String.join("\n", recorded.lines());
        assertTrue(shown.contains("COPR repo  ]0;pwned x"), shown);
        assertEquals(dev.incusspawn.util.TerminalLink.plainText(shown).replace("\033", "ESC"),
                shown.replace("\033", "ESC"), "the label's escapes must not reach the terminal");
    }

    @Test
    void anAnimatedStepLineShowsItsLabelAndContextAsText() {
        // RepoCloner draws a repository's label and URL straight through the renderer (#1610).
        var line = BuildProgress.formatStepLine("\033]0;pwned\007app", "https://x/\033[2J\u009b2Japp.git",
                BuildProgress.StepProgress.running("Cloning"), 0, "Ready");
        assertTrue(line.contains(" ]0;pwned app"), line.replace("\033", "ESC"));
        assertTrue(line.contains("https://x/ [2J 2Japp.git"), line.replace("\033", "ESC"));
        assertFalse(line.contains("\033]") || line.contains("\033[2J") || line.contains("\u009b"), line.replace("\033", "ESC"));
    }

    @Test
    void theFailureLineIsTheLastOneATerminalShowsText() {
        assertEquals("npm error", BuildProgress.lastNonEmptyLine("\033[31mnpm error\n\033[0m\n\033]0;t\007"));
        assertEquals("100%", BuildProgress.lastNonEmptyLine(" 10%\r 50%\r100%"));
        assertEquals("fatal: denied", RepoCloner.firstGitError("remote: x\n\033[31mfatal: denied\033[0m\n\033]0;t\007\n"));
    }

    // --- formatStepLine ---

    @Test
    void formatStepLineShowsSpinnerWhileRunning() {
        var p = BuildProgress.StepProgress.running("Cloning");
        var line = BuildProgress.formatStepLine("alpha", "url", p, 0, "Ready");
        assertTrue(stripAnsi(line).contains("Cloning"), "should show running verb");
        assertTrue(line.contains("alpha"), "should show the label");
    }

    @Test
    void formatStepLineShowsLiveDetailAfterTheLabel() {
        var p = BuildProgress.StepProgress.running("Creating clean Git snapshot", "12s  Receiving objects:  45% (12345/67890)");
        var line = stripAnsi(BuildProgress.formatStepLine("quarkus", "https://github.com/quarkusio/quarkus.git", p, 0, "Ready"));
        assertTrue(line.indexOf("quarkus") < line.indexOf("Receiving objects"), line);
        assertTrue(line.indexOf("Receiving objects") < line.indexOf("(https://"), "the URL goes last: " + line);
        assertTrue(line.indexOf("quarkus") < 50, "the name survives an 80-column cut: " + line);
    }

    @Test
    void formatStepLineShowsCheckmarkAndNoteWhenDone() {
        var p = BuildProgress.StepProgress.done("via host reference");
        var line = BuildProgress.formatStepLine("alpha", "url", p, 0, "Ready");
        assertTrue(line.contains("✓"), "should show checkmark");
        assertTrue(line.contains("Ready"), "should show done verb");
        assertTrue(line.contains("via host reference"), "should show the done note");
    }

    @Test
    void aFailedPrimesCommandKeepsNoSecretOnlyItsRawFormShows() {
        // The command is the failure report's too: scrubbed as written and as shown (#1886)
        var command = "git clone https://u:hunter2\u0001secretpw@host/r";
        var p = BuildProgress.StepProgress.failed("prime", command, "prime failed", "fatal\n");
        assertEquals("git clone https://u:<isx:redacted:url-credentials>@host/r", p.command());
        // The failure line names the command as RepoCloner shows it, scrubbed the same way
        assertEquals("git clone https://u:<isx:redacted:url-credentials>@host/r", RepoCloner.commandText(command));
    }

    @Test
    void formatStepLineShowsErrorDetailWhenFailed() {
        var p = BuildProgress.StepProgress.failed("fatal: nope", null);
        var line = BuildProgress.formatStepLine("alpha", "url", p, 0, "Ready");
        assertTrue(line.contains("✗"), "should show cross");
        assertTrue(line.contains("fatal: nope"), "should show the error detail");
    }

    @Test
    void formatStepLineAlignsLabelAcrossStates() {
        var running = stripAnsi(BuildProgress.formatStepLine("alpha", null,
                BuildProgress.StepProgress.running("Cloning"), 0, "Ready"));
        var done = stripAnsi(BuildProgress.formatStepLine("alpha", null,
                BuildProgress.StepProgress.done(null), 0, "Ready"));
        var failed = stripAnsi(BuildProgress.formatStepLine("alpha", null,
                BuildProgress.StepProgress.failed(null, null), 0, "Ready"));
        assertEquals(running.indexOf("alpha"), done.indexOf("alpha"), "label aligns RUNNING vs DONE");
        assertEquals(done.indexOf("alpha"), failed.indexOf("alpha"), "label aligns DONE vs FAILED");
    }

    // --- dnf progress parsing (real dnf5 5.4.2.1 non-TTY output) ---

    private static String dnfDetail(String line) {
        var state = new java.util.concurrent.atomic.AtomicReferenceArray<BuildProgress.StepProgress>(1);
        state.set(0, BuildProgress.StepProgress.running("", "starting"));
        BuildProgress.onDnfLine(line, new StringBuilder(), state);
        return state.get(0).detail();
    }

    @Test
    void onDnfLineParsesTransactionStep() {
        // "[3/6] Reinstalling setup-0:2.15.0-28.fc 100% |  26.4 MiB/s | 730.6 KiB |  00m00s"
        // The version/arch tail is dropped, leaving the bare package name.
        assertEquals("3/6  Reinstalling setup",
                dnfDetail("[3/6] Reinstalling setup-0:2.15.0-28.fc 100% |  26.4 MiB/s | 730.6 KiB |  00m00s"));
    }

    @Test
    void onDnfLineParsesDownloadStep() {
        // "[1/2] filesystem-0:3.18-52.fc44.aarch64 100% |   2.5 MiB/s |   1.3 MiB |  00m01s"
        assertEquals("1/2  filesystem",
                dnfDetail("[1/2] filesystem-0:3.18-52.fc44.aarch64 100% |   2.5 MiB/s |   1.3 MiB |  00m01s"));
    }

    @Test
    void onDnfLineParsesMultiWordAction() {
        // No NEVRA, so nothing is stripped.
        assertEquals("1/6  Verify package files",
                dnfDetail("[1/6] Verify package files              100% | 222.0   B/s |   2.0   B |  00m00s"));
    }

    @Test
    void shortenNevraStripsVersionButKeepsHyphenatedName() {
        assertEquals("glibc-gconv-extra",
                BuildProgress.shortenNevra("glibc-gconv-extra-0:2.43-8.fc44.aarch64"));
        assertEquals("Installing make",
                BuildProgress.shortenNevra("Installing make-1:4.4.1-12.fc44.aarch64"));
        // A name truncated before the epoch marker has no "-<epoch>:" and is left as-is.
        assertEquals("some-very-long-package-nam",
                BuildProgress.shortenNevra("some-very-long-package-nam"));
    }

    @Test
    void onDnfLineSkipsDownloadSubtotal() {
        var state = new java.util.concurrent.atomic.AtomicReferenceArray<BuildProgress.StepProgress>(1);
        state.set(0, BuildProgress.StepProgress.running("", "before"));
        BuildProgress.onDnfLine("[2/2] Total                             100% |   1.4 MiB/s |   1.5 MiB |  00m01s",
                new StringBuilder(), state);
        assertEquals("before", state.get(0).detail(), "the download 'Total' subtotal line should be ignored");
    }

    @Test
    void onDnfLineIgnoresNonProgressLines() {
        var state = new java.util.concurrent.atomic.AtomicReferenceArray<BuildProgress.StepProgress>(1);
        state.set(0, BuildProgress.StepProgress.running("", "before"));
        var log = new StringBuilder();
        BuildProgress.onDnfLine("Running transaction", log, state);
        BuildProgress.onDnfLine("Repositories loaded.", log, state);
        assertEquals("before", state.get(0).detail(), "non-[N/M] lines shouldn't change the detail");
        assertTrue(log.toString().contains("Running transaction"), "all lines are still logged");
    }

    /** A step whose work throws (an Incus transport error) fails with the cause, its stack trace kept for the report. */
    @Test
    void aStepThatThrowsFailsWithItsCause() {
        var failure = new dev.incusspawn.incus.IncusException[1];
        var said = dev.incusspawn.util.RecordingOutput.during(() -> failure[0] = assertThrows(dev.incusspawn.incus.IncusException.class,
                () -> BuildProgress.runWithSpinner("Doing x", "Failed to do x", state -> {
                    throw new RuntimeException("transport boom");
                })));
        assertEquals(java.util.List.of(dev.incusspawn.util.RecordingOutput.Kind.STEP_START,
                        dev.incusspawn.util.RecordingOutput.Kind.STEP_PROGRESS, dev.incusspawn.util.RecordingOutput.Kind.STEP_BREAK,
                        dev.incusspawn.util.RecordingOutput.Kind.WARN),
                said.events().stream().map(dev.incusspawn.util.RecordingOutput.Event::kind).toList(), said.events()::toString);
        assertTrue(said.warnings().getFirst().startsWith("Doing x failed: transport boom"), said.warnings()::toString);
        assertEquals("Failed to do x: transport boom", failure[0].getMessage());
        assertTrue(failure[0].stepOutput().contains("RuntimeException: transport boom"), "the stack trace is the report's log");
    }

    /** A step that succeeded only on dnf's retry says so beside its ✓. */
    @Test
    void aDnfStepThatNeededARefreshSaysSo() {
        var container = org.mockito.Mockito.mock(dev.incusspawn.incus.Container.class);
        org.mockito.Mockito.when(container.execLines(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(String[].class)))
                .thenReturn(1, 0);
        var said = dev.incusspawn.util.RecordingOutput.during(() -> BuildProgress.runDnf(container, "Installing packages",
                "Failed to install packages", "dnf", "install", "-y", "x"));
        assertEquals(new dev.incusspawn.util.RecordingOutput.Event(dev.incusspawn.util.RecordingOutput.Kind.STEP_DONE,
                "succeeded after refresh"), said.events().getLast());
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;]*m", "");
    }

    @Test
    void transferShowsPercentRateAndTimeLeft() {
        long mb = 1024 * 1024;
        assertEquals("25%  100.0 MB / 400.0 MB  10.0 MB/s  30s left",
                BuildProgress.describeTransfer(100 * mb, 0, 400 * mb, 10_000_000_000L));
        // Without a Content-Length there is no percentage or estimate.
        assertEquals("100.0 MB  10.0 MB/s", BuildProgress.describeTransfer(100 * mb, 0, -1, 10_000_000_000L));
        // Within the first second the rate is not yet meaningful.
        assertEquals("0%  64.0 KB / 400.0 MB", BuildProgress.describeTransfer(64 * 1024, 0, 400 * mb, 200_000_000L));
        // A resumed download counts the bytes it started from in the percentage, not in the rate.
        assertEquals("75%  300.0 MB / 400.0 MB  10.0 MB/s  10s left",
                BuildProgress.describeTransfer(300 * mb, 200 * mb, 400 * mb, 10_000_000_000L));
    }

    @Test
    void durationsReadAtAGlance() {
        assertEquals("42s", BuildProgress.formatDuration(42_000_000_000L));
        assertEquals("5m 07s", BuildProgress.formatDuration(307_000_000_000L));
        assertEquals("1h 03m", BuildProgress.formatDuration(3_780_000_000_000L));
    }

    @Test
    void transferProgressDescribesEachPhase() {
        var progress = new BuildProgress.TransferProgress();
        assertEquals("connecting", progress.detail());
        progress.received(1024, 4096);
        assertTrue(progress.detail().startsWith("25%  1.0 KB / 4.0 KB"), progress.detail());
        assertTrue(progress.fetched());
        progress.retrying("HTTP 500 from example.com", 2, 3);
        assertEquals("HTTP 500 from example.com, retrying (attempt 2 of 3)", progress.detail());
        // Resumed where the body broke off: the percentage carries on from there.
        progress.received(1024, 4096);
        assertTrue(progress.detail().startsWith("25%  1.0 KB / 4.0 KB"), progress.detail());
        progress.verifying();
        assertEquals("verifying checksum", progress.detail());

        var cached = new BuildProgress.TransferProgress();
        cached.verifyingCached();
        assertEquals("verifying cached copy", cached.detail());
        assertFalse(cached.fetched());
    }

    /**
     * A failed step's captured log is one warning, showing its end, and the exception carries all
     * of it for the build's failure report (#1703); before, it was raw stderr between two rules.
     */
    @Test
    void aFailedStepsLogIsAWarningAndTheExceptionCarriesItAll() {
        var log = new StringBuilder();
        for (int i = 1; i <= 60; i++) log.append("line ").append(i).append('\n');
        var thrown = new dev.incusspawn.incus.IncusException[1];
        var recorded = dev.incusspawn.util.RecordingOutput.during(() -> thrown[0] = assertThrows(
                dev.incusspawn.incus.IncusException.class,
                () -> BuildProgress.runWithSpinner("Enabling COPR repo x", "Failed to enable",
                        state -> state.set(0, BuildProgress.StepProgress.failed("boom", log.toString())))));
        var output = recorded.events(dev.incusspawn.util.RecordingOutput.Kind.WARN).stream()
                .filter(e -> e.msg().equals("Enabling COPR repo x failed: boom")).toList();
        assertEquals(1, output.size(), recorded.events().toString());
        assertEquals("line 60", output.getFirst().details().getLast());
        assertEquals("Failed to enable: boom", thrown[0].getMessage());
        assertEquals("Enabling COPR repo x failed:\n" + log.toString().strip() + "\n\n", thrown[0].stepOutput());
    }

    /** A container whose dnf prints {@code lines} and exits {@code code}. */
    private static dev.incusspawn.incus.Container dnf(int code, String... lines) {
        var container = org.mockito.Mockito.mock(dev.incusspawn.incus.Container.class);
        org.mockito.Mockito.when(container.execLines(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(String[].class)))
                .thenAnswer(call -> {
                    java.util.function.Consumer<String> sink = call.getArgument(0);
                    for (var line : lines) sink.accept(line);
                    return code;
                });
        return container;
    }

    /**
     * A dnf step is the seam's live step (#1791), drawn by the one renderer every step uses: its
     * start, its progress, and its ✓, or its ✗ and then the end of its log as a warning.
     */
    @Test
    void aDnfStepIsTheSeamsLiveStep() {
        var done = dev.incusspawn.util.RecordingOutput.during(() -> BuildProgress.runDnf(
                dnf(0, "[3/6] Installing setup-0:2.15.0-28.fc  100% | 26 MiB/s"), "Installing packages",
                "Failed to install packages", "dnf", "install", "-y", "setup"));
        assertEquals(java.util.List.of(dev.incusspawn.util.RecordingOutput.Kind.STEP_START,
                        dev.incusspawn.util.RecordingOutput.Kind.STEP_PROGRESS, dev.incusspawn.util.RecordingOutput.Kind.STEP_DONE),
                done.events().stream().map(dev.incusspawn.util.RecordingOutput.Event::kind).toList(), done.events()::toString);
        assertEquals("Installing packages", done.events().getFirst().msg());

        var failed = dev.incusspawn.util.RecordingOutput.during(() -> assertThrows(dev.incusspawn.incus.IncusException.class,
                () -> BuildProgress.runDnf(dnf(1, "Error: Unable to find a match: nonesuch"), "Installing packages",
                        "Failed to install packages", "dnf", "install", "-y", "nonesuch")));
        assertEquals(java.util.List.of(dev.incusspawn.util.RecordingOutput.Kind.STEP_START,
                        dev.incusspawn.util.RecordingOutput.Kind.STEP_PROGRESS, dev.incusspawn.util.RecordingOutput.Kind.STEP_BREAK,
                        dev.incusspawn.util.RecordingOutput.Kind.WARN),
                failed.events().stream().map(dev.incusspawn.util.RecordingOutput.Event::kind).toList(), failed.events()::toString);
        assertEquals(new dev.incusspawn.util.RecordingOutput.Event(dev.incusspawn.util.RecordingOutput.Kind.WARN,
                "Installing packages failed", "Error: Unable to find a match: nonesuch"), failed.events().getLast());
    }

    /** These tests read what a plain terminal shows, which a test run from a console is not. */
    private static void assumePlainTerminal() {
        org.junit.jupiter.api.Assumptions.assumeFalse(dev.incusspawn.util.TerminalProgress.isAnsiTerminal(),
                "the tests' console is an ANSI terminal");
    }

    /** What a plain terminal receives from {@code work}: its stdout, then its stderr. */
    private static String[] plainTerminal(Runnable work) {
        assumePlainTerminal();
        var out = new java.io.ByteArrayOutputStream();
        var err = new java.io.ByteArrayOutputStream();
        var terminal = dev.incusspawn.util.TerminalOutput.to(
                new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8),
                new java.io.PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8));
        try (var bound = dev.incusspawn.util.BuildOutput.bind(terminal)) {
            assertThrows(dev.incusspawn.incus.IncusException.class, work::run);
        }
        return new String[] {out.toString(java.nio.charset.StandardCharsets.UTF_8),
                err.toString(java.nio.charset.StandardCharsets.UTF_8)};
    }

    /**
     * Off an ANSI terminal a failed dnf step is its label, ended, then one warning with the end of
     * its log under it, whose last line is its detail: no second {@code failed} line.
     */
    @Test
    void aFailedDnfStepIsItsLabelThenOneWarningOnAPlainTerminal() {
        var shown = plainTerminal(() -> BuildProgress.runDnf(dnf(1, "Error: Unable to find a match: nonesuch"),
                "Installing packages", "Failed to install packages", "dnf", "install", "-y", "nonesuch"));
        assertEquals("    Installing packages...\n\n", shown[0], "the label, then the warning's closing blank line");
        assertEquals("""
                    ⚠ Installing packages failed
                      Error: Unable to find a match: nonesuch
                """, shown[1]);
    }

    /** A failed step with no log is its label, ended: the exception, which the command reports, has its detail. */
    @Test
    void aFailedLiveStepIsOneIndentedLineOnAPlainTerminal() {
        var shown = plainTerminal(() -> BuildProgress.runLiveStep("Downloading image", () -> null,
                "Failed to download image", state -> state.set(0, BuildProgress.StepProgress.failed("HTTP 404", null))));
        assertEquals("    Downloading image...\n", shown[0]);
        assertEquals("", shown[1]);
    }
}
