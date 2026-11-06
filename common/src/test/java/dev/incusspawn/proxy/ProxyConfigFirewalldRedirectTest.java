package dev.incusspawn.proxy;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx proxy uninstall} removes the loaded firewalld redirect even where only root may read
 * the direct rules (a server Fedora's polkit policy), rather than cleaning direct.xml alone (#1441).
 */
class ProxyConfigFirewalldRedirectTest {

    private static final String RULE =
            "ipv4 nat PREROUTING 0 -i incusbr0 -d 10.0.0.9 -p tcp --dport 443 -j REDIRECT --to-port 18443";

    /** The user's query fails; the second, standing for {@code sudo -n}, answers with the rule. */
    private static final List<List<String>> READABLE_ONLY_AS_ROOT =
            List.of(List.of("false"), List.of("echo", RULE));

    @Test
    void aRuleReadableOnlyThroughSudoIsRemovedFromTheRunningFirewall() {
        var ran = new ArrayList<String>();
        var fellBack = new boolean[1];
        var cleared = new boolean[1];
        RecordingOutput.during(() -> cleared[0] = ProxyConfig.clearFirewalldRedirect(READABLE_ONLY_AS_ROOT,
                command -> ran.add(String.join(" ", command)),
                () -> fellBack[0] = true));

        assertTrue(cleared[0]);
        assertFalse(fellBack[0], "the direct.xml fallback leaves the loaded rule in the kernel");
        assertEquals(List.of(
                "sudo firewall-cmd --permanent --direct --remove-rule ipv4 nat PREROUTING 0 -i incusbr0 -d 10.0.0.9"
                        + " -p tcp --dport 443 -j REDIRECT --to-port 18443",
                "sudo firewall-cmd --reload"), ran);
    }

    @Test
    void rulesNoQueryCanReadFallBackToDirectXml() {
        var ran = new ArrayList<String>();
        var fellBack = new boolean[1];
        RecordingOutput.during(() -> ProxyConfig.clearFirewalldRedirect(List.of(List.of("false")),
                command -> ran.add(String.join(" ", command)),
                () -> fellBack[0] = true));

        assertTrue(fellBack[0]);
        assertEquals(List.of(), ran);
    }
}
