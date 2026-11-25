package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** #2492: every question with several choices is laid out by the seam, not by each step. */
class PromptsChooseTest {

    @Test
    void aMenuHasALeadInGroupedOptionsASharedNoteAndAPromptSayingWhatEnterDoes() {
        var prompts = ScriptedPrompts.lines("2");
        var answer = prompts.choose("How should instances authenticate with OpenAI?", List.of(
                "ChatGPT account, browser on this machine\n(ChatGPT and Enterprise plans, SSO included)",
                "OpenAI API key (platform.openai.com, billed credits)"),
                "Either ChatGPT option keeps the session on this host.", "leave OpenAI unconfigured");
        assertEquals("2", answer);
        assertEquals(List.of(
                "How should instances authenticate with OpenAI?",
                "",
                "  1. ChatGPT account, browser on this machine",
                "     (ChatGPT and Enterprise plans, SSO included)",
                "  2. OpenAI API key (platform.openai.com, billed credits)",
                "",
                "Either ChatGPT option keeps the session on this host.",
                "",
                "? Choice, or Enter to leave OpenAI unconfigured: "), prompts.conversation());
    }

    /**
     * The prompt says what Enter does in one of two forms (#2528): the choice Enter takes in
     * brackets, or what Enter does that no choice does after "or Enter to". Nothing is said
     * where Enter does nothing.
     */
    @Test
    void thePromptSaysWhatEnterDoesInOneOfTwoForms() {
        var withDefault = ScriptedPrompts.lines("");
        assertEquals("", withDefault.chooseDefault("Which provider should Pi use?", List.of("Claude", "OpenAI"), null, 1));
        assertEquals("? Choice [1]: ", withDefault.conversation().getLast());

        var otherwise = ScriptedPrompts.lines("");
        otherwise.choose("What should the base image do?", List.of("Track", "Pin"), null, "cancel");
        assertEquals("? Choice, or Enter to cancel: ", otherwise.conversation().getLast());

        var nothing = ScriptedPrompts.lines("1");
        nothing.choose("Which agent?", List.of("Pi", "Codex"), null, null);
        assertEquals("? Choice: ", nothing.conversation().getLast());

        var several = ScriptedPrompts.lines("all");
        several.chooseSeveral("Which credentials should isx set up?", List.of("Claude", "GitHub"), "skip");
        assertEquals("? Numbers (1,3) or 'all', or Enter to skip: ", several.conversation().getLast());
    }
}
