package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.*;
import static org.mockito.Mockito.*;

class GhSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final IncusClient.ExecResult FAIL = new IncusClient.ExecResult(1, "", "");
    private static final String CONTAINER = "test-container";

    @Test
    void declaresGhPackage() {
        var setup = new GhSetup();
        assertEquals(java.util.List.of("gh"), setup.packages());
    }

    @Test
    void envEntriesSetsGhToken() {
        var entries = new GhSetup().envEntries(Map.of());

        assertTrue(entries.stream().anyMatch(e ->
                "GH_TOKEN".equals(e.getName()) && "gho_placeholder".equals(e.getValue())
                        && e.getStrategy() == EnvEntry.Strategy.SET));
    }

    @Test
    void envEntriesUsesEnvVarNotHostsYml() {
        var entries = new GhSetup().envEntries(Map.of());

        assertEquals(1, entries.size());
        assertEquals("GH_TOKEN", entries.get(0).getName());
    }

    @Test
    void setsIdentityFromGitHubApi() {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        ghApiUserReturns(incus, "octocat\tThe Octocat\t\n");
        ghApiEmailsReturns(incus, "12345+testuser@users.noreply.github.com\n");

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("The Octocat"));
        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("12345+testuser@users.noreply.github.com"));
    }

    @Test
    void existingConfigSkipsDefaults() {
        var incus = stubIncus();
        existingConfig(incus);
        existingIdentity(incus);

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus, never()).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("push.default"));
        verify(incus, never()).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("alias."));
    }

    @Test
    void existingIdentitySkipsApiCalls() {
        var incus = stubIncus();
        noExistingConfig(incus);
        existingIdentity(incus);

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("gh api user"));
    }

    @Test
    void noTokenFallbackStillWritesDefaults() {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user")))
                .thenReturn(FAIL);

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        // gitConfigGet calls contain "--get"; identity writes do not
        verify(incus, never()).execInContainer(eq(CONTAINER), eq("agentuser"),
                and(contains("user.name"), not(contains("--get"))));
        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("push.default"));
        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("init.defaultBranch"));
    }

    /**
     * The silent path that left a user's template with gh's defaults and no {@code [user]}: no
     * GitHub token when it was built. The build finds it by asking the guest, and says so.
     */
    @Test
    void theGuestIsAskedWhetherGhHasNoIdentity() {
        var incus = stubIncus();
        when(incus.execInContainer(eq(CONTAINER), eq("agentuser"), contains("command -v gh")))
                .thenReturn(FAIL);
        assertTrue(new GhSetup().lacksBakedIdentity(new Container(incus, CONTAINER)));

        when(incus.execInContainer(eq(CONTAINER), eq("agentuser"), contains("command -v gh")))
                .thenReturn(OK);
        assertFalse(new GhSetup().lacksBakedIdentity(new Container(incus, CONTAINER)));
    }

    @Test
    void theNoIdentityWarningSaysHowToFixIt() {
        var warning = new GhSetup().unbakedIdentityWarning("");
        assertTrue(warning.contains("No git identity"), warning);
        assertTrue(warning.contains("no GitHub token is configured"), warning);
        assertTrue(warning.contains("isx init"), warning);
        assertFalse(warning.contains("next start"), "a plain start does not reconcile: " + warning);
    }

    /** Pinned to a token-less account while another has one: "no token is configured" would be untrue. */
    @Test
    void theNoIdentityWarningNamesATokenlessAccount() {
        var warning = new GhSetup().unbakedIdentityWarning("work");
        assertTrue(warning.contains("account 'work'"), warning);
        assertFalse(warning.contains("no GitHub token is configured"), warning);
        assertTrue(warning.contains("isx init"), warning);
    }

    /** With a token configured, an empty login is a failed lookup: it fails the build. */
    @Test
    void aTokenWhoseLoginIsEmptyFailsTheBuild(@TempDir Path home) throws Exception {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        ghApiUserReturns(incus, "\t\t\n");
        var config = home.resolve(".config/incus-spawn/config.yaml");
        java.nio.file.Files.createDirectories(config.getParent());
        java.nio.file.Files.writeString(config, "github:\n  token: ghp_x\n");
        var prev = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var gh = new GhSetup();
            gh.retryDelaysMs = new long[0];
            assertThrows(dev.incusspawn.incus.IncusException.class,
                    () -> gh.install(new Container(incus, CONTAINER), java.util.Map.of()));
        } finally {
            System.setProperty("user.home", prev);
        }
    }

    /** "Nothing was baked" names no account, so no account's rename may claim it. */
    /** Nothing to derive from, so nothing baked: claiming the name would make every use re-derive, and fail. */
    @Test
    void anAccountWithoutATokenBakesNothing() throws Exception {
        var config = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                .readValue("""
                        github:
                          accounts:
                            work:
                              email: "me@example.com"
                          default: work
                        """, dev.incusspawn.config.SpawnConfig.class);
        assertEquals("", new GhSetup().bakedAccountIdentity(config, "work"));
    }

    @Test
    void retriesTransientIdentityLookupFailure(@TempDir Path home) throws IOException {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user --jq")))
                .thenReturn(FAIL)
                .thenReturn(new IncusClient.ExecResult(0, "octocat\tThe Octocat\t\n", ""));
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user/emails")))
                .thenReturn(FAIL);

        var setup = new GhSetup();
        setup.retryDelaysMs = new long[]{0, 0, 0, 0};
        withTokenConfig(home, () ->
                setup.install(new Container(incus, CONTAINER), java.util.Map.of()));

        verify(incus, times(2)).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user --jq"));
        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"), contains("The Octocat"));
    }

    @Test
    void throwsWhenTokenConfiguredButIdentityLookupFails(@TempDir Path home) throws IOException {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user --jq")))
                .thenReturn(FAIL);

        var setup = new GhSetup();
        setup.retryDelaysMs = new long[]{0, 0, 0, 0};
        assertThrows(IncusException.class, () ->
                withTokenConfig(home, () ->
                        setup.install(new Container(incus, CONTAINER), java.util.Map.of())));
    }

    @Test
    void noRetryWithoutConfiguredToken() {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user --jq")))
                .thenReturn(FAIL);

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus, times(1)).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user --jq"));
    }

    @Test
    void prefersNoreplyWhenPrivacyEnabled() {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        ghApiUserReturns(incus, "octocat\tThe Octocat\t\n");
        ghApiEmailsReturns(incus, "12345+testuser@users.noreply.github.com\n");

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("12345+testuser@users.noreply.github.com"));
    }

    @Test
    void prefersRealEmailWhenPrivacyDisabled() {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        ghApiUserReturns(incus, "octocat\tThe Octocat\tpublic@example.com\n");
        ghApiEmailsReturns(incus, "primary@example.com\n");

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("primary@example.com"));
        verify(incus, never()).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("noreply"));
    }

    @Test
    void fallsBackToPublicEmailWhenEmailsApiUnavailable() {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        ghApiUserReturns(incus, "octocat\tThe Octocat\tpublic@example.com\n");
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user/emails")))
                .thenReturn(FAIL);

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("public@example.com"));
    }

    @Test
    void usesLoginAsNameWhenDisplayNameEmpty() {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        ghApiUserReturns(incus, "octocat\t\t\n");
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user/emails")))
                .thenReturn(FAIL);

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                and(contains("user.name"), contains("octocat")));
    }

    @Test
    void fallsBackToNoreplyWhenNoEmailFound() {
        var incus = stubIncus();
        noExistingConfig(incus);
        noExistingIdentity(incus);
        ghApiUserReturns(incus, "octocat\t\t\n");
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user/emails")))
                .thenReturn(FAIL);

        new GhSetup().install(new Container(incus, CONTAINER), java.util.Map.of());

        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("octocat@users.noreply.github.com"));
    }

    // --- Test helpers ---

    private static void withTokenConfig(Path home, Runnable action) throws IOException {
        var configDir = home.resolve(".config/incus-spawn");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yaml"), "github:\n  token: ghp_test123\n");
        var prev = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            action.run();
        } finally {
            System.setProperty("user.home", prev);
        }
    }

    // ── re-pointing an instance to another GitHub account ───────────────────────

    /**
     * Found by running this against a real container: the re-bake used to clear the identity
     * and then resolve, so any failure -- no network, a revoked token, gh missing -- left the
     * instance with <em>no</em> author at all. That is worse than the stale one it had: commits
     * made before the next attempt would be unattributed rather than merely attributed to the
     * previous account.
     */
    @Test
    void aFailedRepointLeavesThePreviousIdentityIntact() {
        var incus = stubIncus();
        existingIdentity(incus);
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user --jq")))
                .thenReturn(new IncusClient.ExecResult(127, "", "sh: gh: not found"));

        var setup = new GhSetup();
        setup.retryDelaysMs = new long[0];
        assertThrows(IncusException.class, () ->
                setup.rebakeForAccount(new Container(incus, CONTAINER), "acme"));

        verify(incus, never()).execInContainer(eq(CONTAINER), eq("agentuser"),
                contains("--unset"));
        verify(incus, never()).execInContainer(eq(CONTAINER), eq("agentuser"),
                and(contains("git config --global"), contains("user.name ")));
    }

    @Test
    void aRepointOverwritesTheIdentityEvenThoughOneIsAlreadySet() {
        var incus = stubIncus();
        // Deliberately "already has an identity": install() would skip, a re-point must not.
        existingIdentity(incus);
        ghApiUserReturns(incus, "acme-bot\tAcme Bot\t\n");
        ghApiEmailsReturns(incus, "bot@acme.example\n");

        new GhSetup().rebakeForAccount(new Container(incus, CONTAINER), "acme");

        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"), contains("Acme Bot"));
        verify(incus).execInContainer(eq(CONTAINER), eq("agentuser"), contains("bot@acme.example"));
    }

    /**
     * A re-point runs on a started instance, whose token changes on every start (#1108): the
     * lookup must present the one its login shells have, never a constant.
     */
    @Test
    void aRepointPresentsTheTokenOfTheInstanceLoginEnvironment() {
        var incus = stubIncus();
        existingIdentity(incus);
        ghApiUserReturns(incus, "acme-bot\tAcme Bot\t\n");
        ghApiEmailsReturns(incus, "bot@acme.example\n");

        new GhSetup().rebakeForAccount(new Container(incus, CONTAINER), "acme");

        var captor = ArgumentCaptor.forClass(String.class);
        verify(incus, atLeastOnce()).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), captor.capture());
        var apiCalls = captor.getAllValues().stream().filter(cmd -> cmd.contains("gh api")).toList();
        assertEquals(2, apiCalls.size(), apiCalls.toString());
        apiCalls.forEach(cmd -> assertTrue(cmd.startsWith(GhSetup.LOGIN_TOKEN + " gh api"), cmd));
    }

    @Test
    void theLoginTokenIsTheOneTheProfileExports(@TempDir Path dir) throws Exception {
        var profile = dir.resolve("profile");
        Files.writeString(profile, "echo noise; echo more >&2; export GH_TOKEN=gho_isx_0123abcd\n");
        assertEquals("gho_isx_0123abcd", tokenSeenBy(GhSetup.loginToken(profile.toString())));

        Files.writeString(profile, "export OTHER=1\n");
        assertEquals("gho_placeholder", tokenSeenBy(GhSetup.loginToken(profile.toString())),
                "a build has only the placeholder");

        assertEquals("gho_placeholder", tokenSeenBy(GhSetup.loginToken(dir.resolve("missing").toString())));
    }

    @Test
    void aProfileThatEndsTheShellEarlyLeavesThePlaceholder(@TempDir Path dir) throws Exception {
        // A failing special builtin ends a POSIX shell, and with it whatever was to follow
        var profile = dir.resolve("profile");
        Files.writeString(profile, "readonly A=1; A=2\nexport GH_TOKEN=gho_isx_0123abcd\n");
        assertEquals("gho_placeholder", tokenSeenBy(GhSetup.loginToken(profile.toString())),
                "a lookup always presents a token: the placeholder at worst, never none");

        Files.writeString(profile, "exit 3\n");
        assertEquals("gho_placeholder", tokenSeenBy(GhSetup.loginToken(profile.toString())));
    }

    private static String tokenSeenBy(String prefix) throws Exception {
        return GuestShell.run(Path.of(System.getProperty("java.io.tmpdir")), Map.of(),
                "sh", "-c", prefix + " sh -c 'printf %s \"$GH_TOKEN\"'");
    }

    /**
     * Every build stamps a GitHub identity, gh or not; where gh is absent no identity was
     * derived, so bringing it in line is a no-op rather than a failure on every use.
     */
    @Test
    void aRepointWithoutGhInstalledDoesNothing() {
        var incus = stubIncus();
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), eq("command -v gh"))).thenReturn(FAIL);

        new GhSetup().rebakeForAccount(new Container(incus, CONTAINER), "acme");

        verify(incus, never()).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api"));
        verify(incus, never()).execInContainer(eq(CONTAINER), eq("agentuser"), contains("git config --global"));
    }

    @Test
    void ghDeclaresItCanRepointAndClaudeDoesNot() {
        assertTrue(new GhSetup().canRebakeForAccount(),
                "the git identity is recomputable, so a GitHub swap is reconciled, not refused");
        assertFalse(new ClaudeSetup().canRebakeForAccount(),
                "the auth mode lives in an env file a running agent has already read");
    }

    private static IncusClient stubIncus() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);
        return incus;
    }

    private static void existingConfig(IncusClient incus) {
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("test -f")))
                .thenReturn(OK);
    }

    private static void noExistingConfig(IncusClient incus) {
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("test -f")))
                .thenReturn(FAIL);
    }

    private static void existingIdentity(IncusClient incus) {
        when(incus.execInContainer(eq(CONTAINER), eq("agentuser"), contains("git config --global --get")))
                .thenReturn(OK);
    }

    private static void noExistingIdentity(IncusClient incus) {
        when(incus.execInContainer(eq(CONTAINER), eq("agentuser"), contains("git config --global --get")))
                .thenReturn(FAIL);
    }

    private static void ghApiUserReturns(IncusClient incus, String tsv) {
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user --jq")))
                .thenReturn(new IncusClient.ExecResult(0, tsv, ""));
    }

    private static void ghApiEmailsReturns(IncusClient incus, String email) {
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("gh api user/emails")))
                .thenReturn(new IncusClient.ExecResult(0, email, ""));
    }
}
