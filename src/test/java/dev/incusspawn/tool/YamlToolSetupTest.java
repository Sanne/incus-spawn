package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class YamlToolSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    @Test
    void executesAllStepsInOrder() {
        var incus = mock(IncusClient.class);
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        var def = new ToolDef();
        def.setName("full-tool");
        def.setDescription("Full test");
        def.setPackages(List.of("pkg-a", "pkg-b"));
        def.setRun(List.of("echo root-step"));
        def.setRunAsUser(List.of("echo user-step"));
        var file = new ToolDef.FileEntry();
        file.setPath("/etc/test.conf");
        file.setContent("content");
        file.setOwner("testuser:testuser");
        def.setFiles(List.of(file));
        def.setEnv(List.of("export X=1"));
        def.setVerify("test-tool --version");

        var setup = new YamlToolSetup(def);
        setup.install(new Container(incus, CONTAINER));

        InOrder order = inOrder(incus);

        // 1. packages via dnfInstall -> runInteractive -> shellExecInteractive
        order.verify(incus).shellExecInteractive(eq(CONTAINER),
                eq("dnf"), eq("install"), eq("-y"), eq("pkg-a"), eq("pkg-b"));

        // 2. run -> shellExecInteractive with sh -c
        order.verify(incus).shellExecInteractive(eq(CONTAINER),
                eq("sh"), eq("-c"), eq("echo root-step"));

        // 3. run_as_user -> shellExecInteractive with su -l agentuser -c
        order.verify(incus).shellExecInteractive(eq(CONTAINER),
                eq("su"), eq("-l"), eq("agentuser"), eq("-c"), eq("echo user-step"));

        // 4. writeFile -> shellExec with sh -c (heredoc)
        order.verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("/etc/test.conf"));

        // 5. chown -> shellExec
        order.verify(incus).shellExec(eq(CONTAINER),
                eq("chown"), eq("-R"), eq("testuser:testuser"), eq("/etc/test.conf"));

        // 6. env -> appendToProfile -> shellExec with sh -c
        order.verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("export X=1"));

        // 7. verify -> shellExec
        order.verify(incus).shellExec(eq(CONTAINER),
                eq("test-tool"), eq("--version"));
    }

    @Test
    void minimalToolDoesNothing() {
        var incus = mock(IncusClient.class);

        var def = new ToolDef();
        def.setName("empty");

        var setup = new YamlToolSetup(def);
        setup.install(new Container(incus, CONTAINER));

        // No interactions with incus for an empty tool
        verifyNoInteractions(incus);
    }

    @Test
    void packagesOnlyTool() {
        var incus = mock(IncusClient.class);
        when(incus.shellExecInteractive(anyString(), any(String[].class))).thenReturn(0);

        var def = new ToolDef();
        def.setName("pkg-only");
        def.setPackages(List.of("vim"));

        var setup = new YamlToolSetup(def);
        setup.install(new Container(incus, CONTAINER));

        verify(incus).shellExecInteractive(eq(CONTAINER),
                eq("dnf"), eq("install"), eq("-y"), eq("vim"));
        verifyNoMoreInteractions(incus);
    }

    @Test
    void fileWithoutOwnerSkipsChown() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        var file = new ToolDef.FileEntry();
        file.setPath("/tmp/test");
        file.setContent("data");
        // no owner set

        var def = new ToolDef();
        def.setName("no-chown");
        def.setFiles(List.of(file));

        var setup = new YamlToolSetup(def);
        setup.install(new Container(incus, CONTAINER));

        // writeFile is called, but chown is not
        verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("/tmp/test"));
        verify(incus, never()).shellExec(eq(CONTAINER), eq("chown"), any(), any(), any());
    }
}
