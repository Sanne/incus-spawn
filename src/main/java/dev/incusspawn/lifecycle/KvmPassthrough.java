package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class KvmPassthrough {

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

        System.out.println("Enabling KVM passthrough...");
        incus.devicesRemoveAll(name, List.of("kvm"));
        incus.deviceAdd(name, "kvm", "unix-char",
                "source=/dev/kvm",
                "path=/dev/kvm");
        incus.configSet(name, Metadata.KVM_ENABLED, "true");
        return true;
    }

    public static void removeKvm(IncusClient incus, String name) {
        incus.devicesRemoveAll(name, List.of("kvm"));
        incus.configUpdate(name, Map.of(Metadata.KVM_ENABLED, null));
    }
}
