package dev.incusspawn.proxy;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.tool.CopilotSetup;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What GitHub Copilot CLI does with its GitHub token, held against what isx serves it (#2537).
 * A Copilot subscription is the only way to run Copilot CLI for real, so these facts are recorded
 * from its published binary instead: {@code @github/copilot-linux-x64} 1.0.115, the bundle its
 * executable carries, read with {@code grep}.
 * <ul>
 *   <li>It reads the token from {@code COPILOT_GITHUB_TOKEN} first, then {@code GH_TOKEN} and
 *       {@code GITHUB_TOKEN}.</li>
 *   <li>It drops a token that starts with {@code ghp_} before sending it: "Classic Personal Access
 *       Tokens (ghp_) are not supported ... will be ignored". It takes {@code gho_} (an OAuth
 *       token) and {@code github_pat_} (a fine-grained one).</li>
 *   <li>It sends the token to {@code https://api.github.com/copilot_internal/...} (its user and
 *       its token exchange) and to the Copilot API at {@code api.githubcopilot.com} and the
 *       per-plan {@code api.individual.}, {@code api.business.} and {@code api.enterprise.}
 *       hosts.</li>
 * </ul>
 * So in an instance its token is isx's placeholder, which must have a prefix it takes, and every
 * one of those hosts must be served the {@code github} account's token in its place. If a later
 * Copilot CLI sends the token to another host, re-record the list from its binary.
 */
class CopilotCliTokenContractTest {

    /** The hosts Copilot CLI 1.0.115 sends its GitHub token to. */
    static final List<String> COPILOT_CLI_TOKEN_HOSTS = List.of(
            "api.github.com",
            "api.githubcopilot.com",
            "api.individual.githubcopilot.com",
            "api.business.githubcopilot.com",
            "api.enterprise.githubcopilot.com");

    /** The token prefixes Copilot CLI 1.0.115 sends; it ignores a {@code ghp_} one. */
    static final List<String> COPILOT_CLI_TAKES = List.of("gho_", "github_pat_");

    static final String SECRET = "34".repeat(32);
    static final String TOKEN = "the-agent-accounts-token";

    /** The built-in tools the proxy serves, whatever the instance's template installed. */
    private static Map<String, ToolSetup> builtInTools() {
        return new ToolDefLoader(List.of()).allToolSetups();
    }

    /**
     * A placeholder Copilot CLI ignored would never reach the proxy, and Copilot would ask to log
     * in: the guest's token must carry a prefix it takes, whatever token the account holds.
     */
    @Test
    void theGuestsTokenHasAPrefixCopilotCliTakes() {
        var placeholders = new CopilotSetup().proxy().getPlaceholders();
        assertEquals(1, placeholders.size(), placeholders.toString());
        var placeholder = placeholders.getFirst();
        assertEquals("COPILOT_GITHUB_TOKEN", placeholder.getEnv(),
                "the variable Copilot CLI reads before GH_TOKEN and GITHUB_TOKEN");
        assertTrue(COPILOT_CLI_TAKES.stream().anyMatch(placeholder.getPrefix()::startsWith),
                "Copilot CLI ignores a token starting with " + placeholder.getPrefix());
    }

    /** The proof in Copilot CLI's bearer token is read as the github namespace's at every host it sends it to. */
    @Test
    void everyHostCopilotCliSendsItsTokenToReadsTheGitHubProof() {
        // The placeholder as a start fills it, in the namespace its credential belongs to
        var placeholder = new CopilotSetup().placeholders().getFirst();
        assertEquals("github", placeholder.namespace(), "the namespace Copilot CLI's proof is minted in");
        var proof = placeholder.tokenFor(SECRET);
        var carriers = new ProofCarriers(builtInTools().values());
        for (var host : COPILOT_CLI_TOKEN_HOSTS) {
            var presented = carriers.read(host,
                    name -> name.equalsIgnoreCase("Authorization") ? List.of("Bearer " + proof) : List.of());
            // An exact host and the wildcard above it may both read it: once per carrier.
            assertEquals(Set.of(ProofToken.derive(SECRET, "github")),
                    Set.copyOf(presented.digests().getOrDefault("github", List.of())),
                    "the github proof at " + host);
        }
    }

    /**
     * Every host gets the github account's token, the one isx init saved. At api.github.com that
     * comes from the gh tool's entry, which the proxy serves to an instance without gh too.
     */
    @Test
    void everyHostCopilotCliSendsItsTokenToIsServedTheGitHubAccountsToken() throws Exception {
        var config = Jjq.spawnConfig("""
                github:
                  accounts:
                    agent:
                      token: "%s"
                  default: agent
                """.formatted(TOKEN));
        var resolved = ToolProxyResolver.resolve(config, builtInTools(), Map.of());
        for (var host : COPILOT_CLI_TOKEN_HOSTS) {
            var serving = resolved.stream()
                    .filter(entry -> ProofCarriers.matchesDomain(entry.domain(), host))
                    .toList();
            assertFalse(serving.isEmpty(), "nothing injects a token at " + host);
            for (var entry : serving) {
                assertEquals("Bearer " + TOKEN, entry.computeHeaderValue(),
                        entry.toolName() + " at " + host + " (" + entry.domain() + ")");
            }
        }
    }
}
