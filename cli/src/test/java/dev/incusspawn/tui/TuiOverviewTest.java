package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.EventLogReader;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.util.Eventually;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The overview in the TUI (#2241): {@code o} on the instance table and the Work tab show what the
 * proxy's event log says each instance did, read off the UI thread, and the marks and F8 still
 * act on the rows. The Work tab adds the selected instance's preview beside the table, or under
 * it on {@code p}, and merges several sessions only for the instance it previews.
 */
@ExtendWith(IsolatedHome.class)
class TuiOverviewTest {

    private static final String WS = "2026-11-01T08:00:00.5Z";
    /** The clock IDLE counts from: three days after the log's last line about ws. */
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    @TempDir
    Path dir;

    private TuiRunner runner;
    private final List<Runnable> reads = new ArrayList<>();
    private final List<Runnable> merges = new ArrayList<>();
    private final AtomicInteger modelCalls = new AtomicInteger();
    /** What the model answers a merge with. */
    private String answer = "Headline: Ported and tested the parser\nDone: The parser is ported.\nState: Its tests pass.\nPending: nothing reported";

    @AfterEach
    void close() {
        if (runner != null) runner.close();
    }

    private Path file;
    private int height = 24;

    /** One Claude Code session's lines, closing with {@code closing}. */
    private static String claudeSession(long at, String closing) {
        var json = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"" + closing + "\"}]}}";
        return "session " + at + " YQ==\nclose " + java.util.Base64.getEncoder().encodeToString(json.getBytes()) + "\n";
    }

    /** What a guest's read prints: one clone with two commits not pushed, and Claude Code's sessions. */
    private static String guest(String claude) {
        return dev.incusspawn.command.WorkState.HEADER + "\nrepo 0 2 0 app\nend\n"
                + "isx-sessions gemini-cli 1\nend\nisx-sessions antigravity 1\nend\nisx-sessions copilot 1\nend\n"
                + dev.incusspawn.command.ClaudeSessions.HEADER + "\n" + claude + "end\n"
                + dev.incusspawn.command.CodexSessions.HEADER + "\ntotal 0\nend\n"
                + dev.incusspawn.command.PiSessions.HEADER + "\ntotal 0\nend\n"
                + dev.incusspawn.command.AntigravitySessions.HEADER + "\ntotal 0\nend\n"
                + dev.incusspawn.command.GeminiSessions.HEADER + "\ntotal 0\nend\n"
                + dev.incusspawn.command.CopilotSessions.HEADER + "\ntotal 0\nend\n"
                + dev.incusspawn.command.BobSessions.HEADER + "\ntotal 0\nend\n"
                + dev.incusspawn.command.OpencodeSessions.HEADER + "\ntotal 0\nend\n";
    }

    private Tui tui(int width) throws Exception {
        return tui(width, daemon -> { });
    }

    private Tui tui(int width, java.util.function.Consumer<FakeIncusDaemon> more) throws Exception {
        runner = TuiRunner.create(Tui.runnerConfig().backend(new HeadlessBackend(width, height))
                .shutdownHook(false).noTick().build());
        var daemon = new FakeIncusDaemon()
                .instance("ws", "container", "Stopped", new LinkedHashMap<>(Map.of(Metadata.TYPE, "branch",
                        Metadata.PARENT, "tpl-minimal", Metadata.PROFILE, "tpl-minimal",
                        Metadata.PURPOSE, "Port the parser to the new grammar")))
                .instance("mine", "container", "Running", Map.of(Metadata.TYPE, "branch", Metadata.PARENT, "tpl-minimal",
                        Metadata.PROFILE, "tpl-minimal"))
                .createdAt("ws", WS)
                .createdAt("mine", "2026-11-02T08:00:00Z")
                .lastUsedAt("ws", "2026-11-03T12:00:00Z")
                .execOutput(dev.incusspawn.command.WorkState.HEADER,
                        guest("total 1\n" + claudeSession(1762164000, "Ported the parser")));
        more.accept(daemon);
        file = dir.resolve("events.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"t\":\"2026-11-01T12:00:00Z\",\"kind\":\"model\",\"instance\":\"ws\",\"incarnation\":\"" + WS + "\","
                        + "\"input_tokens\":40000,\"output_tokens\":2000}",
                "{\"t\":\"2026-11-04T12:00:00Z\",\"kind\":\"github\",\"op\":\"pull_request\",\"instance\":\"ws\","
                        + "\"incarnation\":\"" + WS + "\",\"repo\":\"Sanne/incus-spawn\",\"number\":2287}") + "\n");
        var tui = new Tui() {
            @Override
            boolean showProxyError() {
                return false;
            }

            // The header lays out around the version: a snapshot holds still across builds
            @Override
            String displayVersion() {
                return "1.2.3";
            }

            @Override
            String currentVersion() {
                return "1.2.3";
            }
        };
        tui.eventLogReader = () -> EventLogReader.read(file);
        tui.overviewExecutor = reads::add;
        tui.overviewMergeExecutor = merges::add;
        tui.overviewGitHub = (token, pulls) -> Map.of();
        tui.overviewConfig = TuiOverviewTest::config;
        tui.overviewModel = (config, target, system, question) -> {
            modelCalls.incrementAndGet();
            return answer;
        };
        tui.overviewCache = () -> new dev.incusspawn.command.SessionMerge.Cache(dir.resolve("summaries"));
        tui.overviewKept = () -> new dev.incusspawn.command.KeptReads(dir.resolve("reads"));
        tui.overviewZone = java.time.ZoneOffset.UTC;
        tui.overviewClock = () -> NOW;
        tui.useDefinitions(new LinkedHashMap<>(), new ToolDefLoader(List.of()), List.of());
        tui.startSession(daemon.client(), new BackgroundTaskManager(Runnable::run), new FlockInstanceLockManager(),
                InstanceListing.collectEntries(daemon.client().listJson()));
        this.width = width;
        return tui;
    }

    private int width;

    /** A host with a Claude account that may answer the Messages API, so a merge has an account to run on. */
    private static SpawnConfig config() {
        var config = new SpawnConfig();
        config.getClaude().putAccount("work", SpawnConfig.ClaudeAccount.ofApiKey("work-key"));
        config.getClaude().setDefaultAccount("work");
        return config;
    }

    private String screen(Tui tui) {
        return TuiSnapshot.toText(render(tui));
    }

    private dev.tamboui.buffer.Buffer render(Tui tui) {
        var buffer = TuiSnapshot.render(width, height, tui::render);
        // The frame the keys see: its width says whether the preview is beside the table
        return buffer;
    }

    private void press(Tui tui, KeyEvent... keys) throws Exception {
        for (var key : keys) {
            tui.handleEvent(key, runner);
            // A frame between keys, as the runner draws one, so the TUI knows the terminal's width
            render(tui);
        }
    }

    private void readsFinish() {
        reads.forEach(Runnable::run);
        reads.clear();
    }

    private void workTab(Tui tui) throws Exception {
        render(tui);
        for (int i = 0; i < 3; i++) press(tui, KeyEvent.ofKey(KeyCode.RIGHT));
        assertEquals(Tui.View.WORK, tui.view);
    }

    /**
     * Whether {@code line} is {@code name}'s row: its state glyph, then the name. The preview's
     * title has the same glyph and name, on a rule.
     */
    private static boolean rowOf(String line, String name) {
        for (var glyph : List.of(Glyphs.RUNNING, Glyphs.STOPPED, Glyphs.BETWEEN)) {
            if (line.contains(glyph + " " + name + " ") && !line.contains("─ " + glyph + " " + name)) return true;
        }
        return false;
    }

    private static String row(String screen, String name) {
        return screen.lines().filter(l -> rowOf(l, name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not on screen:\n" + screen));
    }

    /** The line under {@code name}'s row. */
    private static String under(String screen, String name) {
        var lines = screen.lines().toList();
        for (int i = 0; i < lines.size() - 1; i++) {
            if (rowOf(lines.get(i), name)) return lines.get(i + 1);
        }
        throw new AssertionError(name + " not on screen:\n" + screen);
    }

    @Test
    void oShowsWhatTheLogSawOnceItIsReadAndOAgainTheTableAsItWas() throws Exception {
        var tui = tui(80);
        press(tui, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofChar('o'));
        var reading = screen(tui);
        assertTrue(reading.contains("reading the proxy's event log"), reading);
        assertTrue(row(reading, "ws").contains("…"), reading);
        assertEquals(1, reads.size(), "read off the UI thread, once");

        readsFinish();
        var read = screen(tui);
        assertTrue(read.contains("overview since Nov 1"), read);
        assertTrue(read.lines().anyMatch(l -> l.contains("NAME") && l.contains("IDLE") && l.contains("WORK") && l.contains("TOKENS")), read);
        for (var gone : List.of("STATUS", "ACTIVE", "GITHUB", "DONE", "PURPOSE", "LAST USED")) {
            assertFalse(read.contains(gone), gone + " left the table: " + read);
        }
        var ws = row(read, "ws");
        assertTrue(ws.contains("○ ws") && ws.contains("42.0k") && ws.contains("–") && ws.contains("3d"), ws);
        assertTrue(under(read, "ws").contains("Port the parser to the new grammar · Sanne/incus-spawn#2287"), read);
        var mine = row(read, "mine");
        assertTrue(mine.contains("● mine") && mine.contains("↑2"), "read in the running guest: " + mine);
        assertFalse(read.contains("┬"), "no preview outside the Work tab: " + read);

        press(tui, KeyEvent.ofChar('o'));
        var back = screen(tui);
        assertTrue(back.lines().anyMatch(l -> l.contains("NAME") && l.contains("IP") && l.contains("PARENT")), back);
        assertFalse(back.contains("TOKENS"), back);
        assertTrue(reads.isEmpty(), "leaving the overview reads nothing");
    }

    @Test
    void eachPullRequestIsWholeUnderItsRowAndLinksItsPage() throws Exception {
        for (int w : new int[] {80, 110, 160, 200}) {
            var tui = tui(w);
            workTab(tui);
            readsFinish();
            var buffer = render(tui);
            var lines = TuiSnapshot.toText(buffer).lines().toList();
            int y = 0;
            while (y < lines.size() && !rowOf(lines.get(y), "ws")) y++;
            var second = lines.get(y + 1);
            int x = second.indexOf("Sanne/incus-spawn#2287");
            assertTrue(x >= 0, "the repository and number whole at " + w + " columns: " + String.join("\n", lines));
            assertEquals(7, second.indexOf("Port"), "indented past the glyph at " + w + " columns: " + second);
            for (int c = x; c < x + "Sanne/incus-spawn#2287".length(); c++) {
                assertEquals(java.util.Optional.of("https://github.com/Sanne/incus-spawn/pull/2287"),
                        buffer.get(c, y + 1).style().hyperlink().map(dev.tamboui.style.Hyperlink::url),
                        "linked to its page at " + w + " columns, column " + c);
            }
            assertFalse(buffer.get(lines.get(y).indexOf(" ws ") + 1, y).style().hyperlink().isPresent(), "the name is no link");
            runner.close();
            runner = null;
        }
    }

    @Test
    void thePreviewIsBesideTheTableFrom110ColumnsAndWidensItWithTheTerminal() throws Exception {
        // Where the divider stands: the table keeps 64 cells until the preview reaches 90
        var divider = Map.of(80, -1, 109, -1, 110, 64, 160, 68, 200, 108);
        for (var entry : divider.entrySet()) {
            var tui = tui(entry.getKey());
            workTab(tui);
            readsFinish();
            var lines = screen(tui).lines().toList();
            var title = lines.stream().filter(l -> l.contains("Work since Nov 1")).findFirst().orElseThrow();
            assertEquals(entry.getValue().intValue(), title.indexOf("┬"), entry.getKey() + " columns: " + String.join("\n", lines));
            if (entry.getValue() > 0) {
                assertTrue(title.contains("┬─ ● mine "), "the selected instance's name on the title rule: " + title);
                var footer = lines.stream().filter(l -> l.startsWith("────")).reduce((a, b) -> b).orElseThrow();
                assertEquals(entry.getValue().intValue(), footer.indexOf("┴"), footer);
                assertTrue(lines.stream().anyMatch(l -> l.contains("│ Work     2 unpushed")), String.join("\n", lines));
            }
            runner.close();
            runner = null;
        }
    }

    @Test
    void pShowsThePreviewUnderTheTableOnANarrowTerminalAndHidesItAgain() throws Exception {
        height = 30;
        var tui = tui(80);
        workTab(tui);
        readsFinish();
        var without = screen(tui);
        assertFalse(without.contains("─ ● mine ─"), without);
        assertTrue(without.contains("p Preview"), "the context line names the key: " + without);

        press(tui, KeyEvent.ofChar('p'));
        var with = screen(tui);
        assertTrue(with.contains("─ ● mine ─"), "a rule names the instance under the table: " + with);
        assertTrue(with.contains("Done     1 session, Claude Code") && with.contains("Ported the parser"), with);
        assertTrue(with.contains("p Hide preview"), with);
        assertEquals(Tui.Mode.BROWSE, tui.mode(), "p opens no dialog on the Work tab");

        press(tui, KeyEvent.ofChar('p'));
        assertEquals(without, screen(tui), "p again hides it");
    }

    @Test
    void theWorkTabsSnapshots() throws Exception {
        height = 30;
        var tui = tui(120);
        workTab(tui);
        readsFinish();
        press(tui, KeyEvent.ofKey(KeyCode.DOWN));
        TuiSnapshot.assertMatches("tui-tab-work-120x30", render(tui));
        runner.close();
        height = 24;
        tui = tui(80);
        workTab(tui);
        readsFinish();
        press(tui, KeyEvent.ofChar('p'));
        TuiSnapshot.assertMatches("tui-tab-work-preview-below-80x24", render(tui));
    }

    @Test
    void pHidesTheSidePreviewOnAWideTerminal() throws Exception {
        var tui = tui(120);
        workTab(tui);
        readsFinish();
        assertTrue(screen(tui).contains("┬─ ● mine"));
        press(tui, KeyEvent.ofChar('p'));
        var hidden = screen(tui);
        assertFalse(hidden.contains("┬") || hidden.contains("┴"), hidden);
        assertTrue(hidden.contains("p Preview"), hidden);
    }

    @Test
    void theWorkTabShowsTheSelectedInstanceInFull() throws Exception {
        var tui = tui(120);
        workTab(tui);
        assertEquals(1, reads.size(), "entering the tab reads, once");
        readsFinish();
        var work = screen(tui);
        assertTrue(work.lines().anyMatch(l -> l.contains("Instances 2   Work")), work);
        assertTrue(work.contains("Work since Nov 1") && !work.contains("o: back"), work);
        assertTrue(work.contains("│ ● Running   from tpl-minimal"), work);
        assertTrue(work.contains("│ Done     1 session, Claude Code"), "every agent's section answered, so none is named unread: " + work);
        assertFalse(work.contains("not read"), work);
        assertTrue(work.contains("│   Ported the parser"), work);

        press(tui, KeyEvent.ofKey(KeyCode.DOWN));
        var ws = screen(tui);
        assertTrue(ws.contains("┬─ ○ ws "), ws);
        assertTrue(ws.contains(Glyphs.LINK + " Purpose  Port the parser to the new grammar"), "the divider is heavy beside the selected row: " + ws);
        assertTrue(ws.contains(Glyphs.LINK + " Idle     3 days since its last logged activity, last"), ws);
        assertTrue(ws.contains("│          start 4 days ago"), "wrapped under its value: " + ws);
        assertTrue(ws.contains("│ Tokens   42.0k in 1 model call"), ws);
        assertTrue(ws.contains("│ Work     not read: the instance is stopped"), ws);
        assertTrue(ws.contains("│ GitHub   Sanne/incus-spawn#2287"), ws);
        assertTrue(ws.contains("│ Done     not read: the instance is stopped"), ws);

        press(tui, KeyEvent.ofKey(KeyCode.UP), KeyEvent.ofChar(' '), KeyEvent.ofKey(KeyCode.DOWN), KeyEvent.ofChar(' '),
                KeyEvent.ofKey(KeyCode.F8));
        var confirm = screen(tui);
        assertTrue(confirm.contains("mine") && confirm.contains("ws") && confirm.contains("Destroy"), confirm);
    }

    @Test
    void aStoppedInstanceShowsTheReadingKeptBeforeItStopped() throws Exception {
        dev.incusspawn.command.KeptReadsAccess.put(new dev.incusspawn.command.KeptReads(dir.resolve("reads")), "ws", WS,
                guest("total 1\n" + claudeSession(1762164000, "Wrote the grammar")), Instant.parse("2026-11-03T13:00:00Z"));
        var daemons = new java.util.ArrayList<FakeIncusDaemon>();
        var tui = tui(120, daemons::add);
        var daemon = daemons.getFirst();
        workTab(tui);
        readsFinish();
        press(tui, KeyEvent.ofKey(KeyCode.DOWN));
        var ws = screen(tui);
        assertTrue(row(ws, "ws").contains(Glyphs.UNPUSHED + "2"), "its WORK cell is the kept reading's: " + ws);
        assertTrue(ws.contains("│ Work     2 unpushed"), ws);
        assertTrue(ws.contains("│          read 4 days ago, before it stopped"), ws);
        assertTrue(ws.contains("│   Wrote the grammar"), "its closing message is the kept one: " + ws);
        assertEquals(1, daemon.execs().size(), "only the running instance is read: " + daemon.execs());
        assertNotNull(new dev.incusspawn.command.KeptReads(dir.resolve("reads")).get("mine", "2026-11-02T08:00:00Z"),
                "the running instance's read is kept for when it stops");
    }

    @Test
    void aMarkOnATwoLineRowIsOnItsFirstLineAndMovesNothing() throws Exception {
        var tui = tui(120);
        workTab(tui);
        readsFinish();
        press(tui, KeyEvent.ofKey(KeyCode.DOWN));
        var before = screen(tui).lines().toList();
        press(tui, KeyEvent.ofChar(' '));
        var after = screen(tui).lines().toList();
        assertEquals(before.size(), after.size());
        for (int y = 0; y < after.size(); y++) {
            if (rowOf(after.get(y), "ws")) {
                assertTrue(after.get(y).contains(Glyphs.MARKED + "○ ws"), after.get(y));
                assertEquals(before.get(y), after.get(y).replaceFirst(Glyphs.MARKED, " "), "only the mark cell changes");
            } else if (!after.get(y).contains("Space Mark")) {
                assertEquals(before.get(y), after.get(y), "line " + y + " moved");
            }
        }
        assertTrue(under(String.join("\n", after), "ws").contains("Sanne/incus-spawn#2287"), "the second line is the row's own");
    }

    @Test
    void scrollingKeepsTheWholeSelectedInstanceOnScreen() throws Exception {
        height = 12;
        var tui = tui(80, daemon -> {
            for (int i = 0; i < 8; i++) {
                daemon.instance("p-" + i, "container", "Stopped", Map.of(Metadata.TYPE, "branch", Metadata.PARENT, "tpl-minimal",
                        Metadata.PROFILE, "tpl-minimal", Metadata.PURPOSE, "purpose " + i));
            }
        });
        workTab(tui);
        readsFinish();
        for (int i = 0; i < 9; i++) {
            press(tui, KeyEvent.ofKey(KeyCode.DOWN));
            var shown = screen(tui);
            var selected = shown.lines().filter(l -> l.startsWith(" ▸")).findFirst().orElseThrow();
            var name = selected.strip().split(" ")[2];
            var second = under(shown, name);
            assertTrue(second.contains("purpose") || second.contains("Port the parser"), "its second line is on screen too:\n" + shown);
        }
    }

    @Test
    void onlyThePreviewedInstanceIsMergedOneAtATime() throws Exception {
        var two = guest("total 2\n" + claudeSession(1762164000, "Ported the parser") + claudeSession(1762160000, "Wrote the tests"));
        var tui = tui(120, daemon -> daemon
                .instance("other", "container", "Running", Map.of(Metadata.TYPE, "branch", Metadata.PARENT, "tpl-minimal",
                        Metadata.PROFILE, "tpl-minimal"))
                .createdAt("other", "2026-11-02T09:00:00Z")
                .execOutput(dev.incusspawn.command.WorkState.HEADER, two));
        workTab(tui);
        readsFinish();
        press(tui, KeyEvent.ofKey(KeyCode.HOME));
        assertEquals("mine", tui.selectedInstance().name());
        assertEquals(1, merges.size(), "the previewed instance alone is merged");
        assertTrue(screen(tui).contains("merging 2 sessions"), screen(tui));

        // Moving on while it runs asks for nothing more: one model call at a time
        press(tui, KeyEvent.ofKey(KeyCode.DOWN), KeyEvent.ofKey(KeyCode.UP), KeyEvent.ofKey(KeyCode.DOWN));
        assertEquals("other", tui.selectedInstance().name());
        assertEquals(1, merges.size(), "one in flight");
        assertTrue(screen(tui).contains("merging 2 sessions"), "other waits its turn: " + screen(tui));

        // The first one's answer is kept though the cursor left it, and then the one now shown is asked
        merges.removeFirst().run();
        assertEquals(1, modelCalls.get());
        assertEquals(1, merges.size(), "the instance now previewed is merged next");
        merges.removeFirst().run();
        assertEquals(2, modelCalls.get());
        var other = screen(tui);
        assertTrue(other.contains("Ported and tested the parser"), other);
        press(tui, KeyEvent.ofKey(KeyCode.UP));
        assertTrue(screen(tui).contains("Ported and tested the parser"), "mine's merge was kept: " + screen(tui));
        assertTrue(merges.isEmpty());

        // Read again: the cache answers both, with no model call
        press(tui, KeyEvent.ofChar('r'));
        readsFinish();
        press(tui, KeyEvent.ofKey(KeyCode.DOWN));
        assertTrue(merges.isEmpty() && modelCalls.get() == 2, "cached");
        assertTrue(screen(tui).contains("Ported and tested the parser"), screen(tui));
    }

    private static final String TWO_SESSIONS = guest("total 2\n" + claudeSession(1762164000, "Ported the parser")
            + claudeSession(1762160000, "Wrote the tests"));

    @Test
    void f3AsksForTheMergeOfAnInstanceThePreviewDoesNotShow() throws Exception {
        var tui = tui(80, daemon -> daemon.execOutput(dev.incusspawn.command.WorkState.HEADER, TWO_SESSIONS));
        workTab(tui);
        readsFinish();
        assertFalse(screen(tui).contains("─ ● mine ─"), "no preview at 80 columns");
        assertTrue(merges.isEmpty(), "nothing shows the merged summary, so nothing is merged");

        press(tui, KeyEvent.ofKey(KeyCode.F3));
        assertEquals(1, merges.size(), "F3 shows it, so F3 asks for it");
        assertTrue(screen(tui).contains("merging 2 sessions"), screen(tui));
        merges.removeFirst().run();
        var merged = screen(tui);
        assertTrue(merged.contains("a model's merge") && merged.contains("The parser is ported."), merged);
        assertEquals(1, modelCalls.get());
    }

    @Test
    void f3OnTheInstancesTabsOverviewAsksForTheMergeToo() throws Exception {
        var tui = tui(120, daemon -> daemon.execOutput(dev.incusspawn.command.WorkState.HEADER, TWO_SESSIONS));
        press(tui, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofChar('o'));
        readsFinish();
        assertTrue(merges.isEmpty());
        press(tui, KeyEvent.ofKey(KeyCode.F3));
        assertEquals(1, merges.size());
    }

    @Test
    void pTogglesThePreviewWhileTheInstanceIsBusy() throws Exception {
        var tui = tui(120, daemon -> daemon.instance("busy", "container", "Running", Map.of(Metadata.TYPE, "branch",
                Metadata.PARENT, "tpl-minimal", Metadata.PROFILE, "tpl-minimal", Metadata.PENDING_OP, Metadata.OP_STOPPING)));
        workTab(tui);
        readsFinish();
        press(tui, KeyEvent.ofKey(KeyCode.HOME));
        assertEquals("busy", tui.selectedInstance().name());
        assertTrue(screen(tui).contains("┬─ ◐ busy"));
        press(tui, KeyEvent.ofChar('p'));
        assertFalse(screen(tui).contains("┬"), "p is not an action on the instance: " + screen(tui));
    }

    @Test
    void aTerminalTooShortForThePreviewOffersNoKeyForIt() throws Exception {
        height = 12;
        var tui = tui(80, daemon -> daemon.execOutput(dev.incusspawn.command.WorkState.HEADER, TWO_SESSIONS));
        workTab(tui);
        readsFinish();
        press(tui, KeyEvent.ofChar('p'));
        var shown = screen(tui);
        assertFalse(shown.contains("p Preview") || shown.contains("Hide preview"), shown);
        assertFalse(tui.previewDrawn());
        assertTrue(merges.isEmpty(), "no merge for a preview that is not drawn");
    }

    @Test
    void aMergeQueuedByARefreshedReadIsDropped() throws Exception {
        var held = new java.util.concurrent.CountDownLatch(1);
        var configs = new AtomicInteger();
        var tui = tui(80, daemon -> daemon.execOutput(dev.incusspawn.command.WorkState.HEADER, TWO_SESSIONS));
        // The first read's config waits, so its guest has passed the read's check and waits to merge
        tui.overviewConfig = () -> {
            if (configs.incrementAndGet() == 1) {
                try {
                    held.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
            return config();
        };
        workTab(tui);
        var first = new Thread(reads.removeFirst());
        first.start();
        Eventually.await("the first read's guest answered", () -> row(screen(tui), "mine").contains("↑2"));
        press(tui, KeyEvent.ofChar('r'));
        held.countDown();
        first.join(10_000);
        assertEquals(0, tui.mergingSessions("mine"), "the older read queued nothing after the refresh began");
    }

    @Test
    void aRowCutByTheOffsetInAOneLineViewportIsDrawnFromTheTop() {
        var rows = List.of(dev.tamboui.widgets.table.Row.from("a").height(2), dev.tamboui.widgets.table.Row.from("b").height(2));
        var area = new dev.tamboui.layout.Rect(0, 0, 10, 2);
        assertArrayEquals(new int[] {-1, 1}, MainScreen.rowTops(rows, 3, area), "as tamboui draws the second row, from the top");
        assertArrayEquals(new int[] {1, -1}, MainScreen.rowTops(rows, 0, area));
        assertArrayEquals(new int[] {1, -1}, MainScreen.rowTops(rows, 1, area), "the offset inside the first row still shows it");
    }

    @Test
    void theMergedSummaryIsShownALineAtATime() throws Exception {
        answer = "Headline: Ported\tand tested\nDone: The parser\tis ported.\nState: Its tests\tpass.\nPending: nothing\treported";
        var tui = tui(120, daemon -> daemon.execOutput(dev.incusspawn.command.WorkState.HEADER, TWO_SESSIONS));
        workTab(tui);
        readsFinish();
        press(tui, KeyEvent.ofKey(KeyCode.HOME));
        merges.removeFirst().run();
        var shown = screen(tui);
        assertTrue(shown.contains("Ported and tested") && shown.contains("Finished: The parser is ported.")
                && shown.contains("State: Its tests pass.") && shown.contains("Pending: nothing reported (inferred)"), shown);
        assertFalse(shown.contains("\t"), "a tab the model echoed is a space: " + shown);
    }

    /** Whether any cell of {@code name}'s row is tinted in {@code buffer}: a row's own text has no fill. */
    private boolean rowTinted(dev.tamboui.buffer.Buffer buffer, String name) {
        var lines = TuiSnapshot.toText(buffer).lines().toList();
        for (int y = 0; y < lines.size(); y++) {
            if (rowOf(lines.get(y), name)) return buffer.get(lines.get(y).indexOf(name), y).style().bg().isPresent();
        }
        throw new AssertionError(name + " not on screen");
    }

    /** The cell where {@code text} starts on the screen. */
    private dev.tamboui.buffer.Cell cellAt(dev.tamboui.buffer.Buffer buffer, String text) {
        var lines = TuiSnapshot.toText(buffer).lines().toList();
        for (int y = 0; y < lines.size(); y++) {
            int x = lines.get(y).indexOf(text);
            if (x >= 0) return buffer.get(x, y);
        }
        throw new AssertionError(text + " not on screen");
    }

    /** What a key asks for is whole in its first frame, and where its consequence landed is lit (visual-language.md "Motion"). */
    @Test
    void aCursorOnAnotherInstanceLightsThePreviewsTitleAndOpeningThePreviewLightsNothing() throws Exception {
        var tui = tui(120);
        tui.motion.start();
        try {
            workTab(tui);
            readsFinish();
            render(tui);
            assertTrue(cellAt(render(tui), "● mine ─").style().bg().isEmpty(), "the tab opens with nothing lit");
            press(tui, KeyEvent.ofKey(KeyCode.DOWN));
            var lit = render(tui);
            var lines = TuiSnapshot.toText(lit).lines().toList();
            int y = 0;
            while (!lines.get(y).contains("┬─ ○ ws")) y++;
            int x = lines.get(y).indexOf("○ ws");
            assertTrue(lit.get(x, y).style().bg().isPresent(), "the preview's title is lit: it changed away from the cursor");
            assertTrue(lit.get(x - 2, y).style().bg().isEmpty(), "and the rule beside it is not");
            assertTrue(tui.motion.moving());
        } finally {
            tui.motion.stop();
        }
    }

    @Test
    void aReadThatChangesARowTintsItAndTheFirstReadTintsNoRow() throws Exception {
        var daemons = new java.util.ArrayList<FakeIncusDaemon>();
        var tui = tui(120, daemons::add);
        tui.motion.start();
        try {
            workTab(tui);
            press(tui, KeyEvent.ofKey(KeyCode.DOWN));
            readsFinish();
            assertTrue(row(screen(tui), "mine").contains("↑2"));
            assertFalse(rowTinted(render(tui), "mine"), "the first read is not news");

            daemons.getFirst().execOutput(dev.incusspawn.command.WorkState.HEADER,
                    guest("total 1\n" + claudeSession(1762164000, "Ported the parser")).replace("repo 0 2 0 app", "repo 3 2 0 app"));
            press(tui, KeyEvent.ofChar('r'));
            assertFalse(rowTinted(render(tui), "mine"), "the last read stays, untinted, while the next runs");
            readsFinish();
            var changed = render(tui);
            assertTrue(row(TuiSnapshot.toText(changed), "mine").contains("±3↑2"), TuiSnapshot.toText(changed));
            assertTrue(rowTinted(changed, "mine"), "a read that changed the row tints it");
        } finally {
            tui.motion.stop();
        }
    }

    @Test
    void aValueArrivingInThePreviewTintsItsLineAndAnotherInstancesPreviewTintsNothing() throws Exception {
        var tui = tui(120);
        tui.motion.start();
        try {
            workTab(tui);
            assertTrue(screen(tui).contains("Work     reading the clones"), screen(tui));
            readsFinish();
            var arrived = render(tui);
            assertTrue(cellAt(arrived, "Work     2 unpushed").style().bg().isPresent(), "the value that arrived is tinted");
            assertTrue(cellAt(arrived, "Idle     ").style().bg().isEmpty(), "Idle, which only the clock changes, never is");

            // At once, while the tint of what arrived is still fading: it does not go on to the next preview
            press(tui, KeyEvent.ofKey(KeyCode.DOWN));
            assertTrue(cellAt(render(tui), "Work     not read").style().bg().isEmpty(), "the cursor brought this preview: nothing in it is news");
        } finally {
            tui.motion.stop();
        }
    }

    /** The TUI is left open for days: a Work tab with nothing under way asks for no frames. */
    @Test
    void anIdleWorkTabWithItsPreviewAsksForNoFrames() throws Exception {
        var tui = tui(120);
        tui.motion.start();
        try {
            workTab(tui);
            readsFinish();
            Eventually.holdsWithin(Eventually.DEFAULT_TIMEOUT, () -> {
                render(tui);
                return !tui.motion.moving();
            });
            assertFalse(tui.motion.moving(), "the tints of what arrived have faded, and no spinner is shown");
        } finally {
            tui.motion.stop();
        }
    }

    /** A closing message may hold a glyph the spinner uses: it is the guest's text, and says the same in every frame. */
    @Test
    void aSpinnerGlyphInAClosingMessageIsNotTakenForTheSpinner() throws Exception {
        var tui = tui(120, daemon -> daemon.execOutput(dev.incusspawn.command.WorkState.HEADER,
                guest("total 1\n" + claudeSession(1762164000, "Ported ⠙ the parser"))));
        workTab(tui);
        readsFinish();
        var mine = tui.selectedInstance();
        assertEquals("mine", mine.name());
        var said = new java.util.HashSet<String>();
        for (var glyph : List.of("⠋", "⠙", "⠹")) {
            var subject = new WorkPreview.Subject(mine, dev.tamboui.text.Span.raw("● "), "Running",
                    dev.incusspawn.command.OverviewCommand.effortOf(tui.overviewLog, mine), tui.overviewReading, false, 0,
                    glyph, NOW, java.time.ZoneOffset.UTC);
            said.add(WorkPreview.preview(subject, 60, tui.theme).blocks().stream()
                    .filter(b -> b.key().equals("Done")).findFirst().orElseThrow().says());
        }
        assertEquals(1, said.size(), "nothing is merging, so the spinner's frame changes nothing: " + said);
        assertTrue(said.iterator().next().contains("⠙"), said.toString());
    }

    @Test
    void theCutPreviewNamesNoKeysWhileTheSearchBarTakesThem() throws Exception {
        height = 12;
        var tui = tui(120);
        workTab(tui);
        readsFinish();
        assertTrue(screen(tui).contains("J/K Scroll"), screen(tui));
        press(tui, KeyEvent.ofChar('/'));
        var searching = screen(tui);
        assertTrue(searching.contains("more lines") && !searching.contains("J/K Scroll"), searching);
    }

    @Test
    void aPreviewThatDoesNotFitSaysSoAndJAndKScrollIt() throws Exception {
        height = 12;
        var tui = tui(120);
        workTab(tui);
        readsFinish();
        press(tui, KeyEvent.ofKey(KeyCode.DOWN));
        var top = screen(tui);
        var count = java.util.regex.Pattern.compile("(\\d+) more lines   J/K Scroll").matcher(top);
        assertTrue(count.find(), "a cut preview says how much is below, and the keys: " + top);
        int below = Integer.parseInt(count.group(1));
        assertTrue(top.contains("○ Stopped   from tpl-minimal"), top);

        press(tui, KeyEvent.ofChar('K'));
        assertEquals(top, screen(tui), "K at the top moves nothing");
        press(tui, KeyEvent.ofChar('J'));
        var down = screen(tui);
        assertTrue(down.contains((below - 1) + " more lines, 1 above"), down);
        assertFalse(down.contains("○ Stopped   from tpl-minimal"), "the first line scrolled away: " + down);
        for (int i = 0; i < below + 3; i++) press(tui, KeyEvent.ofChar('J'));
        var end = screen(tui);
        assertTrue(end.contains(below + " lines above   J/K Scroll"), "J stops at the last line: " + end);
        assertTrue(end.contains("Done     not read"), end);

        press(tui, KeyEvent.ofKey(KeyCode.UP), KeyEvent.ofKey(KeyCode.DOWN));
        assertEquals(top, screen(tui), "another instance's preview starts at its top");
    }

    @Test
    void idleIsGradedByAgeAndTheSelectedRowsDividerIsHeavy() throws Exception {
        var tui = tui(120);
        workTab(tui);
        readsFinish();
        var drawn = render(tui);
        var lines = TuiSnapshot.toText(drawn).lines().toList();
        int y = 0;
        while (!rowOf(lines.get(y), "ws")) y++;
        assertEquals(tui.theme.textFaint(), drawn.get(lines.get(y).indexOf("3d"), y).style().fg().orElseThrow(),
                "idle for days is faint");
        assertEquals(WorkTable.Age.HOUR, WorkTable.age(java.time.Duration.ofMinutes(59)));
        assertEquals(WorkTable.Age.DAY, WorkTable.age(java.time.Duration.ofHours(23)));
        assertEquals(WorkTable.Age.OLDER, WorkTable.age(java.time.Duration.ofHours(24)));
        assertEquals(WorkTable.Age.OLDER, WorkTable.age(null), "never at work is the faintest");

        // The divider is heavy beside the selected row alone, on the row's own fill
        int mine = 0;
        while (!rowOf(lines.get(mine), "mine")) mine++;
        int x = lines.get(mine).indexOf(Glyphs.LINK);
        assertTrue(x > 0, "the selected row meets its preview: " + lines.get(mine));
        assertEquals(tui.theme.accent(), drawn.get(x, mine).style().fg().orElseThrow());
        assertEquals(drawn.get(x - 1, mine).style().bg(), drawn.get(x, mine).style().bg(), "the row's fill runs into it");
        assertTrue(lines.get(y).contains("│") && !lines.get(y).contains(Glyphs.LINK), lines.get(y));
    }

    @Test
    void aMergesAgeIsCountedInTheGivenZone() {
        var merge = new dev.incusspawn.command.SessionMerge.Merged("h", "d", "s", "p", 2, 2, 1, NOW.minus(java.time.Duration.ofHours(2)));
        for (var zone : List.of(java.time.ZoneOffset.UTC, java.time.ZoneId.of("Pacific/Kiritimati"), java.time.ZoneId.of("America/Adak"))) {
            assertEquals("merged from 2 sessions 2h ago", merge.label(NOW, zone), "both times in " + zone);
        }
    }

    @Test
    void aNameShownWholeOnTheWorkTabStaysWholeWhileAnOperationRuns() throws Exception {
        var tui = tui(80, daemon -> daemon.instance("quarkus-feature-x", "container", "Running", Map.of(Metadata.TYPE, "branch",
                Metadata.PARENT, "tpl-minimal", Metadata.PROFILE, "tpl-minimal", Metadata.PENDING_OP, Metadata.OP_STOPPING)));
        workTab(tui);
        readsFinish();
        assertTrue(screen(tui).contains("quarkus-feature-x stopping"), screen(tui));
    }

    @Test
    void theShimmerStaysOnTheNameAsItsCellDrawsIt() throws Exception {
        var long38 = "mcp-isx-agent-2458-companion-y27vl-abc";
        var daemon = new FakeIncusDaemon().instance(long38, "container", "Running", Map.of(Metadata.TYPE, "branch",
                Metadata.PARENT, "tpl-minimal", Metadata.PENDING_OP, Metadata.OP_STOPPING));
        var instance = InstanceListing.collectEntries(daemon.client().listJson()).getFirst();
        // A NAME cell of 16 cells: the glyph, the name cut in its middle, then " stopping"
        var drawn = MainScreen.drawnName(instance, 25, true);
        assertTrue(drawn.contains("…") && dev.tamboui.text.CharWidth.of(drawn) == 25 - 2 - " stopping".length(), drawn);
        assertEquals(dev.tamboui.text.CharWidth.of(drawn), MainScreen.shimmerWidth(instance, 25, true, 80),
                "the shimmer ends where the cut name does, before the word, IDLE and WORK");
        assertEquals(14, MainScreen.shimmerWidth(instance, 16, false, 80), "never past the NAME cell on the Instances tab");
        var wide = new FakeIncusDaemon().instance("名前名前", "container", "Running", Map.of(Metadata.TYPE, "branch",
                Metadata.PARENT, "tpl-minimal", Metadata.PENDING_OP, Metadata.OP_STOPPING));
        var named = InstanceListing.collectEntries(wide.client().listJson()).getFirst();
        assertEquals(8, MainScreen.shimmerWidth(named, 30, false, 80), "measured in cells, not chars");
    }

    @Test
    void marksAndF8DestroyTheMarkedRowsOfTheOverview() throws Exception {
        var tui = tui(80);
        press(tui, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofChar('o'));
        readsFinish();
        press(tui, KeyEvent.ofChar(' '), KeyEvent.ofKey(KeyCode.DOWN), KeyEvent.ofChar(' '), KeyEvent.ofKey(KeyCode.F8));
        var confirm = screen(tui);
        assertTrue(confirm.contains("mine") && confirm.contains("ws") && confirm.contains("Destroy"), confirm);
    }

    @Test
    void rReadsTheLogAgainWhileTheOverviewIsShown() throws Exception {
        var tui = tui(80);
        press(tui, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofChar('o'));
        readsFinish();
        press(tui, KeyEvent.ofChar('r'));
        assertEquals(1, reads.size());
        press(tui, KeyEvent.ofChar('o'), KeyEvent.ofChar('r'));
        assertEquals(1, reads.size(), "not without the overview");
    }

    @Test
    void aStoppedRowIsShownAtOnceAndARunningOneWaitsForItsGuest() throws Exception {
        var tui = tui(120);
        workTab(tui);
        var before = screen(tui);
        assertTrue(row(before, "ws").contains("–"), "a stopped instance is not read, so nothing waits: " + before);
        assertTrue(row(before, "mine").contains("…"), before);
        assertTrue(before.contains("◐ reading the proxy's event log and 0/1 instance"), before);
    }

    @Test
    void aGuestIsShownWhileTheEventLogIsStillBeingRead() throws Exception {
        var tui = tui(120);
        var logHeld = new java.util.concurrent.CountDownLatch(1);
        tui.eventLogReader = () -> {
            try {
                logHeld.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return EventLogReader.read(file);
        };
        workTab(tui);
        var reading = new Thread(reads.removeFirst());
        reading.start();
        try {
            // The title is drawn before the rows, so a frame drawn while the guest's answer lands shows one without the other (#2669)
            var drawn = new AtomicReference<String>();
            Eventually.holdsWithin(Eventually.DEFAULT_TIMEOUT, () -> {
                drawn.set(screen(tui));
                return row(drawn.get(), "mine").contains("↑2") && !drawn.get().contains("0/1 instance");
            });
            var shown = drawn.get();
            assertTrue(row(shown, "mine").contains("↑2"), "the guest's row does not wait for the log: " + shown);
            assertTrue(shown.contains("reading the proxy's event log"), shown);
            assertFalse(shown.contains("0/1 instance"), "the title counts the guest read: " + shown);
        } finally {
            logHeld.countDown();
            reading.join(10_000);
        }
        var read = screen(tui);
        assertTrue(row(read, "ws").contains("42.0k"), read);
        assertTrue(read.contains("Ported the parser"), read);
    }

    @Test
    void comingBackToTheWorkTabKeepsTheLastReadUntilItIsReadAgain() throws Exception {
        var tui = tui(120);
        workTab(tui);
        readsFinish();
        press(tui, KeyEvent.ofKey(KeyCode.LEFT));
        press(tui, KeyEvent.ofKey(KeyCode.RIGHT));
        assertEquals(1, reads.size(), "entering the tab again reads again");
        var refreshing = screen(tui);
        assertTrue(row(refreshing, "mine").contains("↑2") && refreshing.contains("Ported the parser"),
                "the last read stays: " + refreshing);
        assertTrue(row(refreshing, "ws").contains("42.0k"), refreshing);
        assertTrue(refreshing.contains("Work since Nov 1, ◐ reading 0/1 instance"), refreshing);
        readsFinish();
        assertFalse(screen(tui).contains("reading 0/1"));
    }

    @Test
    void aReadThatEndsAfterALaterOneStartedChangesNothing() throws Exception {
        var tui = tui(120);
        workTab(tui);
        press(tui, KeyEvent.ofChar('r'));
        assertEquals(2, reads.size());
        reads.get(1).run();
        Files.writeString(file, "{\"t\":\"2026-11-01T12:00:00Z\",\"kind\":\"model\",\"instance\":\"ws\",\"incarnation\":\""
                + WS + "\",\"input_tokens\":5,\"output_tokens\":0}\n");
        reads.get(0).run();
        reads.clear();
        var shown = screen(tui);
        assertTrue(row(shown, "ws").contains("42.0k"), "the earlier read's log is dropped: " + shown);
    }

    private void search(Tui tui, String query) throws Exception {
        press(tui, KeyEvent.ofChar('/'));
        for (char c : query.toCharArray()) press(tui, KeyEvent.ofChar(c));
    }

    private static List<String> shown(Tui tui) {
        return tui.entries.stream().map(InstanceListing.InstanceInfo::name).toList();
    }

    @Test
    void theWorkTabsSearchFindsTheInstanceThatOpenedAPullRequestAndShowsItsPreview() throws Exception {
        var tui = tui(120);
        workTab(tui);
        readsFinish();
        search(tui, "2287");
        assertEquals(List.of("ws"), shown(tui), "ws opened Sanne/incus-spawn#2287");
        var found = screen(tui);
        assertTrue(found.contains("Port the parser"), "the preview shows the row found: " + found);

        press(tui, KeyEvent.ofKey(KeyCode.BACKSPACE), KeyEvent.ofKey(KeyCode.BACKSPACE));
        assertEquals(List.of(), shown(tui), "22 is no whole number of ws's");
        press(tui, KeyEvent.ofKey(KeyCode.ESCAPE));
        assertEquals(List.of("mine", "ws"), shown(tui).stream().sorted().toList());

        // The Instances tab still finds by name, parent and address alone
        press(tui, KeyEvent.ofKey(KeyCode.LEFT));
        search(tui, "2287");
        assertEquals(List.of(), shown(tui));
    }

    @Test
    void aSearchTypedBeforeTheLogIsReadFindsThePullRequestOnceItIs() throws Exception {
        var tui = tui(120);
        workTab(tui);
        search(tui, "#2287");
        assertEquals(List.of(), shown(tui), "the log is not read yet");
        readsFinish();
        tui.handleEvent(dev.tamboui.tui.event.TickEvent.of(1), runner);
        assertEquals(List.of("ws"), shown(tui));
        assertEquals("#2287", tui.searchInput.text());
    }
}
