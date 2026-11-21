package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the Antigravity CLI action's command with a stub {@code agy} that reports how it was
 * started, on files laid out as agy 1.5.13 writes them: one {@code conversations/<id>.db} per
 * conversation and a pretty-printed {@code cache/last_conversations.json}, directory to ID.
 * Linux only: the command runs in a Fedora guest and uses GNU {@code find -printf}.
 */
@EnabledOnOs(OS.LINUX)
class AntigravityActionTest {

    private static final String OLDER = "984c7e75-5c63-4c21-a612-a76db549f75d";
    private static final String NEWER = "7e793d65-0c0a-4d8c-9969-7f90679ec72c";

    @TempDir
    Path home;

    @Test
    void withoutConversationsAgyStartsFresh() throws Exception {
        assertEquals("agy [] in " + home, runAction());
    }

    /** {@code agy --continue} from the home directory would open a new conversation instead. */
    @Test
    void resumesTheNewestConversationInItsDirectory() throws Exception {
        var older = Files.createDirectories(home.resolve("older"));
        var newer = Files.createDirectories(home.resolve("with space"));
        conversation(OLDER, 1_000);
        conversation(NEWER, 2_000);
        lastConversations(Map.of(older, OLDER, newer, NEWER));

        assertEquals("agy [--conversation " + NEWER + "] in " + newer, runAction());
    }

    /** A resume from another directory maps the same ID there too, and either one will do. */
    @Test
    void takesADirectoryOfTheConversationThatIsStillThere() throws Exception {
        var kept = Files.createDirectories(home.resolve("kept"));
        conversation(NEWER, 2_000);
        var map = new LinkedHashMap<Path, String>();
        map.put(home.resolve("gone"), NEWER);
        map.put(kept, NEWER);
        lastConversations(map);

        assertEquals("agy [--conversation " + NEWER + "] in " + kept, runAction());
    }

    /** A removed checkout or worktree does not lose the conversation: it resumes from home. */
    @Test
    void resumesFromHomeWhenNoDirectoryOfTheConversationIsLeft() throws Exception {
        conversation(NEWER, 2_000);
        lastConversations(Map.of(home.resolve("gone"), NEWER));

        assertEquals("agy [--conversation " + NEWER + "] in " + home, runAction());
    }

    /** A newer file that is not a conversation, a backup or a bare {@code .db} say, does not hide the newest one. */
    @Test
    void skipsAFileThatIsNotAConversation() throws Exception {
        var dir = Files.createDirectories(home.resolve("dir"));
        conversation(NEWER, 1_000);
        conversation("backup", 2_000);
        conversation("", 3_000);
        lastConversations(Map.of(dir, NEWER));

        assertEquals("agy [--conversation " + NEWER + "] in " + dir, runAction());
    }

    /** The template's model and effort reach a resume and a fresh start alike. */
    @Test
    void passesTheTemplatesModelAndEffort() throws Exception {
        var env = Map.of(AntigravitySetup.MODEL_ENV, "gemini-4-argon", AntigravitySetup.EFFORT_ENV, "high");
        assertEquals("agy [--model gemini-4-argon --effort high] in " + home, runAction(env));

        var dir = Files.createDirectories(home.resolve("dir"));
        conversation(NEWER, 1_000);
        lastConversations(Map.of(dir, NEWER));
        assertEquals("agy [--conversation " + NEWER + " --model gemini-4-argon --effort high] in " + dir, runAction(env));
    }

    private void conversation(String id, long mtimeSeconds) throws IOException {
        var dir = Files.createDirectories(home.resolve(".gemini/antigravity-cli/conversations"));
        var file = Files.writeString(dir.resolve(id + ".db"), "SQLite format 3");
        Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(mtimeSeconds)));
    }

    private void lastConversations(Map<Path, String> byDirectory) throws IOException {
        var cache = Files.createDirectories(home.resolve(".gemini/antigravity-cli/cache"));
        Files.writeString(cache.resolve("last_conversations.json"), byDirectory.entrySet().stream()
                .map(e -> "  \"" + e.getKey() + "\": \"" + e.getValue() + "\"")
                .collect(Collectors.joining(",\n", "{\n", "\n}")));
    }

    private String runAction() throws Exception {
        return runAction(Map.of());
    }

    private String runAction(Map<String, String> env) throws Exception {
        var bin = Files.createDirectories(home.resolve("bin"));
        var agy = bin.resolve("agy");
        Files.writeString(agy, "#!/bin/sh\necho \"agy [$*] in $PWD\"\n");
        Files.setPosixFilePermissions(agy, PosixFilePermissions.fromString("rwx------"));
        var command = new AntigravitySetup().actions().getFirst().getCommand();
        var pb = new ProcessBuilder("bash", "-c", command).directory(home.toFile()).redirectErrorStream(true);
        pb.environment().put("HOME", home.toString());
        pb.environment().put("PATH", bin + ":/usr/bin:/bin");
        pb.environment().remove(AntigravitySetup.MODEL_ENV);
        pb.environment().remove(AntigravitySetup.EFFORT_ENV);
        pb.environment().putAll(env);
        var process = pb.start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        process.waitFor();
        return out;
    }
}
