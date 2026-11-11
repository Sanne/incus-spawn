package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The run with sudo refreshes apt's package lists before its first apt install, as the
 * {@code --no-sudo} script does (#2134 review): a fresh Debian or Ubuntu host may have none.
 */
@ExtendWith(IsolatedHome.class)
class InitAptRefreshTest {

    private static InitPlanTest.PlanInit host(String... commands) {
        var init = new InitPlanTest.PlanInit();
        init.commands = Set.of(commands);
        return init;
    }

    /** Once, before the first install, and not again for the next. */
    @Test
    void theFirstAptInstallRefreshesThePackageListsOnce() {
        var init = host("apt");
        var recorded = RecordingOutput.during(() -> {
            init.host().installDependencies();
            assertTrue(init.host().install("Installing firewalld", HostSetup.installCommand(init::hostHasCommand, "firewalld")));
        });
        assertEquals(List.of("sudo apt-get update", "sudo apt install -y openssl openssh-client btrfs-progs",
                "sudo apt install -y firewalld"), init.ran);
        assertTrue(recorded.events(RecordingOutput.Kind.WARN).isEmpty(), recorded.lines().toString());
    }

    /** A refresh that fails warns, and the install still runs from the lists there are. */
    @Test
    void aFailedRefreshWarnsAndTheInstallStillRuns() {
        var init = host("apt");
        init.exits.put("sudo apt-get update", 100);
        var recorded = RecordingOutput.during(() ->
                assertTrue(init.host().install("Installing Incus", HostSetup.installCommand(init::hostHasCommand, "incus"))));
        assertEquals(List.of("sudo apt-get update", "sudo apt install -y incus"), init.ran);
        assertTrue(recorded.events(RecordingOutput.Kind.WARN).stream()
                .anyMatch(e -> e.msg().equals(HostSetup.APT_REFRESH_FAILED)), recorded.lines().toString());
    }

    /** Another package manager refreshes as it installs: nothing is added. */
    @Test
    void dnfInstallsAsBefore() {
        var init = host("dnf");
        RecordingOutput.during(() -> init.host().installDependencies());
        assertEquals(List.of("sudo dnf install -y openssl openssh-clients btrfs-progs"), init.ran);
    }
}
