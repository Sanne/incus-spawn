package dev.incusspawn.command;

import dev.incusspawn.config.GitIdentity;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.function.Supplier;

import static dev.incusspawn.command.IsolatedHome.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx init}'s offer of the host's Git identity to agents with no GitHub account (#2410).
 * Only a typed yes, or the flag, writes {@code git-identity:}; Enter and a run without a terminal
 * leave it unset. Every assertion reads config.yaml back from disk.
 */
@ExtendWith(IsolatedHome.class)
class AgentGitIdentityStepTest {

    private static final GitIdentity JANE = new GitIdentity("Jane Doe", "jane@example.org");

    private static final String NO_ACCOUNT = "features: []\n";

    private static final String GITHUB_ACCOUNT = """
            github:
              token: "ghp_flat"
            """;

    private static boolean offer(Prompts prompts, boolean flag, GitIdentity host) throws Exception {
        return offer(prompts, flag, () -> host);
    }

    private static boolean offer(Prompts prompts, boolean flag, Supplier<GitIdentity> host) throws Exception {
        var written = new boolean[1];
        RecordingOutput.during(() -> written[0] = AgentGitIdentityStep.offer(SpawnConfig.load(), prompts, flag, host));
        return written[0];
    }

    @Test
    void enterLeavesTheKeyUnset() throws Exception {
        seed(NO_ACCOUNT);
        var prompts = ScriptedPrompts.lines("");
        assertFalse(offer(prompts, false, JANE));
        prompts.assertFullyConsumed();
        assertUnchanged(NO_ACCOUNT);
        assertEquals(List.of("No GitHub account: the agent has no name to commit under.",
                        "? Let it commit as Jane Doe <jane@example.org>, the identity of this host? (y/N): "),
                prompts.conversation());
    }

    @Test
    void aTypedYesWritesTheHostsIdentity() throws Exception {
        seed(NO_ACCOUNT);
        var prompts = ScriptedPrompts.lines("y");
        assertTrue(offer(prompts, false, JANE));
        prompts.assertFullyConsumed();
        assertEquals("Jane Doe", saved("git-identity.name"));
        assertEquals("jane@example.org", saved("git-identity.email"));
    }

    @Test
    void endOfInputIsNo() throws Exception {
        seed(NO_ACCOUNT);
        assertFalse(offer(ScriptedPrompts.lines(), false, JANE));
        assertUnchanged(NO_ACCOUNT);
    }

    @Test
    void withoutATerminalNothingIsAssumed() throws Exception {
        seed(NO_ACCOUNT);
        var asked = new boolean[1];
        assertFalse(offer(null, false, () -> {
            asked[0] = true;
            return JANE;
        }));
        assertFalse(asked[0], "the host's identity is not even read");
        assertUnchanged(NO_ACCOUNT);
    }

    @Test
    void theFlagAnswersYesWithOrWithoutATerminal() throws Exception {
        seed(NO_ACCOUNT);
        assertTrue(offer(null, true, JANE));
        assertEquals("Jane Doe", saved("git-identity.name"));

        seed(NO_ACCOUNT);
        var prompts = ScriptedPrompts.lines();
        assertTrue(offer(prompts, true, JANE));
        prompts.assertFullyConsumed();
        assertEquals("jane@example.org", saved("git-identity.email"));
    }

    @Test
    void aHostWithNoIdentityIsNeverAskedAbout() throws Exception {
        seed(NO_ACCOUNT);
        var prompts = ScriptedPrompts.lines();
        assertFalse(offer(prompts, false, (GitIdentity) null));
        assertFalse(offer(prompts, true, (GitIdentity) null));
        prompts.assertFullyConsumed();
        assertUnchanged(NO_ACCOUNT);
    }

    @Test
    void aGitHubAccountGivesTheIdentitySoNothingIsAsked() throws Exception {
        seed(GITHUB_ACCOUNT);
        var prompts = ScriptedPrompts.lines();
        assertFalse(offer(prompts, true, JANE));
        prompts.assertFullyConsumed();
        assertUnchanged(GITHUB_ACCOUNT);
    }

    @Test
    void aConfiguredIdentityIsKept() throws Exception {
        var configured = "git-identity:\n  name: Someone\n  email: someone@example.org\n";
        seed(configured);
        var prompts = ScriptedPrompts.lines();
        assertFalse(offer(prompts, true, JANE));
        prompts.assertFullyConsumed();
        assertUnchanged(configured);
    }
}
