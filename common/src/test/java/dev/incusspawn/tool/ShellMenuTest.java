package dev.incusspawn.tool;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDef.ActionEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellMenuTest {

    private static final String NAME = "dev-1";
    private static final String TEMPLATE = "tpl-dev";

    @TempDir
    Path home;
    private String originalHome;

    @BeforeEach
    void isolateHome() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", originalHome);
    }

    private void enableStatusBar() throws IOException {
        var config = home.resolve(".config/incus-spawn/config.yaml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "features:\n  - " + ShellMenu.FEATURE + "\n");
    }

    private static ActionResolver resolver(FakeIncusDaemon daemon) {
        return new ActionResolver(daemon.client(), new ToolDefLoader(), List.of(), Map.of());
    }

    private static FakeIncusDaemon daemon() {
        return new FakeIncusDaemon().container(NAME, Map.of(Metadata.PARENT, TEMPLATE));
    }

    private static ToolAction action(String tool, String id, String type, boolean shellMenu,
                                     ActionContext.RepoInfo repo) {
        return action(tool, id, type, shellMenu, repo, id == null ? "x" : id.substring(0, 1));
    }

    private static ToolAction action(String tool, String id, String type, boolean shellMenu,
                                     ActionContext.RepoInfo repo, String shortcut) {
        var entry = new ActionEntry();
        entry.setShortcut(shortcut);
        entry.setId(id);
        entry.setLabel(tool + " " + id);
        entry.setType(type);
        entry.setShellMenu(shellMenu);
        return repo == null ? new YamlToolAction(tool, entry) : new YamlToolAction(tool, entry, repo);
    }

    @Test
    void withoutTheStatusBarAShellResolvesNoMenuAndAsksIncusNothing() {
        var daemon = daemon();
        var menu = resolver(daemon).shellMenu(NAME, TEMPLATE, null);
        assertSame(ShellMenu.NONE, menu);
        assertEquals(List.of(), daemon.requests(),
                "a shell without the status bar must not pay for the menu");
    }

    @Test
    void anInstanceWithoutATemplateGetsNoMenu() throws IOException {
        enableStatusBar();
        var daemon = daemon();
        assertSame(ShellMenu.NONE, resolver(daemon).shellMenu(NAME, "", null));
        assertEquals(List.of(), daemon.requests());
    }

    @Test
    void theMenuCostsNoMoreThanItsActionContext() throws IOException {
        enableStatusBar();
        var contextOnly = daemon();
        resolver(contextOnly).buildActionContext(NAME, TEMPLATE);

        var daemon = daemon();
        var menu = resolver(daemon).shellMenu(NAME, TEMPLATE, null);
        assertEquals(TEMPLATE, menu.context().parent());
        assertEquals(contextOnly.requests(), daemon.requests(),
                "the menu's actions must come from the context, not a second round of reads");
    }

    @Test
    void offersOnlyUrlActionsThatOptIn() {
        // A command action's process would share the session's raw tty with the shell.
        var url = action("vscode-remote", "open", "url", true, null);
        var command = action("tool", "cmd", "command", true, null);
        var notOptedIn = action("tool", "quiet", "url", false, null);
        var inContainer = action("tool", "sh", "shell", true, null);

        var menu = ShellMenu.of(List.of(url, notOptedIn, command, inContainer), null, null);
        assertEquals(List.of(url), menu.actions());
    }

    @Test
    void actionsWithoutAnIdAreAllOffered() {
        var docs = action("tool", null, "url", true, null, "d");
        var site = action("tool", null, "url", true, null, "s");
        assertEquals(List.of(docs, site), ShellMenu.of(List.of(docs, site), null, null).actions());
    }

    @Test
    void aRepoActionIsOfferedOnceForTheRepoTheSessionStartsIn() {
        var a = new ActionContext.RepoInfo("a", "/home/agentuser/a", "u");
        var b = new ActionContext.RepoInfo("b", "/home/agentuser/b", "u");
        // One declaration, expanded once per repo.
        var open = new ActionEntry();
        open.setId("open");
        open.setType("url");
        open.setShellMenu(true);
        open.setShortcut("o");
        ToolAction openA = new YamlToolAction("vscode-remote", open, a);
        ToolAction openB = new YamlToolAction("vscode-remote", open, b);

        assertEquals(List.of(openB), ShellMenu.of(List.of(openA, openB), "/home/agentuser/b", null).actions());
        assertEquals(List.of(openB), ShellMenu.of(List.of(openA, openB), "/home/agentuser/b/src", null).actions());
        assertEquals(List.of(openA), ShellMenu.of(List.of(openA, openB), "/home/agentuser/bb", null).actions());
        assertEquals(List.of(openA), ShellMenu.of(List.of(openA, openB), "/elsewhere", null).actions());
        assertEquals(List.of(openA), ShellMenu.of(List.of(openA, openB), null, null).actions());
    }

    @Test
    void twoRepoActionsOfOneToolAreBothOffered() {
        var a = new ActionContext.RepoInfo("a", "/home/agentuser/a", "u");
        var open = action("idea-backend", "open", "url", true, a);
        var other = action("idea-backend", "other", "url", true, a, "t");

        var menu = ShellMenu.of(List.of(open, other), "/home/agentuser/a", null);
        assertTrue(menu.actions().containsAll(List.of(open, other)));
    }

    @Test
    void twoIdLessRepoActionsOfOneToolAreBothOffered() {
        var a = new ActionContext.RepoInfo("a", "/home/agentuser/a", "u");
        var b = new ActionContext.RepoInfo("b", "/home/agentuser/b", "u");
        var gateway = new ActionEntry();
        gateway.setType("url");
        gateway.setShellMenu(true);
        gateway.setShortcut("g");
        var docs = new ActionEntry();
        docs.setType("url");
        docs.setShellMenu(true);
        docs.setShortcut("d");
        List<ToolAction> actions = List.of(
                new YamlToolAction("idea-backend", gateway, a), new YamlToolAction("idea-backend", gateway, b),
                new YamlToolAction("idea-backend", docs, a), new YamlToolAction("idea-backend", docs, b));

        var menu = ShellMenu.of(actions, "/home/agentuser/b", null).actions();
        assertEquals(List.of(actions.get(1), actions.get(3)), menu);
    }

    @Test
    void anActionWithoutOneUsableKeyIsLeftOut() {
        var ok = action("tool", "ok", "url", true, null, "v");
        var none = action("tool", "none", "url", true, null, null);
        var word = action("tool", "word", "url", true, null, "vs");
        var nonAscii = action("tool", "accent", "url", true, null, "é");
        var space = action("tool", "space", "url", true, null, " ");

        assertEquals(List.of(ok), ShellMenu.of(List.of(none, word, ok, nonAscii, space), null, null).actions());
        assertEquals(List.of(), ShellMenu.of(List.of(none, word), null, null).actions(),
                "a menu of only unusable entries is empty, so F12 stays the shell's");
    }

    @Test
    void ofTwoActionsOnOneKeyOnlyTheFirstIsOffered() {
        var first = action("tool", "first", "url", true, null, "v");
        var sameKey = action("tool", "second", "url", true, null, "V");
        var other = action("tool", "other", "url", true, null, "o");

        assertEquals(List.of(first, other), ShellMenu.of(List.of(first, sameKey, other), null, null).actions());
    }
}
