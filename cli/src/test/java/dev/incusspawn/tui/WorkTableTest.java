package dev.incusspawn.tui;

import dev.incusspawn.command.PullRequestStates;
import dev.incusspawn.command.WorkState;
import dev.incusspawn.proxy.EventLogReader;
import dev.tamboui.text.CharWidth;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Work tab's cells (design/terminal.md "The overview of what each instance did"), each a pure function. */
class WorkTableTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    @Test
    void idleIsCompactAndChangesUnitAtAnHourADayAndTwoWeeks() {
        assertEquals("-", WorkTable.idle(null));
        assertEquals("0m", WorkTable.idle(Duration.ofSeconds(30)));
        assertEquals("44m", WorkTable.idle(Duration.ofMinutes(44)));
        assertEquals("59m", WorkTable.idle(Duration.ofMinutes(59)));
        assertEquals("1h", WorkTable.idle(Duration.ofMinutes(60)));
        assertEquals("22h", WorkTable.idle(Duration.ofHours(22)));
        assertEquals("1d", WorkTable.idle(Duration.ofHours(24)));
        assertEquals("3d", WorkTable.idle(Duration.ofDays(3)));
        assertEquals("13d", WorkTable.idle(Duration.ofDays(13)));
        assertEquals("2w", WorkTable.idle(Duration.ofDays(14)));
        assertEquals("4w", WorkTable.idle(Duration.ofDays(28)));
        assertTrue(WorkTable.idle(Duration.ofDays(400)).length() <= WorkTable.IDLE_WIDTH, "fits its column");
        assertEquals("1 minute", WorkTable.idleWords(Duration.ofMinutes(1)));
        assertEquals("1 hour", WorkTable.idleWords(Duration.ofMinutes(61)));
        assertEquals("3 days", WorkTable.idleWords(Duration.ofDays(3)));
        assertEquals("5 weeks", WorkTable.idleWords(Duration.ofDays(35)));
    }

    @Test
    void aNameIsCutInItsMiddleSoItsEndStays() {
        assertEquals("isx-claude-1", WorkTable.middleCut("isx-claude-1", 38));
        var cut = WorkTable.middleCut("mcp-isx-agent-2458-companion-y27vl-and-more", 24);
        assertEquals(24, CharWidth.of(cut), cut);
        assertEquals("mcp-isx-ag…27vl-and-more", cut, "four tenths of it from the start, the rest from the end");
        var wide = WorkTable.middleCut("名前名前名前名前名前名前", 10);
        assertTrue(CharWidth.of(wide) <= 10 && CharWidth.of(wide) >= 9, "measured in cells, never a wide character split: " + wide);
        assertEquals("…", WorkTable.middleCut("abc", 1));
    }


    @Test
    void workIsASymbolForEachState() {
        assertEquals(new WorkTable.Work("–", WorkTable.Tone.FAINT), WorkTable.work(WorkState.NOT_READ, false));
        assertEquals("…", WorkTable.work(WorkState.NOT_READ, true).text(), "while the guest is read");
        assertEquals(new WorkTable.Work("✓", WorkTable.Tone.OK), WorkTable.work(state(0, 0, 0), false));
        assertEquals("±3", WorkTable.work(state(3, 0, 0), false).text());
        assertEquals("↑1", WorkTable.work(state(0, 1, 0), false).text());
        assertEquals("±3↑1", WorkTable.work(state(3, 1, 2), false).text(), "stashes are in the preview");
        assertEquals(new WorkTable.Work("≡2", WorkTable.Tone.WARN), WorkTable.work(state(0, 0, 2), false),
                "stashes alone are never shown as clean");
        assertEquals("±3?", WorkTable.work(new WorkState(WorkState.State.UNSAVED,
                List.of(new WorkState.Repo("a", 3, 0, 0), new WorkState.Repo("b", -1, -1, -1)), "Git could not read a clone"), false).text());
        assertEquals(new WorkTable.Work("?", WorkTable.Tone.WARN),
                WorkTable.work(new WorkState(WorkState.State.UNKNOWN, List.of(), "timed out"), false));
        assertEquals(WorkTable.Tone.DIM, WorkTable.work(new WorkState(WorkState.State.NO_REPOS, List.of(), null), false).tone());
    }

    private static WorkState state(int uncommitted, int unpushed, int stashes) {
        boolean held = uncommitted + unpushed + stashes > 0;
        return new WorkState(held ? WorkState.State.UNSAVED : WorkState.State.CLEAN,
                List.of(new WorkState.Repo("app", uncommitted, unpushed, stashes)), null);
    }

    private static EventLogReader.Effort effort(List<EventLogReader.PullRequest> pulls, List<EventLogReader.Branch> branches) {
        return new EventLogReader.Effort(1, 1, 1, 0, 0, java.time.Instant.EPOCH, java.time.Instant.EPOCH, branches, pulls);
    }

    private static final String IS = "Sanne/incus-spawn";
    private static final String WF = "Sanne/isx-workflow";

    @Test
    void aRepositorysLaterPullRequestsShowTheirNumberAlone() {
        var refs = WorkTable.references(effort(List.of(new EventLogReader.PullRequest(IS, 2457),
                new EventLogReader.PullRequest(IS, 2471), new EventLogReader.PullRequest(WF, 128)),
                List.of(new EventLogReader.Branch(IS, "fix-x"))),
                List.of(PullRequestStates.State.MERGED, PullRequestStates.State.OPEN));
        assertEquals(List.of("Sanne/incus-spawn#2457", "#2471", "Sanne/isx-workflow#128", "⇡ fix-x"),
                refs.stream().map(WorkTable.Ref::text).toList());
        assertEquals(WorkTable.PullState.DONE, refs.get(0).state(), "merged is dimmed");
        assertEquals(WorkTable.PullState.OPEN, refs.get(1).state());
        assertEquals(WorkTable.PullState.OPEN, refs.get(2).state(), "unknown is shown as open");
        assertEquals("https://github.com/Sanne/incus-spawn/pull/2471", refs.get(1).url(), "the short form still links its page");
    }

    @Test
    void referencesAreWholeAndTheRestCounted() {
        var refs = WorkTable.references(effort(List.of(new EventLogReader.PullRequest(IS, 2457),
                new EventLogReader.PullRequest(IS, 2471), new EventLogReader.PullRequest(IS, 2480)), List.of()), List.of());
        var all = WorkTable.fitReferences(refs, 80);
        assertEquals(3, all.shown().size());
        assertEquals(0, all.more());
        var some = WorkTable.fitReferences(refs, 31);
        assertEquals(List.of("Sanne/incus-spawn#2457", "#2471"), some.shown().stream().map(WorkTable.Ref::text).toList());
        assertEquals(1, some.more());
        assertTrue(some.width() <= 31, "with its count: " + some.width());
        assertEquals(1, WorkTable.fitReferences(refs, 30).shown().size(), "the count's room is kept");
        var none = WorkTable.fitReferences(refs, 10);
        assertTrue(none.shown().isEmpty(), "a pull request is never cut");
        assertEquals(3, none.more());
    }

    @Test
    void theCountIsReservedAsItIsDrawnAtEveryWidth() {
        var many = new java.util.ArrayList<EventLogReader.PullRequest>();
        for (int i = 0; i < 11; i++) many.add(new EventLogReader.PullRequest(IS, 2600 + i));
        var branchFirst = WorkTable.references(effort(List.of(), List.of(new EventLogReader.Branch(IS, "a-long-branch-name"),
                new EventLogReader.Branch(IS, "b"), new EventLogReader.Branch(IS, "c"))), List.of());
        for (var refs : List.of(WorkTable.references(effort(many, List.of()), List.of()), branchFirst)) {
            for (int room = 3; room <= 120; room++) {
                var fitted = WorkTable.fitReferences(refs, room);
                assertTrue(fitted.width() <= room, room + " cells: " + fitted);
                assertEquals(refs.size(), fitted.shown().size() + fitted.more(), "every reference shown or counted");
            }
        }
        var one = WorkTable.fitReferences(WorkTable.references(effort(many, List.of()), List.of()), 26);
        assertEquals(1, one.shown().size());
        assertEquals(10, one.more());
        assertEquals(26, one.width(), "Sanne/incus-spawn#2600 +10");
    }

    @Test
    void aBranchAloneAndFirstIsCutAtItsEnd() {
        var refs = WorkTable.references(effort(List.of(), List.of(new EventLogReader.Branch(IS, "issue-2651-chatgpt-opus-routing"))), List.of());
        var fitted = WorkTable.fitReferences(refs, 20);
        assertEquals(1, fitted.shown().size());
        assertEquals("⇡ issue-2651-chatgp…", fitted.shown().getFirst().text());
        assertEquals(0, fitted.more());
    }

    private static String text(dev.tamboui.text.Line line) {
        var b = new StringBuilder();
        line.spans().forEach(s -> b.append(s.content()));
        return b.toString();
    }

    @Test
    void theSecondLineIsThePurposeThenTheReferences() {
        assertNull(WorkTable.secondLine(null, List.of(), 60, THEME), "nothing to say, no second line");
        var refs = WorkTable.references(effort(List.of(new EventLogReader.PullRequest(IS, 2609),
                new EventLogReader.PullRequest(IS, 2611)), List.of()), List.of());
        assertEquals("Review new init · Sanne/incus-spawn#2609 #2611", text(WorkTable.secondLine("Review new init", refs, 60, THEME)));
        assertEquals("Sanne/incus-spawn#2609 #2611", text(WorkTable.secondLine(null, refs, 60, THEME)));
        var narrow = text(WorkTable.secondLine("Different layout explorations for the Work tab", refs, 52, THEME));
        assertTrue(narrow.endsWith(" · Sanne/incus-spawn#2609 +1"), narrow);
        assertTrue(narrow.startsWith("Different layout explor… "), "the purpose keeps its least, 24 cells: " + narrow);
        assertTrue(CharWidth.of(narrow) <= 52, narrow);
        var link = WorkTable.secondLine(null, refs, 60, THEME).spans().getFirst().style();
        assertEquals(java.util.Optional.of("https://github.com/Sanne/incus-spawn/pull/2609"),
                link.hyperlink().map(dev.tamboui.style.Hyperlink::url));
    }

    @Test
    void thePreviewTakesWhatTheTableLeavesUpToItsCap() {
        assertEquals(44, WorkTable.previewWidth(108));
        assertEquals(90, WorkTable.previewWidth(158));
        assertEquals(90, WorkTable.previewWidth(198));
    }

    private static dev.incusspawn.command.InstanceListing.InstanceInfo searched(String name, String purpose, String mcpPurpose) {
        var metadata = purpose == null ? null
                : dev.incusspawn.config.Jjq.parseForeign("{\"config\":{\"" + dev.incusspawn.incus.Metadata.PURPOSE + "\":\"" + purpose + "\"}}", "test");
        var mcp = mcpPurpose == null ? null
                : new dev.incusspawn.mcp.McpStanding(dev.incusspawn.mcp.McpStanding.State.HELD, mcpPurpose, null, null, null, null, null);
        return new dev.incusspawn.command.InstanceListing.InstanceInfo(name, "Running", "default", "default", "",
                "container", "tpl-isx-agent", "", "", "", "10.0.0.27", "", "x86_64",
                "", "", "branch", "", "", "", -1, -1, "", false, mcp, false, metadata);
    }

    private static EventLogReader.Effort opened(long pull, String branch) {
        return new EventLogReader.Effort(0, 0, 0, 0, 0, null, null,
                List.of(new EventLogReader.Branch("Sanne/incus-spawn", branch)),
                List.of(new EventLogReader.PullRequest("Sanne/incus-spawn", pull)));
    }

    @Test
    void theSearchFindsAnInstanceByThePullRequestItOpenedItsBranchAndItsPurposes() {
        var worker = searched("mcp-isx-agent-2542-impl-auqvj", null, "Implement issue 2542");
        var effort = opened(2728, "issue-2542-interface-text");
        for (var query : List.of("2728", "#2728", "incus-spawn#2728", "pull/2728", "2542", "#2542", "interface-text", "IMPLEMENT")) {
            assertTrue(WorkTable.matchesSearch(query, worker, effort), query);
        }
        assertFalse(WorkTable.matchesSearch("2728", worker, null), "nothing of the log before it is read");
        assertTrue(WorkTable.matchesSearch("2542", worker, null), "the name is the listing's");

        var person = searched("ws", "Port issue 2731 to the new grammar", null);
        assertTrue(WorkTable.matchesSearch("2731", person, EventLogReader.Effort.NONE));
        assertTrue(WorkTable.matchesSearch("grammar", person, EventLogReader.Effort.NONE));
        assertTrue(WorkTable.matchesSearch("tpl-isx", person, null), "the parent, as on the Instances tab");
        assertTrue(WorkTable.matchesSearch("10.0.0", person, null), "the address, as on the Instances tab");
        assertTrue(WorkTable.matchesSearch("", person, null));
    }

    @Test
    void aNumberAloneFindsOnlyThatWholeNumberNeverPartOfALongerOneOrOfTheAddress() {
        var worker = searched("mcp-isx-agent-2542-impl-auqvj", "Review 2728", null);
        var effort = opened(2728, "issue-2542-x");
        for (var query : List.of("27", "#27", "254", "542", "0", "10")) {
            assertFalse(WorkTable.matchesSearch(query, worker, effort), query);
        }
        assertTrue(WorkTable.matchesSearch("02728", worker, opened(2728, "b")), "leading zeros are the same number");
        assertTrue(WorkTable.matchesSearch(" 2728 ", worker, EventLogReader.Effort.NONE), "spaces around it are not the query");
    }
}
