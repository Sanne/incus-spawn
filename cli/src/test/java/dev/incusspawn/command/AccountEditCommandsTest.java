package dev.incusspawn.command;

import dev.incusspawn.config.NamespaceAccounts;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static dev.incusspawn.command.IsolatedHome.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx account remove}, {@code default} and {@code rename} (#2608): the account menu's
 * 'x', 'd' and 'n' as commands, run on the menu's own code. Before them, a namespace's only
 * account could not be removed at all, since the menu offers 'x' only beside a second one.
 */
@ExtendWith(IsolatedHome.class)
class AccountEditCommandsTest {

    private static final String ONE_GEMINI_KEY = """
            gemini:
              accounts:
                default:
                  apiKey: "AIza-no-quota"
              default: default
            """;

    private static final String TWO_ACCOUNTS = """
            github:
              accounts:
                personal:
                  token: "ghp_personal"
                acme:
                  token: "ghp_acme"
              default: personal
            """;

    private static String said(RecordingOutput.Work body) {
        var recorded = RecordingOutput.during(body);
        var text = new StringBuilder();
        for (var event : recorded.events()) {
            if (event.msg() != null) text.append(event.text()).append('\n');
        }
        return text.toString();
    }

    @Test
    void removesTheOnlyAccountOfANamespaceAndSaysWhatStopsWorking() throws Exception {
        var config = seed(ONE_GEMINI_KEY);
        var exit = new int[1];
        var out = said(() -> exit[0] = AccountCommand.remove(new AccountMenuTest.StubbedInit(), config,
                "gemini=default", false, ScriptedPrompts.lines("y")));
        assertEquals(0, exit[0], out);
        assertEquals(List.of(), NamespaceAccounts.names(SpawnConfig.load(), "gemini"));
        assertEquals("", saved("gemini.accounts.default.apiKey"));
        assertTrue(out.contains("This is the last gemini account that works."), out);
    }

    @Test
    void withoutATerminalRemoveRefusesUnlessToldToSkipTheQuestion() throws Exception {
        var config = seed(ONE_GEMINI_KEY);
        var init = new AccountMenuTest.StubbedInit();
        var exit = new int[1];
        var out = said(() -> exit[0] = AccountCommand.remove(init, config, "gemini=default", false, null));
        assertEquals(1, exit[0], out);
        assertUnchanged(ONE_GEMINI_KEY);

        assertEquals(0, AccountCommand.remove(init, config, "gemini=default", true, null));
        assertEquals("", saved("gemini.accounts.default.apiKey"));
    }

    @Test
    void declinedRemoveKeepsTheAccountAndNamesWhatUsesIt() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        var init = new AccountMenuTest.StubbedInit().pin("review-1", "github", "acme");
        init.templates.put("github=acme", List.of("tpl-acme"));
        var exit = new int[1];
        var out = said(() -> exit[0] = AccountCommand.remove(init, config, "github=acme", false, ScriptedPrompts.lines("")));
        assertEquals(0, exit[0], "declining is not an error");
        assertTrue(out.contains("review-1 (github=acme)") && out.contains("tpl-acme"), out);
        assertUnchanged(TWO_ACCOUNTS);
    }

    @Test
    void removingTheDefaultMovesItToTheAccountLeft() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        var init = new AccountMenuTest.StubbedInit().following("box", true);
        assertEquals(0, AccountCommand.remove(init, config, "github=personal", false, ScriptedPrompts.lines("y")));
        assertEquals("acme", saved("github.default"));
        assertEquals(List.of("box"), init.refreshed, "a running follower takes the new default's identity");
    }

    /** As Claude's menu did before it shared this code: the default never lands on an incomplete account. */
    @Test
    void removingTheDefaultSkipsAnIncompleteAccount() throws Exception {
        var config = seed("""
                claude:
                  accounts:
                    half:
                      type: vertex
                    work:
                      type: api-key
                      apiKey: "sk-ant-api03-work"
                    client:
                      type: oauth
                      oauthToken: "sk-ant-oat01-client"
                  default: work
                """);
        assertEquals(0, AccountCommand.remove(new AccountMenuTest.StubbedInit(), config, "claude=work", true, null));
        assertEquals("client", saved("claude.default"));
    }

    /** The reviewer's case: a dotted name was reported removed and stayed in config.yaml. */
    @Test
    void removesAnAccountWhoseNameHoldsADot() throws Exception {
        var config = seed("""
                claude:
                  accounts:
                    work:
                      type: api-key
                      apiKey: "sk-ant-api03-work"
                    my.work:
                      type: api-key
                      apiKey: "sk-ant-api03-personal"
                  default: work
                """);
        assertEquals(0, AccountCommand.remove(new AccountMenuTest.StubbedInit(), config, "claude=my.work", true, null));
        assertEquals(List.of("work"), NamespaceAccounts.names(SpawnConfig.load(), "claude"));
        assertEquals("sk-ant-api03-work", saved("claude.accounts.work.apiKey"));
    }

    @Test
    void anAccountThatIsNotConfiguredIsAnError() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        var init = new AccountMenuTest.StubbedInit();
        assertEquals(1, AccountCommand.remove(init, config, "github=nope", true, null));
        assertEquals(1, AccountCommand.remove(init, config, "github", true, null));
        assertEquals(1, AccountCommand.makeDefault(init, config, "github=nope", null));
        assertEquals(1, AccountCommand.rename(init, config, "github=nope", "x"));
        assertUnchanged(TWO_ACCOUNTS);
    }

    @Test
    void defaultMovesTheDefault() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        assertEquals(0, AccountCommand.makeDefault(new AccountMenuTest.StubbedInit(), config, "github=acme", null));
        assertEquals("acme", saved("github.default"));
    }

    /** With no default: line the first account serves, so naming it changes nothing and asks nothing. */
    @Test
    void defaultOfTheAccountThatAlreadyServesSucceedsWithoutATerminal() throws Exception {
        var seeded = """
                github:
                  accounts:
                    personal:
                      token: "ghp_personal"
                    acme:
                      token: "ghp_acme"
                """;
        var config = seed(seeded);
        var init = new AccountMenuTest.StubbedInit().following("box", false);
        assertEquals(0, AccountCommand.makeDefault(init, config, "github=personal", null));
        assertUnchanged(seeded);
    }

    /**
     * An account that serves only because the default: line names an incomplete one is written
     * as the default, so completing the other account later does not take the default back.
     */
    @Test
    void defaultOfAnAccountServingInPlaceOfAnIncompleteDefaultWritesTheLine() throws Exception {
        var config = seed("""
                claude:
                  accounts:
                    half:
                      type: vertex
                    work:
                      type: api-key
                      apiKey: "sk-ant-api03-work"
                  default: half
                """);
        var init = new AccountMenuTest.StubbedInit().following("box", false);
        assertEquals(0, AccountCommand.makeDefault(init, config, "claude=work", null));
        assertEquals("work", saved("claude.default"));
    }

    /** Instances that follow the default are never moved unasked. */
    @Test
    void defaultWithFollowersNeedsATerminal() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        var init = new AccountMenuTest.StubbedInit().following("box", false);
        assertEquals(1, AccountCommand.makeDefault(init, config, "github=acme", null));
        assertUnchanged(TWO_ACCOUNTS);

        assertEquals(0, AccountCommand.makeDefault(init, config, "github=acme", ScriptedPrompts.lines("k")));
        assertEquals("acme", saved("github.default"));
        assertEquals(List.of("box:github=personal"), init.pinned, "k keeps it on the old default");
    }

    /** Not knowing who follows the default is not knowing that nobody does. */
    @Test
    void defaultWithoutATerminalRefusesWhenIncusCannotBeAsked() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        var init = new AccountMenuTest.StubbedInit();
        init.incusDown = true;
        assertEquals(1, AccountCommand.makeDefault(init, config, "github=acme", null));
        assertUnchanged(TWO_ACCOUNTS);
    }

    @Test
    void renameRePointsPinnedInstancesAndTheDefault() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        var init = new AccountMenuTest.StubbedInit().pin("review-1", "github", "personal");
        assertEquals(0, AccountCommand.rename(init, config, "github=personal", "home"));
        assertEquals("home", saved("github.default"));
        assertEquals("ghp_personal", saved("github.accounts.home.token"));
        assertEquals(List.of("github:personal->home"), init.renames);
    }

    @Test
    void renameRefusesANameTheMenuWouldRefuse() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        var init = new AccountMenuTest.StubbedInit();
        assertEquals(1, AccountCommand.rename(init, config, "github=personal", "acme"));
        assertEquals(1, AccountCommand.rename(init, config, "github=personal", "inherit"));
        assertEquals(1, AccountCommand.rename(init, config, "github=personal", "a b"));
        // A dotted name would be read as nested keys: listed, yet given no credential
        assertEquals(1, AccountCommand.rename(init, config, "github=personal", "my.work"));
        assertUnchanged(TWO_ACCOUNTS);
    }
}
