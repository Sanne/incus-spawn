package dev.incusspawn.command;

import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.incusspawn.command.IsolatedHome.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The directory-list prompts behind both "Code Directories" (host paths) and "Template Search
 * Paths": add, remove by number, and what is saved. Host paths decide which checkouts can be
 * reference-cloned into containers, so an entry must never be added or dropped by accident.
 */
@ExtendWith(IsolatedHome.class)
class PathListPromptsTest {

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    private static Path dir(String name) throws Exception {
        return Files.createDirectories(home().resolve(name));
    }

    private static void edit(SpawnConfig config, ScriptedPrompts prompts) {
        new InitCommand().setupPathList(SpawnConfig::getHostPaths, SpawnConfig::setHostPaths,
                "  (skipped)", config, prompts);
        prompts.assertFullyConsumed();
    }

    private static List<String> savedPaths() {
        return SpawnConfig.load().getHostPaths();
    }

    @Test
    void anAddedDirectoryIsSavedAsAnAbsoluteNormalizedPath() throws Exception {
        var code = dir("code");
        edit(new SpawnConfig(), ScriptedPrompts.lines(code + "/./", ""));
        assertEquals(List.of(code.toString()), savedPaths());
    }

    @Test
    void aTildeIsExpandedToTheHostHome() throws Exception {
        var code = dir("code");
        edit(new SpawnConfig(), ScriptedPrompts.lines("~/code", ""));
        assertEquals(List.of(code.toString()), savedPaths());
    }

    /** A directory that does not exist yet can still be recorded -- it may be created later -- once confirmed. */
    @Test
    void aMissingDirectoryIsAddedWhenConfirmed() {
        var missing = home().resolve("not-yet");
        edit(new SpawnConfig(), ScriptedPrompts.lines(missing.toString(), "y", ""));
        assertEquals(List.of(missing.toString()), savedPaths());
    }

    /** More often a missing directory is a typo, so it takes a yes to add one. */
    @Test
    void aMissingDirectoryIsNotAddedByDefault() {
        edit(new SpawnConfig(), ScriptedPrompts.lines(home().resolve("not-yet").toString(), "", ""));
        assertNothingSaved();
    }

    @Test
    void aUrlIsRefused() {
        edit(new SpawnConfig(), ScriptedPrompts.lines("https://github.com/me/code", ""));
        assertNothingSaved();
    }

    @Test
    void aDuplicateIsNotAddedTwice() throws Exception {
        var code = dir("code");
        edit(new SpawnConfig(), ScriptedPrompts.lines(code.toString(), "~/code", ""));
        assertEquals(List.of(code.toString()), savedPaths());
    }

    @Test
    void aNumberRemovesThatEntry() throws Exception {
        var config = new SpawnConfig();
        config.setHostPaths(List.of(dir("a").toString(), dir("b").toString()));
        edit(config, ScriptedPrompts.lines("1", ""));
        assertEquals(List.of(home().resolve("b").toString()), savedPaths());
    }

    /** The list prints numbered entries, so "#2" is read as entry 2 rather than a directory named "#2" (#777). */
    @Test
    void aHashPrefixedNumberRemovesThatEntry() throws Exception {
        var config = new SpawnConfig();
        config.setHostPaths(List.of(dir("a").toString(), dir("b").toString()));
        edit(config, ScriptedPrompts.lines("#2", ""));
        assertEquals(List.of(home().resolve("a").toString()), savedPaths());
    }

    /** "#" alone, or "#" before anything but a number, is a botched removal and never a path to add. */
    @Test
    void otherHashInputIsNotAddedAsADirectory() throws Exception {
        var config = new SpawnConfig();
        config.setHostPaths(List.of(dir("a").toString()));
        edit(config, ScriptedPrompts.lines("#", "#a", ""));
        assertNothingSaved();
    }

    /** A number that names no entry is not read as a path to add, and removes nothing. */
    @Test
    void anOutOfRangeNumberChangesNothing() throws Exception {
        var config = new SpawnConfig();
        config.setHostPaths(List.of(dir("a").toString()));
        edit(config, ScriptedPrompts.lines("2", "0", ""));
        assertNothingSaved();
    }

    @Test
    void finishingWithoutChangesDoesNotRewriteTheFile() throws Exception {
        var config = new SpawnConfig();
        config.setHostPaths(List.of(dir("a").toString()));
        edit(config, ScriptedPrompts.lines(""));
        assertNothingSaved();
    }

    /** EOF finishes the list, keeping what was entered before it. */
    @Test
    void closedStdinKeepsWhatWasAlreadyEntered() throws Exception {
        var code = dir("code");
        var prompts = ScriptedPrompts.lines(code.toString());
        new InitCommand().setupPathList(SpawnConfig::getHostPaths, SpawnConfig::setHostPaths,
                "  (skipped)", new SpawnConfig(), prompts);
        assertEquals(List.of(code.toString()), savedPaths());
    }

    @Test
    void searchPathsUseTheSameListAndSaveToTheirOwnField() throws Exception {
        var templates = dir("templates");
        var prompts = ScriptedPrompts.lines(templates.toString(), "");
        new InitCommand().setupPathList(SpawnConfig::getSearchPaths, SpawnConfig::setSearchPaths,
                "  (skipped)", new SpawnConfig(), prompts);
        prompts.assertFullyConsumed();

        var saved = SpawnConfig.load();
        assertEquals(List.of(templates.toString()), saved.getSearchPaths());
        assertEquals(List.of(), saved.getHostPaths());
    }
}
