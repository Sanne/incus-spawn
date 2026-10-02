package dev.incusspawn.incus;

import dev.incusspawn.tool.ActionContext;
import dev.incusspawn.tool.ActionResult;
import dev.incusspawn.tool.ToolAction;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellStatusBarTest {

    private final ByteArrayOutputStream terminal = new ByteArrayOutputStream();
    private final ShellStatusBar bar = new ShellStatusBar("dev-1", terminal);

    private String drained() {
        var s = terminal.toString(StandardCharsets.UTF_8);
        terminal.reset();
        return s;
    }

    private static ActionContext context() {
        return new ActionContext("dev-1", MachineType.CONTAINER, Set.of(), List.of(),
                new ActionContext.InstanceState("10.0.0.5", "RUNNING", "tpl-dev", "FULL"));
    }

    private static ToolAction urlAction(String shortcut, ActionResult result, AtomicInteger runs) {
        var released = new CountDownLatch(0);
        return urlAction(shortcut, result, runs, released);
    }

    private static ToolAction urlAction(String shortcut, ActionResult result, AtomicInteger runs,
                                        CountDownLatch release) {
        return new ToolAction() {
            @Override public String toolName() { return "vscode-remote"; }
            @Override public String label() { return "Open in VS Code"; }
            @Override public Optional<String> type() { return Optional.of("url"); }
            @Override public Optional<String> shortcut() { return Optional.of(shortcut); }
            @Override public ActionResult execute(ActionContext context) {
                throw new AssertionError("the menu must not run an action that may prompt");
            }
            @Override public ActionResult executeWithoutPrompting(ActionContext context) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                runs.incrementAndGet();
                return result;
            }
        };
    }

    @Test
    void theShellGetsTheRowsAboveTheBar() {
        assertEquals(22, bar.effectiveHeight(24));
        bar.setup(80, 24);
        var out = drained();
        assertTrue(out.contains("\033[1;22r"), "scroll region must stop above the bar: " + out);
        assertTrue(out.contains("\033[23;1H") && out.contains("\033[24;1H"), "bar on the last two rows");
    }

    @Test
    void theBarShowsTheInstanceAndItsTemplate() {
        bar.setMenuActions(List.of(), context());
        bar.setup(80, 24);
        var out = drained();
        assertTrue(out.contains(" isx dev-1 [tpl-dev]"), out);
        assertTrue(out.contains("10.0.0.5"), out);
    }

    @Test
    void leavingRemovesTheWholeBarAndPutsTheCursorBack() {
        bar.setup(80, 24);
        drained();
        bar.cleanup();
        var out = drained();
        // From the bar's first row down, then the full screen back, then the shell's cursor.
        assertEquals("\0337\033[23;1H\033[J\033[r\0338", out);
    }

    @Test
    void aShortcutRunsItsActionOffTheInputThreadAndFlashesTheResultAsUtf8() throws Exception {
        var runs = new AtomicInteger();
        var release = new CountDownLatch(1);
        var action = urlAction("v", ActionResult.error("no handler"), runs, release);
        bar.setMenuActions(List.of(action), context());
        bar.setup(80, 24);
        bar.showMenu();

        // handleMenuKey runs on the thread relaying keystrokes: it must return while the
        // action is still blocked.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> bar.handleMenuKey((byte) 'V'));
        assertFalse(bar.isMenuActive());
        release.countDown();
        for (int i = 0; i < 200 && !terminal.toString(StandardCharsets.UTF_8).contains("✗ no handler"); i++) {
            Thread.sleep(10);
        }
        assertEquals(1, runs.get());
        assertTrue(drained().contains("✗ no handler"));
    }

    @Test
    void anUnknownKeyKeepsTheMenuOpenAndSaysWhatToPress() {
        var runs = new AtomicInteger();
        bar.setMenuActions(List.of(urlAction("v", ActionResult.ok("ok"), runs)), context());
        bar.setup(80, 24);
        bar.showMenu();
        drained();

        bar.handleMenuKey((byte) 'x');
        assertEquals(0, runs.get());
        assertTrue(bar.isMenuActive());
        assertTrue(drained().contains("Press v or Esc"));
    }

    @Test
    void aBarWiderThanTheTerminalIsCut() {
        var bar = new ShellStatusBar("an-instance-with-a-very-long-name", terminal);
        bar.setup(20, 10);
        var out = drained();
        assertTrue(out.contains(" isx an-instance-wit"), out);
        assertFalse(out.contains("very-long-name"), out);
    }

    @Test
    void theBarWaitsForAnEscapeSequenceSplitAcrossFramesToEnd() throws Exception {
        // ls --color can end a frame mid-sequence; a repaint there would cancel it.
        bar.setup(80, 24);
        drained();
        var first = "\033[3".getBytes(StandardCharsets.UTF_8);
        bar.writeOutput(first, 0, first.length);
        assertEquals("\033[3", drained(), "nothing may follow an unfinished sequence");

        var rest = "1mred".getBytes(StandardCharsets.UTF_8);
        bar.writeOutput(rest, 0, rest.length);
        var out = drained();
        assertTrue(out.startsWith("1mred\0337"), "repainted once the sequence ended: " + out);
    }

    @Test
    void theBarWaitsForACharacterSplitAcrossFramesToEnd() throws Exception {
        bar.setup(80, 24);
        drained();
        var check = "✓".getBytes(StandardCharsets.UTF_8);
        bar.writeOutput(check, 0, 1);
        bar.writeOutput(check, 1, check.length - 1);
        var out = drained();
        assertTrue(out.startsWith("✓\0337"), out);
    }

    @Test
    void anOscTitleSplitAcrossFramesIsNotCutShort() throws Exception {
        bar.setup(80, 24);
        drained();
        var title = "\033]0;my title\007$ ".getBytes(StandardCharsets.UTF_8);
        bar.writeOutput(title, 0, 6);
        assertEquals("\033]0;my", drained());
        bar.writeOutput(title, 6, title.length - 6);
        assertTrue(drained().startsWith(" title\007$ \0337"));
    }

    // --- After the session: nothing may bring the bar back ---

    private static void write(ShellStatusBar bar, String s) throws Exception {
        var bytes = s.getBytes(StandardCharsets.UTF_8);
        bar.writeOutput(bytes, 0, bytes.length);
    }

    @Test
    void aFrameAfterCleanupGetsNoScrollRegionOrBar() throws Exception {
        // SIGTERM while the shell prints (tail -f): the hook's cleanup races the output loop.
        bar.setup(80, 24);
        bar.cleanup();
        drained();
        write(bar, "late output");
        assertEquals("late output", drained());
    }

    @Test
    void nothingRepaintsAfterCleanup() {
        bar.setMenuActions(List.of(urlAction("v", ActionResult.ok("ok"), new AtomicInteger())), context());
        bar.setup(80, 24);
        bar.cleanup();
        drained();
        bar.showMenu();
        bar.handleMenuKey((byte) 'x');
        bar.hideMenu();
        bar.resize(100, 30);
        bar.cleanup();
        assertEquals("", drained());
    }

    @Test
    void anActionThatFinishesAfterTheSessionDrawsNothing() throws Exception {
        // In the TUI path the JVM outlives the session, and the TUI owns the screen by then.
        var release = new CountDownLatch(1);
        var runs = new AtomicInteger();
        bar.setMenuActions(List.of(urlAction("v", ActionResult.ok("ok"), runs, release)), context());
        bar.setup(80, 24);
        var running = bar.runAction(bar.menuActions().get(0));
        bar.cleanup();
        drained();
        release.countDown();
        running.join(5000);
        assertEquals(1, runs.get());
        assertEquals("", drained());
    }

    @Test
    void aReconnectDrawsAgain() throws Exception {
        bar.setup(80, 24);
        bar.cleanup();
        bar.setup(80, 24);
        drained();
        write(bar, "x");
        assertTrue(drained().contains("\033[1;22r"));
    }

    @Test
    void cleanupFirstCancelsASequenceTheChildLeftOpen() throws Exception {
        bar.setup(80, 24);
        write(bar, "\033]0;a title the child never ended");
        drained();
        bar.cleanup();
        assertEquals("\030\0337\033[23;1H\033[J\033[r\0338", drained());
    }

    // --- Repaints that do not follow a frame wait for a boundary too ---

    @Test
    void theMenuWaitsForTheChildsSequenceToEnd() throws Exception {
        bar.setMenuActions(List.of(urlAction("v", ActionResult.ok("ok"), new AtomicInteger())), context());
        bar.setup(80, 24);
        write(bar, "\033[3");
        drained();
        bar.showMenu();
        assertEquals("", drained(), "F12 mid-sequence must not cut it");
        write(bar, "1m");
        var out = drained();
        assertTrue(out.startsWith("1m\0337") && out.contains("[Esc] Close"), out);
    }

    @Test
    void aResizeMidSequenceRedrawsAtTheNextBoundary() throws Exception {
        bar.setup(80, 24);
        write(bar, "\033[3");
        drained();
        bar.resize(100, 30);
        assertEquals("", drained());
        write(bar, "1m");
        var out = drained();
        assertTrue(out.startsWith("1m\033[1;28r\033[2J"), out);
    }

    // --- What the bottom row shows ---

    @Test
    void aMultiLineErrorFlashesItsFirstLineOnly() throws Exception {
        var error = ActionResult.error("VS Code does not appear to be installed.\nInstall it from: https://x");
        bar.setMenuActions(List.of(urlAction("v", error, new AtomicInteger())), context());
        bar.setup(80, 24);
        drained();
        bar.runAction(bar.menuActions().get(0)).join(5000);
        var out = drained();
        assertTrue(out.contains("✗ VS Code does not appear to be installed."), out);
        assertFalse(out.contains("Install it from"), out);
        assertFalse(out.contains("\n"), out);
    }

    @Test
    void theBarIsCutByCodePointsAndShowsNoControlCharacters() {
        // 19 columns of text, then an emoji (a surrogate pair) at the 20th.
        var bar = new ShellStatusBar("abcdefghijklmn\uD83D\uDE00xyz\u0007", terminal);
        bar.setup(20, 10);
        var out = drained();
        assertTrue(out.contains(" isx abcdefghijklmn\uD83D\uDE00"), out);
        assertFalse(out.contains("xyz"), out);
        var wide = new ShellStatusBar("bell\u0007", terminal);
        wide.setup(40, 10);
        assertFalse(drained().contains("\u0007"));
    }
}
