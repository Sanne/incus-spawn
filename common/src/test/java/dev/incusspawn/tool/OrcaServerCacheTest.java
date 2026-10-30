package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OrcaServerCacheTest {
    @TempDir Path dir;

    private ToolDef definition() throws Exception {
        try (var stream = getClass().getResourceAsStream("/tools/orca.yaml")) {
            return ToolDef.loadFromStream(stream);
        }
    }

    private record Run(int exit, String output) {}

    private Run install(String cache) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")));
        var def = definition();
        var values = ParameterResolver.resolve(def.getParameters(), Map.of("server-cache", cache));
        assertFalse(values.hasErrors(), values.errors().toString());
        var script = ParameterSubstitutor.substitute(def.getRunAsUser().getFirst(), values.resolvedValues());
        var home = Files.createDirectories(dir.resolve("home"));
        var log = dir.resolve("install.log");
        var builder = new ProcessBuilder("/bin/bash", "-c", script).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().put("HOME", home.toString());
        var process = builder.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Cache import timed out");
        }
        return new Run(process.exitValue(), Files.readString(log));
    }

    private Path cache() throws Exception {
        var cache = Files.createDirectories(dir.resolve("cache with spaces"));
        var slot = Files.createDirectories(cache.resolve("orcad-0.1.0+example"));
        for (var marker : new String[]{".version", ".runtime-node", ".install-complete"}) {
            Files.writeString(slot.resolve(marker), "example");
        }
        var runtime = Files.createDirectories(cache.resolve("runtimes/node-example/bin"));
        Files.writeString(runtime.getParent().resolve(".verified"), "");
        var node = Files.writeString(runtime.resolve("node"), "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(node, PosixFilePermissions.fromString("rwxr-xr-x"));
        return cache;
    }

    @Test
    void defaultNeedsNoCache() throws Exception {
        var resolved = ParameterResolver.resolve(definition().getParameters(), Map.of());
        assertFalse(resolved.hasErrors());
        assertEquals("", resolved.resolvedValues().get("server-cache"));
        assertEquals(0, install("").exit());
        assertFalse(Files.exists(dir.resolve("home/.orca-remote")));
    }

    @Test
    void importsInstallationsWithoutMachineStateAndPreservesExecutable() throws Exception {
        var cache = cache();
        Files.writeString(cache.resolve("orcad-machine.json"), "source identity");
        Files.writeString(cache.resolve("orcad-activation.json"), "source activation");
        Files.createDirectories(cache.resolve("hosts/source-machine"));
        var result = install(cache.toString());
        assertEquals(0, result.exit(), result.output());
        var installed = dir.resolve("home/.orca-remote");
        assertTrue(Files.exists(installed.resolve("orcad-0.1.0+example/.install-complete")));
        assertTrue(Files.isExecutable(installed.resolve("runtimes/node-example/bin/node")));
        assertFalse(Files.exists(installed.resolve("orcad-machine.json")));
        assertFalse(Files.exists(installed.resolve("orcad-activation.json")));
        assertFalse(Files.exists(installed.resolve("hosts")));
    }

    @Test
    void missingCacheFails() throws Exception {
        assertNotEquals(0, install(dir.resolve("missing").toString()).exit());
    }

    @Test
    void incompleteSlotFailsBeforeCopying() throws Exception {
        var cache = cache();
        Files.delete(cache.resolve("orcad-0.1.0+example/.install-complete"));
        var result = install(cache.toString());
        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains(".install-complete is missing"));
        assertFalse(Files.exists(dir.resolve("home/.orca-remote")));
    }

    @Test
    void lockedSlotFailsBeforeCopying() throws Exception {
        var cache = cache();
        Files.createDirectory(cache.resolve("orcad-0.1.0+example/.install-lock"));
        var result = install(cache.toString());
        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("server installation is locked"));
        assertFalse(Files.exists(dir.resolve("home/.orca-remote")));
    }

    @Test
    void incompleteRuntimeFailsBeforeCopying() throws Exception {
        var cache = cache();
        Files.delete(cache.resolve("runtimes/node-example/.verified"));
        var result = install(cache.toString());
        assertNotEquals(0, result.exit());
        assertTrue(result.output().contains("incomplete Node runtime"));
        assertFalse(Files.exists(dir.resolve("home/.orca-remote")));
    }
}
