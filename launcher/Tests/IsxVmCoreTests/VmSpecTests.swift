// The command line `isx-vm` reads (#2031): what isx writes for vfkit today, and nothing else.
import IsxVmCore
import XCTest

final class VmSpecTests: XCTestCase {
    // `VmManager.vfkitCommand`, word for word.
    private let fromIsx = [
        "--cpus", "4", "--memory", "8192",
        "--kernel", "/Users/u/.local/share/incus-spawn/vmlinuz",
        "--initrd", "/Users/u/.local/state/incus-spawn/dummy-initrd",
        "--kernel-cmdline", "root=/dev/vda rootfstype=btrfs rw rootflags=commit=300 console=hvc0 isx.time=1",
        "--device", "virtio-blk,path=/Users/u/.local/state/incus-spawn/disk.img",
        "--device", "virtio-blk,path=/Users/u/.local/state/incus-spawn/swap.img",
        "--device", "virtio-blk,path=/Users/u/.local/state/incus-spawn/data.img",
        "--device", "virtio-net,nat,mac=52:54:00:69:73:78",
        "--device", "virtio-serial,logFilePath=/Users/u/.local/state/incus-spawn/vm.log",
        "--device", "virtio-fs,sharedDir=/Users/u,mountTag=hostfs",
        "--device", "virtio-vsock,port=8443,socketURL=/Users/u/.local/state/incus-spawn/vm.incus.sock,connect",
        "--device", "virtio-vsock,port=1025,socketURL=/Users/u/.local/state/incus-spawn/vm.agent.sock,connect",
        "--timesync", "vsockPort=1024",
        "--restful-uri", "unix:///Users/u/.local/state/incus-spawn/vm.rest.sock",
    ]

    func testTheCommandLineIsxWritesIsRead() throws {
        let spec = try VmSpec(arguments: fromIsx)

        XCTAssertEqual(spec.cpus, 4)
        XCTAssertEqual(spec.memoryMiB, 8192)
        XCTAssertEqual(spec.kernel, "/Users/u/.local/share/incus-spawn/vmlinuz")
        XCTAssertEqual(spec.commandLine, "root=/dev/vda rootfstype=btrfs rw rootflags=commit=300 console=hvc0 isx.time=1")
        XCTAssertEqual(spec.disks, ["disk", "swap", "data"].map { "/Users/u/.local/state/incus-spawn/\($0).img" })
        XCTAssertTrue(spec.nat)
        XCTAssertEqual(spec.mac, "52:54:00:69:73:78")
        XCTAssertEqual(spec.consoleLog, "/Users/u/.local/state/incus-spawn/vm.log")
        XCTAssertEqual(spec.shares, [VmSpec.Share(directory: "/Users/u", tag: "hostfs")])
        XCTAssertEqual(spec.sockets, [
            VmSpec.Socket(port: 8443, path: "/Users/u/.local/state/incus-spawn/vm.incus.sock"),
            VmSpec.Socket(port: 1025, path: "/Users/u/.local/state/incus-spawn/vm.agent.sock"),
        ])
        XCTAssertEqual(spec.clockPort, 1024)
    }

    // `boot_vfkit` of appliance/test-boot.sh: no MAC address, no share, no guest agent.
    func testTheBareApplianceOfTheBootTestIsRead() throws {
        let spec = try VmSpec(arguments: [
            "--cpus", "2", "--memory", "2048", "--kernel", "build/vmlinuz", "--initrd", "/tmp/initrd",
            "--kernel-cmdline", "root=/dev/vda console=hvc0",
            "--device", "virtio-blk,path=build/disk.img",
            "--device", "virtio-net,nat",
            "--device", "virtio-serial,logFilePath=/tmp/boot.log",
            "--device", "virtio-vsock,port=8444,socketURL=/tmp/silent.sock,connect",
            "--restful-uri", "tcp://localhost:0",
        ])

        XCTAssertTrue(spec.nat)
        XCTAssertNil(spec.mac)
        XCTAssertEqual(spec.shares, [])
        XCTAssertEqual(spec.sockets, [VmSpec.Socket(port: 8444, path: "/tmp/silent.sock")])
        XCTAssertNil(spec.clockPort)
    }

    func testAPathMayHoldAnEqualsSign() throws {
        let spec = try VmSpec(arguments: ["--cpus", "1", "--memory", "512", "--kernel", "k",
                                          "--device", "virtio-blk,path=/tmp/a=b.img"])
        XCTAssertEqual(spec.disks, ["/tmp/a=b.img"])
    }

    // A machine that lacks something isx asked for must not start: whatever is not understood
    // is refused, and named.
    func testWhatIsNotUnderstoodIsRefusedByName() {
        let machine = ["--cpus", "1", "--memory", "512", "--kernel", "k"]
        func refusal(_ more: [String], from base: [String]? = nil) -> String {
            do {
                _ = try VmSpec(arguments: (base ?? machine) + more)
                return "accepted"
            } catch {
                return "\(error)"
            }
        }

        XCTAssertEqual(refusal(["--gui", "yes"]), "unknown option '--gui'")
        XCTAssertEqual(refusal(["vmlinuz"]), "unknown option 'vmlinuz'")
        XCTAssertEqual(refusal(["--kernel-cmdline"]), "--kernel-cmdline needs a value")
        XCTAssertEqual(refusal(["--cpus", "2"]), "--cpus is given twice")
        XCTAssertEqual(refusal(["--device", "virtio-gpu,width=800"]), "unknown device 'virtio-gpu'")
        XCTAssertEqual(refusal(["--device", "virtio-blk,path=/a,readonly"]),
                       "--device virtio-blk,path=/a,readonly: 'readonly' is not something virtio-blk takes")
        XCTAssertEqual(refusal(["--device", "virtio-blk"]), "--device virtio-blk: 'path' is required")
        // A comma in a path reads as another key.
        XCTAssertEqual(refusal(["--device", "virtio-fs,sharedDir=/Users/a,b,mountTag=hostfs"]),
                       "--device virtio-fs,sharedDir=/Users/a,b,mountTag=hostfs: 'b' is not something virtio-fs takes")
        XCTAssertEqual(refusal(["--device", "virtio-net,unixSocketPath=/tmp/net.sock"]),
                       "--device virtio-net,unixSocketPath=/tmp/net.sock: only 'nat' is supported")
        XCTAssertEqual(refusal(["--device", "virtio-net,nat", "--device", "virtio-net,nat"]),
                       "--device virtio-net,nat: a second network device")
        XCTAssertEqual(refusal(["--device", "virtio-vsock,port=8443,socketURL=/tmp/s,listen"]),
                       "--device virtio-vsock,port=8443,socketURL=/tmp/s,listen: only 'connect' is supported")
        XCTAssertEqual(refusal(["--device", "virtio-vsock,port=incus,socketURL=/tmp/s,connect"]),
                       "--device virtio-vsock,port=incus,socketURL=/tmp/s,connect: 'port' is not a number")
        XCTAssertEqual(refusal(["--device", "virtio-blk,path=/a,path=/b"]),
                       "--device virtio-blk,path=/a,path=/b: 'path' is given twice")
        XCTAssertEqual(refusal(["--timesync", "1024"]), "--timesync: '1024' is not a value it takes")
        XCTAssertEqual(refusal(["--memory", "2G"], from: ["--cpus", "1", "--kernel", "k"]),
                       "--memory: '2G' is not a value it takes")
        XCTAssertEqual(refusal([], from: ["--cpus", "1", "--memory", "512"]), "--kernel is required")
        XCTAssertEqual(refusal([], from: ["--memory", "512", "--kernel", "k"]), "--cpus is required")
        XCTAssertEqual(refusal([], from: ["--cpus", "0", "--memory", "512", "--kernel", "k"]),
                       "--cpus: '0' is not a value it takes")
    }
}
