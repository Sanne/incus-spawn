package dev.incusspawn.vm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code appliance/test-proxy-remote.sh} boots the appliance with the gateway, MITM port and proxy
 * mode isx boots it with on macOS, copied by hand. If {@link VmManager#kernelCmdline()} moves
 * one of them, the script would go on passing on a shape no Mac boots (#1585).
 * {@code appliance/test-macos-role.sh} copies the whole command line and the VM's MAC (#1800): the
 * proxy finds the VM by that MAC in the leases file, and the guest's DNAT follows the command line.
 */
class RemoteProxyTestCmdlineTest {

    private static final Path SCRIPT = Path.of("../appliance/test-proxy-remote.sh");
    private static final Path MACOS_ROLE_SCRIPT = Path.of("../appliance/test-macos-role.sh");

    @Test
    void scriptBootsWithTheCmdlineIsxUsesOnMacOs() throws IOException {
        assumeTrue(unset("ISX_GATEWAY") && unset("ISX_MITM_PORT"), "the cmdline follows ISX_GATEWAY and ISX_MITM_PORT");
        var script = Files.readString(SCRIPT);
        var cmdline = VmManager.kernelCmdline();

        assertTrue(cmdline.contains(" isx.gateway=" + assignment(SCRIPT, script, "GATEWAY") + " "), cmdline);
        assertTrue(cmdline.contains(" isx.mitm_port=" + assignment(SCRIPT, script, "MITM_PORT") + " "), cmdline);
        assertTrue(cmdline.contains(" isx.proxy=remote"), cmdline);
        assertTrue(script.contains("isx.gateway=$GATEWAY isx.mitm_port=$MITM_PORT isx.proxy=remote"),
                "the script boots with its GATEWAY, MITM_PORT and remote proxy mode");
    }

    @Test
    void macosRoleScriptBootsTheVmIsxBootsOnMacOs() throws IOException {
        assumeTrue(unset("ISX_GATEWAY") && unset("ISX_MITM_PORT"), "the cmdline follows ISX_GATEWAY and ISX_MITM_PORT");
        var script = Files.readString(MACOS_ROLE_SCRIPT);
        assertEquals(VmNetwork.ISX_VM_MAC, assignment(MACOS_ROLE_SCRIPT, script, "VM_MAC"));
        var m = Pattern.compile("-append \"([^\"]*)\"").matcher(script);
        if (!m.find()) fail(MACOS_ROLE_SCRIPT + " no longer passes -append");
        // The console is QEMU's own, not the launcher's
        var booted = m.group(1).replace(" console=$CONSOLE", "").replace("$GATEWAY", assignment(MACOS_ROLE_SCRIPT, script, "GATEWAY"))
                .replace("$MITM_PORT", assignment(MACOS_ROLE_SCRIPT, script, "MITM_PORT"));
        // Less the console, the clock and the shared folder, which the script has no part of
        var cmdline = VmManager.kernelCmdline().replaceAll(" console=\\S+", "").replaceAll(" isx\\.time=\\d+", "")
                .replace(" isx.shared=/host", "");
        assertEquals(cmdline, booted, "the script's kernel command line is VmManager.kernelCmdline()'s, but for its console");
    }

    private static boolean unset(String name) {
        var value = System.getenv(name);
        return value == null || value.isBlank();
    }

    private static String assignment(Path path, String script, String name) {
        var m = Pattern.compile("(?m)^" + name + "=(\\S+)$").matcher(script);
        if (!m.find()) fail(path + " no longer sets " + name);
        return m.group(1);
    }
}
