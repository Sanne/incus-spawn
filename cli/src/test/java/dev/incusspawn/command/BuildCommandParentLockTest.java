package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.lifecycle.TemplateLockProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** {@code isx build}'s look at a parent, against another isx rebuilding that parent (#1212). */
// The template lock lives under the home directory
@ExtendWith(IsolatedHome.class)
class BuildCommandParentLockTest {

    @Test
    void theParentAndItsOwnParentAreHeldWhileItsStateIsChecked() {
        var incus = mock(IncusClient.class);
        var heldWhenAsked = new LinkedHashMap<String, Boolean>();
        // Missing, as it would read between a rebuild's delete and rename, were it not held
        when(incus.exists("tpl-parent")).thenAnswer(inv -> {
            heldWhenAsked.put("tpl-parent", TemplateLockProbe.held("tpl-parent"));
            // Read by the outdated check, where caught missing it makes the parent look current
            heldWhenAsked.put("tpl-grandparent", TemplateLockProbe.held("tpl-grandparent"));
            return false;
        });
        var cmd = new BuildCommand();
        cmd.incus = incus;
        var parentDef = new ImageDef();
        parentDef.setParent("tpl-grandparent");

        assertTrue(cmd.parentNeedsBuild("tpl-child", "tpl-parent", parentDef));
        assertEquals(Map.of("tpl-parent", true, "tpl-grandparent", true), heldWhenAsked,
                "looked up without holding it, so a rebuild could swap it meanwhile");
        assertFalse(TemplateLockProbe.held("tpl-parent"));
        assertFalse(TemplateLockProbe.held("tpl-grandparent"));
    }

    @Test
    void aChainWhoseTargetCannotBeBuiltRebuildsNoParent() {
        var incus = mock(IncusClient.class);
        var cmd = new BuildCommand();
        cmd.incus = incus;
        var parent = new ImageDef();
        parent.setName("tpl-parent");
        var child = new ImageDef();
        child.setName("t".repeat(BuildCommand.MAX_TEMPLATE_NAME_LENGTH + 1));
        child.setParent("tpl-parent");

        // The parent is missing, so the chain would build it first
        assertThrows(BuildCommand.BuildFailedException.class,
                () -> cmd.buildChain(child, Map.of("tpl-parent", parent, child.getName(), child)));
        verify(incus).exists("tpl-parent");
        verifyNoMoreInteractions(incus);
    }
}
