package dev.incusspawn.command;

import dev.incusspawn.vm.VmManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DoctorCommandTest {

    @Test
    void unmeasurableCountIsOkAndOffersNoFix() {
        var f = DoctorCommand.forwarderFinding(-1);
        assertEquals(DoctorCommand.Status.OK, f.status());
        assertNull(f.remediation());
    }

    @Test
    void countAtOrBelowThresholdIsOk() {
        assertEquals(DoctorCommand.Status.OK, DoctorCommand.forwarderFinding(1).status());
        assertEquals(DoctorCommand.Status.OK,
                DoctorCommand.forwarderFinding(VmManager.VSOCK_CONN_WARN_THRESHOLD).status(),
                "exactly at threshold must not warn");
    }

    @Test
    void countAboveThresholdWarnsWithDestructiveRemediation() {
        var f = DoctorCommand.forwarderFinding(VmManager.VSOCK_CONN_WARN_THRESHOLD + 1);
        assertEquals(DoctorCommand.Status.WARN, f.status());
        assertNotNull(f.remediation(), "a leak must offer a remediation");
        assertTrue(f.remediation().destructive(), "VM restart is disruptive and must be flagged");
        assertTrue(f.label().contains(String.valueOf(VmManager.VSOCK_CONN_WARN_THRESHOLD + 1)),
                "label should report the actual count");
    }
}
