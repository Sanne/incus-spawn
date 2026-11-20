package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.command.IsolatedHome;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.EventLogReader;
import dev.incusspawn.tool.ToolDefLoader;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The overview in the TUI (#2241): {@code o} on the instance table shows what the proxy's event
 * log says each instance did, read off the UI thread, and the marks and F8 still act on the rows.
 */
@ExtendWith(IsolatedHome.class)
class TuiOverviewTest {

    private static final String WS = "2026-11-01T08:00:00.5Z";

    @TempDir
    Path dir;

    private TuiRunner runner;
    private final List<Runnable> reads = new ArrayList<>();

    @AfterEach
    void close() {
        if (runner != null) runner.close();
    }

    private Tui tui(int width) throws Exception {
        runner = TuiRunner.create(Tui.runnerConfig().backend(new HeadlessBackend(width, 24))
                .shutdownHook(false).noTick().build());
        var daemon = new FakeIncusDaemon()
                .instance("ws", "container", "Stopped", new LinkedHashMap<>(Map.of(Metadata.TYPE, "branch",
                        Metadata.PARENT, "tpl-minimal", Metadata.PROFILE, "tpl-minimal")))
                .instance("mine", "container", "Running", Map.of(Metadata.TYPE, "branch", Metadata.PARENT, "tpl-minimal",
                        Metadata.PROFILE, "tpl-minimal"))
                .createdAt("ws", WS)
                .createdAt("mine", "2026-11-02T08:00:00Z")
                .execOutput(dev.incusspawn.command.WorkState.HEADER,
                        dev.incusspawn.command.WorkState.HEADER + "\nrepo 0 2 0 app\nend\n"
                                + "isx-sessions gemini-cli 1\nend\nisx-sessions copilot 1\nend\nisx-sessions bob 1\nend\n"
                                + dev.incusspawn.command.ClaudeSessions.HEADER + "\ntotal 1\nsession 1762164000 YQ==\nclose eyJ0eXBlIjoiYXNzaXN0YW50IiwibWVzc2FnZSI6eyJjb250ZW50IjpbeyJ0eXBlIjoidGV4dCIsInRleHQiOiJQb3J0ZWQgdGhlIHBhcnNlciJ9XX19\nend\n"
                                + dev.incusspawn.command.CodexSessions.HEADER + "\ntotal 0\nend\n"
                                + dev.incusspawn.command.PiSessions.HEADER + "\ntotal 0\nend\n"
                                + dev.incusspawn.command.OpencodeSessions.HEADER + "\ntotal 0\nend\n");
        var file = dir.resolve("events.jsonl");
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
        };
        tui.eventLogReader = () -> EventLogReader.read(file);
        tui.overviewExecutor = reads::add;
        tui.overviewGitHub = (token, pulls) -> Map.of();
        tui.overviewModel = (config, target, system, question) -> org.junit.jupiter.api.Assertions.fail("no instance here has several sessions");
        tui.overviewCache = () -> new dev.incusspawn.command.SessionMerge.Cache(dir.resolve("summaries"));
        tui.overviewZone = java.time.ZoneOffset.UTC;
        tui.useDefinitions(new LinkedHashMap<>(), new ToolDefLoader(List.of()), List.of());
        tui.startSession(daemon.client(), new BackgroundTaskManager(Runnable::run), new FlockInstanceLockManager(),
                InstanceListing.collectEntries(daemon.client().listJson()));
        return tui;
    }

    private static String screen(Tui tui, int width) {
        return TuiSnapshot.toText(TuiSnapshot.render(width, 24, tui::render));
    }

    private void press(Tui tui, KeyEvent... keys) throws Exception {
        for (var key : keys) tui.handleEvent(key, runner);
    }

    private void readsFinish() {
        reads.forEach(Runnable::run);
        reads.clear();
    }

    @Test
    void oShowsWhatTheLogSawOnceItIsReadAndOAgainTheTableAsItWas() throws Exception {
        var tui = tui(80);
        press(tui, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofChar('o'));
        var reading = screen(tui, 80);
        assertTrue(reading.contains("reading the proxy's event log"), reading);
        assertTrue(reading.lines().anyMatch(l -> l.contains(" ws ") && l.contains("…")), reading);
        assertEquals(1, reads.size(), "read off the UI thread, once");

        readsFinish();
        var read = screen(tui, 80);
        assertTrue(read.contains("overview since Nov 1"), read);
        assertTrue(read.lines().anyMatch(l -> l.contains("WORK") && l.contains("TOKENS") && l.contains("GITHUB")), read);
        assertFalse(read.contains("ACTIVE"), "no room for it at 80 columns beside WORK");
        var ws = read.lines().filter(l -> l.contains(" ws ")).findFirst().orElseThrow();
        assertTrue(ws.contains("42.0k") && ws.contains("not read") && ws.contains("Sanne/incus-spawn#2287"), ws);
        var mine = read.lines().filter(l -> l.contains(" mine ")).findFirst().orElseThrow();
        assertFalse(mine.contains("Sanne/"), mine);
        assertTrue(mine.contains("2 unpushed"), "read in the running guest: " + mine);
        assertFalse(read.contains(" IP "), read);

        press(tui, KeyEvent.ofChar('o'));
        var back = screen(tui, 80);
        assertTrue(back.lines().anyMatch(l -> l.contains("NAME") && l.contains("IP") && l.contains("PARENT")), back);
        assertFalse(back.contains("TOKENS"), back);
        assertTrue(reads.isEmpty(), "leaving the overview reads nothing");
    }

    @Test
    void theGitHubColumnShowsEachPullRequestWholeAndLinksItsPage() throws Exception {
        for (int width : new int[] {80, 100, 120, 150}) {
            var tui = tui(width);
            press(tui, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofChar('o'));
            readsFinish();
            var buffer = TuiSnapshot.render(width, 24, tui::render);
            var screen = TuiSnapshot.toText(buffer);
            var lines = screen.lines().toList();
            int y = 0;
            while (y < lines.size() && !lines.get(y).contains(" ws ")) y++;
            var ws = lines.get(y);
            int x = ws.indexOf("Sanne/incus-spawn#2287");
            assertTrue(x >= 0, "the repository and number whole at " + width + " columns: " + screen);
            for (int c = x; c < x + "Sanne/incus-spawn#2287".length(); c++) {
                assertEquals(java.util.Optional.of("https://github.com/Sanne/incus-spawn/pull/2287"),
                        buffer.get(c, y).style().hyperlink().map(dev.tamboui.style.Hyperlink::url),
                        "linked to its page at " + width + " columns, column " + c);
            }
            assertFalse(buffer.get(ws.indexOf(" ws ") + 1, y).style().hyperlink().isPresent(), "the name is no link");
            runner.close();
            runner = null;
        }
    }

    @Test
    void theWorkTabShowsTheOverviewsColumnsReadOnEnteringIt() throws Exception {
        var tui = tui(120);
        for (int i = 0; i < 3; i++) press(tui, KeyEvent.ofKey(KeyCode.RIGHT));
        assertEquals(Tui.View.WORK, tui.view);
        assertEquals(1, reads.size(), "entering the tab reads, once");
        readsFinish();
        var work = screen(tui, 120);
        assertTrue(work.lines().anyMatch(l -> l.contains("Instances 2   Work")), work);
        assertTrue(work.contains("Work since Nov 1") && !work.contains("o: back"), work);
        assertTrue(work.lines().anyMatch(l -> l.contains("WORK") && l.contains("ACTIVE")), work);
        var ws = work.lines().filter(l -> l.contains(" ws ")).findFirst().orElseThrow();
        assertTrue(ws.contains("Nov 1–Nov 4"), ws);
        assertTrue(work.lines().anyMatch(l -> l.contains("DONE")), "a 120-column terminal has room for DONE");
        var mine = work.lines().filter(l -> l.contains(" mine ")).findFirst().orElseThrow();
        assertTrue(mine.contains("Ported the parser"), mine);
        assertFalse(mine.contains("not read"), "every agent's section answered: " + mine);

        press(tui, KeyEvent.ofChar(' '), KeyEvent.ofKey(KeyCode.DOWN), KeyEvent.ofChar(' '), KeyEvent.ofKey(KeyCode.F8));
        var confirm = screen(tui, 120);
        assertTrue(confirm.contains("mine") && confirm.contains("ws") && confirm.contains("Destroy"), confirm);
    }

    @Test
    void marksAndF8DestroyTheMarkedRowsOfTheOverview() throws Exception {
        var tui = tui(80);
        press(tui, KeyEvent.ofKey(KeyCode.TAB), KeyEvent.ofChar('o'));
        readsFinish();
        press(tui, KeyEvent.ofChar(' '), KeyEvent.ofKey(KeyCode.DOWN), KeyEvent.ofChar(' '), KeyEvent.ofKey(KeyCode.F8));
        var confirm = screen(tui, 80);
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
}
