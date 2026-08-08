package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProxyServiceTest {

    @TempDir
    Path tempDir;

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
        ProxyService.writeProxyStartScript(script, "/home/user/.local/bin/isx");

        var content = Files.readString(script);
        assertTrue(content.startsWith("#!/bin/bash\n"));
        assertTrue(content.contains("/home/user/.local/bin/isx"));
        assertTrue(content.contains("proxy start"));
        assertTrue(Files.isExecutable(script));
    }

    @Test
    void writeProxyStartScriptEscapesSingleQuotes() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        ProxyService.writeProxyStartScript(script, "/home/user/it's here/isx");

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
        assertTrue(ProxyService.startScriptIsStale(script, "/home/user/.local/bin/isx"));
    }

    @Test
    void startScriptIsNotStaleWhenItMatches() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        ProxyService.writeProxyStartScript(script, "/home/user/.local/bin/isx");
        assertFalse(ProxyService.startScriptIsStale(script, "/home/user/.local/bin/isx"));
    }

    @Test
    void startScriptIsStaleWhenBinaryMoved() throws IOException {
        var script = tempDir.resolve("proxy-start.sh");
        ProxyService.writeProxyStartScript(script, "/usr/bin/isx");
        assertTrue(ProxyService.startScriptIsStale(script, "/home/user/.local/bin/isx"),
                "an upgrade that relocates the binary must be detected");
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
}
