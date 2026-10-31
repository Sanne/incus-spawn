package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BuildAccounts;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.util.RecordingOutput;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// The address a template is pinned to is claimed under a lock in the home directory
@ExtendWith(IsolatedHome.class)
class UpdateAllCommandTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String NAME = "tpl-test";
    /** Where the update runs: a copy that replaces the template once it succeeded (#1760). */
    private static final String COPY = NAME + "-rebuilding";

    private final List<String> guestScripts = new ArrayList<>();
    /** Each command run as root, joined with spaces, in order. */
    private final List<String> rootCommands = new ArrayList<>();

    private FakeIncusDaemon daemon;

    /** A stopped base template whose every step succeeds; each test fails one. Records what runs as agentuser. */
    private IncusClient incus() {
        return incus("container");
    }

    private IncusClient incus(String type) {
        daemon = new FakeIncusDaemon().instance(NAME, type, "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_BASE));
        var incus = spy(daemon.client());
        doNothing().when(incus).waitForReady(anyString(), any(), any(), anyMap());
        doAnswer(inv -> {
            var args = inv.getArguments();
            // Matchers pass null while a stub is set up
            if (COPY.equals(args[0])) rootCommands.add(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length, String[].class)));
            return OK;
        }).when(incus).shellExec(anyString(), any(String[].class));
        doReturn(new IncusClient.ExecResult(1, "", "")).when(incus).shellExec(COPY, "which", "npm");
        doAnswer(inv -> {
            // Not the cleanup of the update's proof caches, which every update ends with
            if (!((String) inv.getArgument(2)).startsWith("export ANTHROPIC_API_KEY=")) guestScripts.add(inv.getArgument(2));
            return OK;
        }).when(incus).execInContainer(eq(COPY), eq("agentuser"), anyString());
        rootCommands.clear();
        return incus;
    }

    /** A base template as read, in {@code status}. */
    private static JqObject template(String status) {
        return JqObject.builder(3).put("status", status).put("type", "container")
                .put("config", JqObject.builder(1).put(Metadata.TYPE, Metadata.TYPE_BASE).build()).build();
    }

    private static int update(IncusClient incus) {
        var resolved = new LinkedHashMap<String, ImageDef>();
        resolved.put(NAME, null);
        return new UpdateAllCommand().updateTemplates(incus, resolved);
    }

    /**
     * A template starts as an instance does (#1457): on a host that cannot enforce IP spoofing
     * protection the start drops it and goes on, instead of failing the update.
     */
    @Test
    void aTemplateStartsOnAHostThatCannotEnforceIpFiltering() {
        var incus = incus();
        doThrow(new IncusException("Failed to start device \"eth0\": Failed to run: ebtables -t filter -A INPUT"
                + " -i veth0 -j DROP: exit status 255 (The kernel doesn't support the ebtables 'filter' table.)"))
                .doCallRealMethod().when(incus).start(COPY);

        assertEquals(0, update(incus));

        verify(incus).deviceConfigSet(COPY, "eth0", "security.ipv4_filtering", "false");
        verify(incus, times(2)).start(COPY);
    }

    /**
     * A template runs its prime commands as a build does, so it is pinned to an address with IP
     * spoofing protection as a build is (#1743): unfiltered, code in it could take a stopped
     * branch's address and be served that branch's credentials. Once stopped it gives it back.
     */
    @Test
    void aTemplateRunsItsPrimeCommandsOnAPinnedFilteredAddress() throws Exception {
        var incus = incus();
        var nicDuringPrime = new ArrayList<JqValue>();
        var staticIpDuringPrime = new ArrayList<String>();
        doAnswer(inv -> {
            var instance = daemon.instance(COPY);
            nicDuringPrime.add(instance.path("expanded_devices").path("eth0"));
            staticIpDuringPrime.add(instance.path("config").pathText(Metadata.STATIC_IP, ""));
            return OK;
        }).when(incus).execInContainer(eq(COPY), eq("agentuser"), contains("./prime.sh"));
        var resolved = new LinkedHashMap<String, ImageDef>();
        resolved.put(NAME, ImageDef.parseYaml("name: " + NAME + "\nrepos:\n  - url: https://github.com/a/b.git\n"
                + "    path: ~/b\n    prime: ./prime.sh\n"));
        var command = new UpdateAllCommand();
        command.prime = true;

        assertEquals(0, command.updateTemplates(incus, resolved));

        assertEquals(1, nicDuringPrime.size(), "the prime command ran");
        var nic = nicDuringPrime.getFirst();
        assertEquals("true", nic.pathText("security.ipv4_filtering", ""),
                "without filtering the template can take any address on the bridge");
        assertFalse(staticIpDuringPrime.getFirst().isEmpty(), "the template is known to the proxy by its own address");
        assertEquals(staticIpDuringPrime.getFirst(), nic.pathText("ipv4.address", ""));
        var after = daemon.instance(NAME);
        assertEquals("Stopped", after.pathText("status", ""));
        assertFalse(after.path("config").has(Metadata.STATIC_IP), "the stopped template gives its address back");
        assertEquals(0, after.path("devices").size(), "the NIC is the profile's again");
    }

    private static final String PIN = Metadata.accountKey("github");

    /** What {@link BuildAccounts#revoke} leaves: no secret or proof the proxy would accept. */
    private void assertRevoked(String name) {
        var config = daemon.instance(name).path("config");
        for (var key : List.of(Metadata.INSTANCE_SECRET_SHA256, Metadata.INSTANCE_PROOF_SHA256, Metadata.INSTANCE_PROOF_ID)) {
            assertEquals("", config.pathText(key, ""), key + " outlived the update");
        }
    }

    /** The cleanup of the update's proof caches from the rootfs, as a build runs before it publishes. */
    private static boolean clearsProofCaches(String script) {
        return script.startsWith("export ANTHROPIC_API_KEY=");
    }

    /**
     * A template's prime commands are served its own accounts, as a build's are (#1743): fresh
     * proofs and a secret with its pinned address, delivered as the guest answers, never a grant it
     * was left with. Once the update ends they are revoked, the guest's caches of them cleared, and
     * the address given back.
     */
    @Test
    void aTemplateIsGrantedItsOwnAccountsWhileItRunsAndRevokedAfter() {
        var incus = incus();
        daemon.instance(NAME, "container", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, PIN, "bot",
                Metadata.INSTANCE_SECRET_SHA256, "stale", Metadata.MCP_CALLER, "true"));
        var configDuringUpdate = new ArrayList<JqValue>();
        var servedDuringUpdate = new ArrayList<dev.incusspawn.proxy.InstanceRegistry.InstanceAccounts>();
        doAnswer(inv -> {
            var config = daemon.instance(COPY).path("config");
            configDuringUpdate.add(config);
            var registry = new dev.incusspawn.proxy.InstanceRegistry(incus);
            registry.refresh();
            servedDuringUpdate.add(registry.lookup(config.pathText(Metadata.STATIC_IP, "")));
            return OK;
        }).when(incus).shellExec(COPY, "dnf", "update", "-y");
        var delivered = new ArrayList<Map<String, String>>();
        doAnswer(inv -> {
            delivered.add(inv.getArgument(3));
            return null;
        }).when(incus).waitForReady(eq(COPY), any(), eq(InstanceSecret.GUEST_SCRIPT), anyMap());
        var cleared = new ArrayList<String>();
        doAnswer(inv -> {
            if (clearsProofCaches(inv.getArgument(2))) cleared.add(inv.getArgument(2));
            return OK;
        }).when(incus).execInContainer(eq(COPY), eq("agentuser"), anyString());

        assertEquals(0, update(incus));

        var config = configDuringUpdate.getFirst();
        var secretHash = config.pathText(Metadata.INSTANCE_SECRET_SHA256, "");
        assertFalse(secretHash.isEmpty() || secretHash.equals("stale"), "a fresh secret, not the one it was left with");
        assertFalse(config.pathText(Metadata.INSTANCE_PROOF_SHA256, "").isEmpty(), "proofs for its accounts");
        assertFalse(config.has(Metadata.MCP_CALLER), "no MCP grant goes with them");
        assertEquals(1, delivered.size(), "delivered as the guest answers");
        assertEquals(secretHash, InstanceSecret.sha256(delivered.getFirst().get(InstanceSecret.DELIVERY_ENV)));
        assertEquals(Map.of("github", "bot"), servedDuringUpdate.getFirst().accountsByNamespace(),
                "the proxy serves the template its own pins");

        assertRevoked(NAME);
        assertEquals(1, cleared.size(), "the guest's caches of them are cleared before it stops");
        var after = daemon.instance(NAME).path("config");
        assertEquals("bot", after.pathText(PIN, ""), "the template's own pins stay");
        assertFalse(after.has(Metadata.STATIC_IP));
    }

    private static final String MARK = "user.test.state";

    /** The template as built, marked so that its replacement by the update's copy shows. */
    private IncusClient markedTemplate() {
        var incus = incus();
        daemon.instance(NAME, "container", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, MARK, "built",
                Metadata.DISK_REFERENCED, "123"));
        // What the update changes in the guest, seen from Incus
        doAnswer(inv -> {
            daemon.instance(COPY, "container", "Running", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, MARK, "updated",
                    Metadata.UNTRUSTED_LINEAGE, daemon.instance(COPY).path("config").pathText(Metadata.UNTRUSTED_LINEAGE, "")));
            return OK;
        }).when(incus).shellExec(COPY, "dnf", "update", "-y");
        return incus;
    }

    /**
     * An update runs in a copy, never in the template (#1760): while it runs the template is the
     * stopped, consistent one a branch may copy, and the copy is untrusted as a build is.
     */
    @Test
    void theTemplateIsLeftAsItIsWhileItsUpdateRuns() {
        var incus = markedTemplate();
        var duringUpdate = new ArrayList<JqValue>();
        var copyDuringUpdate = new ArrayList<JqValue>();
        doAnswer(inv -> {
            duringUpdate.add(daemon.instance(NAME));
            copyDuringUpdate.add(daemon.instance(COPY).path("config"));
            return OK;
        }).when(incus).execInContainer(COPY, "agentuser", GuestUpdate.GIT_FETCH_SCRIPT);

        assertEquals(0, update(incus));

        var template = duringUpdate.getFirst();
        assertEquals("Stopped", template.pathText("status", ""));
        assertEquals("built", template.path("config").pathText(MARK, ""));
        assertFalse(template.path("config").has(Metadata.INSTANCE_SECRET_SHA256), "the template is granted nothing");
        assertEquals(Metadata.UNTRUSTED_BUILDING, copyDuringUpdate.getFirst().pathText(Metadata.UNTRUSTED_LINEAGE, ""));
        verify(incus, never()).start(NAME);
    }

    /** Once every step succeeded the copy replaces the template, trusted again and not claiming the old size. */
    @Test
    void anUpdateThatSucceedsReplacesTheTemplate() {
        var incus = markedTemplate();

        assertEquals(0, update(incus));

        var config = daemon.instance(NAME).path("config");
        assertEquals("updated", config.pathText(MARK, ""));
        assertFalse(config.has(Metadata.UNTRUSTED_LINEAGE), "host routes trust it and its branches again");
        assertFalse(config.has(Metadata.DISK_REFERENCED), "the old template's size is not the updated one's");
        assertEquals("Stopped", daemon.instance(NAME).pathText("status", ""));
        assertFalse(incus.exists(COPY));
    }

    /** One step that fails discards the whole update: the template stays as it was built. */
    @Test
    void anUpdateWithAFailedStepLeavesTheTemplateAsItWas() {
        var incus = markedTemplate();
        doReturn(new IncusClient.ExecResult(1, "", "git fetch failed in /home/agentuser/repo/"))
                .when(incus).execInContainer(COPY, "agentuser", GuestUpdate.GIT_FETCH_SCRIPT);

        assertEquals(1, update(incus));

        assertEquals("built", daemon.instance(NAME).path("config").pathText(MARK, ""),
                "the system update that succeeded is not kept without the rest");
        assertFalse(incus.exists(COPY), "the failed update is discarded");
    }

    /** An update that throws is discarded too, and the template stays. */
    @Test
    void anUpdateThatThrowsLeavesTheTemplateAsItWas() {
        var incus = markedTemplate();
        doThrow(new IncusException("exec failed")).when(incus).execInContainer(COPY, "agentuser", GuestUpdate.GIT_FETCH_SCRIPT);

        assertThrows(IncusException.class, () -> update(incus));

        assertEquals("built", daemon.instance(NAME).path("config").pathText(MARK, ""));
        assertFalse(incus.exists(COPY));
    }

    /** A template rebuilt by another isx while the update ran is kept: the update was of the one it replaced. */
    @Test
    void anUpdateDoesNotReplaceATemplateRebuiltWhileItRan() {
        var incus = markedTemplate();
        doAnswer(inv -> {
            daemon.instance(NAME, "container", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, MARK, "rebuilt"));
            doReturn(JqObject.builder(2).put("created_at", "2026-10-31T12:00:00Z").put("status", "Stopped").build())
                    .when(incus).instanceMetadataOrThrow(NAME);
            return OK;
        }).when(incus).execInContainer(COPY, "agentuser", GuestUpdate.GIT_FETCH_SCRIPT);

        var said = RecordingOutput.during(() -> assertEquals(1, update(incus)));

        assertEquals("rebuilt", daemon.instance(NAME).path("config").pathText(MARK, ""));
        assertFalse(incus.exists(COPY));
        assertTrue(failedSteps(said).contains("was rebuilt, updated or removed by another isx"), failedSteps(said));
    }

    /**
     * Another isx building or updating the template works under the copy's name, and each deletes
     * what it finds there first: this update is refused, and touches neither (#1760).
     */
    @Test
    void anUpdateIsRefusedWhileAnotherIsxBuildsTheTemplate() throws Exception {
        var incus = markedTemplate();
        daemon.instance(COPY, "container", "Running", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, MARK, "building"));
        var held = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var other = new Thread(() -> {
            try (var hold = dev.incusspawn.lifecycle.TemplateLock.building(NAME).orElseThrow()) {
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        other.start();
        held.await();
        try {
            var said = RecordingOutput.during(() -> assertEquals(1, update(incus)));

            assertEquals("building", daemon.instance(COPY).path("config").pathText(MARK, ""));
            assertEquals("built", daemon.instance(NAME).path("config").pathText(MARK, ""));
            assertTrue(failedSteps(said).contains("Another isx is building or updating " + NAME), failedSteps(said));
            assertFalse(warnings(said).contains("re-run"), "a re-run is refused the same way while it lasts: " + warnings(said));
        } finally {
            release.countDown();
            other.join();
        }
    }

    /** A copy an interrupted update left running, which nothing works in any more, is deleted as a build deletes one. */
    @Test
    void aCopyAnInterruptedUpdateLeftRunningIsReplaced() {
        var incus = markedTemplate();
        daemon.instance(COPY, "container", "Running", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, MARK, "left behind"));

        assertEquals(0, update(incus));

        assertEquals("updated", daemon.instance(NAME).path("config").pathText(MARK, ""));
    }

    /**
     * A copy request that fails, because another isx made the copy's name in between, deletes
     * nothing: the instance under that name is the other one's work.
     */
    @Test
    void aCopyRequestThatFailsLeavesAnotherIsxsCopyAlone() {
        var incus = markedTemplate();
        doAnswer(inv -> {
            daemon.instance(COPY, "container", "Running", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, MARK, "theirs"));
            throw new IncusException("Failed to copy " + NAME + " to " + COPY);
        }).when(incus).copy(eq(NAME), eq(COPY), any(IncusClient.CopyPlan.class), anyMap());

        assertThrows(IncusException.class, () -> update(incus));

        assertEquals("theirs", daemon.instance(COPY).path("config").pathText(MARK, ""));
        assertEquals("built", daemon.instance(NAME).path("config").pathText(MARK, ""));
    }

    /**
     * A copy another isx deleted before the swap, as its leftover check deletes a stopped one,
     * is not swapped in: the template is never deleted for a rename that cannot follow.
     */
    @Test
    void aCopyGoneBeforeTheSwapLeavesTheTemplate() {
        var incus = markedTemplate();
        doAnswer(inv -> {
            inv.callRealMethod();
            incus.rename(COPY, NEXT); // gone from its name, as a delete would leave it
            return null;
        }).when(incus).stop(COPY);

        var said = RecordingOutput.during(() -> assertEquals(1, update(incus)));

        assertEquals("built", daemon.instance(NAME).path("config").pathText(MARK, ""));
        assertTrue(failedSteps(said).contains("or its update's copy was rebuilt, updated or removed"), failedSteps(said));
    }

    /** Another isx's copy made in its place is never swapped in, nor deleted by this update. */
    @Test
    void aCopyReplacedBeforeTheSwapIsNeitherSwappedInNorDeleted() {
        var incus = markedTemplate();
        doAnswer(inv -> {
            inv.callRealMethod();
            daemon.instance(COPY, "container", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, MARK, "theirs"));
            doReturn(JqObject.builder(2).put("created_at", "2026-10-31T12:00:00Z").put("status", "Stopped").build())
                    .when(incus).instanceMetadataOrThrow(COPY);
            return null;
        }).when(incus).stop(COPY);

        assertEquals(1, update(incus));

        assertEquals("built", daemon.instance(NAME).path("config").pathText(MARK, ""));
        assertEquals("theirs", daemon.instance(COPY).path("config").pathText(MARK, ""));
    }

    /** A copy that cannot be stopped cannot replace the template; the next update deletes it. */
    @Test
    void aCopyThatCannotBeStoppedIsDiscarded() {
        var incus = markedTemplate();
        doThrow(new IncusException("Failed to stop")).when(incus).stop(COPY);
        doThrow(new IncusException("Failed to force-stop")).when(incus).forceStop(COPY);

        var said = RecordingOutput.during(() -> assertEquals(1, update(incus)));

        assertEquals("built", daemon.instance(NAME).path("config").pathText(MARK, ""));
        assertTrue(warnings(said).contains("Some stops failed"), warnings(said));
    }

    /** A step that throws still revokes the grant and clears the guest's caches of it. */
    @Test
    void aFailedUpdateRevokesItsGrant() {
        var incus = incus();
        doThrow(new IncusException("exec failed")).when(incus).shellExec(COPY, "dnf", "update", "-y");
        var cleared = new ArrayList<String>();
        doAnswer(inv -> {
            if (clearsProofCaches(inv.getArgument(2))) cleared.add(inv.getArgument(2));
            return OK;
        }).when(incus).execInContainer(eq(COPY), eq("agentuser"), anyString());

        assertThrows(IncusException.class, () -> update(incus));

        assertRevoked(NAME);
        assertEquals(1, cleared.size());
        assertEquals("Stopped", daemon.instance(NAME).pathText("status", ""));
    }

    /** A guest that never answered never received the proofs: they are revoked, with no exec into it. */
    @Test
    void aGuestThatNeverAnsweredIsRevokedWithoutAnExec() {
        var incus = incus();
        doThrow(new IncusException("Timed out waiting for tpl-test")).when(incus).waitForReady(eq(COPY), any(), any(), anyMap());

        assertThrows(IncusException.class, () -> update(incus));

        assertRevoked(NAME);
        verify(incus, never()).execInContainer(eq(COPY), eq("agentuser"), anyString());
    }

    /** With no template to update, why the command failed is an error, never a raw print the TUI would lose (#1703). */
    @Test
    void noTemplatesIsAnError() {
        var incus = new FakeIncusDaemon().client();

        var said = RecordingOutput.during(() -> assertEquals(1, new UpdateAllCommand().updateAll(incus)));

        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR,
                "No templates found. Run 'isx build' first.")), said.events());
    }

    /** What an interrupted build or update left is not a template to update: the update of its template replaces it. */
    @Test
    void aLeftoverCopyIsNotUpdatedAsATemplate() {
        var incus = incus();
        daemon.instance(COPY, "container", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, MARK, "left behind"));

        assertEquals(0, new UpdateAllCommand().updateAll(incus));

        verify(incus, never()).copy(eq(COPY), anyString(), any(IncusClient.CopyPlan.class), anyMap());
        assertFalse(incus.exists(COPY));
    }

    /** A revocation Incus refuses fails the update, saying so, and the template is still stopped. */
    @Test
    void aRevocationThatFailsFailsTheUpdate() {
        var incus = incus();
        doThrow(new IncusException("Failed to set config on " + NAME)).when(incus).configSetAll(eq(COPY), anyMap());

        var said = RecordingOutput.during(() -> assertEquals(1, update(incus)));

        assertTrue(warnings(said).contains("Could not revoke " + COPY + "'s update credentials"), warnings(said));
        assertEquals("Stopped", daemon.instance(NAME).pathText("status", ""));
    }

    /** A template that would not start gives back the address it was pinned to. */
    @Test
    void aTemplateThatFailsToStartGivesItsAddressBack() {
        var incus = incus();
        doThrow(new IncusException("Failed to start: disk full")).when(incus).start(COPY);

        assertThrows(IncusException.class, () -> update(incus));

        assertFalse(daemon.instance(NAME).path("config").has(Metadata.STATIC_IP));
        assertEquals(0, daemon.instance(NAME).path("devices").size());
    }

    /** Pinning changes the NIC, which must not happen under a running guest: such a template is refused, and left as it is. */
    @Test
    void aRunningTemplateIsRefusedWithoutTouchingIt() {
        var incus = incus();
        daemon.instance(NAME, "container", "Running", Map.of(Metadata.TYPE, Metadata.TYPE_BASE));

        var said = RecordingOutput.during(() -> assertEquals(1, update(incus)));

        assertTrue(failedSteps(said).contains(NAME + " must be stopped"), failedSteps(said));
        assertFalse(daemon.instance(NAME).path("config").has(Metadata.INSTANCE_SECRET_SHA256), "nothing is granted");
        assertEquals("Running", daemon.instance(NAME).pathText("status", ""));
        assertEquals(0, daemon.instance(NAME).path("devices").size());
        verify(incus, never()).stop(COPY);
    }

    private static final String NEXT = "tpl-next";
    private static final String NEXT_COPY = NEXT + "-rebuilding";

    /** {@link #NAME}, which each test sets up to fail, then {@link #NEXT}, a template that updates. */
    private int updateBothWith(IncusClient incus) {
        daemon.instance(NEXT, "container", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_BASE));
        doReturn(new IncusClient.ExecResult(1, "", "")).when(incus).shellExec(NEXT_COPY, "which", "npm");
        doReturn(OK).when(incus).execInContainer(eq(NEXT_COPY), eq("agentuser"), anyString());
        var resolved = new LinkedHashMap<String, ImageDef>();
        resolved.put(NAME, null);
        resolved.put(NEXT, null);
        return new UpdateAllCommand().updateTemplates(incus, resolved);
    }

    /** One template that cannot be updated is that template's failure: the ones after it still update. */
    @Test
    void aRefusedTemplateDoesNotStopTheNextOnesUpdate() {
        var incus = incus();
        daemon.instance(NAME, "container", "Running", Map.of(Metadata.TYPE, Metadata.TYPE_BASE));

        var said = RecordingOutput.during(() -> assertEquals(1, updateBothWith(incus)));

        verify(incus).shellExec(NEXT_COPY, "dnf", "update", "-y");
        assertRevoked(NEXT);
        assertFalse(warnings(said).contains("re-run"), "a re-run is refused the same way: " + warnings(said));
    }

    /** A guest that did not take its pinned address has no network at all: its update fails, the next one runs. */
    @Test
    void aTemplateThatDidNotTakeItsAddressDoesNotStopTheNextOnesUpdate() {
        var incus = incus();
        doThrow(new IncusException("Template " + NAME + " was given 10.166.11.2 but its DHCP client got 10.166.11.87"))
                .when(incus).ipv4Addresses(COPY);

        var said = RecordingOutput.during(() -> assertEquals(1, updateBothWith(incus)));

        verify(incus, never()).shellExec(COPY, "dnf", "update", "-y");
        verify(incus).shellExec(NEXT_COPY, "dnf", "update", "-y");
        assertRevoked(NAME);
        assertRevoked(NEXT);
        assertTrue(failedSteps(said).contains("its DHCP client got"), failedSteps(said));
        assertTrue(warnings(said).contains("Some address checks failed"), warnings(said));
        assertEquals("Stopped", daemon.instance(NAME).pathText("status", ""));
        assertFalse(daemon.instance(NAME).path("config").has(Metadata.STATIC_IP), "the address is given back");
    }

    private static String warnings(RecordingOutput said) {
        return String.join("\n", said.events(RecordingOutput.Kind.WARN).stream()
                .map(RecordingOutput.Event::msg).toList());
    }

    /** A template the update leaves running fails the next update-all's start. */
    @Test
    void aTemplateIsStoppedWhenItsUpdateThrows() {
        var incus = incus();
        doThrow(new IncusException("Timed out waiting for tpl-test")).when(incus).waitForReady(eq(COPY), any(), any(), anyMap());

        assertThrows(IncusException.class, () -> update(incus));

        verify(incus).stop(COPY);
    }

    @Test
    void aTemplateIsStoppedWhenAGuestStepThrows() {
        var incus = incus();
        doThrow(new IncusException("exec failed")).when(incus).shellExec(COPY, "dnf", "update", "-y");

        assertThrows(IncusException.class, () -> update(incus));

        verify(incus).stop(COPY);
    }

    /** The guest that never answered may ignore the shutdown too: it is forced, and why the update failed stays the error. */
    @Test
    void aTemplateThatIgnoresTheStopIsForcedWithoutHidingWhyTheUpdateFailed() {
        var incus = incus();
        doThrow(new IncusException("Timed out waiting for tpl-test")).when(incus).waitForReady(eq(COPY), any(), any(), anyMap());
        doThrow(new IncusException("Failed to stop tpl-test")).when(incus).stop(COPY);

        var e = assertThrows(IncusException.class, () -> update(incus));

        assertEquals("Timed out waiting for tpl-test", e.getMessage());
        verify(incus).forceStop(COPY);
    }

    /** Incus refuses to stop a stopped instance: one that died is stopped, not one that cannot be. */
    @Test
    void aTemplateThatDiedIsNotForceStopped() {
        var incus = incus();
        doReturn(template("Stopped")).when(incus).instanceMetadataOrThrow(COPY);
        doThrow(new IncusException("Timed out waiting for tpl-test")).when(incus).waitForReady(eq(COPY), any(), any(), anyMap());
        doThrow(new IncusException("Failed to stop tpl-test")).when(incus).stop(COPY);

        var e = assertThrows(IncusException.class, () -> update(incus));

        assertEquals("Timed out waiting for tpl-test", e.getMessage());
        verify(incus, never()).forceStop(any());
    }

    @Test
    void aTemplateAlreadyStoppedAtTheEndDoesNotFailTheCommand() {
        var incus = incus();
        doReturn(template("Stopped")).when(incus).instanceMetadataOrThrow(COPY);
        doThrow(new IncusException("Failed to stop tpl-test")).when(incus).stop(COPY);

        assertEquals(0, update(incus));
        verify(incus, never()).forceStop(any());
    }

    /** Not knowing the status is no reason to leave the template running. */
    @Test
    void aTemplateIsForceStoppedWhenItsStatusCannotBeRead() {
        var incus = incus();
        // Read once as the copy is made, then by the stop, then by the swap
        doCallRealMethod().doThrow(new IncusException("Failed to read instance 'tpl-test' (HTTP 500)")).doCallRealMethod()
                .when(incus).instanceMetadataOrThrow(COPY);
        doThrow(new IncusException("Failed to stop tpl-test")).when(incus).stop(COPY);

        assertEquals(0, update(incus));
        verify(incus).forceStop(COPY);
    }

    /** A copy gone by the end is not forced to stop, and not swapped in: the template stays. */
    @Test
    void aCopyGoneByTheEndIsNotForceStoppedNorSwappedIn() {
        var incus = incus();
        doCallRealMethod().doReturn(null).when(incus).instanceMetadataOrThrow(COPY);
        doThrow(new IncusException("Failed to stop tpl-test")).when(incus).stop(COPY);

        assertEquals(1, update(incus));
        verify(incus, never()).forceStop(any());
        verify(incus, never()).deleteIfExists(NAME);
    }

    @Test
    void aTemplateThatCannotBeStoppedFailsTheCommand() {
        var incus = incus();
        doThrow(new IncusException("Failed to stop tpl-test")).when(incus).stop(COPY);
        doThrow(new IncusException("Failed to force-stop tpl-test")).when(incus).forceStop(COPY);

        assertEquals(1, update(incus));
    }

    @Test
    void anUpdateEmptiesTmpBeforeTheTemplateIsStopped() {
        var incus = incus();
        assertEquals(0, update(incus));
        var order = inOrder(incus);
        order.verify(incus).execInContainer(COPY, "agentuser", GuestUpdate.GIT_FETCH_SCRIPT);
        order.verify(incus).shellExec(COPY, "sh", "-c", GuestUpdate.EMPTY_TMP_SCRIPT);
        order.verify(incus).stop(COPY);
    }

    @Test
    void aTmpThatCannotBeEmptiedDoesNotFailTheUpdate() {
        var incus = incus();
        doThrow(new IncusException("exec failed")).when(incus).shellExec(COPY, "sh", "-c", GuestUpdate.EMPTY_TMP_SCRIPT);
        assertEquals(0, update(incus));
        verify(incus).stop(COPY);
    }

    @Test
    void succeedsWhenEveryStepSucceeds() {
        assertEquals(0, update(incus()));
    }

    @Test
    void aFailedSystemUpdateFailsTheCommand() {
        var incus = incus();
        doReturn(new IncusClient.ExecResult(1, "", "Failed to download metadata"))
                .when(incus).shellExec(COPY, "dnf", "update", "-y");

        assertEquals(1, update(incus));
    }

    /**
     * A template boots on DHCP, and exec answers before the lease is in place: dnf run then
     * cannot resolve its mirrors (#1734). The network is waited for first, in one exec.
     */
    @Test
    void theSystemUpdateWaitsForTheNetwork() {
        assertEquals(0, update(incus()));

        assertTrue(rootCommands.size() > 1, rootCommands.toString());
        assertTrue(rootCommands.getFirst().contains("mirrors.fedoraproject.org"),
                "the first exec must wait for the network: " + rootCommands);
        assertEquals("dnf update -y", rootCommands.get(1));
    }

    /** A VM's NIC is not eth0 (#997), and its guest may not run systemd-networkd. */
    @Test
    void aVmTemplateWaitsForItsNetworkWithoutNamingANic() {
        var incus = incus("virtual-machine");

        assertEquals(0, update(incus));

        var wait = rootCommands.getFirst();
        assertTrue(wait.contains("mirrors.fedoraproject.org"), rootCommands.toString());
        assertTrue(wait.contains("ip -4 route show default"), wait);
        assertFalse(wait.contains("eth0"), wait);
        assertFalse(wait.contains("systemd-networkd"), wait);
    }

    /** No step that needs the network runs without one; the update fails, saying what it waited for. */
    @Test
    void aNetworkThatNeverComesUpFailsTheUpdateWithoutRunningDnf() {
        var incus = incus();
        doReturn(new IncusClient.ExecResult(4, "", ""))
                .when(incus).shellExec(eq(COPY), eq("sh"), eq("-c"), contains("mirrors.fedoraproject.org"));

        var said = RecordingOutput.during(() -> assertEquals(1, update(incus)));

        verify(incus, never()).shellExec(COPY, "dnf", "update", "-y");
        assertTrue(guestScripts.isEmpty(), "nothing but the proof cleanup ran as agentuser: " + guestScripts);
        verify(incus).stop(COPY);
        assertTrue(failedSteps(said).contains("cannot reach https://mirrors.fedoraproject.org"), failedSteps(said));
    }

    /** No address at all: the guest's own network state is what explains it. */
    @Test
    void aTemplateWithoutAnAddressFailsWithTheGuestsNetworkState() {
        var incus = incus();
        doReturn(new IncusClient.ExecResult(3, "", ""))
                .when(incus).shellExec(eq(COPY), eq("sh"), eq("-c"), contains("mirrors.fedoraproject.org"));
        doReturn(new IncusClient.ExecResult(0, "eth0: no carrier", ""))
                .when(incus).shellExec(eq(COPY), eq("sh"), eq("-c"), contains("networkctl"));

        var said = RecordingOutput.during(() -> assertEquals(1, update(incus)));

        verify(incus, never()).shellExec(COPY, "dnf", "update", "-y");
        var failed = failedSteps(said);
        assertTrue(failed.contains(COPY + " did not acquire an IPv4 address"), failed);
        assertTrue(failed.contains("eth0: no carrier"), failed);
    }

    /** {@code --prime} runs a repo's prime commands in turn and names the first that fails (#1745). */
    @Test
    void primeRunsEachCommandInTurnAndStopsAtTheFirstFailure() throws Exception {
        var incus = incus();
        when(incus.execInContainer(COPY, "agentuser", "cd '/home/agentuser/app' && ./gradlew dependencies"))
                .thenAnswer(inv -> {
                    guestScripts.add(inv.getArgument(2));
                    return new IncusClient.ExecResult(1, "", "");
                });
        guestScripts.clear(); // stubbing ran the default answer
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/app.git");
        repo.setPath("~/app");
        repo.setPrime(List.of("mise install", "./gradlew dependencies", "echo never"));
        var def = new ImageDef();
        def.setRepos(List.of(repo));
        var command = new UpdateAllCommand();
        var prime = UpdateAllCommand.class.getDeclaredField("prime");
        prime.setAccessible(true);
        prime.setBoolean(command, true);
        var resolved = new LinkedHashMap<String, ImageDef>();
        resolved.put(NAME, def);

        var said = RecordingOutput.during(() -> assertEquals(1, command.updateTemplates(incus, resolved)));

        assertEquals(List.of("cd '/home/agentuser/app' && mise install", "cd '/home/agentuser/app' && ./gradlew dependencies"),
                guestScripts.subList(1, 3), "after the git fetch script");
        assertTrue(guestScripts.get(3).startsWith("/usr/local/bin/isx-trust "), "then the bind: " + guestScripts);
        assertEquals(4, guestScripts.size(), guestScripts.toString());
        assertTrue(failedSteps(said).contains("Prime command failed for ~/app: ./gradlew dependencies"), failedSteps(said));
    }

    private static String failedSteps(RecordingOutput said) {
        return String.join("\n", said.events(RecordingOutput.Kind.STEP_FAIL).stream()
                .map(RecordingOutput.Event::msg).toList());
    }

    /** #1746: `isx update-all --prime` binds a JDK the prime downloaded and primes again, as a build does. */
    @Test
    void aPrimeThatDownloadsItsOwnJdkRunsAgainOnUpdateOnceThatJdkTrustsTheIsxCa() {
        var incus = incus();
        var prime = "cd '/home/agentuser/jbang' && mise x -- ./gradlew dependencies";
        when(incus.execInContainer(COPY, "agentuser", prime))
                .thenReturn(new IncusClient.ExecResult(1, "javax.net.ssl.SSLHandshakeException:"
                        + " (certificate_unknown) PKIX path building failed", ""))
                .thenReturn(OK);
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/jbangdev/jbang");
        repo.setPath("~/jbang");
        repo.setPrime(List.of("mise x -- ./gradlew dependencies"));
        var def = new ImageDef();
        def.setName(COPY);
        def.setRepos(List.of(repo));
        var resolved = new LinkedHashMap<String, ImageDef>();
        resolved.put(NAME, def);
        var command = new UpdateAllCommand();
        command.prime = true;

        assertEquals(0, command.updateTemplates(incus, resolved));

        verify(incus, times(2)).execInContainer(COPY, "agentuser", prime);
        assertTrue(rootCommands.contains("/usr/local/bin/isx-trust /usr/lib/jvm /opt /usr/local"),
                String.join("\n", rootCommands));
    }

    /** A JDK a successful prime downloaded trusts the isx CA in later branches too, as after a build. */
    @Test
    void anUpdateThatPrimesBindsWhatThePrimesDownloadedBeforeTheStop() {
        var incus = incus();
        var command = new UpdateAllCommand();
        command.prime = true;

        assertEquals(0, command.updateTemplates(incus, primed("~/app", "./gradlew dependencies")));

        var stop = inOrder(incus);
        stop.verify(incus).execInContainer(COPY, "agentuser", "cd '/home/agentuser/app' && ./gradlew dependencies");
        stop.verify(incus).shellExec(COPY, "/usr/local/bin/isx-trust", "/usr/lib/jvm", "/opt", "/usr/local");
        stop.verify(incus).execInContainer(eq(COPY), eq("agentuser"),
                startsWith("/usr/local/bin/isx-trust '/home/agentuser/opt' "));
        stop.verify(incus).stop(COPY);
    }

    @Test
    void aBindThatFailsAfterThePrimesWarnsWithoutFailingThem() {
        var incus = incus();
        when(incus.shellExec(COPY, "/usr/local/bin/isx-trust", "/usr/lib/jvm", "/opt", "/usr/local"))
                .thenThrow(new IncusException("exec failed"));
        var command = new UpdateAllCommand();
        command.prime = true;

        var said = RecordingOutput.during(() ->
                assertEquals(0, command.updateTemplates(incus, primed("~/app", "./gradlew dependencies"))));

        var warned = String.join("\n", said.events(RecordingOutput.Kind.WARN).stream().map(RecordingOutput.Event::msg).toList());
        assertTrue(warned.contains("exec failed"), warned);
        verify(incus).stop(COPY);
    }

    @Test
    void aFailedPrimeIsNamedAsTheBuildsFailureLineNamesIt() {
        // The template's command is shown as RepoCloner shows it: safe, and scrubbed as written and shown (#1886)
        var incus = incus();
        var prime = "git clone https://u:hunter2\u0001secretpw@host/r";
        doReturn(new IncusClient.ExecResult(1, "", "fatal\n"))
                .when(incus).execInContainer(COPY, "agentuser", "cd '/home/agentuser/app' && " + prime);
        var command = new UpdateAllCommand();
        command.prime = true;

        var said = RecordingOutput.during(() -> assertEquals(1, command.updateTemplates(incus, primed("~/app", prime))));

        var failed = failedSteps(said);
        assertTrue(failed.contains("Prime command failed for ~/app: git clone https://u:<isx:redacted:url-credentials>@host/r"), failed);
        assertFalse(failed.contains("hunter2") || failed.contains("secretpw") || failed.contains("\u0001"), failed);
    }

    @Test
    void aFailedBindsWarningKeepsNoSecretItsRawOutputShows() {
        // The warning quotes what isx-trust printed last: scrubbed as printed and as shown (#1890)
        var incus = incus();
        when(incus.shellExec(COPY, "/usr/local/bin/isx-trust", "/usr/lib/jvm", "/opt", "/usr/local"))
                .thenReturn(new IncusClient.ExecResult(1, "", "binding\nfatal: https://u:hunter2\u0001secretpw@host/r/\n"));
        var command = new UpdateAllCommand();
        command.prime = true;

        var said = RecordingOutput.during(() ->
                assertEquals(0, command.updateTemplates(incus, primed("~/app", "./gradlew dependencies"))));

        var warned = String.join("\n", said.events(RecordingOutput.Kind.WARN).stream().map(RecordingOutput.Event::msg).toList());
        assertTrue(warned.contains("fatal: https://u:<isx:redacted:url-credentials>@host/r/"), warned);
        assertFalse(warned.contains("hunter2") || warned.contains("secretpw"), warned);
    }

    @Test
    void aTemplateBuiltBeforeTheHelperIsUpdatedWithoutAWarning() {
        var incus = incus();
        when(incus.shellExec(COPY, "/usr/local/bin/isx-trust", "/usr/lib/jvm", "/opt", "/usr/local"))
                .thenReturn(new IncusClient.ExecResult(127, "", "isx-trust: not found"));
        var command = new UpdateAllCommand();
        command.prime = true;

        var said = RecordingOutput.during(() ->
                assertEquals(0, command.updateTemplates(incus, primed("~/app", "./gradlew dependencies"))));

        assertTrue(said.events(RecordingOutput.Kind.WARN).isEmpty(), said.events(RecordingOutput.Kind.WARN).toString());
    }

    @Test
    void anUpdateWithoutPrimeBindsNothing() {
        var incus = incus();

        assertEquals(0, new UpdateAllCommand().updateTemplates(incus, primed("~/app", "./gradlew dependencies")));

        assertTrue(rootCommands.stream().noneMatch(c -> c.contains("isx-trust")), String.join("\n", rootCommands));
    }

    @Test
    void aPrimeStillUntrustedOnUpdateSaysWhatIsLeft() {
        var incus = incus();
        var prime = "cd '/home/agentuser/app' && ./fetch-deps";
        when(incus.execInContainer(COPY, "agentuser", prime))
                .thenReturn(new IncusClient.ExecResult(1, "PKIX path building failed", ""));
        var command = new UpdateAllCommand();
        command.prime = true;

        var said = RecordingOutput.during(() ->
                assertEquals(1, command.updateTemplates(incus, primed("~/app", "./fetch-deps"))));

        verify(incus, times(2)).execInContainer(COPY, "agentuser", prime);
        assertTrue(failedSteps(said).contains(RepoCloner.UNTRUSTED_AFTER_BIND), failedSteps(said));
    }

    private static Map<String, ImageDef> primed(String path, String prime) {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/app");
        repo.setPath(path);
        repo.setPrime(List.of(prime));
        var def = new ImageDef();
        def.setName(COPY);
        def.setRepos(List.of(repo));
        var resolved = new LinkedHashMap<String, ImageDef>();
        resolved.put(NAME, def);
        return resolved;
    }

    @Test
    void aFailedGitFetchFailsTheCommand() {
        var incus = incus();
        doReturn(new IncusClient.ExecResult(1, "", "git fetch failed in /home/agentuser/repo/"))
                .when(incus).execInContainer(eq(COPY), eq("agentuser"), anyString());

        assertEquals(1, update(incus));
    }

    @Test
    void theGitFetchScriptFetchesEveryRepoAndReportsAFailure(@TempDir Path home) throws Exception {
        update(incus());
        assertEquals(1, guestScripts.size());
        var script = guestScripts.getFirst();

        // a repository with no remotes fetches nothing, successfully
        git(home, "init", "-q", "good");
        Files.createDirectories(home.resolve("not-a-repo"));
        assertEquals(0, runAsLoginShell(script, home), "the script must parse and succeed");

        git(home, "init", "-q", "broken");
        git(home.resolve("broken"), "remote", "add", "origin", home.resolve("missing").toString());
        assertEquals(1, runAsLoginShell(script, home), "a failed fetch must fail the script");
    }

    /** Runs {@code script} the way {@code su - agentuser -c} does: one bash command line. */
    private static int runAsLoginShell(String script, Path home) throws Exception {
        var pb = new ProcessBuilder("bash", "-c", script).redirectErrorStream(true);
        pb.environment().put("HOME", home.toString());
        var process = pb.start();
        process.getInputStream().transferTo(System.out);
        int exit = process.waitFor();
        return exit;
    }

    private static void git(Path dir, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        assertEquals(0, new ProcessBuilder(command).inheritIO().start().waitFor());
    }
}
