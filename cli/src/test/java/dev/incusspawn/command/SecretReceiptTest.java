package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every pasted secret gets a receipt (#2565): a secret is not echoed, so "Received N characters"
 * is the only sign that the paste arrived. The first review of the first run removed it as a
 * second outcome line, and the GitHub step then went from the paste to its email question with
 * nothing said.
 */
class SecretReceiptTest {

    @Test
    void theReceiptGivesTheLengthAndTheMaskedEndsWithNoTick() {
        var secret = "sk-ant-oat01-" + "A".repeat(91) + "AwAA";
        assertEquals("Received 108 characters (" + CredentialSetup.maskSecret(secret) + ")",
                CredentialSetup.describeReceivedSecret(secret));
        assertEquals("Received 1 character (****)", CredentialSetup.describeReceivedSecret("x"));
        assertFalse(CredentialSetup.describeReceivedSecret(secret).contains(secret));
    }

    @Test
    void askSecretSaysTheSecretArrivedAndNeverShowsIt() {
        var secret = "github_pat_" + "b".repeat(40);
        var lines = RecordingOutput.during(() ->
                assertEquals(secret, CredentialSetup.askSecret(new ScriptedPrompts().secret("  " + secret + " "), "Paste it: "))).lines();
        assertTrue(lines.stream().anyMatch(l -> l.contains(CredentialSetup.describeReceivedSecret(secret))), lines::toString);
        assertTrue(lines.stream().noneMatch(l -> l.contains(secret)), lines::toString);
        assertTrue(lines.stream().noneMatch(l -> l.contains("\u2713")), "a receipt is no tick: " + lines);
    }

    @Test
    void anEmptyAnswerGetsNoReceipt() {
        var lines = RecordingOutput.during(() ->
                assertEquals("", CredentialSetup.askSecret(new ScriptedPrompts().secret(""), "Paste it: "))).lines();
        assertTrue(lines.stream().noneMatch(l -> l.contains("Received")), lines::toString);
    }

    /**
     * No prompt reads a secret without a receipt: every raw {@code askSecret} of a
     * {@link Prompts} in the command sources is followed, within its own handling, by
     * {@code received(}. A new secret prompt goes through {@link CredentialSetup#askSecret},
     * which has it, or calls {@code received} itself after ruling out what is no secret.
     */
    @Test
    void everyPromptThatReadsASecretGivesAReceipt() throws IOException {
        var root = Path.of("src", "main", "java");
        var missing = new ArrayList<String>();
        var read = 0;
        try (var files = Files.walk(root)) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                // The interface that declares it, and its console implementations, read and say nothing
                if (file.getFileName().toString().equals("Prompts.java")) continue;
                var lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (!lines.get(i).contains("prompts.askSecret(")) continue;
                    read++;
                    var window = String.join("\n", lines.subList(i, Math.min(lines.size(), i + WINDOW)));
                    if (!window.contains("received(")) missing.add(root.relativize(file) + ":" + (i + 1));
                }
            }
        }
        assertEquals(List.of(), missing, "a secret is read with no receipt after it");
        assertTrue(read >= 4, "the scan found " + read + " secret prompts: has the call been renamed?");
    }

    /** Lines after a raw secret read within which its receipt must come: the GitHub prompt first rules out c, u and Enter. */
    private static final int WINDOW = 30;
}
