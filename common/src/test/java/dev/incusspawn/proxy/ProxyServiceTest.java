package dev.incusspawn.proxy;

import dev.incusspawn.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProxyServiceTest {

    @TempDir
    Path tempDir;

    private static final String PROXY_BIN = "/home/user/.local/bin/isx-proxy";

    @Test
    void checkJvmWrapperReturnsNullForNativeBinary() throws IOException {
        var binary = tempDir.resolve("isx");
        Files.write(binary, new byte[]{0x7f, 'E', 'L', 'F'});
        assertNull(ProxyService.checkJvmWrapper(binary.toString()));
    }

    @Test
    void checkJvmWrapperReturnsNullForWorkingJavaUnquoted() throws IOException {
        var javaBin = ProcessHandle.current().info().command().orElse(null);
        if (javaBin == null) return;

        var wrapper = tempDir.resolve("isx");
        Files.writeString(wrapper, "#!/bin/bash\nexec " + javaBin + " -jar /some/app.jar \"$@\"\n");
        assertNull(ProxyService.checkJvmWrapper(wrapper.toString()));
    }

    @Test
    void checkJvmWrapperReturnsNullForWorkingJavaQuoted() throws IOException {
        var javaBin = ProcessHandle.current().info().command().orElse(null);
        if (javaBin == null) return;

        var wrapper = tempDir.resolve("isx");
        Files.writeString(wrapper, "#!/bin/bash\nexec \"" + javaBin + "\" -jar /some/app.jar \"$@\"\n");
        assertNull(ProxyService.checkJvmWrapper(wrapper.toString()));
    }

    @Test
    void checkJvmWrapperDetectsMissingJavaBinary() throws IOException {
        var wrapper = tempDir.resolve("isx");
        Files.writeString(wrapper, "#!/bin/bash\nexec \"/nonexistent/java\" -jar /some/app.jar \"$@\"\n");
        var result = ProxyService.checkJvmWrapper(wrapper.toString());
        assertNotNull(result);
        assertTrue(result.contains("/nonexistent/java"));
        assertTrue(result.contains("not found"));
    }

    @Test
    void checkJvmWrapperReturnsNullForNonJavaWrapper() throws IOException {
        var wrapper = tempDir.resolve("isx");
        Files.writeString(wrapper, "#!/bin/bash\nexec /usr/bin/python3 app.py \"$@\"\n");
        assertNull(ProxyService.checkJvmWrapper(wrapper.toString()));
    }

    @Test
    void writeProxyStartScriptCreatesExecutableScript() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        ProxyService.writeProxyStartScript(script, PROXY_BIN);

        var content = Files.readString(script);
        assertTrue(content.startsWith("#!/bin/bash\n"));
        assertTrue(content.contains(PROXY_BIN));
        assertTrue(Files.isExecutable(script));
    }

    @Test
    void startScriptIncludesPath() {
        var content = ProxyService.proxyStartScriptContent(PROXY_BIN,
                "/usr/bin:/usr/local/bin:/opt/gcloud/bin");
        assertTrue(content.contains("export PATH='/usr/bin:/usr/local/bin:/opt/gcloud/bin'"));
    }

    @Test
    void startScriptUsesFallbackPathWhenNull() {
        var content = ProxyService.proxyStartScriptContent(PROXY_BIN, null);
        assertTrue(content.contains("export PATH="), "should use fallback PATH, not omit it");
    }

    @Test
    void oldScriptWithoutPathIsStale() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        Files.writeString(script, "#!/bin/bash\nexec '" + PROXY_BIN + "'\n");
        assertTrue(ProxyService.startScriptIsStale(script, PROXY_BIN),
                "scripts from before the PATH fix must be rewritten on upgrade");
    }

    @Test
    void pathDriftDoesNotCauseStaleness() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        // Install with one PATH
        Files.writeString(script, ProxyService.proxyStartScriptContent(
                PROXY_BIN, "/usr/bin:/usr/local/bin"));
        // Check staleness with the same binary but a different PATH (conda activated)
        assertFalse(ProxyService.startScriptIsStale(script, PROXY_BIN),
                "PATH differences alone must not trigger a restart");
    }

    @Test
    void writeProxyStartScriptEscapesSingleQuotes() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        ProxyService.writeProxyStartScript(script, "/home/user/it's here/isx-proxy");

        var content = Files.readString(script);
        assertTrue(content.contains("it"));
        assertTrue(content.contains("s here"));
        assertFalse(content.contains("it's"), "unescaped single quote would break the shell script");
    }

    // --- start script staleness -------------------------------------------------
    //
    // The isx binary path lives only in the start script, never in the unit, so an upgrade that
    // moves the binary (a distro package landing in /usr/bin over a previous ~/.local/bin install,
    // or the reverse) is invisible to a unit-text comparison. Checking the unit alone left the
    // service exec'ing the previous installation's binary forever.

    @Test
    void startScriptIsStaleWhenMissing() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        assertTrue(ProxyService.startScriptIsStale(script, PROXY_BIN));
    }

    @Test
    void startScriptIsNotStaleWhenItMatches() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        ProxyService.writeProxyStartScript(script, PROXY_BIN);
        assertFalse(ProxyService.startScriptIsStale(script, PROXY_BIN));
    }

    @Test
    void startScriptIsStaleWhenBinaryMoved() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        ProxyService.writeProxyStartScript(script, "/usr/bin/isx-proxy");
        assertTrue(ProxyService.startScriptIsStale(script, PROXY_BIN),
                "an upgrade that relocates the binary must be detected");
    }

    @Test
    void startScriptWithoutSgFallbackIsStaleOnLinux() throws IOException {
        assumeTrue(Platform.isLinux());
        var script = tempDir.resolve("proxy-start.sh");
        // Old-format script: has PATH and exec command but no sg fallback block
        Files.writeString(script, "#!/bin/bash\nexport PATH='/usr/bin'\n"
                + "exec '" + PROXY_BIN + "'\n");
        assertTrue(ProxyService.startScriptIsStale(script, PROXY_BIN),
                "scripts from before the sg fallback must be rewritten on upgrade");
    }

    // --- the self-restart loop (issue #701) ---------------------------------------
    //
    // A unit that execs `isx proxy start` restarts itself: that command finds the service
    // installed and unhealthy and restarts it, systemd starts it again, and `reset-failed` on
    // each pass clears the start rate limiter, so nothing ever breaks the cycle. The service must
    // exec the proxy binary directly, and an installation that still routes through the CLI must
    // be recognised as stale so an upgrade rewrites it.

    @Test
    void startScriptExecsProxyBinaryDirectly() {
        var content = ProxyService.proxyStartScriptContent(PROXY_BIN, "/usr/bin");
        assertTrue(content.contains("exec '" + PROXY_BIN + "'"),
                "the service must exec isx-proxy, not route back through the CLI");
        assertFalse(content.contains("proxy start"),
                "exec'ing `isx proxy start` makes the unit restart itself");
    }

    @Test
    void scriptThatRoutesThroughTheCliIsStale() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        // A script as written before this fix: correct PATH and sg block, but exec'ing the CLI.
        Files.writeString(script, "#!/bin/bash\nexport PATH='/usr/bin'\n"
                + ProxyService.sgFallbackBlock("exec '/home/user/.local/bin/isx' proxy start"));
        assertTrue(ProxyService.startScriptIsStale(script, PROXY_BIN),
                "upgrading must rewrite a script that still crash-loops");
    }

    @Test
    void haltingAnUnusableServiceNeverFiresOnLinux() {
        // The macOS halt removes a launch agent, so the platform gate is the only thing standing
        // between a Linux user and a deleted service file. On Linux the unit halts on its own:
        // RestartPreventExitStatus stops systemd retrying the EXIT_CONFIG failure.
        assumeTrue(Platform.isLinux());
        assertFalse(ProxyService.haltUnusableMacOsService());
    }

    @Test
    void macOsPlistLaunchesProxyBinaryDirectly() {
        var plist = ProxyService.generateProxyPlist(PROXY_BIN);
        assertTrue(plist.contains("<string>" + PROXY_BIN + "</string>"));
        assertFalse(plist.contains("<string>proxy</string>"),
                "launchd KeepAlive plus `isx proxy start` is the same self-restart loop");
    }

    // --- JBang launcher scripts ---------------------------------------------------

    @Test
    void checkJvmWrapperAcceptsJbangWrapperWhenJbangIsOnPath() throws IOException {
        var jbang = tempDir.resolve("jbang");
        Files.writeString(jbang, "#!/bin/sh\n");
        Files.setPosixFilePermissions(jbang, PosixFilePermissions.fromString("rwxr-xr-x"));

        var wrapper = tempDir.resolve("isx-proxy");
        Files.writeString(wrapper,
                "#!/bin/sh\nexec " + jbang + " run isx-proxy@Sanne/incus-spawn \"$@\"\n");
        assertNull(ProxyService.checkJvmWrapper(wrapper.toString()));
    }

    @Test
    void checkJvmWrapperDetectsJbangMissingFromPath() throws IOException {
        var wrapper = tempDir.resolve("isx-proxy");
        Files.writeString(wrapper,
                "#!/bin/sh\nexec " + tempDir.resolve("nope/jbang")
                        + " run isx-proxy@Sanne/incus-spawn \"$@\"\n");
        var result = ProxyService.checkJvmWrapper(wrapper.toString());
        assertNotNull(result);
        assertTrue(result.contains("JBang"));
        assertTrue(result.contains("PATH"),
                "the service inherits an install-time PATH, which is the actual failure mode");
    }

    @Test
    void unitTextCarriesNoBinaryPath() {
        // Pins the reason the script must be checked separately: two installations pointing at
        // different isx binaries produce byte-identical units, so comparing units can never
        // notice the difference. If the unit ever does embed the binary path, this test fails
        // and the staleness logic above should be revisited.
        var unit = ProxyService.serviceUnitContent();
        assertFalse(unit.contains("/usr/bin/isx"));
        assertFalse(unit.contains(".local/bin/isx"));
        assertTrue(unit.contains("proxy-start.sh"),
                "the unit should exec the start script, which is what holds the binary path");
    }

    // --- exit-code interpretation ------------------------------------------------

    @Test
    void describeExitDecodesSignalKill() {
        var desc = ProxyService.describeExit(128 + 9);
        assertTrue(desc.contains("SIGKILL"));
        assertTrue(desc.contains("kill -9"));
        if (Platform.isLinux()) {
            assertTrue(desc.contains("journalctl"), "Linux hint should mention journalctl");
            assertTrue(desc.contains("OOM killer"));
        } else if (Platform.isMacOS()) {
            assertTrue(desc.contains("jetsam"), "macOS hint should mention jetsam");
        }
    }

    @Test
    void describeExitDecodesSignalTerm() {
        var desc = ProxyService.describeExit(128 + 15);
        assertTrue(desc.contains("SIGTERM"));
    }

    @Test
    void describeExitDecodesSignalInt() {
        var desc = ProxyService.describeExit(128 + 2);
        assertTrue(desc.contains("SIGINT"));
    }

    @Test
    void describeExitDecodesSignalHup() {
        var desc = ProxyService.describeExit(128 + 1);
        assertTrue(desc.contains("SIGHUP"));
    }

    @Test
    void describeExitHandlesUnknownSignal() {
        var desc = ProxyService.describeExit(128 + 6);
        assertTrue(desc.contains("signal 6"));
    }

    @Test
    void describeExitDecodesConfigError() {
        var desc = ProxyService.describeExit(ProxyService.EXIT_CONFIG);
        assertTrue(desc.contains("configuration"));
        assertTrue(desc.contains("isx init"));
    }

    @Test
    void describeExitHandlesGenericNonZero() {
        var desc = ProxyService.describeExit(1);
        assertTrue(desc.contains("exit 1"));
    }

    // --- systemd restart policy -------------------------------------------------
    //
    // A misconfiguration the user must fix by hand (init not run, Vertex fields blank) exits
    // EXIT_CONFIG. RestartPreventExitStatus stops systemd retrying it forever and burying the
    // reason in the journal. Transient failures still exit 1 and are still retried.

    @Test
    void generatedUnitPreventsRestartOnConfigError() {
        var unit = ProxyService.serviceUnitContent();
        assertTrue(unit.contains("Restart=on-failure"),
                "transient failures must still be retried");
        assertTrue(unit.contains(ProxyService.RESTART_PREVENT_LINE),
                "config errors must not crash-loop");
        // The directive is derived from EXIT_CONFIG, so this also pins the unit text to the
        // exit code the proxy actually returns — the two cannot drift apart.
        assertEquals("RestartPreventExitStatus=78", ProxyService.RESTART_PREVENT_LINE);
    }

    @Test
    void generatedUnitIsStructurallyWellFormed() {
        var unit = ProxyService.serviceUnitContent();
        assertTrue(unit.contains("[Unit]"));
        assertTrue(unit.contains("[Service]"));
        assertTrue(unit.contains("[Install]"));
        assertTrue(unit.contains("ExecStart="));
        assertFalse(unit.contains("%s"), "format placeholder left unsubstituted");
        // The restart policy lines belong together.
        assertTrue(unit.contains("Restart=on-failure\nRestartPreventExitStatus=78\nRestartSec=5"));
    }

    @Test
    void generatedUnitDoesNotRequireSg() {
        var unit = ProxyService.serviceUnitContent();
        assertFalse(unit.contains("sg incus-admin"),
                "sg belongs in the start script, not the systemd unit");
    }

    // --- sg fallback in start script ----------------------------------------------

    @Test
    void startScriptHasSgFallbackOnLinux() {
        assumeTrue(Platform.isLinux());
        var content = ProxyService.proxyStartScriptContent("/home/user/.local/bin/isx",
                "/usr/bin:/usr/local/bin");
        assertTrue(content.contains("command -v sg"),
                "start script should check for sg availability");
        assertTrue(content.contains("sg incus-admin"),
                "start script should use sg when available");
        assertTrue(content.contains("id -nG"),
                "start script should check group membership when sg is absent");
        assertTrue(content.contains("exit 78"),
                "start script should exit with EX_CONFIG when group is missing");
    }

    @Test
    void sgFallbackBlockContainsActionableErrors() {
        var block = ProxyService.sgFallbackBlock("exec '/usr/bin/isx' proxy start");
        assertTrue(block.contains("incus-admin"));
        assertTrue(block.contains("log out and log back in"),
                "both error paths should suggest re-login");
        assertTrue(block.contains("isx init"),
                "not-a-member path should suggest isx init");
        // Two distinct exit 78 paths: configured-but-inactive and not-a-member
        assertEquals(2, block.split("exit 78").length - 1,
                "should have two exit 78 paths");
    }

    @Test
    void sgFallbackBlockChecksActiveGroupThenConfiguredGroup() {
        var block = ProxyService.sgFallbackBlock("exec '/usr/bin/isx' proxy start");
        // Active-group check (id -nG, no argument) must come first for direct exec
        int activeCheck = block.indexOf("id -nG |");
        // Configured-group check (id -nG "$(id -un)") comes second for sg fallback
        int configuredCheck = block.indexOf("id -nG \"$(id -un)\"");
        assertTrue(activeCheck < configuredCheck,
                "active-group check must precede configured-group check");
        assertTrue(configuredCheck < block.indexOf("command -v sg"),
                "configured-group check must precede sg attempt");
        // sg branch: exec sg ... -c '<command>'
        assertTrue(block.contains("exec sg incus-admin -c"));
    }

    @Test
    void sgFallbackBlockDirectExecsWhenGroupActive() {
        var block = ProxyService.sgFallbackBlock("exec '/usr/bin/isx' proxy start");
        // The first branch (group already active) should exec the command directly
        int activeCheck = block.indexOf("id -nG |");
        int firstExecCmd = block.indexOf("exec '/usr/bin/isx' proxy start");
        int sgCheck = block.indexOf("command -v sg");
        assertTrue(firstExecCmd > activeCheck && firstExecCmd < sgCheck,
                "direct exec should be in the active-group branch, before sg");
    }

    @Test
    void sgFallbackBlockUsesExactGroupMatch() {
        var block = ProxyService.sgFallbackBlock("exec '/usr/bin/isx' proxy start");
        assertTrue(block.contains("grep -qx incus-admin"),
                "must use exact-line match to avoid false positives on incus-admin-testing");
        assertFalse(block.contains("grep -qw"),
                "word-boundary match would false-positive on hyphenated group names");
    }
}
