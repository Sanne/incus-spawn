package dev.incusspawn.lifecycle;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProofToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every isx start gives the instance a new secret (#934): its hash recorded before the start,
 * the secret itself put in place once the guest answers, and the proxy told.
 */
@ExtendWith(TempHome.class)
class InstanceSecretRotationTest {

    private static final String NAME = "box";

    private static String recordedHash(FakeIncusDaemon daemon, String name) {
        return daemon.instance(name).path("config").path(Metadata.INSTANCE_SECRET_SHA256).asText("");
    }

    /** The secret an exec hands the guest, or null. */
    private static String secretIn(FakeIncusDaemon.Exec exec) {
        var secret = exec.environment().get(InstanceSecret.DELIVERY_ENV);
        return secret != null && String.join(" ", exec.command()).contains(InstanceSecret.GUEST_SCRIPT) ? secret : null;
    }

    @Test
    void aStoppedStartRotatesTheSecretAndTheReadinessProbeDeliversIt() {
        var previous = InstanceSecret.sha256(InstanceSecret.generate());
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(Metadata.INSTANCE_SECRET_SHA256, previous))
                .ipFiltering(NAME, "true");
        int signalsBefore = TempHome.proxySignals();

        // FakeIncusDaemon serves no exec, so the wait can only time out; what matters is what
        // was asked of Incus on the way
        assertThrows(IncusException.class, () -> InstanceLifecycle.ensureReady(
                daemon.clientWithShortReadyWait(), NAME, daemon.instance(NAME), MachineType.CONTAINER, RecordingOutput.QUIET));

        var recorded = recordedHash(daemon, NAME);
        assertNotEquals(previous, recorded, "a start must retire the previous start's secret");
        var requests = daemon.requests();
        int write = requests.indexOf("PATCH /1.0/instances/" + NAME);
        int start = requests.indexOf("PUT /1.0/instances/" + NAME + "/state");
        assertTrue(write >= 0 && start > write, "recorded before the guest can run:\n" + String.join("\n", requests));
        assertEquals(signalsBefore, TempHome.proxySignals(),
                "signalling the proxy costs every start a bridge read, a health call and a fork");

        var probes = daemon.execs();
        assertFalse(probes.isEmpty(), "the readiness probe ran");
        for (var probe : probes) {
            assertEquals(List.of("sh", "-c"), probe.command().subList(0, 2));
            var secret = secretIn(probe);
            assertNotNull(secret, "every probe carries the secret: any one of them may be the first to land");
            assertEquals(recorded, InstanceSecret.sha256(secret), "the guest gets the secret of the recorded hash");
            assertFalse(String.join(" ", probe.command()).contains(secret),
                    "any guest user can read a command line: the secret travels in the environment");
            assertTrue(probe.command().get(2).endsWith("\necho ready"), probe.command().get(2));
        }
        assertFalse(daemon.instance(NAME).path("config").toString().contains(secretIn(probes.getFirst())),
                "the guest can read its own user.* keys: the secret itself is never stored there");
    }

    @Test
    void everyStartMakesANewSecret() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of());
        var incus = daemon.client();
        var first = InstanceLifecycle.rotateInstanceSecret(incus, NAME);
        var firstHash = recordedHash(daemon, NAME);
        var second = InstanceLifecycle.rotateInstanceSecret(incus, NAME);

        assertNotEquals(first, second);
        assertNotEquals(firstHash, recordedHash(daemon, NAME));
        assertEquals(InstanceSecret.sha256(second.secret()), recordedHash(daemon, NAME));
    }

    // ── the ChatGPT session a start serves (#1332) ──────────────────────────────

    /** A host configured with a ChatGPT account and a session signed in to it. */
    private static dev.incusspawn.proxy.ChatGptSessionStore.Session chatGptHost() throws Exception {
        var configDir = java.nio.file.Files.createDirectories(
                java.nio.file.Path.of(System.getProperty("user.home"), ".config/incus-spawn"));
        java.nio.file.Files.writeString(configDir.resolve("config.yaml"), """
                openai:
                  accounts:
                    sso:
                      type: chatgpt
                  default: sso
                """);
        var enc = java.util.Base64.getUrlEncoder().withoutPadding();
        var auth = "{\"chatgpt_account_id\":\"acct-ent\"}";
        var jwt = enc.encodeToString("{}".getBytes()) + "."
                + enc.encodeToString(("{\"https://api.openai.com/auth\":" + auth + "}").getBytes()) + ".sig";
        return new dev.incusspawn.proxy.ChatGptSessionStore().create("sso",
                new dev.incusspawn.proxy.ChatGptSessionStore.Tokens(jwt, jwt, "rt-real", "acct-ent"));
    }

    @Test
    void aStartServesTheInstancesChatGptSessionWithTheSecretItMinted() throws Exception {
        var session = chatGptHost();
        var daemon = new FakeIncusDaemon().container(NAME,
                Map.of(Metadata.accountKey("openai"), "sso"));

        var delivery = InstanceLifecycle.rotateInstanceSecret(daemon.client(), NAME);

        var config = daemon.instance(NAME).path("config");
        assertEquals("sso:" + session.sessionId(), config.pathText(Metadata.CHATGPT_SESSION, ""));
        var proofs = dev.incusspawn.proxy.ChatGptProof.derive(delivery.secret(), "sso", session.sessionId());
        assertEquals(dev.incusspawn.proxy.ChatGptProof.verifier(proofs.access()),
                config.pathText(Metadata.CHATGPT_ACCESS_PROOF_SHA256, ""));
        assertEquals(dev.incusspawn.proxy.ChatGptProof.verifier(proofs.refresh()),
                config.pathText(Metadata.CHATGPT_REFRESH_PROOF_SHA256, ""));
        assertEquals(config.pathText(Metadata.INSTANCE_SECRET_SHA256, ""),
                config.pathText(Metadata.CHATGPT_PROOF_SECRET_SHA256, ""),
                "the verifiers are keyed with the secret this start minted");

        // What the delivery exec hands the guest: Codex's auth.json, placeholders only
        var auth = delivery.chatGptAuth();
        assertTrue(auth.contains("\"auth_mode\":\"chatgpt\""), auth);
        assertTrue(auth.contains("rt." + dev.incusspawn.proxy.ChatGptProof.MARKER), auth);
        assertFalse(auth.contains("rt-real"), "the real refresh token stays on the host: " + auth);
        assertEquals(auth, InstanceSecret.guestEnv(delivery, false, List.of())
                .get(InstanceSecret.CHATGPT_DELIVERY_ENV));
    }

    @Test
    void aStartThatServesNoChatGptSessionDeliversNoneAndClearsWhatAnEarlierOneStamped() throws Exception {
        chatGptHost();
        var stale = Map.of(Metadata.accountKey("openai"), "sso",
                Metadata.CHATGPT_SESSION, "sso:old",
                Metadata.CHATGPT_PROOF_SECRET_SHA256, "d".repeat(64));
        var daemon = new FakeIncusDaemon().container(NAME, stale);
        // The session is gone: nothing may bind this instance to one
        new dev.incusspawn.proxy.ChatGptSessionStore().remove("sso");

        var delivery = InstanceLifecycle.rotateInstanceSecret(daemon.client(), NAME);

        var config = daemon.instance(NAME).path("config");
        assertEquals("", config.pathText(Metadata.CHATGPT_SESSION, ""), "an empty value unsets");
        assertEquals("", delivery.chatGptAuth());
        assertEquals("", InstanceSecret.guestEnv(delivery, false, List.of())
                .get(InstanceSecret.CHATGPT_DELIVERY_ENV));
    }

    @Test
    void theGuestScriptsArePosixSh() throws Exception {
        // The delivery script and the profile it writes run in the guest's sh, as root and as the
        // instance user: a syntax error would break every start, so it is parsed here first
        assertEquals(0, syntaxCheck(InstanceSecret.GUEST_SCRIPT), InstanceSecret.GUEST_SCRIPT);
        assertEquals(0, syntaxCheck(dev.incusspawn.proxy.InstanceSecret.CHATGPT_PROFILE_SCRIPT),
                "the profile that writes Codex's auth.json");
    }

    private static int syntaxCheck(String script) throws Exception {
        var process = new ProcessBuilder("sh", "-n", "-c", script).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    @Test
    void aBranchHasItsOwnSecretFromTheCopyOn() {
        // A branch of a branch: the copy would otherwise carry its source's hash
        var sourceHash = InstanceSecret.sha256(InstanceSecret.generate());
        var daemon = new FakeIncusDaemon().container("dev-1",
                Map.of(Metadata.PROFILE, "tpl-dev", Metadata.INSTANCE_SECRET_SHA256, sourceHash));
        var request = new BranchFlow.Request("dev-1", "dev-2", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());
        BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of()));

        var branchHash = recordedHash(daemon, "dev-2");
        assertTrue(branchHash.matches("[0-9a-f]{64}"), branchHash);
        assertNotEquals(sourceHash, branchHash);
        assertEquals(sourceHash, recordedHash(daemon, "dev-1"), "the source keeps its own");
    }

    @Test
    void aBranchIsServedItsChatGPTSessionFromTheCopyOn() throws Exception {
        var session = chatGptHost();
        // A source carrying stamps: the copy must not keep them unless they are its own
        var sourceStamps = new LinkedHashMap<String, String>();
        sourceStamps.put(Metadata.CHATGPT_SESSION, "sso:old");
        sourceStamps.put(Metadata.CHATGPT_PROOF_SECRET_SHA256, "e".repeat(64));
        sourceStamps.put(Metadata.PROFILE, "tpl-dev");
        var daemon = new FakeIncusDaemon().container("dev-1", sourceStamps);
        var request = new BranchFlow.Request("dev-1", "dev-2", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());

        BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of()));

        var config = daemon.instance("dev-2").path("config");
        assertEquals("sso:" + session.sessionId(), config.pathText(Metadata.CHATGPT_SESSION, ""),
                "the branch is served its own session, not its source's");
        assertEquals(config.pathText(Metadata.INSTANCE_SECRET_SHA256, ""),
                config.pathText(Metadata.CHATGPT_PROOF_SECRET_SHA256, ""),
                "keyed with the secret the copy request minted");
        assertEquals(64, config.pathText(Metadata.CHATGPT_ACCESS_PROOF_SHA256, "").length());
        assertEquals(64, config.pathText(Metadata.CHATGPT_REFRESH_PROOF_SHA256, "").length());
        // The source keeps what it had
        assertEquals("sso:old", daemon.instance("dev-1").path("config")
                .pathText(Metadata.CHATGPT_SESSION, ""));
    }

    @Test
    void aBranchServedNoSessionCarriesNoStamps() throws Exception {
        chatGptHost();
        // The session is gone: nothing may bind the branch, whatever its source carried
        new dev.incusspawn.proxy.ChatGptSessionStore().remove("sso");
        var sourceStamps = new LinkedHashMap<String, String>();
        sourceStamps.put(Metadata.CHATGPT_SESSION, "sso:old");
        sourceStamps.put(Metadata.PROFILE, "tpl-dev");
        var daemon = new FakeIncusDaemon().container("dev-1", sourceStamps);
        var request = new BranchFlow.Request("dev-1", "dev-2", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());

        BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of()));

        assertEquals("", daemon.instance("dev-2").path("config").pathText(Metadata.CHATGPT_SESSION, ""),
                "an empty value unsets what the copy would have inherited");
    }

    @Test
    void theBranchSetupScriptDeliversTheSecret() {
        var script = RuntimeSetup.buildSetupScript(null, null, NetworkMode.FULL, List.of(), true);
        assertTrue(script.contains(InstanceSecret.GUEST_SCRIPT), script);
        assertFalse(RuntimeSetup.buildSetupScript(null, null, NetworkMode.FULL, List.of())
                .contains(InstanceSecret.GUEST_PATH), "no secret, no delivery");
    }

    @Test
    void aRestartedVmGetsANewSecretToo() {
        var previous = InstanceSecret.sha256(InstanceSecret.generate());
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Running",
                Map.of(Metadata.INSTANCE_SECRET_SHA256, previous));
        assertThrows(IncusException.class,
                () -> VmAgentRecovery.restartForAgent(daemon.clientWithShortReadyWait(), NAME, RecordingOutput.QUIET));

        var recorded = recordedHash(daemon, NAME);
        assertNotEquals(previous, recorded);
        var delivered = daemon.execs().stream()
                .map(InstanceSecretRotationTest::secretIn).filter(java.util.Objects::nonNull).toList();
        assertFalse(delivered.isEmpty(), "the probe after the restart carries the secret");
        assertEquals(recorded, InstanceSecret.sha256(delivered.getLast()));
    }

    @Test
    void aRestartFromTheTuiRotatesTheSecretToo() {
        // The reboot empties the guest's /run: a restart that kept the hash would leave the box
        // with no secret at all
        var previous = InstanceSecret.sha256(InstanceSecret.generate());
        var daemon = new FakeIncusDaemon().instance(NAME, "container", "Running",
                Map.of(Metadata.INSTANCE_SECRET_SHA256, previous));
        assertThrows(IncusException.class, () -> InstanceLifecycle.restartForUse(
                daemon.clientWithShortReadyWait(), NAME, MachineType.CONTAINER, false, RecordingOutput.QUIET));

        var recorded = recordedHash(daemon, NAME);
        assertNotEquals(previous, recorded);
        assertEquals(List.of(NAME + " restart"), daemon.stateActions());
        assertEquals(recorded, InstanceSecret.sha256(secretIn(daemon.execs().getFirst())));
    }

    /**
     * The exec that delivers a start's secret carries the proof of every declared placeholder
     * (#1106), derived from that same secret: one exec, nothing the start did not already send.
     */
    @Test
    void aStartsProbeCarriesTheProofOfEveryDeclaredPlaceholder() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of()).ipFiltering(NAME, "true");
        assertThrows(IncusException.class, () -> InstanceLifecycle.ensureReady(
                daemon.clientWithShortReadyWait(), NAME, daemon.instance(NAME), MachineType.CONTAINER, RecordingOutput.QUIET));

        var declared = ProofToken.declared();
        assertTrue(declared.stream().map(ProofToken.Placeholder::namespace).collect(java.util.stream.Collectors.toSet())
                .containsAll(List.of("github", "claude", "openai", "bob")), declared.toString());
        var probes = daemon.execs();
        assertFalse(probes.isEmpty());
        for (var probe : probes) {
            var secret = secretIn(probe);
            assertProofsFor(probe.environment().get(dev.incusspawn.proxy.CredentialProof.DELIVERY_ENV), declared, probe.environment().get(InstanceSecret.PROOFS_DELIVERY_ENV));
            assertEquals(dev.incusspawn.proxy.ProofVerifier.stamp(probe.environment().get(dev.incusspawn.proxy.CredentialProof.DELIVERY_ENV), declared),
                    daemon.instance(NAME).path("config").path(Metadata.INSTANCE_PROOF_SHA256).asText());
            assertFalse(String.join(" ", probe.command()).contains(ProofToken.derive(secret, "github")),
                    "a proof opens a credential: it travels in the environment, not on a command line");
        }
    }

    @Test
    void aBranchsFirstStartCarriesItsOwnProofs() {
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of(Metadata.PROFILE, "tpl-dev"));
        var incus = org.mockito.Mockito.spy(daemon.client());
        var env = org.mockito.ArgumentCaptor.<Map<String, String>>captor();
        org.mockito.Mockito.doReturn("").when(incus).pollUntilReadyOutput(org.mockito.ArgumentMatchers.eq("dev-2"),
                org.mockito.ArgumentMatchers.anyInt(), env.capture(), org.mockito.ArgumentMatchers.any(String[].class));
        var request = new BranchFlow.Request("dev-1", "dev-2", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), true, Map.of());
        try {
            BranchFlow.create(incus, BranchFlow.preflight(incus, request, Map.of()));
        } catch (RuntimeException afterTheSetupScript) {
            // What follows the setup exec is not what this test is about
        }

        var delivered = env.getAllValues().stream().filter(e -> e.containsKey(InstanceSecret.DELIVERY_ENV)).toList();
        assertFalse(delivered.isEmpty(), "the branch's setup script carries its secret");
        var secret = delivered.getFirst().get(InstanceSecret.DELIVERY_ENV);
        assertEquals(recordedHash(daemon, "dev-2"), InstanceSecret.sha256(secret));
        assertEquals(dev.incusspawn.proxy.ProofVerifier.stamp(delivered.getFirst().get(dev.incusspawn.proxy.CredentialProof.DELIVERY_ENV), ProofToken.declared()),
                daemon.instance("dev-2").path("config").path(Metadata.INSTANCE_PROOF_SHA256).asText());
        assertProofsFor(delivered.getFirst().get(dev.incusspawn.proxy.CredentialProof.DELIVERY_ENV), ProofToken.declared(), delivered.getFirst().get(InstanceSecret.PROOFS_DELIVERY_ENV));
    }

    private static void assertProofsFor(String secret, List<ProofToken.Placeholder> declared, String exports) {
        assertNotNull(exports, "the probe that delivers the secret delivers its proofs");
        for (var placeholder : declared) {
            var export = "export " + placeholder.env() + "='"
                    + placeholder.prefix() + ProofToken.MARKER + ProofToken.derive(secret, placeholder.namespace()) + "'";
            assertTrue(exports.lines().anyMatch(l -> l.startsWith("case \"${" + placeholder.env() + "-}\" in ")
                    && l.contains(export)), () -> "no proof for " + placeholder + " in:\n" + exports);
        }
        assertEquals(declared.size(), exports.lines().count(), exports);
        assertFalse(exports.contains(secret), "never the secret itself");
    }
}
