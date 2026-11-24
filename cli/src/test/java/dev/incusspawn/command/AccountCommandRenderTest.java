package dev.incusspawn.command;

import dev.incusspawn.config.AccountOrigin;
import dev.incusspawn.config.AccountUsage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The text of {@code isx account show} and {@code list}, which users act on. */
class AccountCommandRenderTest {

    /** Every subcommand naming a missing instance says what {@code isx stop} says, its name on one line. */
    @Test
    void aMissingInstanceIsNamedWithWhereToLook() {
        var recorded = dev.incusspawn.util.RecordingOutput.during(() ->
                assertEquals(1, AccountCommand.noInstance("gh\u001bost").getResultValue()));
        assertEquals(List.of(new dev.incusspawn.util.RecordingOutput.Event(dev.incusspawn.util.RecordingOutput.Kind.ERROR,
                        "no instance named 'gh ost' found.", "Run 'isx list' to see available instances.")),
                recorded.events(dev.incusspawn.util.RecordingOutput.Kind.ERROR));
    }


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

    private static List<String> list(List<AccountCommand.NamespaceListing> namespaces, String unavailable,
                                     boolean verbose, int width) {
        return AccountCommand.renderList(namespaces, unavailable, verbose, () -> width, true);
    }

    private static final List<AccountCommand.NamespaceListing> GITHUB = List.of(
            new AccountCommand.NamespaceListing("github", List.of(
                    new AccountCommand.AccountLine("me", "", true, "", List.of(), List.of("scratch", "other")),
                    new AccountCommand.AccountLine("bot", "commits as bot@x", false, "",
                            List.of("review-1", "review-2"), List.of())),
                    Map.of("gone", List.of("old-box"))));

    @Test
    void listMarksTheDefaultCountsItsFollowersAndNamesWhoIsPinned() {
        assertEquals(List.of(
                "github",
                "  me   default",
                "                2 instances follow it",
                "  bot           commits as bot@x",
                "                pinned to it: review-1, review-2",
                "  Problem: 'gone' is not configured, but old-box is pinned to it",
                "",
                "Instances not pinned to an account use the default; changing it moves them all.",
                "Name them with: isx account list --verbose"), list(GITHUB, "", false, 80));
    }

    /** The followers of a default are nearly every instance, so only --verbose names them. */
    @Test
    void verboseNamesTheFollowers() {
        var out = list(GITHUB, "", true, 80);
        assertEquals("  me   default", out.get(1));
        assertEquals("                following it: scratch, other", out.get(2));
        assertFalse(String.join("\n", out).contains("--verbose"), out.toString());
    }

    @Test
    void aLongListOfPinsWrapsUnderItsFirstName() {
        var pins = java.util.stream.IntStream.range(0, 12).mapToObj(i -> "instance-number-" + i).toList();
        var out = list(List.of(new AccountCommand.NamespaceListing("claude", List.of(
                new AccountCommand.AccountLine("api", "Anthropic API key", false, "", pins, List.of())), Map.of())),
                "", false, 70);
        assertEquals("       pinned to it: instance-number-0, instance-number-1,", out.get(2));
        assertEquals("                     instance-number-2, instance-number-3,", out.get(3));
        assertTrue(out.stream().allMatch(l -> l.length() <= 70), String.join("\n", out));
    }

    @Test
    void listWorksWithoutIncus() {
        var out = list(List.of(
                new AccountCommand.NamespaceListing("github", List.of(
                        new AccountCommand.AccountLine("me", "", true, "", List.of(), List.of())), Map.of())),
                "connection refused", false, 80);
        assertEquals("  me  default", out.get(1));
        assertTrue(out.get(out.size() - 1).contains("connection refused"), String.join("\n", out));
    }

    @Test
    void listWithNothingConfiguredSaysHowToStart() {
        assertEquals(List.of("No accounts configured. Run 'isx account add' to add one."),
                list(List.of(), "", false, 80));
    }

    @Test
    void showExplainsARefusalWithItsOwnFix() {
        var refused = new AccountUsage.Use("claude", "work", DEFAULT, "", "Anthropic API key", "", "",
                "Instance 'review-1' was built for claude 'oauth', but it follows the global default. "
                        + "Pin it to one it was built for: isx account set review-1 claude=personal.");
        var out = String.join("\n", AccountCommand.renderShow("review-1", "tpl-acme", List.of(refused), Map.of()));
        assertTrue(out.contains("Problem: its claude requests are refused. Instance 'review-1' was built for"), out);
        assertTrue(out.contains("isx account set review-1 claude=personal."), out);
        assertFalse(out.contains("isx account unset"), "unpinning is not the fix here:\n" + out);
    }
}
