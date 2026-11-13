// The machine and the relays in front of its vsock ports (#2031).
//
// Every setting here is chosen for isx and is one of the decisions of design/macos-and-vms.md
// "The launcher's machine"; vfkit's choice is named where this one differs.
import Dispatch
import Foundation
import IsxRelay
import IsxVmCore
import Virtualization

// A line written whole and unbuffered, so a script that waits for one sees it when it is said.
@Sendable func write(_ line: String, to descriptor: Int32) {
    let bytes = Array("\(line)\n".utf8)
    _ = bytes.withUnsafeBytes { Darwin.write(descriptor, $0.baseAddress, $0.count) }
}

@Sendable func say(_ line: String) { write(line, to: STDOUT_FILENO) }

@Sendable func complain(_ line: String) { write("isx-vm: \(line)", to: STDERR_FILENO) }

@Sendable func fail(_ message: String, status: Int32) -> Never {
    complain(message)
    exit(status)
}

struct Unusable: Error, CustomStringConvertible {
    let description: String
}

/// The guest connections that are open, by descriptor.
///
/// The framework closes a connection's descriptor when its `VZVirtioSocketConnection` is
/// closed or released, so the object is kept for as long as the relay uses the descriptor and
/// closed through the framework, once. vfkit's library does neither: it copies the descriptor,
/// closes the framework's own and lets the object go.
final class GuestConnections: @unchecked Sendable {
    private let lock = NSLock()
    private var open: [Int32: VZVirtioSocketConnection] = [:]

    func hold(_ connection: VZVirtioSocketConnection) -> Int32 {
        let descriptor = connection.fileDescriptor
        lock.withLock { open[descriptor] = connection }
        return descriptor
    }

    /// Taken out before it is closed, so the number is not in here when another connection gets it.
    func hangUp(_ descriptor: Int32) {
        let connection = lock.withLock { open.removeValue(forKey: descriptor) }
        connection?.close()
    }
}

/// What a dial waits for: written before the semaphore is signalled and read after it.
private final class Answer: @unchecked Sendable {
    var descriptor: Int32 = -1
}

/// The machine, the relays and what happens on a wake.
///
/// The framework's objects are used on the main queue alone, which is the queue the machine is
/// made on. Everything else here that changes is touched there too. The relays' threads reach
/// the guest through `dial`, which asks the main queue and waits for it; nothing on the main
/// queue ever waits for a relay.
final class Launcher: NSObject, VZVirtualMachineDelegate, @unchecked Sendable {
    private let spec: VmSpec
    private let machine: VZVirtualMachine
    private let guestSockets: VZVirtioSocketDevice?
    private let connections = GuestConnections()
    private var served: [(path: String, relay: Relay)] = []
    private var watch = SleepWatch()
    /// Sets the guest's clock, one wake after another: its agent serves one client at a time.
    private let clockQueue = DispatchQueue(label: "isx-vm.clock")
    private var sources: [any DispatchSourceProtocol] = []
    private var stopping = false

    @MainActor init(_ spec: VmSpec) throws {
        self.spec = spec
        machine = VZVirtualMachine(configuration: try Launcher.configuration(spec))
        guestSockets = machine.socketDevices.first as? VZVirtioSocketDevice
        super.init()
        machine.delegate = self
    }

    @MainActor private static func configuration(_ spec: VmSpec) throws -> VZVirtualMachineConfiguration {
        let configuration = VZVirtualMachineConfiguration()
        configuration.cpuCount = spec.cpus
        configuration.memorySize = spec.memoryMiB << 20

        // No initial ramdisk: the appliance kernel is built without support for one
        // (CONFIG_BLK_DEV_INITRD=n) and the framework does not ask for one. vfkit does.
        let boot = VZLinuxBootLoader(kernelURL: URL(fileURLWithPath: spec.kernel))
        boot.commandLine = spec.commandLine
        configuration.bootLoader = boot

        // Every disk as vfkit attaches it: the host's page cache in front of the image, and a
        // guest flush carried out as fsync(). The measurement compares relays, so the disks
        // must not differ. A policy for each disk (the swap disk needs no durability) needs
        // arguments that say which disk is which, and is the shipping launcher's to choose.
        var disks: [VZStorageDeviceConfiguration] = []
        for path in spec.disks {
            let image = try VZDiskImageStorageDeviceAttachment(
                url: URL(fileURLWithPath: path), readOnly: false,
                cachingMode: .cached, synchronizationMode: .fsync)
            disks.append(VZVirtioBlockDeviceConfiguration(attachment: image))
        }
        configuration.storageDevices = disks

        if spec.nat {
            let network = VZVirtioNetworkDeviceConfiguration()
            network.attachment = VZNATNetworkDeviceAttachment()
            if let mac = spec.mac {
                // isx's fixed address, which the guest's lease on the host's NAT follows.
                guard let address = VZMACAddress(string: mac) else {
                    throw Unusable(description: "'\(mac)' is not a MAC address")
                }
                network.macAddress = address
            }
            configuration.networkDevices = [network]
        }

        if let log = spec.consoleLog {
            // Not appended to: isx reads a boot's console from the start of the file.
            let console = VZVirtioConsoleDeviceSerialPortConfiguration()
            console.attachment = try VZFileSerialPortAttachment(url: URL(fileURLWithPath: log), append: false)
            configuration.serialPorts = [console]
        }

        // Read-only at the hypervisor. vfkit shares it writable and only the guest's `-o ro`
        // mount keeps it read-only, which root in the guest can undo (crc-org/vfkit#495).
        var shares: [VZDirectorySharingDeviceConfiguration] = []
        for share in spec.shares {
            try VZVirtioFileSystemDeviceConfiguration.validateTag(share.tag)
            let device = VZVirtioFileSystemDeviceConfiguration(tag: share.tag)
            let directory = VZSharedDirectory(url: URL(fileURLWithPath: share.directory), readOnly: true)
            device.share = VZSingleDirectoryShare(directory: directory)
            shares.append(device)
        }
        configuration.directorySharingDevices = shares

        configuration.socketDevices = [VZVirtioSocketDeviceConfiguration()]

        // The host's randomness for the guest's pool from the first moment of the boot. The
        // appliance kernel has the driver (CONFIG_HW_RANDOM_VIRTIO); vfkit gives it no device.
        configuration.entropyDevices = [VZVirtioEntropyDeviceConfiguration()]

        try configuration.validate()
        return configuration
    }

    /// Runs `body` on the main queue.
    private nonisolated func onMain(_ body: @escaping @MainActor @Sendable () -> Void) {
        DispatchQueue.main.async { MainActor.assumeIsolated(body) }
    }

    @MainActor func start() {
        machine.start { @Sendable result in
            switch result {
            case .failure(let error):
                fail("the machine did not start: \(error.localizedDescription)", status: 1)
            case .success:
                self.onMain { self.serve() }
            }
        }
    }

    // Once the machine runs, so a start that failed leaves no socket that answers nobody.
    @MainActor private func serve() {
        // A copy has the watch's start, and that is all a stamp reads.
        let clocks = watch
        for socket in spec.sockets {
            let path = socket.path, port = socket.port
            let relay = Relay(
                dial: { self.dial(port: port) },
                hangUp: { self.connections.hangUp($0) },
                log: { complain("\(path): \($0)") },
                stamp: { clocks.ahead() })
            let listener: Int32
            do {
                listener = try Relay.listen(at: path)
            } catch {
                fail("\(error)", status: 1)
            }
            let serving = Relay.detach {
                relay.serve(listener: listener)
                fail("\(path): stopped accepting", status: 1)
            }
            guard serving else { fail("\(path): no thread to accept on", status: 1) }
            served.append((path, relay))
        }
        say("relaying \(served.count) sockets to the guest")

        // Asked every two seconds, with some leeway so the wake-up can share another's.
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now() + 2, repeating: 2.0, leeway: .milliseconds(500))
        timer.setEventHandler { self.onMain { self.wakeIfSlept() } }
        timer.resume()
        sources.append(timer)
    }

    /// Opens a connection to a port of the guest and returns its descriptor, or -1 when the
    /// guest does not take it. For any thread but the main one, which it waits for.
    nonisolated func dial(port: UInt32) -> Int32 {
        let answered = DispatchSemaphore(value: 0)
        let answer = Answer()
        onMain {
            self.connect(port) { descriptor in
                answer.descriptor = descriptor
                answered.signal()
            }
        }
        answered.wait()
        return answer.descriptor
    }

    @MainActor private func connect(_ port: UInt32, _ done: @escaping @Sendable (Int32) -> Void) {
        guard let guestSockets else { return done(-1) }
        let connections = self.connections
        guestSockets.connect(toPort: port) { @Sendable result in
            switch result {
            case .success(let connection): done(connections.hold(connection))
            case .failure: done(-1)
            }
        }
    }

    // A host that slept: a connection may have died on either side meanwhile, so every one
    // accepted before the sleep is ended on both sides and isx makes new ones. And the guest
    // did not run, so its clock is behind by the time asleep.
    //
    // The wake is seen up to two and a half seconds late, and a connection isx made in that
    // time is kept: its stamp says it was accepted after the sleep.
    //
    // What dropping gives up: a connection that would have survived a short sleep, an open
    // `isx shell` say, is cut on every wake.
    @MainActor private func wakeIfSlept() {
        guard let asleep = watch.slept() else { return }
        let limit = watch.wakeLimit
        var dropped = 0
        for one in served { dropped += one.relay.dropAll(stampedBefore: limit) }
        say("woke after \(asleep.components.seconds) s asleep, dropped \(dropped) connections")
        guard let port = spec.clockPort else { return }
        // In turn: a second wake while the clock is being set waits for that to end, and then
        // sets it again, since the guest stood still once more.
        clockQueue.async { self.setGuestClock(port: port) }
    }

    // A connection of its own for each wake, tried again for a few seconds: the guest is
    // waking too. vfkit keeps one connection for all of them, and stops for good when that
    // one cannot be made.
    private nonisolated func setGuestClock(port: UInt32) {
        for _ in 0..<5 {
            let agent = dial(port: port)
            if agent >= 0 {
                let reply = GuestClock.set(on: agent)
                shutdown(agent, Int32(SHUT_RDWR))
                connections.hangUp(agent)
                if let reply {
                    if GuestClock.accepted(reply) {
                        say("set the guest's clock")
                    } else {
                        complain("the guest agent did not set the clock: \(reply)")
                    }
                    return
                }
            }
            sleep(1)
        }
        complain("the guest's clock is not set: its agent on vsock port \(port) did not answer")
    }

    /// The counts of every relay, one line each, as `isx-vsock-bridge` prints them.
    @MainActor private func report() {
        for one in served { say("\(one.path) \(one.relay.counts)") }
    }

    // The guest is not asked: isx asks it through its own agent first and sends a signal only
    // when that did not end it. vfkit presses a power button the appliance does not have and
    // waits five seconds before it does this.
    //
    // A second signal while that stop is under way is not a reason to leave before it ends.
    @MainActor private func stop() {
        if stopping { return }
        guard machine.canStop else { exit(0) }
        stopping = true
        machine.stop { @Sendable error in
            if let error { fail("stopping the machine: \(error.localizedDescription)", status: 1) }
            exit(0)
        }
    }

    /// SIGTERM and SIGINT stop the machine, SIGUSR1 prints the relays' counts.
    @MainActor func watchSignals() {
        // Ignored first, so the source below is all that takes one.
        for number in [SIGTERM, SIGINT, SIGUSR1] { signal(number, SIG_IGN) }
        func on(_ number: Int32, _ body: @escaping @MainActor @Sendable () -> Void) {
            let source = DispatchSource.makeSignalSource(signal: number, queue: .main)
            source.setEventHandler { self.onMain(body) }
            source.resume()
            sources.append(source)
        }
        on(SIGTERM) { self.stop() }
        on(SIGINT) { self.stop() }
        on(SIGUSR1) { self.report() }
    }

    // The launcher lives as long as its machine: isx waits for this process to know that the
    // guest has powered off.
    func guestDidStop(_ virtualMachine: VZVirtualMachine) {
        exit(0)
    }

    func virtualMachine(_ virtualMachine: VZVirtualMachine, didStopWithError error: any Error) {
        fail("the machine stopped: \(error.localizedDescription)", status: 1)
    }
}
