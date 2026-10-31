package dev.incusspawn.command;

import dev.incusspawn.IncusSpawn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * The {@code isx} a completion script runs in the real-shell tests: it records the words of each
 * {@code isx --aesh-complete -- <words>} request and prints the answer the test gave it.
 * <p>
 * {@link #answerFromCompleters()} gives it what {@link Completers#answer} answers for the words
 * the script sent, so a test runs the script against the real request: once to record the words,
 * then again with their answer.
 */
final class FakeIsx {

    private final Path bin, request, answer;

    FakeIsx(Path dir) throws IOException {
        bin = Files.createDirectories(dir.resolve("bin"));
        request = dir.resolve("request");
        answer = dir.resolve("answer");
        var isx = bin.resolve("isx");
        Files.writeString(isx, """
                #!/bin/bash
                [ "$1 $2" = "--aesh-complete --" ] || exit 0
                shift 2
                printf '%%s\\0' "$@" > %s
                cat %s 2>/dev/null
                exit 0
                """.formatted(request, answer));
        isx.toFile().setExecutable(true);
        answer("");
    }

    /** The directory to put first on the shell's {@code PATH}. */
    Path bin() {
        return bin;
    }

    /** Has the fake print {@code text} for every request, and forgets the last request. */
    void answer(String text) throws IOException {
        Files.writeString(answer, text);
        Files.deleteIfExists(request);
    }

    /** The words of the last request, or null when the script made none. */
    List<String> request() throws IOException {
        if (!Files.exists(request)) return null;
        var words = Files.readString(request);
        return words.isEmpty() ? List.of() : Arrays.asList(words.substring(0, words.length() - 1).split("\0", -1));
    }

    /**
     * Has the fake print what {@link Completers#answer} answers on Linux for the words of the last
     * request. Returns false when the script made none.
     */
    boolean answerFromCompleters() throws IOException {
        var words = request();
        if (words == null) return false;
        var bytes = new ByteArrayOutputStream();
        Completers.answer(IncusSpawn.IncusSpawnLinuxCommand.class, words, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        answer(bytes.toString(StandardCharsets.UTF_8));
        return true;
    }
}
