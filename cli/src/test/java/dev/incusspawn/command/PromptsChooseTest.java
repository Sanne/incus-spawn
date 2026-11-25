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
                "? Choice (Enter to leave OpenAI unconfigured): "), prompts.conversation());
    }
}
