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
