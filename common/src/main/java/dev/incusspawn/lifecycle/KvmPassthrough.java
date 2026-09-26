package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.InstanceUpdate;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.BuildOutput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class KvmPassthrough {

    private static final List<String> DEVICES = List.of("kvm", "vhost-vsock");

    private KvmPassthrough() {}

    public static boolean configureKvm(IncusClient incus, String name) {
        if (!Files.exists(Path.of("/dev/kvm"))) {
            System.err.println("Error: /dev/kvm not found on the host.");
            if (Files.exists(Path.of("/sys/hypervisor")) || Files.exists(Path.of("/proc/xen"))) {
                System.err.println("This host appears to be a VM. Enable nested virtualization on the hypervisor,");
                System.err.println("then verify /dev/kvm is present before using --kvm.");
            } else {
                System.err.println("Ensure your CPU supports hardware virtualization (VT-x/AMD-V) and that");
                System.err.println("the kvm kernel module is loaded: sudo modprobe kvm_intel  (or kvm_amd)");
            }
            return false;
        }

        BuildOutput.step("Enabling KVM passthrough.");
        incus.devicesRemoveAll(name, DEVICES);
        incus.deviceAdd(name, "kvm", "unix-char",
                "source=/dev/kvm",
                "path=/dev/kvm");
        if (Files.exists(Path.of("/dev/vhost-vsock"))) {
            incus.deviceAdd(name, "vhost-vsock", "unix-char",
                    "source=/dev/vhost-vsock",
                    "path=/dev/vhost-vsock");
        }
        incus.configSet(name, Metadata.KVM_ENABLED, "true");
        return true;
    }

    /**
     * Add to {@code update} the removal of KVM passthrough an instance inherited, given its
     * current state. Contributes nothing when there is none, so an instance without KVM devices
     * costs no device rewrite.
     */
    public static void removeKvm(JsonNode instance, InstanceUpdate update) {
        for (var device : DEVICES) {
            if (instance.path("devices").has(device)) update.removeDevice(device);
        }
        if (instance.path("config").has(Metadata.KVM_ENABLED)) update.unset(Metadata.KVM_ENABLED);
    }
}
