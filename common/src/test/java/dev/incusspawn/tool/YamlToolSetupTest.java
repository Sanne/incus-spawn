package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class YamlToolSetupTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    private static IncusClient mockIncusWithArch() {
        var incus = mock(IncusClient.class);
        when(incus.getInstanceArchitecture(CONTAINER)).thenReturn("x86_64");
        return incus;
    }

    @Test
    void declaresPackages() {
        var def = new ToolDef();
        def.setName("test");
        def.setPackages(List.of("pkg-a", "pkg-b"));

        var setup = new YamlToolSetup(def);
        assertEquals(List.of("pkg-a", "pkg-b"), setup.packages());
    }

    @Test
    void executesAllStepsInOrder() {
        var incus = mockIncusWithArch();
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.execInContainer(anyString(), anyString(), anyString())).thenReturn(OK);

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
        def.setEnv(List.of(EnvEntry.set("X", "1")));
        def.setVerify("test-tool --version");

        var setup = new YamlToolSetup(def);
        setup.install(new Container(incus, CONTAINER), java.util.Map.of());

        InOrder order = inOrder(incus);

        // Packages are installed in bulk by BuildCommand, not by install().

        // 1. run -> runQuiet -> shellExec with sh -c
        order.verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), eq("echo root-step"));

        // 2. run_as_user -> runAsUserQuiet -> execInContainer (su - user -c)
        order.verify(incus).execInContainer(eq(CONTAINER),
                eq("agentuser"), eq("echo user-step"));

        // 3. writeFile -> shellExec with sh -c (heredoc)
        order.verify(incus).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), contains("/etc/test.conf"));

        // 4. chown -> shellExec
        order.verify(incus).shellExec(eq(CONTAINER),
                eq("chown"), eq("-R"), eq("testuser:testuser"), eq("/etc/test.conf"));

        // 5. env is no longer written by install() — collected centrally by BuildCommand

        // 6. verify -> shellExec
        order.verify(incus).shellExec(eq(CONTAINER),
                eq("test-tool"), eq("--version"));
    }

    @Test
    void minimalToolDoesNothing() {
        var incus = mockIncusWithArch();

        var def = new ToolDef();
        def.setName("empty");

        var setup = new YamlToolSetup(def);
        setup.install(new Container(incus, CONTAINER), java.util.Map.of());

        // Only getArchitecture is called for an empty tool (to filter downloads)
        verify(incus, only()).getInstanceArchitecture(CONTAINER);
    }

    @Test
    void packagesOnlyToolHasNoInstallInteractions() {
        var incus = mockIncusWithArch();

        var def = new ToolDef();
        def.setName("pkg-only");
        def.setPackages(List.of("vim"));

        var setup = new YamlToolSetup(def);
        assertEquals(List.of("vim"), setup.packages());

        setup.install(new Container(incus, CONTAINER), java.util.Map.of());

        // Packages are installed in bulk by BuildCommand — only getArchitecture is called
        verify(incus, only()).getInstanceArchitecture(CONTAINER);
    }

    @Test
    void downloadsExecuteBeforeRunCommands(@TempDir Path tempDir) throws IOException {
        var incus = mockIncusWithArch();
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        // Create a fake archive (just a file to make the extraction step work)
        var fakeArchive = tempDir.resolve("tool.tar.gz");
        Files.writeString(fakeArchive, "fake");

        var downloadCache = mock(DownloadCache.class);
        when(downloadCache.download(anyString(), any())).thenReturn(fakeArchive);

        var dl = new ToolDef.DownloadEntry();
        dl.setUrl("https://example.com/tool.tar.gz");
        dl.setSha256("abc123");
        dl.setExtract("/opt");
        dl.setLinks(Map.of("/opt/tool/bin/tool", "/usr/local/bin/tool"));

        var def = new ToolDef();
        def.setName("dl-tool");
        def.setDownloads(List.of(dl));
        def.setRun(List.of("echo after-download"));

        var setup = new YamlToolSetup(def, downloadCache);
        // install() will fail at the extractOnHost step since fakeArchive isn't a real archive,
        // but we can verify that downloadCache.download() was called before any run commands
        try {
            setup.install(new Container(incus, CONTAINER), java.util.Map.of());
        } catch (RuntimeException expected) {
            // Extraction of the fake archive will fail
        }

        // Verify download was attempted (before the run commands)
        verify(downloadCache).download("https://example.com/tool.tar.gz", "abc123");
        // The run command should NOT have been called since downloads failed first
        verify(incus, never()).shellExec(eq(CONTAINER),
                eq("sh"), eq("-c"), eq("echo after-download"));
    }

    @Test
    void destinationFileCopiesDownloadWithoutExtracting(@TempDir Path tempDir) throws IOException {
        var incus = mockIncusWithArch();
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        var downloaded = tempDir.resolve("bash_completion.bash");
        Files.writeString(downloaded, "complete -W clean mvn");

        var downloadCache = mock(DownloadCache.class);
        when(downloadCache.download(anyString(), any())).thenReturn(downloaded);

        var dl = new ToolDef.DownloadEntry();
        dl.setUrl("https://example.com/bash_completion.bash");
        dl.setSha256("abc123");
        dl.setDestinationFile("~/.bashrc.d/maven-bash-completion.sh");

        var def = new ToolDef();
        def.setName("maven-completion");
        def.setDownloads(List.of(dl));

        new YamlToolSetup(def, downloadCache).install(new Container(incus, CONTAINER), Map.of());

        var destination = "/home/agentuser/.bashrc.d/maven-bash-completion.sh";
        InOrder order = inOrder(incus);
        order.verify(incus).shellExec(CONTAINER, "mkdir", "-p", "/home/agentuser/.bashrc.d");
        order.verify(incus).filePush(downloaded.toString(), CONTAINER, destination);
        order.verify(incus).shellExec(CONTAINER, "chmod", "a+r", destination);
        verify(incus, never()).filePushRecursive(any(), any(), any());
    }

    @Test
    void destinationFileAndExtractAreBothProcessed(@TempDir Path tempDir) throws IOException {
        var incus = mockIncusWithArch();
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        var downloaded = tempDir.resolve("tool.tar.gz");
        Files.writeString(downloaded, "fake");

        var downloadCache = mock(DownloadCache.class);
        when(downloadCache.download(anyString(), any())).thenReturn(downloaded);

        var dl = new ToolDef.DownloadEntry();
        dl.setUrl("https://example.com/tool.tar.gz");
        dl.setDestinationFile("/opt/downloads/tool.tar.gz");
        dl.setExtract("/opt/tool");
        dl.setExtractInContainer(true);

        var def = new ToolDef();
        def.setName("raw-and-expanded");
        def.setDownloads(List.of(dl));

        new YamlToolSetup(def, downloadCache).install(new Container(incus, CONTAINER), Map.of());

        verify(incus).filePush(downloaded.toString(), CONTAINER, "/opt/downloads/tool.tar.gz");
        verify(incus).filePush(downloaded.toString(), CONTAINER, "/tmp/tool.tar.gz");
        verify(incus).shellExec(CONTAINER, "tar", "xf", "/tmp/tool.tar.gz", "-C", "/opt/tool");
    }

    @Test
    void destinationFileUsesMountAndCopyForVm(@TempDir Path tempDir) throws IOException {
        var incus = mockIncusWithArch();
        when(incus.isVm(CONTAINER)).thenReturn(true);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        var downloaded = tempDir.resolve("tool.zip").toAbsolutePath();
        Files.writeString(downloaded, "fake");

        var downloadCache = mock(DownloadCache.class);
        when(downloadCache.download(anyString(), any())).thenReturn(downloaded);

        var dl = new ToolDef.DownloadEntry();
        dl.setUrl("https://example.com/tool.zip");
        dl.setDestinationFile("/opt/downloads/tool.zip");

        var def = new ToolDef();
        def.setName("vm-file");
        def.setDownloads(List.of(dl));

        new YamlToolSetup(def, downloadCache).install(new Container(incus, CONTAINER), Map.of());

        verify(incus).deviceAdd(eq(CONTAINER), eq("dl-file-vm-file"), eq("disk"), any(String[].class));
        verify(incus).shellExec(CONTAINER, "cp", "/mnt/isx-download-file/tool.zip",
                "/opt/downloads/tool.zip");
        verify(incus).deviceRemove(CONTAINER, "dl-file-vm-file");
        verify(incus, never()).filePush(any(), any(), any());
    }

    @Test
    void extractInContainerPushesArchiveAndExtractsInsideContainer(@TempDir Path tempDir) throws IOException {
        var incus = mockIncusWithArch();
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        var fakeArchive = tempDir.resolve("idea.tar.gz");
        Files.writeString(fakeArchive, "fake");

        var downloadCache = mock(DownloadCache.class);
        when(downloadCache.download(anyString(), any())).thenReturn(fakeArchive);

        var dl = new ToolDef.DownloadEntry();
        dl.setUrl("https://example.com/idea.tar.gz");
        dl.setSha256("abc123");
        dl.setExtract("/opt");
        dl.setExtractInContainer(true);
        dl.setLinks(Map.of("/opt/idea/bin/idea", "/usr/local/bin/idea"));

        var def = new ToolDef();
        def.setName("idea-backend");
        def.setDownloads(List.of(dl));

        var setup = new YamlToolSetup(def, downloadCache);
        setup.install(new Container(incus, CONTAINER), Map.of());

        InOrder order = inOrder(incus);
        // 1. mkdir -p /opt
        order.verify(incus).shellExec(eq(CONTAINER), eq("mkdir"), eq("-p"), eq("/opt"));
        // 2. push archive into container
        order.verify(incus).filePush(eq(fakeArchive.toString()), eq(CONTAINER), eq("/tmp/idea.tar.gz"));
        // 3. extract inside container (quiet — captured exec)
        order.verify(incus).shellExec(eq(CONTAINER),
                eq("tar"), eq("xf"), eq("/tmp/idea.tar.gz"), eq("-C"), eq("/opt"));
        // 4. clean up archive
        order.verify(incus).shellExec(eq(CONTAINER), eq("rm"), eq("-f"), eq("/tmp/idea.tar.gz"));
        // 5. create symlinks
        order.verify(incus).shellExec(eq(CONTAINER), eq("ln"), eq("-sf"),
                eq("/opt/idea/bin/idea"), eq("/usr/local/bin/idea"));

        // filePushRecursive should NOT be called
        verify(incus, never()).filePushRecursive(any(), any(), any());
    }

    @Test
    void downloadWithMatchingArchIsProcessed(@TempDir Path tempDir) throws IOException {
        var incus = mockIncusWithArch();
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        when(incus.getInstanceArchitecture(CONTAINER)).thenReturn("x86_64");

        var fakeArchive = tempDir.resolve("tool.tar.gz");
        Files.writeString(fakeArchive, "fake");

        var downloadCache = mock(DownloadCache.class);
        when(downloadCache.download(anyString(), any())).thenReturn(fakeArchive);

        var dl = new ToolDef.DownloadEntry();
        dl.setUrl("https://example.com/tool-x86_64.tar.gz");
        dl.setSha256("abc123");
        dl.setExtract("/opt");
        dl.setArch("x86_64");

        var def = new ToolDef();
        def.setName("arch-match");
        def.setDownloads(List.of(dl));

        var setup = new YamlToolSetup(def, downloadCache);
        try {
            setup.install(new Container(incus, CONTAINER), Map.of());
        } catch (RuntimeException ignored) {
            // extraction of fake archive fails
        }

        verify(downloadCache).download(dl.getUrl(), "abc123");
    }

    @Test
    void downloadWithNonMatchingArchIsSkipped(@TempDir Path tempDir) throws IOException {
        var incus = mockIncusWithArch();
        when(incus.getInstanceArchitecture(CONTAINER)).thenReturn("x86_64");

        var downloadCache = mock(DownloadCache.class);

        var dl = new ToolDef.DownloadEntry();
        dl.setUrl("https://example.com/tool-aarch64.tar.gz");
        dl.setSha256("abc123");
        dl.setExtract("/opt");
        dl.setArch("aarch64");

        var def = new ToolDef();
        def.setName("arch-skip");
        def.setDownloads(List.of(dl));

        var setup = new YamlToolSetup(def, downloadCache);
        setup.install(new Container(incus, CONTAINER), Map.of());

        verify(downloadCache, never()).download(anyString(), any());
    }

    @Test
    void downloadWithoutArchIsAlwaysProcessed(@TempDir Path tempDir) throws IOException {
        var incus = mockIncusWithArch();
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        var fakeArchive = tempDir.resolve("tool.tar.gz");
        Files.writeString(fakeArchive, "fake");

        var downloadCache = mock(DownloadCache.class);
        when(downloadCache.download(anyString(), any())).thenReturn(fakeArchive);

        var dl = new ToolDef.DownloadEntry();
        dl.setUrl("https://example.com/tool.tar.gz");
        dl.setSha256("abc123");
        dl.setExtract("/opt");
        // no arch set — should match all

        var def = new ToolDef();
        def.setName("no-arch");
        def.setDownloads(List.of(dl));

        var setup = new YamlToolSetup(def, downloadCache);
        try {
            setup.install(new Container(incus, CONTAINER), Map.of());
        } catch (RuntimeException ignored) {
            // extraction of fake archive fails
        }

        verify(downloadCache).download(dl.getUrl(), "abc123");
    }

    @Test
    void fileWithoutOwnerSkipsChown() {
        var incus = mockIncusWithArch();
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        var file = new ToolDef.FileEntry();
        file.setPath("/tmp/test");
        file.setContent("data");
        // no owner set

        var def = new ToolDef();
        def.setName("no-chown");
        def.setFiles(List.of(file));

        var setup = new YamlToolSetup(def);
        setup.install(new Container(incus, CONTAINER), java.util.Map.of());

        // writeFile is called, but chown is not
        verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), contains("/tmp/test"));
        verify(incus, never()).shellExec(eq(CONTAINER), eq("chown"), any(), any(), any());
    }
}
