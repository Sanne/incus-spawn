package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests that verify architecture-specific download support works correctly.
 * This addresses issue #164: tools should download the correct binary for
 * the container's architecture, not the host's architecture.
 */
class ArchitectureSupportTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final String CONTAINER = "test-container";

    @Test
    void toolDownloadsX86_64BinaryOnX86_64Container(@TempDir Path tempDir) throws IOException {
        var incus = mock(IncusClient.class);
        when(incus.getInstanceArchitecture(CONTAINER)).thenReturn("x86_64");
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        var x86Archive = tempDir.resolve("tool-x86_64.tar.gz");
        Files.writeString(x86Archive, "x86_64 binary");

        var downloadCache = mock(DownloadCache.class);
        when(downloadCache.download(anyString(), any())).thenReturn(x86Archive);

        var x86Download = new ToolDef.DownloadEntry();
        x86Download.setUrl("https://example.com/tool-x86_64.tar.gz");
        x86Download.setSha256("abc123");
        x86Download.setExtract("/opt");
        x86Download.setArch("x86_64");

        var aarch64Download = new ToolDef.DownloadEntry();
        aarch64Download.setUrl("https://example.com/tool-aarch64.tar.gz");
        aarch64Download.setSha256("def456");
        aarch64Download.setExtract("/opt");
        aarch64Download.setArch("aarch64");

        var def = new ToolDef();
        def.setName("multi-arch-tool");
        def.setDownloads(List.of(x86Download, aarch64Download));

        var setup = new YamlToolSetup(def, downloadCache);
        try {
            setup.install(new Container(incus, CONTAINER), Map.of());
        } catch (RuntimeException ignored) {
            // extraction fails with fake archive
        }

        // Verify only x86_64 download was fetched
        verify(downloadCache).download(eq(x86Download.getUrl()), eq("abc123"));
        verify(downloadCache, never()).download(eq(aarch64Download.getUrl()), any());
    }

    @Test
    void toolDownloadsAarch64BinaryOnAarch64Container(@TempDir Path tempDir) throws IOException {
        var incus = mock(IncusClient.class);
        when(incus.getInstanceArchitecture(CONTAINER)).thenReturn("aarch64");
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);

        var aarch64Archive = tempDir.resolve("tool-aarch64.tar.gz");
        Files.writeString(aarch64Archive, "aarch64 binary");

        var downloadCache = mock(DownloadCache.class);
        when(downloadCache.download(anyString(), any())).thenReturn(aarch64Archive);

        var x86Download = new ToolDef.DownloadEntry();
        x86Download.setUrl("https://example.com/tool-x86_64.tar.gz");
        x86Download.setSha256("abc123");
        x86Download.setExtract("/opt");
        x86Download.setArch("x86_64");

        var aarch64Download = new ToolDef.DownloadEntry();
        aarch64Download.setUrl("https://example.com/tool-aarch64.tar.gz");
        aarch64Download.setSha256("def456");
        aarch64Download.setExtract("/opt");
        aarch64Download.setArch("aarch64");

        var def = new ToolDef();
        def.setName("multi-arch-tool");
        def.setDownloads(List.of(x86Download, aarch64Download));

        var setup = new YamlToolSetup(def, downloadCache);
        try {
            setup.install(new Container(incus, CONTAINER), Map.of());
        } catch (RuntimeException ignored) {
            // extraction fails with fake archive
        }

        // Verify only aarch64 download was fetched
        verify(downloadCache).download(eq(aarch64Download.getUrl()), eq("def456"));
        verify(downloadCache, never()).download(eq(x86Download.getUrl()), any());
    }

    @Test
    void canonicalArchNormalizesArchitectureNames() {
        assertEquals("x86_64", YamlToolSetup.canonicalArch("x86_64"));
        assertEquals("x86_64", YamlToolSetup.canonicalArch("amd64"));
        assertEquals("aarch64", YamlToolSetup.canonicalArch("aarch64"));
        assertEquals("aarch64", YamlToolSetup.canonicalArch("arm64"));
        assertEquals("unknown", YamlToolSetup.canonicalArch("unknown"));
    }

    @Test
    void claudeDetectPlatformNormalizesArchitectureNames() {
        assertEquals("linux-x64", ClaudeSetup.detectPlatform("x86_64"));
        assertEquals("linux-x64", ClaudeSetup.detectPlatform("amd64"));
        assertEquals("linux-arm64", ClaudeSetup.detectPlatform("aarch64"));
        assertEquals("linux-arm64", ClaudeSetup.detectPlatform("arm64"));

        assertThrows(RuntimeException.class, () -> ClaudeSetup.detectPlatform("sparc"));
    }

    @Test
    void throwsWhenArchitectureCannotBeDetermined() {
        var incus = mock(IncusClient.class);
        when(incus.getInstanceArchitecture(CONTAINER))
                .thenThrow(new IncusException("Failed to get architecture for instance " + CONTAINER));

        var dl = new ToolDef.DownloadEntry();
        dl.setUrl("https://example.com/tool-x86_64.tar.gz");
        dl.setSha256("abc123");
        dl.setExtract("/opt");
        dl.setArch("x86_64");

        var def = new ToolDef();
        def.setName("test");
        def.setDownloads(List.of(dl));

        var setup = new YamlToolSetup(def, mock(DownloadCache.class));
        var exception = assertThrows(IncusException.class,
                () -> setup.install(new Container(incus, CONTAINER), Map.of()));
        assertTrue(exception.getMessage().contains("Failed to get architecture"));
    }

    @Test
    void cachesArchitectureLookup() {
        var incus = mock(IncusClient.class);
        when(incus.getInstanceArchitecture(CONTAINER)).thenReturn("x86_64");

        var container = new Container(incus, CONTAINER);

        // Call getArchitecture multiple times
        container.getArchitecture();
        container.getArchitecture();
        container.getArchitecture();

        // Verify the API was only called once (cached)
        verify(incus, times(1)).getInstanceArchitecture(CONTAINER);
    }
}
