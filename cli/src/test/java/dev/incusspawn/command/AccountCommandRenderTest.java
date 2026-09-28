package dev.incusspawn.command;

import dev.incusspawn.config.AccountOrigin;
import dev.incusspawn.config.AccountUsage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The text of {@code isx account show} and {@code list}, which users act on. */
class AccountCommandRenderTest {

    private static final AccountOrigin DEFAULT = null;
    private static final AccountOrigin TEMPLATE = AccountOrigin.template("tpl-acme");
    private static final AccountOrigin INSTANCE = AccountOrigin.EXPLICIT;

    private static AccountUsage.Use use(String ns, String account, AccountOrigin origin,
                                        String template, String description, String problem) {
        return new AccountUsage.Use(ns, account, origin, template, description, problem, "");
    }

    @Test
    void showNeverSuggestsFollowingATemplateToAMissingAccount() {
        var stale = new AccountUsage.Use("github", "acme-bot", INSTANCE, "bot", "", "",
                "Account 'bot' is not configured under 'github'.");
        var out = String.join("\n", AccountCommand.renderShow("review-1", "tpl-acme",
                List.of(stale), Map.of()));
        assertTrue(out.contains("tpl-acme chooses 'bot', which is not configured"), out);
        assertFalse(out.contains("To use that"), out);
    }

    @Test
    void showNamesTheSourceOfEveryAccount() {
        var out = String.join("\n", AccountCommand.renderShow("review-1", "tpl-acme", List.of(
                use("claude", "acme", TEMPLATE, "acme", "Google Cloud Vertex AI", ""),
                use("github", "me", DEFAULT, "", "", "")), Map.of()));
        assertTrue(out.startsWith("review-1 (branched from tpl-acme):"), out);
        assertTrue(out.contains("claude  acme -- Google Cloud Vertex AI"), out);
        assertTrue(out.contains("Pinned by template tpl-acme's accounts: setting, copied onto this instance"), out);
        assertTrue(out.contains("github  me\n"), out);
        assertTrue(out.contains("Not pinned: follows the global default (github.default in config.yaml),"
                + " so it changes if that default is changed."), out);
        assertFalse(out.contains("Problem"), "nothing to warn about:\n" + out);
    }

    @Test
    void showTellsHowToFollowATemplateThatNowSaysOtherwise() {
        var out = String.join("\n", AccountCommand.renderShow("review-1", "tpl-acme", List.of(
                use("github", "me", INSTANCE, "bot", "", "")), Map.of()));
        assertTrue(out.contains("Pinned by an explicit choice for this instance"), out);
        assertTrue(out.contains("Template tpl-acme would choose 'bot'. To use that: isx account set review-1 github=bot"), out);
    }

    /** The template's own pin, after the template changed its mind: say it changed. */
    @Test
    void showSaysWhenTheTemplateHasSinceChanged() {
        var out = String.join("\n", AccountCommand.renderShow("review-1", "tpl-acme", List.of(
                use("github", "me", TEMPLATE, "bot", "", "")), Map.of()));
        assertTrue(out.contains("Template tpl-acme has since changed its accounts: to choose 'bot'."), out);
    }

    @Test
    void showExplainsADanglingPinAndHowToLeaveIt() {
        var out = String.join("\n", AccountCommand.renderShow("review-1", "tpl-dev", List.of(
                use("github", "gone", INSTANCE, "", "", "Account 'gone' is not configured.")), Map.of()));
        assertTrue(out.contains("Account 'gone' is not configured."), out);
        assertTrue(out.contains("isx account unset review-1 github"), out);
    }

    @Test
    void showMentionsAPendingIdentityRefresh() {
        var out = String.join("\n", AccountCommand.renderShow("review-1", "tpl-dev", List.of(
                use("github", "bot", INSTANCE, "", "", "")), Map.of("github", "bot")));
        assertTrue(out.contains("git identity inside is updated to 'bot'"), out);
    }

    @Test
    void aTemplateBehindItsDefinitionIsToldToRebuild() {
        var out = String.join("\n", AccountCommand.renderShow("tpl-acme", "tpl-acme", List.of(
                use("github", "me", DEFAULT, "bot", "", "")), Map.of()));
        assertTrue(out.contains("isx build tpl-acme"), out);
        assertFalse(out.contains("isx account set"), out);
    }

    @Test
    void aTemplateIsNotShownAsBranchedFromItself() {
        var out = AccountCommand.renderShow("tpl-dev", "tpl-dev", List.of(), Map.of());
        assertEquals("tpl-dev (template):", out.get(0));
    }

    @Test
    void listMarksTheDefaultAndNamesWhoIsPinned() {
        var out = String.join("\n", AccountCommand.renderList(List.of(
                new AccountCommand.NamespaceListing("github", List.of(
                        new AccountCommand.AccountLine("me", "", true, "", List.of()),
                        new AccountCommand.AccountLine("bot", "commits as bot@x", false, "",
                                List.of("review-1", "review-2"))),
                        Map.of("gone", List.of("old-box")))), ""));
        assertTrue(out.contains("  me   [default]"), out);
        assertTrue(out.contains("  bot  commits as bot@x"), out);
        assertTrue(out.contains("pinned to it: review-1, review-2"), out);
        assertTrue(out.contains("Problem: 'gone' is not configured, but old-box is pinned to it"), out);
        assertFalse(out.contains("*"), "no unexplained markers:\n" + out);
    }

    @Test
    void listWorksWithoutIncus() {
        var out = AccountCommand.renderList(List.of(
                new AccountCommand.NamespaceListing("github", List.of(
                        new AccountCommand.AccountLine("me", "", true, "", List.of())), Map.of())),
                "connection refused");
        assertTrue(out.get(out.size() - 1).contains("connection refused"), String.join("\n", out));
    }

    @Test
    void listWithNothingConfiguredSaysHowToStart() {
        assertEquals(List.of("No accounts configured. Run 'isx init' to add one."),
                AccountCommand.renderList(List.of(), ""));
    }
}
