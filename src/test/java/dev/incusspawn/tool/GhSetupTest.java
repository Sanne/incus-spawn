package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GhSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    @Test
    void declaresGhPackage() {
        var setup = new GhSetup();
        assertEquals(java.util.List.of("gh"), setup.packages());
    }

    @Test
    void installSetsGhToken() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        var setup = new GhSetup();
        setup.install(new Container(incus, CONTAINER));

        // Packages are installed in bulk by BuildCommand, not by install().
        // install() only configures auth.
        verify(incus, never()).shellExecInteractive(anyString(), any(String[].class));

        // Set GH_TOKEN placeholder in .bashrc (proxy replaces it with real token)
        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("GH_TOKEN=gho_placeholder"));
    }

    @Test
    void usesEnvVarNotHostsYml() {
        // GH_TOKEN env var is used instead of hosts.yml because newer gh versions
        // store tokens in the system keyring (via D-Bus), which doesn't exist in
        // containers. Any hosts.yml triggers a migration that fails without D-Bus.
        var incus = mock(IncusClient.class);
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        var setup = new GhSetup();
        setup.install(new Container(incus, CONTAINER));

        // Should NOT write any hosts.yml
        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("hosts.yml"));

        // Should set GH_TOKEN via .bashrc
        verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("GH_TOKEN="));
    }
}
