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
        var entry = new ActionEntry();
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
    void offersOnlyHostSideActionsThatOptIn() {
        var url = action("vscode-remote", "open", "url", true, null);
        var command = action("tool", "cmd", "command", true, null);
        var notOptedIn = action("tool", "quiet", "url", false, null);
        var inContainer = action("tool", "sh", "shell", true, null);

        var menu = ShellMenu.of(List.of(url, notOptedIn, command, inContainer), null, null);
        assertEquals(List.of(url, command), menu.actions());
    }

    @Test
    void aRepoActionIsOfferedOnceForTheRepoTheSessionStartsIn() {
        var a = new ActionContext.RepoInfo("a", "/home/agentuser/a", "u");
        var b = new ActionContext.RepoInfo("b", "/home/agentuser/b", "u");
        var openA = action("vscode-remote", "open", "url", true, a);
        var openB = action("vscode-remote", "open", "url", true, b);

        assertEquals(List.of(openB), ShellMenu.of(List.of(openA, openB), "/home/agentuser/b", null).actions());
        assertEquals(List.of(openA), ShellMenu.of(List.of(openA, openB), "/elsewhere", null).actions());
        assertEquals(List.of(openA), ShellMenu.of(List.of(openA, openB), null, null).actions());
    }

    @Test
    void twoRepoActionsOfOneToolAreBothOffered() {
        var a = new ActionContext.RepoInfo("a", "/home/agentuser/a", "u");
        var open = action("idea-backend", "open", "url", true, a);
        var other = action("idea-backend", "other", "url", true, a);

        var menu = ShellMenu.of(List.of(open, other), "/home/agentuser/a", null);
        assertTrue(menu.actions().containsAll(List.of(open, other)));
    }
}
