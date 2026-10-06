package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.proxy.ProxyHealthCheck.ProxyStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx proxy status} and {@code isx vm status} off a terminal (#1037): the report is the
 * result and goes to stdout whatever it says, and the exit code is what a script checks.
 */
@ExtendWith(IsolatedHome.class)
class StatusCommandOutputTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
    private final PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);

    @Test
    void proxyDownIsReportedOnStdoutWithItsExitCode() {
        assertEquals(1, ProxyCommand.Status.reportDown(ProxyStatus.NOT_RUNNING, false, false, outStream));
        assertEquals(2, ProxyCommand.Status.reportDown(ProxyStatus.STALE_DNS, true, false, outStream));
        assertEquals(3, ProxyCommand.Status.reportDown(ProxyStatus.STALE_GATEWAY, true, true, outStream));
        assertEquals("""
                Proxy is not running.
                Start it with: isx proxy start
                Or install as a service: isx proxy install
                Proxy is not running, but DNS overrides are still active.
                Start the proxy to restore connectivity: isx proxy start
                Proxy is running, but on an old address of incusbr0 that instances cannot reach.
                Restart it to bind the current address: isx proxy restart
                """, out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void vmStatusFailsWhenIncusIsUnreachable() {
        var unreachable = new IncusClient() {
            @Override public String checkConnectivity() { return "no socket"; }
        };
        assertEquals(1, VmCommand.Status.report("VM not running", unreachable, outStream, errStream));
        assertEquals("VM not running\n", out.toString(StandardCharsets.UTF_8));
        assertEquals("\nIncus not reachable: no socket\n", err.toString(StandardCharsets.UTF_8));
    }
}
