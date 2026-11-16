// The command line `isx-vm` reads (#2031): what isx writes, and nothing else.
import IsxVmCore
import XCTest

final class VmSpecTests: XCTestCase {
    // `VmManager.launcherCommand`, word for word.
    private let fromIsx = [
        "--cpus", "4", "--memory", "8192",
        "--kernel", "/Users/u/.local/share/incus-spawn/vmlinuz",
        "--cmdline", "root=/dev/vda rootfstype=btrfs rw rootflags=commit=300 console=hvc0 isx.time=1",
        "--disk", "/Users/u/.local/state/incus-spawn/disk.img",
        "--disk", "/Users/u/.local/state/incus-spawn/swap.img",
        "--disk", "/Users/u/.local/state/incus-spawn/data.img",
        "--nat", "52:54:00:69:73:78",
        "--console", "/Users/u/.local/state/incus-spawn/vm.log",
        "--share", "hostfs=/Users/u",
        "--vsock", "8443=/Users/u/.local/state/incus-spawn/vm.incus.sock",
        "--vsock", "1025=/Users/u/.local/state/incus-spawn/vm.agent.sock",
        "--clock-port", "1024",
        "--control", "/Users/u/.local/state/incus-spawn/vm.control.sock",
    ]

    func testTheCommandLineIsxWritesIsRead() throws {
        let spec = try VmSpec(arguments: fromIsx)

        XCTAssertEqual(spec.cpus, 4)
        XCTAssertEqual(spec.memoryMiB, 8192)
        XCTAssertEqual(spec.kernel, "/Users/u/.local/share/incus-spawn/vmlinuz")
        XCTAssertEqual(spec.commandLine, "root=/dev/vda rootfstype=btrfs rw rootflags=commit=300 console=hvc0 isx.time=1")
        XCTAssertEqual(spec.disks, ["disk", "swap", "data"].map { "/Users/u/.local/state/incus-spawn/\($0).img" })
        XCTAssertEqual(spec.mac, "52:54:00:69:73:78")
        XCTAssertEqual(spec.consoleLog, "/Users/u/.local/state/incus-spawn/vm.log")
        XCTAssertEqual(spec.shares, [VmSpec.Share(directory: "/Users/u", tag: "hostfs")])
        XCTAssertEqual(spec.sockets, [
            VmSpec.Socket(port: 8443, path: "/Users/u/.local/state/incus-spawn/vm.incus.sock"),
            VmSpec.Socket(port: 1025, path: "/Users/u/.local/state/incus-spawn/vm.agent.sock"),
        ])
        XCTAssertEqual(spec.clockPort, 1024)
        XCTAssertEqual(spec.control, "/Users/u/.local/state/incus-spawn/vm.control.sock")
    }

    // `boot_launcher` of appliance/test-boot.sh: no network address, no share, no guest agent.
    func testTheBareApplianceOfTheBootTestIsRead() throws {
        let spec = try VmSpec(arguments: [
            "--cpus", "2", "--memory", "2048", "--kernel", "build/vmlinuz",
            "--cmdline", "root=/dev/vda console=hvc0",
            "--disk", "build/disk.img",
            "--console", "/tmp/boot.log",
            "--vsock", "8444=/tmp/silent.sock",
        ])

        XCTAssertNil(spec.mac)
        XCTAssertEqual(spec.shares, [])
        XCTAssertEqual(spec.sockets, [VmSpec.Socket(port: 8444, path: "/tmp/silent.sock")])
        XCTAssertNil(spec.clockPort)
        XCTAssertNil(spec.control)
    }

    func testAPathMayHoldAnEqualsSignOrAComma() throws {
        let spec = try VmSpec(arguments: ["--cpus", "1", "--memory", "512", "--kernel", "k",
                                          "--disk", "/tmp/a=b,c.img", "--share", "hostfs=/Users/a=b,c",
                                          "--vsock", "1025=/tmp/x=y.sock"])
        XCTAssertEqual(spec.disks, ["/tmp/a=b,c.img"])
        XCTAssertEqual(spec.shares, [VmSpec.Share(directory: "/Users/a=b,c", tag: "hostfs")])
        XCTAssertEqual(spec.sockets, [VmSpec.Socket(port: 1025, path: "/tmp/x=y.sock")])
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
        XCTAssertEqual(refusal(["--cmdline"]), "--cmdline needs a value")
        XCTAssertEqual(refusal(["--cpus", "2"]), "--cpus is given twice")
        XCTAssertEqual(refusal(["--nat", "a", "--nat", "b"]), "--nat is given twice")
        XCTAssertEqual(refusal(["--console", "a", "--console", "b"]), "--console is given twice")
        // vfkit's form is not read.
        XCTAssertEqual(refusal(["--device", "virtio-blk,path=/a"]), "unknown option '--device'")
        XCTAssertEqual(refusal(["--timesync", "vsockPort=1024"]), "unknown option '--timesync'")
        XCTAssertEqual(refusal(["--disk", ""]), "--disk: '' is not a value it takes")
        XCTAssertEqual(refusal(["--share", "/Users/u"]), "--share: '/Users/u' is not a value it takes")
        XCTAssertEqual(refusal(["--share", "=/Users/u"]), "--share: '=/Users/u' is not a value it takes")
        XCTAssertEqual(refusal(["--share", "hostfs="]), "--share: 'hostfs=' is not a value it takes")
        XCTAssertEqual(refusal(["--vsock", "incus=/tmp/s"]), "--vsock: 'incus=/tmp/s' is not a value it takes")
        XCTAssertEqual(refusal(["--vsock", "8443"]), "--vsock: '8443' is not a value it takes")
        XCTAssertEqual(refusal(["--vsock", "8443=/a", "--vsock", "8443=/b"]), "--vsock: '8443=/b' is not a value it takes")
        XCTAssertEqual(refusal(["--vsock", "8443=/a", "--vsock", "1025=/a"]), "--vsock: '1025=/a' is not a value it takes")
        XCTAssertEqual(refusal(["--clock-port", "ga"]), "--clock-port: 'ga' is not a value it takes")
        XCTAssertEqual(refusal(["--memory", "2G"], from: ["--cpus", "1", "--kernel", "k"]),
                       "--memory: '2G' is not a value it takes")
        XCTAssertEqual(refusal(["--memory", "17592186044416"], from: ["--cpus", "1", "--kernel", "k"]),
                       "--memory: '17592186044416' is not a value it takes")
        // An option that is not read is named as that, with a value after it or without.
        XCTAssertEqual(refusal(["--gui"]), "unknown option '--gui'")
        XCTAssertEqual(refusal(["--gui", "--cpus", "2"]), "unknown option '--gui'")
        XCTAssertEqual(refusal([], from: ["--cpus", "1", "--memory", "512"]), "--kernel is required")
        XCTAssertEqual(refusal([], from: ["--memory", "512", "--kernel", "k"]), "--cpus is required")
        XCTAssertEqual(refusal([], from: ["--cpus", "0", "--memory", "512", "--kernel", "k"]),
                       "--cpus: '0' is not a value it takes")
    }
}
