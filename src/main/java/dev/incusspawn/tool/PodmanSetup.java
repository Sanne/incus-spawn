package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import jakarta.enterprise.context.Dependent;

@Dependent
public class PodmanSetup implements ToolSetup {

    @Override
    public String name() {
        return "podman";
    }

    @Override
    public void install(Container c) {
        System.out.println("Installing Podman...");
        c.dnfInstall("Failed to install Podman", "podman");

        // Rootless podman requires nested user namespaces, which need more host
        // subordinate UIDs than typical Incus installations provide (65536 is not
        // enough for both the container's own namespace and podman's nested one).
        // Since the container IS the security boundary, we run podman rootful via
        // a wrapper script. This is transparent to all callers including Testcontainers.
        c.sh("printf '#!/bin/sh\\nexec sudo /usr/bin/podman \"$@\"\\n' > /usr/local/bin/podman && " +
                "chmod +x /usr/local/bin/podman && " +
                "ln -sf /usr/local/bin/podman /usr/local/bin/docker");

        // Enable the podman socket so Testcontainers can discover it via
        // /var/run/docker.sock, and ensure /run/podman is traversable (755).
        // The tmpfiles rule is needed because /run is a tmpfs recreated on boot.
        c.sh("systemctl enable podman.socket && " +
                "echo 'd /run/podman 0755 root root -' > /etc/tmpfiles.d/podman-socket.conf && " +
                "ln -sf /run/podman/podman.sock /var/run/docker.sock");
    }
}
