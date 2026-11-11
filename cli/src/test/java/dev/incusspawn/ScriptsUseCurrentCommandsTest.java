package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shell script must not call {@code isx instances}, removed in favour of {@code isx list}
 * (#2133): appliance/test-with-isx.sh polled it and always timed out against a current isx.
 */
class ScriptsUseCurrentCommandsTest {

    private static final Path ROOT = Path.of("..");

    @Test
    void noScriptCallsTheRemovedInstancesCommand() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Stream.of("appliance", "scripts", ".github")
                .map(ROOT::resolve).filter(Files::isDirectory).flatMap(d -> {
                    try {
                        return Files.walk(d);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })) {
            offenders = files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".sh") || p.toString().endsWith(".yml"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).lines()
                                    .anyMatch(l -> l.contains("ISX\" instances") || l.contains("isx instances"));
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .map(Path::toString).toList();
        }
        assertTrue(offenders.isEmpty(), "calls the removed `isx instances`: " + offenders);
    }
}
