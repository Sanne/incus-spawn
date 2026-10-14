package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.McpClientRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Every exec that delivers a start's secret tells the guest whether the instance holds the
 * {@code mcp-caller} grant, read from the instance itself (#1182): the guest registers
 * {@code isx mcp} in its Claude Code on "1" and removes it on anything else, so a coordinator
 * keeps its registration and a copy loses it on its first start.
 */
@ExtendWith(TempHome.class)
class McpClientRegistrationStartTest {

    private static final String NAME = "coord";
    private static final String GRANT = Metadata.newMcpCallerGrant();

    /** What each exec that delivered a secret told the guest about the grant. */
    private static List<String> grantsTold(FakeIncusDaemon daemon) {
        return daemon.execs().stream()
                .filter(e -> String.join(" ", e.command()).contains(InstanceSecret.GUEST_SCRIPT))
                .map(e -> e.environment().get(McpClientRegistration.ENV))
                .toList();
    }

    private static Map<String, String> config(String grant) {
        var config = new HashMap<String, String>();
        if (grant != null) config.put(Metadata.MCP_CALLER, grant);
        return config;
    }

    private static List<String> startStopped(String grant) {
        var daemon = new FakeIncusDaemon().container(NAME, config(grant)).ipFiltering(NAME, "true");
        // FakeIncusDaemon serves no exec, so the wait times out; the probes are what matters
        assertThrows(IncusException.class, () -> InstanceLifecycle.ensureReady(
                daemon.clientWithShortReadyWait(), NAME, dev.incusspawn.incus.JacksonTrees.fromJsonNode(daemon.instance(NAME)), MachineType.CONTAINER, msg -> {}));
        var told = grantsTold(daemon);
        assertFalse(told.isEmpty(), "the readiness probe delivered the secret");
        return told;
    }

    @Test
    void aCoordinatorsStartTellsTheGuestItHoldsTheGrant() {
        assertTrue(startStopped(GRANT).stream().allMatch("1"::equals));
    }

    @Test
    void anyOtherStartTellsTheGuestItDoesNot() {
        assertTrue(startStopped(null).stream().allMatch("0"::equals));
    }

    @Test
    void aStampThatIsNoGrantSwitchesNothingOn() {
        // The proxy and isx mcp refuse it (Metadata.isMcpCallerGrant): so does the registration
        assertTrue(startStopped("2026-10-05T10:00:00").stream().allMatch("0"::equals));
    }

    /** The environment the branch's setup exec gets, for a branch of a coordinator. */
    private static Map<String, String> branchSetupEnv(Map<String, String> extraConfig) {
        var daemon = new FakeIncusDaemon().container("tpl", Map.of(Metadata.PROFILE, "tpl-dev"))
                .container(NAME, Map.of(Metadata.PARENT, "tpl", Metadata.MCP_CALLER, GRANT));
        var incus = spy(daemon.client());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        doReturn(true).when(incus).pollUntilReady(eq("copy"), anyInt(), env.capture(), any(String[].class));
        var request = new BranchFlow.Request(NAME, "copy", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), true, extraConfig);

        BranchFlow.create(incus, BranchFlow.preflight(incus, request, Map.of()));

        return env.getValue();
    }

    @Test
    void aCopyOfACoordinatorIsToldToRemoveTheRegistrationOnItsFirstStart() {
        // isx branch --from coord, or a fork made over MCP: the copy carries the source's
        // registration in its rootfs, and its first start is the branch's own
        assertEquals("0", branchSetupEnv(Map.of()).get(McpClientRegistration.ENV));
    }

    @Test
    void aBranchMadeWithMcpClientIsToldToRegister() {
        assertEquals("1", branchSetupEnv(Map.of(Metadata.MCP_CALLER, Metadata.newMcpCallerGrant()))
                .get(McpClientRegistration.ENV));
    }

    /**
     * Every path tells a coordinator {@code 1} and anything else {@code 0}: the stamp-derived value,
     * never a constant -- a path that said {@code 1} to all would register isx mcp in every copy.
     */
    static Stream<Arguments> stamps() {
        return Stream.of(
                Arguments.of("a coordinator", GRANT, "1"),
                Arguments.of("no stamp, as on a copy", null, "0"),
                Arguments.of("a stamp that is no grant", "2026-10-05T10:00:00", "0"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stamps")
    void aContainerRebootIsxDidNotDoTellsTheStampedValue(String what, String grant, String told) {
        var config = config(grant);
        config.put(Metadata.INSTANCE_SECRET_BOOT, "2026-10-01T09:00:00Z");
        var daemon = new FakeIncusDaemon().instance(NAME, "container", "Running", config)
                .lastUsedAt(NAME, "2026-10-05T07:30:00Z");

        InstanceLifecycle.ensureReady(daemon.client(), NAME, dev.incusspawn.incus.JacksonTrees.fromJsonNode(daemon.instance(NAME)), MachineType.CONTAINER, msg -> {});

        assertEquals(List.of(told), grantsTold(daemon));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stamps")
    void aVmRebootedInPlaceTellsTheStampedValue(String what, String grant, String told) {
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Running", config(grant));
        var incus = spy(daemon.client());
        // The agent probe finds the guest without a secret, as after a reboot inside QEMU
        doReturn(new IncusClient.ExecResult(0, InstanceSecret.MISSING + "\n", ""))
                .when(incus).shellExec(eq(NAME), eq("sh"), eq("-c"),
                        eq(InstanceSecret.GUEST_CHECK + "\n" + InstanceSecret.generationCheck("")));

        InstanceLifecycle.ensureReady(incus, NAME, dev.incusspawn.incus.JacksonTrees.fromJsonNode(daemon.instance(NAME)), MachineType.VM, msg -> {});

        assertEquals(List.of(told), grantsTold(daemon));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stamps")
    void aVmRestartedForItsAgentTellsTheStampedValue(String what, String grant, String told) {
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Running", config(grant));
        assertThrows(IncusException.class,
                () -> VmAgentRecovery.restartForAgent(daemon.clientWithShortReadyWait(), NAME, msg -> {}));
        var probes = grantsTold(daemon);
        assertFalse(probes.isEmpty());
        assertTrue(probes.stream().allMatch(told::equals), probes.toString());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(booleans = {true, false})
    void aRestartFromTheTuiPassesWhatItsListingSays(boolean mcpCaller) {
        // The listing's value is derived from the stamp by ListCommand (ListCommandMcpCallerTest)
        var daemon = new FakeIncusDaemon().instance(NAME, "container", "Running", config(GRANT));
        var incus = spy(daemon.client());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        doNothing().when(incus).waitForReady(eq(NAME), eq(MachineType.CONTAINER), any(), env.capture());

        InstanceLifecycle.restartForUse(incus, NAME, MachineType.CONTAINER, mcpCaller);

        assertEquals(mcpCaller ? "1" : "0", env.getValue().get(McpClientRegistration.ENV));
    }
}
