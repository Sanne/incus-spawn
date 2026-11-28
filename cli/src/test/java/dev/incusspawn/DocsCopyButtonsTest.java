package dev.incusspawn;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The site gives a code block the home page's Copy button when its fence names a language
 * (site/render.js), and the button copies the block whole (#2749). So a command never sits in a
 * bare fence, where it would get no button, and a shell block never holds a {@code $ } prompt,
 * which the button would copy with it (.claude/rules/writing.md "A code block").
 */
@Tag("docs")
class DocsCopyButtonsTest {

    /** An opening fence and its info string, whose first word is the language (as DocsShapeTest reads fences). */
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})\\s*(\\S*)");
    private static final Pattern SHELL = Pattern.compile("shell|bash|sh|zsh|console");
    /** The start of a line a reader types, as the docs' commands begin. */
    private static final Pattern COMMAND = Pattern.compile(
            "^\\s*(isx|sudo|git|curl|brew|jbang|gh|ssh|scp|incus|mvn|gradle|podman|docker|zmx|cd|chmod|export|\\./\\S+)\\s");
    private static final Pattern PROMPT = Pattern.compile("^\\s*\\$\\s");

    @Test
    void everyPageKeepsCommandsCopyable() throws IOException {
        var problems = new ArrayList<String>();
        for (var page : DocsShapeTest.pages()) {
            var name = Path.of("..").relativize(page).toString();
            for (var problem : problems(Files.readAllLines(page))) problems.add(name + ":" + problem);
        }
        assertEquals(List.of(), problems, "a command in a bare fence gets no Copy button, and a $ prompt is copied with the command");
    }

    @Test
    void findsACommandInABareFenceAndAPromptInAShellBlock() {
        assertEquals(List.of("2: a command in a bare fence", "6: a $ prompt in a shell block"), problems(List.of(
                "```",
                "isx branch tpl-java fix",
                "```",
                "",
                "```shell",
                "$ isx list",
                "```",
                "```",
                "tpl-java  (stopped)",
                "```",
                "```shell",
                "isx list",
                "```")));
    }

    /** Each problem as {@code <line>: <what>}, 1-based. */
    static List<String> problems(List<String> lines) {
        var problems = new ArrayList<String>();
        String fence = null;
        String language = null;
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            if (fence == null) {
                var opens = FENCE.matcher(line);
                if (opens.find()) {
                    fence = opens.group(1);
                    language = opens.group(2);
                }
                continue;
            }
            // A fence closes on a line of the same character, at least as long, and nothing else.
            var s = line.strip();
            char mark = fence.charAt(0);
            if (s.length() >= fence.length() && s.chars().allMatch(c -> c == mark)) {
                fence = null;
                continue;
            }
            if (language.isEmpty() && COMMAND.matcher(line).find()) problems.add((i + 1) + ": a command in a bare fence");
            if (SHELL.matcher(language).matches() && PROMPT.matcher(line).find()) problems.add((i + 1) + ": a $ prompt in a shell block");
        }
        return problems;
    }
}
