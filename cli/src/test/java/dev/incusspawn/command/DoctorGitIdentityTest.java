package dev.incusspawn.command;

import dev.incusspawn.config.Jjq;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@code isx doctor} names whom agents commit as and where that came from (#2410). */
class DoctorGitIdentityTest {

    @Test
    void insideAnInstanceTheIdentityAndItsSourceAreNamed() {
        var fallback = DoctorCommand.instanceGitIdentityFinding(
                "file:/etc/gitconfig\tJane Doe\nfile:/etc/gitconfig\tjane@example.org\nconfig.yaml\n");
        assertEquals(DoctorCommand.Status.OK, fallback.status());
        assertEquals("(Jane Doe <jane@example.org>, from the git-identity in config.yaml)", fallback.detail());

        var account = DoctorCommand.instanceGitIdentityFinding(
                "file:/home/agentuser/.gitconfig\tagent-bot\nfile:/home/agentuser/.gitconfig\tbot@example.com\n");
        assertEquals("(agent-bot <bot@example.com>, from ~/.gitconfig, which the gh tool writes from the GitHub account)",
                account.detail());
    }

    @Test
    void insideAnInstanceNoIdentityIsAWarning() {
        var none = DoctorCommand.instanceGitIdentityFinding("");
        assertEquals(DoctorCommand.Status.WARN, none.status());
        assertEquals("(none: commits made here have no author)", none.detail());
    }

    @Test
    void onTheHostTheConfiguredIdentityIsNamed() throws Exception {
        assertNull(DoctorCommand.hostGitIdentityFinding(Jjq.spawnConfig("{}")));

        var ok = DoctorCommand.hostGitIdentityFinding(Jjq.spawnConfig(
                "git-identity:\n  name: Jane Doe\n  email: jane@example.org\n"));
        assertEquals(DoctorCommand.Status.OK, ok.status());
        assertTrue(ok.detail().startsWith("(Jane Doe <jane@example.org>, from the git-identity in config.yaml"), ok.detail());

        var wrong = DoctorCommand.hostGitIdentityFinding(Jjq.spawnConfig("git-identity:\n  name: Jane Doe\n"));
        assertEquals(DoctorCommand.Status.WARN, wrong.status());
        assertTrue(wrong.detail().contains("email: is missing"), wrong.detail());
    }
}
