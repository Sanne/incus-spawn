package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code git-identity:} (#2410): the name and email agents commit under when no GitHub account
 * gives one. A wrong section sets no identity and says why.
 */
class GitIdentityTest {

    private final List<String> warnings = new ArrayList<>();

    private GitIdentity read(String yaml) throws Exception {
        return Jjq.spawnConfig(yaml).gitIdentity(warnings::add);
    }

    @Test
    void noSectionSetsNoIdentityAndWritesNoKey() throws Exception {
        assertNull(read("{}"));
        assertFalse(Jjq.toYaml(new SpawnConfig()).contains(GitIdentity.KEY));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aNameAndAnEmailAreTheIdentity() throws Exception {
        var identity = read("""
                git-identity:
                  name: " Jane Doe "
                  email: jane@example.org
                """);
        assertEquals(new GitIdentity("Jane Doe", "jane@example.org"), identity);
        assertEquals("Jane Doe <jane@example.org>", identity.toString());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aWrittenIdentityReadsBack() throws Exception {
        var config = new SpawnConfig();
        config.useGitIdentity(new GitIdentity("Jane Doe", "jane@example.org"));
        var yaml = Jjq.toYaml(config);
        assertEquals(new GitIdentity("Jane Doe", "jane@example.org"), read(yaml));
    }

    @Test
    void aWrongSectionSetsNoIdentityAndSaysWhy() throws Exception {
        assertNull(read("git-identity: Jane Doe"));
        assertNull(read("git-identity:\n  name: Jane Doe\n"));
        assertNull(read("git-identity:\n  name: Jane Doe\n  email: jane@example.org\n  signingkey: abc\n"));
        assertNull(read("git-identity:\n  name: \"Jane <x>\"\n  email: jane@example.org\n"));
        assertNull(read("git-identity:\n  name: \"Jane\\nDoe\"\n  email: jane@example.org\n"));
        assertEquals(5, warnings.size(), warnings.toString());
        warnings.forEach(w -> assertTrue(w.startsWith("Ignoring git-identity: in config.yaml"), w));
    }

    @Test
    void aWrongSectionIsKeptAsWritten() throws Exception {
        var yaml = "git-identity:\n  name: Jane Doe\n  mail: jane@example.org\n";
        assertTrue(Jjq.toYaml(Jjq.spawnConfig(yaml)).contains("mail: jane@example.org"));
    }
}
