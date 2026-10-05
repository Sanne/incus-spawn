package dev.incusspawn.lifecycle;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ResourceLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a branch gets for the settings its request leaves open (#869): the same in
 * {@code isx branch} and the TUI's branch dialog, both taken from the template definition.
 */
@ExtendWith(TempHome.class)
class BranchDefaultsTest {

    @BeforeEach
    void inAWaylandSession() {
        BranchFlow.waylandSession = () -> true;
    }

    @AfterEach
    void restoreSessionCheck() {
        BranchFlow.waylandSession = GuiPassthrough::inWaylandSession;
    }

    private static Map<String, ImageDef> template(String type, boolean gui) {
        var def = new ImageDef();
        def.setName("tpl-dev");
        def.setType(type);
        def.setGui(gui);
        return Map.of("tpl-dev", def);
    }

    @Test
    void aBranchLeftToItsDefaultsKeepsKvmTheDefinitionAsksFor() {
        // A template built with type: kvm before the build stamped its instance mode
        var daemon = new FakeIncusDaemon()
                .container("tpl-dev", Map.of(Metadata.KVM_ENABLED, "true"))
                .device("tpl-dev", "kvm", Map.of("type", "unix-char", "source", "/dev/kvm"));
        var request = new BranchFlow.Request("tpl-dev", "dev-1", false, null, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());
        var defs = template("kvm", false);
        BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, defs));

        assertTrue(daemon.instance("dev-1").path("devices").has("kvm"),
                "the definition says type: kvm, as the TUI's dialog would have ticked it");
    }

    @Test
    void theDefinitionDecidesGuiAndKvm() {
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of());
        var incus = daemon.client();

        var plain = BranchFlow.defaultsFor("tpl-dev", incus.instanceMetadata("tpl-dev"), template("container", false));
        assertFalse(plain.gui());
        assertFalse(plain.kvm());

        var both = BranchFlow.defaultsFor("tpl-dev", incus.instanceMetadata("tpl-dev"), template("kvm", true));
        assertTrue(both.gui());
        assertTrue(both.kvm());
    }

    @Test
    void withoutADefinitionTheSourceDecides() {
        // A branch of a branch: no definition carries the instance's name
        var daemon = new FakeIncusDaemon().container("dev-1",
                Map.of(Metadata.GUI_ENABLED, "true", Metadata.INSTANCE_MODE, "kvm"));
        var defaults = BranchFlow.defaultsFor("dev-1", daemon.client().instanceMetadata("dev-1"), template("container", false));
        assertTrue(defaults.gui());
        assertTrue(defaults.kvm());
    }

    @Test
    void resourceLimitsFollowTheMachineType() {
        var daemon = new FakeIncusDaemon()
                .container("ct", Map.of())
                .instance("vm", "virtual-machine", "Stopped", Map.of());
        var incus = daemon.client();

        var container = BranchFlow.defaultsFor("ct", incus.instanceMetadata("ct"), Map.of());
        assertNull(container.cpu(), "a container gets no CPU limit");
        assertEquals(ResourceLimits.adaptiveMemoryLimit(), container.memory());

        var vm = BranchFlow.defaultsFor("vm", incus.instanceMetadata("vm"), Map.of());
        assertEquals(Math.max(1, ResourceLimits.hostProcessorCount() - 2), vm.cpu());
        assertEquals(ResourceLimits.defaultVmMemoryLimit(), vm.memory());
        assertEquals(ResourceLimits.defaultDiskLimit(), vm.disk());
    }

    @Test
    void outsideAWaylandSessionGuiIsNotTheDefaultAndSaysWhy() {
        // Both front ends ask defaultsFor, so the dialog does not tick what isx branch would skip
        BranchFlow.waylandSession = () -> false;
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of());
        var defaults = BranchFlow.defaultsFor("tpl-dev", daemon.client().instanceMetadata("tpl-dev"),
                template("container", true));
        assertFalse(defaults.gui());
        assertNotNull(defaults.guiNote());
        assertNull(BranchFlow.defaultsFor("tpl-dev", daemon.client().instanceMetadata("tpl-dev"),
                template("container", false)).guiNote(), "nothing to explain when no GUI was asked for");
    }

    @Test
    void anAgentsBranchNeverGetsGui() {
        // isx mcp's request: GPU and the host's Wayland socket are never an agent's by default,
        // even from a gui: true template in a Wayland session
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of(Metadata.GUI_ENABLED, "true"));
        var defaults = BranchFlow.defaultsFor("tpl-dev", daemon.client().instanceMetadata("tpl-dev"),
                template("container", true));
        assertTrue(defaults.gui(), "the template asks for GUI");
        assertFalse(BranchFlow.gui(BranchFlow.Request.defaults("tpl-dev", "agent-1"), defaults));
    }

    @Test
    void aVmDoesNotDefaultToGui() {
        // GUI passthrough hands over a GPU device, which a VM cannot start with
        var daemon = new FakeIncusDaemon().instance("tpl-dev", "virtual-machine", "Stopped",
                Map.of(Metadata.GUI_ENABLED, "true"));
        var defaults = BranchFlow.defaultsFor("tpl-dev", daemon.client().instanceMetadata("tpl-dev"),
                template("vm", true));
        assertFalse(defaults.gui());
    }

    @Test
    void creatingABranchDoesNotReadItsSourceAgain() {
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of());
        var request = new BranchFlow.Request("tpl-dev", "dev-1", null, null, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());
        var preflight = BranchFlow.preflight(daemon.client(), request, template("container", false));
        int before = daemon.requests().size();
        BranchFlow.create(daemon.client(), preflight);

        var sourceReads = daemon.requests().subList(before, daemon.requests().size()).stream()
                .filter("GET /1.0/instances/tpl-dev"::equals).count();
        assertEquals(0, sourceReads, "preflight read it: the copy plan and the defaults come from that read\n"
                + String.join("\n", daemon.requests()));
    }
}
