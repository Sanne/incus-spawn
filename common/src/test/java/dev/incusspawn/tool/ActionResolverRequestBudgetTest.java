package dev.incusspawn.tool;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.TempHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins how many Incus round trips building an {@link ActionContext} costs. {@code isx run} pays
 * it before every action, so it is on the same latency path as
 * {@code InstanceLifecycleRequestBudgetTest}'s flows (#979).
 *
 * <p>Budgets are exact: fewer is an improvement, so lower the number here in the same change.
 * Tool resolution reads {@code config.yaml} for feature gates, hence the temporary home.
 */
@ExtendWith(TempHome.class)
class ActionResolverRequestBudgetTest {

    private static final String NAME = "dev-1";
    private static final String TEMPLATE = "tpl-java";

    private static ActionResolver resolver(FakeIncusDaemon daemon) {
        return new ActionResolver(daemon.client(), new ToolDefLoader(), List.of(), Map.of());
    }

    private static String buildSource(String tool) {
        var def = new ImageDef();
        def.setTools(List.of(new ToolDef.ToolRef(tool)));
        return new BuildSource(Map.of(TEMPLATE, def), Map.of(), Map.of(), Map.of()).toJson();
    }

    private static void assertBudget(int expected, FakeIncusDaemon daemon, String flow) {
        var requests = daemon.requests();
        assertEquals(expected, requests.size(), () -> flow + " should make " + expected
                + " Incus request(s), made " + requests.size() + ":\n  "
                + String.join("\n  ", requests)
                + "\nMore is a latency regression; fewer is an improvement -- lower the budget.");
    }

    @Test
    void aRunningInstanceIsReadOnceAndAskedForItsAddress() {
        var daemon = new FakeIncusDaemon()
                .instance(NAME, "container", "Running", Map.of(
                        Metadata.TYPE, Metadata.TYPE_CLONE,
                        Metadata.STATIC_IP, "10.166.11.99",
                        Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(),
                        Metadata.BUILD_SOURCE, buildSource("some-tool")))
                .ipFiltering(NAME, "true");
        var context = resolver(daemon).buildActionContext(NAME, TEMPLATE);
        assertBudget(2, daemon, "buildActionContext (running)");

        assertEquals("GET /1.0/instances/" + NAME + "/state", daemon.requests().get(1));
        assertEquals(MachineType.CONTAINER, context.machineType());
        assertEquals("Running", context.status());
        assertEquals("10.166.11.20", context.ipv4(), "the address the guest holds, not the stamp");
        assertEquals(NetworkMode.PROXY_ONLY.name(), context.networkMode());
        assertEquals(TEMPLATE, context.parent());
        assertEquals(Set.of("some-tool"), context.installedTools(),
                "a clone's tools are the ones it was built with");
    }

    @Test
    void aFrozenInstanceStillReportsTheAddressItHolds() {
        // Paused, not stopped: the guest keeps its network, so /state still has the address.
        var daemon = new FakeIncusDaemon()
                .instance(NAME, "container", "Frozen", Map.of(Metadata.STATIC_IP, "10.166.11.99"))
                .ipFiltering(NAME, "true");
        var context = resolver(daemon).buildActionContext(NAME, TEMPLATE);
        assertBudget(2, daemon, "buildActionContext (frozen)");
        assertEquals("10.166.11.20", context.ipv4(), "the address the guest holds, not the stamp");
    }

    @Test
    void aStoppedVmFallsBackToItsStaticIpWithoutAskingForState() {
        // A stopped guest holds no address, so its /state has nothing to give.
        var daemon = new FakeIncusDaemon().instance(NAME, "virtual-machine", "Stopped", Map.of(
                Metadata.STATIC_IP, "10.166.11.21"));
        var context = resolver(daemon).buildActionContext(NAME, TEMPLATE);
        assertBudget(1, daemon, "buildActionContext (stopped)");

        assertEquals(MachineType.VM, context.machineType());
        assertEquals("Stopped", context.status());
        assertEquals("10.166.11.21", context.ipv4());
        assertEquals("", context.networkMode());
    }

    @Test
    void aBaseTemplatesToolsComeFromItsDefinitionsNotItsBuildSource() {
        var daemon = new FakeIncusDaemon().container(TEMPLATE, Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.BUILD_SOURCE, buildSource("some-tool")));
        var def = new ImageDef();
        def.setTools(List.of(new ToolDef.ToolRef("other-tool")));
        var context = new ActionResolver(daemon.client(), new ToolDefLoader(), List.of(), Map.of(TEMPLATE, def))
                .buildActionContext(TEMPLATE, TEMPLATE);
        assertBudget(1, daemon, "buildActionContext (base template)");
        assertEquals(Set.of("other-tool"), context.installedTools(), "the definition on disk is authoritative");
    }

    @Test
    void aBaseTemplateWhoseDefinitionIsGoneFallsBackToItsBuildSource() {
        var daemon = new FakeIncusDaemon().container(TEMPLATE, Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.BUILD_SOURCE, buildSource("some-tool")));
        var context = resolver(daemon).buildActionContext(TEMPLATE, TEMPLATE);
        assertBudget(1, daemon, "buildActionContext (base template, YAML deleted)");
        assertEquals(Set.of("some-tool"), context.installedTools(), "what it was built with (#868)");
    }

    @Test
    void collectingInstalledToolsReadsTheInstanceOnce() {
        var daemon = new FakeIncusDaemon().container(NAME, Map.of(
                Metadata.TYPE, Metadata.TYPE_CLONE,
                Metadata.BUILD_SOURCE, buildSource("some-tool")));
        var tools = resolver(daemon).collectInstalledTools(NAME, TEMPLATE);
        assertBudget(1, daemon, "collectInstalledTools");
        assertEquals(Set.of("some-tool"), tools);
    }

    @Test
    void aMissingInstanceFailsRatherThanYieldingAnEmptyContext() {
        var resolver = resolver(new FakeIncusDaemon());
        assertThrows(IncusException.class, () -> resolver.buildActionContext(NAME, TEMPLATE));
        assertThrows(IncusException.class, () -> resolver.collectInstalledTools(NAME, TEMPLATE));
    }
}
