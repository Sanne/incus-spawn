package dev.incusspawn.tui;

import dev.incusspawn.command.BaseImageAge;
import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.config.AccountUsage;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The F3 instance details on their own: which keys they keep, which they hand back, and how they look. */
class InstanceDetailViewTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    /** The clock the details read the "Created:" age against. */
    private static final java.time.LocalDateTime NOW = java.time.LocalDateTime.of(2026, 10, 11, 12, 0);

    private static InstanceDetailView view() {
        return new InstanceDetailView(new ModalRenderer(THEME), THEME, () -> NOW);
    }

    // No creation time here; the VM test pins the "Created:" row against a fixed clock.
    private static InstanceInfo instance() {
        return new InstanceInfo("dev-1", "Running", "default", "default", "",
                "container", "tpl-dev", "4", "8GiB", "20GiB", "10.0.0.5", "", "x86_64",
                "", "", "instance", "", "", "", -1, -1, "", false, null, false, null);
    }

    @Test
    void scrollKeysAreItsOwnAndTheRestAreTheTuis() {
        var view = view();
        assertEquals(InstanceDetailView.Outcome.HANDLED, view.handleKey(KeyEvent.ofKey(KeyCode.DOWN)));
        assertEquals(InstanceDetailView.Outcome.HANDLED, view.handleKey(KeyEvent.ofChar('G')));
        assertEquals(InstanceDetailView.Outcome.CLOSE, view.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertEquals(InstanceDetailView.Outcome.CLOSE, view.handleKey(KeyEvent.ofKey(KeyCode.F3)));
        assertEquals(InstanceDetailView.Outcome.ACCOUNTS, view.handleKey(KeyEvent.ofChar('a')));
        assertEquals(InstanceDetailView.Outcome.SHELL, view.handleKey(KeyEvent.ofKey(KeyCode.F2)));
        assertEquals(InstanceDetailView.Outcome.DEFAULT_ACTION, view.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals(InstanceDetailView.Outcome.UNHANDLED, view.handleKey(KeyEvent.ofChar('x')));
    }

    @Test
    void rendersAnInstanceWithItsAccounts() {
        var view = view();
        var uses = List.of(new AccountUsage.Use("github", "work", null, "", "", "", ""));
        TuiSnapshot.assertMatches("instance-detail-view-80x24",
                TuiSnapshot.render(80, 24, f -> view.render(f, f.area(), instance(), uses, List.of(), null, null, List.of(), null)));
    }

    /**
     * A line wrapped to the widest the dialog gets keeps its last cell when the dialog scrolls:
     * the scrollbar's column is added to the dialog, never taken from the text.
     */
    @Test
    void aWrappedLineIsNotCutByTheScrollbar() {
        var view = view();
        var uses = List.of(new AccountUsage.Use("github", "work", null, "", "", "", ""));
        var text = TuiSnapshot.toText(TuiSnapshot.render(80, 24,
                f -> view.render(f, f.area(), instance(), uses, List.of(), null, null, List.of(), null)));
        assertTrue(text.contains("(github.default in"), text);
    }

    @Test
    void rendersAVmWithoutTheKvmRowAndWithItsAge() {
        // Created three hours before the pinned clock, so the age reads "3h ago" on any day.
        var vm = new InstanceInfo("vm-1", "Stopped", "default", "default", "2026-10-11T09:00:00",
                "virtual-machine", "tpl-dev", "4", "8GiB", "50GiB", "", "", "x86_64",
                "", "", "instance", "", "", "", -1, -1, "", false, null, false, null);
        TuiSnapshot.assertMatches("instance-detail-view-vm-80x24",
                TuiSnapshot.render(80, 24, f -> view().render(f, f.area(), vm, List.of(), List.of(), null, null, List.of(), null)));
    }

    @Test
    void theBaseImageRowSaysWhenARebuildWouldPickUpImageFixes() {
        var older = new BaseImageAge.Assessment(BaseImageAge.Standing.OLDER, "fedora-44-20261001", "fedora-44-20261021");
        assertEquals(List.of(
                        new InstanceDetailView.DetailRow("Base image:", "fedora-44-20261001"),
                        new InstanceDetailView.DetailRow(null,
                                "older than fedora-44-20261021: 'isx doctor' says what to rebuild to pick up image fixes"),
                        new InstanceDetailView.DetailRow(null,
                                "before fedora-44-20261009 its network watchdog restarts systemd-networkd every 30 seconds")),
                InstanceDetailView.baseImageRows("fedora-44-20261001", older));
        var current = new BaseImageAge.Assessment(BaseImageAge.Standing.CURRENT, "fedora-44-20261021", "fedora-44-20261021");
        assertEquals(List.of(new InstanceDetailView.DetailRow("Base image:", "fedora-44-20261021")),
                InstanceDetailView.baseImageRows("fedora-44-20261021", current));
        var sameFile = new BaseImageAge.Assessment(BaseImageAge.Standing.CURRENT, "fedora-44-20261001", "fedora-44-20261021");
        assertEquals(List.of(new InstanceDetailView.DetailRow("Base image:", "fedora-44-20261001"),
                        new InstanceDetailView.DetailRow(null, "the same file as fedora-44-20261021")),
                InstanceDetailView.baseImageRows("fedora-44-20261001", sameFile));
        var unknown = new BaseImageAge.Assessment(BaseImageAge.Standing.UNKNOWN, null, "fedora-44-20261021");
        assertEquals(List.of(new InstanceDetailView.DetailRow("Base image:", "unknown")),
                InstanceDetailView.baseImageRows(null, unknown));
        assertEquals(List.of(), InstanceDetailView.baseImageRows(null, null), "nothing recorded, nothing to say");
    }

    /** #1720: the details name the tools a host integration registered the instance with, and nothing otherwise. */
    @Test
    void theRegisteredRowNamesTheToolsThatListTheInstance() {
        assertEquals(List.of(new InstanceDetailView.DetailRow("Registered:", "herdr"),
                        new InstanceDetailView.DetailRow(null, "isx added it there from the host, and removes it on destroy")),
                InstanceDetailView.registrationRows(List.of("herdr")));
        assertEquals(List.of(), InstanceDetailView.registrationRows(List.of()));
    }

    @Test
    void theAgentsClosingMessageIsShownALineAtATimeWithItsSource() {
        var summary = new dev.incusspawn.command.AgentSessions.Summary("newest_session", "Done", "newest of 2: Done", 2,
                java.time.LocalDateTime.of(2026, 10, 11, 10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant(),
                "Done\n\nFixed \u001b[31mit\n" + "line\n".repeat(30), "Fix it");
        var rows = InstanceDetailView.summaryRows(summary, java.time.LocalDateTime.of(2026, 10, 11, 12, 0));
        assertEquals(new InstanceDetailView.DetailRow("Done:", "newest of 2: Done"), rows.getFirst());
        assertEquals(InstanceDetailView.BLANK, rows.get(1), "a blank line between the sections");
        assertTrue(rows.get(2).value().startsWith("the agent's own words, from 2 sessions, the newest 2h"), rows.get(2).value());
        assertEquals(new InstanceDetailView.DetailRow("Asked:", "Fix it"), rows.get(3), "every label at the same indent");
        assertEquals(InstanceDetailView.BLANK, rows.get(4));
        assertEquals(new InstanceDetailView.DetailRow("Closing:", "Done"), rows.get(5));
        assertEquals(new InstanceDetailView.DetailRow("", "Fixed  [31mit"), rows.get(6), "one line, with no control character");
        assertEquals(5 + InstanceDetailView.CLOSING_LINES + 1, rows.size(), "a long message is cut, and says where the rest is");
        assertTrue(InstanceDetailView.summaryRows(null, java.time.LocalDateTime.now()).isEmpty(), "nothing before the overview read it");
    }

    @Test
    void theDetailsLinkEachPullRequestAndWrapALongMessageUnderItsValue() {
        var effort = new dev.incusspawn.proxy.EventLogReader.Effort(0, 0, 0, 0, 0, null, null,
                List.of(new dev.incusspawn.proxy.EventLogReader.Branch("Sanne/incus-spawn", "fix-x")),
                List.of(new dev.incusspawn.proxy.EventLogReader.PullRequest("Sanne/incus-spawn", 2287),
                        new dev.incusspawn.proxy.EventLogReader.PullRequest("not a repo", 3)));
        var github = InstanceDetailView.githubRows(effort, List.of(dev.incusspawn.command.PullRequestStates.State.MERGED));
        assertEquals(List.of(
                new InstanceDetailView.DetailRow("Pull requests:", "https://github.com/Sanne/incus-spawn/pull/2287 (merged)",
                        "https://github.com/Sanne/incus-spawn/pull/2287"),
                new InstanceDetailView.DetailRow("", "not a repo#3"),
                new InstanceDetailView.DetailRow("Pushed:", "Sanne/incus-spawn:fix-x")), github);
        assertTrue(InstanceDetailView.githubRows(null, List.of()).isEmpty(), "nothing before the overview read the log");

        var summary = new dev.incusspawn.command.AgentSessions.Summary("newest_session", "Done", "Done", 1,
                java.time.LocalDateTime.of(2026, 10, 11, 10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant(),
                "Done", "word ".repeat(40).strip());
        var buffer = TuiSnapshot.render(80, 50, f -> view().render(f, f.area(), instance(), List.of(), List.of(), null, null,
                github, summary));
        var lines = TuiSnapshot.toText(buffer).lines().toList();
        int y = 0;
        while (!lines.get(y).contains("Pull requests:")) y++;
        int value = lines.get(y).indexOf("https://");
        assertEquals(java.util.Optional.of("https://github.com/Sanne/incus-spawn/pull/2287"),
                buffer.get(value, y).style().hyperlink().map(dev.tamboui.style.Hyperlink::url), "a click opens the page");
        assertTrue(lines.get(y).contains("(merged)"), lines.get(y));
        int done = lines.indexOf(lines.stream().filter(l -> l.contains("Done:")).findFirst().orElseThrow());
        int asked = lines.indexOf(lines.stream().filter(l -> l.contains("Asked:")).findFirst().orElseThrow());
        int closing = lines.indexOf(lines.stream().filter(l -> l.contains("Closing:")).findFirst().orElseThrow());
        int label = lines.get(y).indexOf("Pull requests:");
        assertEquals(List.of(label, label, label), List.of(lines.get(done).indexOf("Done:"),
                lines.get(asked).indexOf("Asked:"), lines.get(closing).indexOf("Closing:")), "every label at one indent");
        assertTrue(lines.get(done + 1).replace("\u2502", "").isBlank(), "a blank line after Done: " + lines.get(done + 1));
        assertTrue(lines.get(closing - 1).replace("\u2502", "").isBlank(), "a blank line before Closing: " + lines.get(closing - 1));
        assertEquals(value, lines.get(asked).indexOf("word"));
        assertEquals(value, lines.get(asked + 1).indexOf("word"), "the rest of a long ask under its value: " + lines.get(asked + 1));
        assertTrue(lines.get(asked + 1).substring(lines.get(asked + 1).indexOf('\u2502') + 1, value).isBlank(), lines.get(asked + 1));
        assertTrue(lines.get(y - 1).replace("\u2502", "").isBlank(), "a blank line before the pull requests: " + lines.get(y - 1));
    }
}
