// What the relay owes the tunnel (#2031), with the guest played by the test: every dial hands
// the relay one end of a socketpair and the test keeps the other, so it sees exactly what a
// guest would, without a VM.
import Foundation
import IsxRelay
import Synchronization
import XCTest

#if canImport(Glibc)
import Glibc
private let sockStream = Int32(SOCK_STREAM.rawValue)
private let descriptorLimit = __rlimit_resource_t(RLIMIT_NOFILE.rawValue)
private let processUsage = RUSAGE_SELF.rawValue
#else
import Darwin
private let sockStream = SOCK_STREAM
private let descriptorLimit = RLIMIT_NOFILE
private let processUsage = RUSAGE_SELF
#endif

final class RelayTests: XCTestCase {
    func testBytesCrossBothWays() throws {
        let rig = try Rig()
        defer { rig.close() }
        let client = try rig.newClient()
        defer { close(client) }
        XCTAssertTrue(writeAll(client, Array("ping".utf8)))
        let guest = try rig.guestEnd(0)
        defer { close(guest) }
        XCTAssertEqual(readSome(guest), Array("ping".utf8))
        XCTAssertTrue(writeAll(guest, Array("pong".utf8)))
        XCTAssertEqual(readSome(client), Array("pong".utf8))
        XCTAssertEqual(rig.relay.counts, Relay.Counts(held: 1, total: 1, refused: 0))
    }

    // The fault of #2031: through vfkit the guest's end stays open after isx closed its own, and
    // the guest's forwarder keeps a process for it until the daemon behind gives up on it.
    func testAHostCloseReachesTheGuest() throws {
        let rig = try Rig()
        defer { rig.close() }
        let client = try rig.newClient()
        XCTAssertTrue(writeAll(client, Array("ping".utf8)))
        let guest = try rig.guestEnd(0)
        defer { close(guest) }
        XCTAssertEqual(readSome(guest), Array("ping".utf8))

        close(client)

        XCTAssertTrue(seesTheEnd(guest, within: 1), "the guest's end is still open a second after the host closed")
        XCTAssertTrue(eventually { rig.relay.counts.held == 0 }, "the relay still holds the connection")
        XCTAssertEqual(rig.guest.state.withLock { $0.hangUps }, 1)
    }

    func testAGuestCloseReachesTheHost() throws {
        let rig = try Rig()
        defer { rig.close() }
        let client = try rig.newClient()
        defer { close(client) }
        XCTAssertTrue(writeAll(client, Array("ping".utf8)))
        let guest = try rig.guestEnd(0)
        XCTAssertEqual(readSome(guest), Array("ping".utf8))

        close(guest)

        XCTAssertTrue(seesTheEnd(client, within: 1), "the host's end is still open a second after the guest closed")
        XCTAssertTrue(eventually { rig.relay.counts.held == 0 }, "the relay still holds the connection")
    }

    // A guest that answers and closes while the host is still sending, as Incus does when it
    // turns an upload down. The answer must arrive: the relay's write to the guest fails first,
    // and a relay that ended the connection there would close the host's side before the other
    // way had carried the answer. And that write must fail without a signal: SIGPIPE is left at
    // its default in this process, so an unguarded one ends the test run here. A relay that is
    // wrong loses either race about one time in five.
    //
    // The connection must also end, which is why there are hundreds of rounds: on macOS a relay
    // of two threads, each in a blocking read that the other ends with shutdown(), kept the
    // connection in 3 of 600 rounds, the host still sending into it. Linux never showed it.
    func testAnAnswerArrivesFromAGuestThatClosesWhileTheHostStillSends() throws {
        let answer = Array("refused".utf8)
        for round in 0..<400 {
            let rig = try Rig()
            defer { rig.close() }
            let client = try rig.newClient()
            defer { close(client) }
            XCTAssertTrue(writeAll(client, Array("put".utf8)))
            let guest = try rig.guestEnd(0)
            XCTAssertEqual(readSome(guest), Array("put".utf8))

            let chunk = [UInt8](repeating: 7, count: 65536)
            let stopped = DispatchSemaphore(value: 0)
            Thread.detachNewThread {
                while writeAll(client, chunk) {}
                stopped.signal()
            }
            usleep(2_000)
            XCTAssertTrue(writeAll(guest, answer))
            close(guest)

            var received: [UInt8] = []
            while let some = readSome(client), !some.isEmpty { received += some }
            XCTAssertEqual(received, answer, "round \(round): the guest's answer did not reach the host")
            let toldTheHost = stopped.wait(timeout: .now() + 5) == .success
            XCTAssertTrue(toldTheHost, "round \(round): the host is still sending to a guest that closed")
            let letGo = eventually { rig.relay.counts.held == 0 }
            XCTAssertTrue(letGo, "round \(round): the relay still holds the connection")
            // One round says it, and a relay that holds on costs ten seconds in each.
            guard received == answer, toldTheHost, letGo else { break }
        }
    }

    // A dial that does not return keeps a host connection and a thread, which `held` does not
    // count: on a Mac that is a guest that stopped answering, the state #785 calls the wedge.
    func testAClientWaitingForTheGuestIsCounted() throws {
        let rig = try Rig()
        defer { rig.close() }
        let answering = DispatchSemaphore(value: 0)
        rig.guest.state.withLock { $0.answering = answering }
        let client = try rig.newClient()
        defer { close(client) }

        XCTAssertTrue(eventually { rig.relay.counts == Relay.Counts(waiting: 1) }, "\(rig.relay.counts)")
        answering.signal()
        XCTAssertTrue(eventually { rig.relay.counts == Relay.Counts(held: 1, total: 1) }, "\(rig.relay.counts)")
    }

    // Closing both sides at the first end must not cut off what the closing side had sent.
    func testAReplySentJustBeforeTheGuestClosesArrivesWhole() throws {
        let rig = try Rig()
        defer { rig.close() }
        let client = try rig.newClient()
        defer { close(client) }
        XCTAssertTrue(writeAll(client, Array("get".utf8)))
        let guest = try rig.guestEnd(0)
        XCTAssertEqual(readSome(guest), Array("get".utf8))

        let reply = (0..<(1 << 20)).map { UInt8(truncatingIfNeeded: $0 &* 31) }
        Thread.detachNewThread {
            _ = writeAll(guest, reply)
            close(guest)
        }

        var received: [UInt8] = []
        while let chunk = readSome(client), !chunk.isEmpty { received += chunk }
        XCTAssertEqual(received.count, reply.count)
        XCTAssertTrue(received == reply, "the reply arrived changed")
    }

    // Both sides send a megabyte before either reads: the relay cannot write what it read at
    // once, in either direction, and must lose and reorder nothing while it waits.
    func testBytesCrossBothWaysAtOnce() throws {
        let rig = try Rig()
        defer { rig.close() }
        let client = try rig.newClient()
        defer { close(client) }
        let guest = try rig.guestEnd(0)
        defer { close(guest) }

        let fromHost = (0..<(1 << 20)).map { UInt8(truncatingIfNeeded: $0 &* 31) }
        let fromGuest = (0..<(1 << 20)).map { UInt8(truncatingIfNeeded: $0 &* 17 &+ 5) }
        Thread.detachNewThread { _ = writeAll(client, fromHost) }
        Thread.detachNewThread { _ = writeAll(guest, fromGuest) }
        usleep(50_000)
        let atHost = Mutex([UInt8]())
        let hostRead = DispatchSemaphore(value: 0)
        Thread.detachNewThread {
            let all = readExactly(client, fromGuest.count)
            atHost.withLock { $0 = all }
            hostRead.signal()
        }
        let atGuest = readExactly(guest, fromHost.count)
        XCTAssertEqual(hostRead.wait(timeout: .now() + 10), .success)

        XCTAssertTrue(atGuest == fromHost, "the guest got \(atGuest.count) bytes of \(fromHost.count), or other bytes")
        XCTAssertTrue(atHost.withLock { $0 } == fromGuest, "the host got other bytes than the guest sent")
        XCTAssertEqual(rig.relay.counts.held, 1)
    }

    // The host fills the tunnel and closes while the guest is not reading. What the relay
    // read is still the guest's to have, so the connection must wait for the guest, asleep:
    // the host's closed end is ready on every poll, and a relay that watched it would spin.
    func testWhatAHostSentBeforeClosingWaitsForAGuestThatReadsLate() throws {
        let rig = try Rig()
        defer { rig.close() }
        let client = try rig.newClient()
        let guest = try rig.guestEnd(0)
        defer { close(guest) }

        // As much as the tunnel takes with nobody reading: full once nothing more fits for 200 ms.
        _ = fcntl(client, F_SETFL, fcntl(client, F_GETFL) | O_NONBLOCK)
        let chunk = (0..<4096).map { UInt8(truncatingIfNeeded: $0 &* 31) }
        var sent: [UInt8] = []
        var full = Date().addingTimeInterval(0.2)
        while Date() < full {
            let written = chunk.withUnsafeBytes { write(client, $0.baseAddress!, chunk.count) }
            if written > 0 {
                sent += chunk[..<written]
                full = Date().addingTimeInterval(0.2)
            } else {
                usleep(2_000)
            }
        }
        close(client)

        let before = processorSeconds()
        usleep(400_000)
        let busy = processorSeconds() - before
        XCTAssertLessThan(busy, 0.2, "the relay is busy while it waits for the guest")
        XCTAssertEqual(rig.relay.counts.held, 1, "the relay dropped what the guest had not read yet")

        let received = readExactly(guest, sent.count)
        XCTAssertTrue(received == sent, "the guest got \(received.count) bytes of \(sent.count), or other bytes")
        XCTAssertTrue(seesTheEnd(guest, within: 1), "the guest's end is still open after it read everything")
        XCTAssertTrue(eventually { rig.relay.counts.held == 0 }, "the relay still holds the connection")
    }

    func testNothingIsHeldAfterABurst() throws {
        let rig = try Rig()
        defer { rig.close() }
        let before = openDescriptors()
        let burst = 128

        let clients = try (0..<burst).map { _ in try rig.newClient() }
        // The relay dials in whatever order its threads run, so each client says which it is.
        for (index, client) in clients.enumerated() { XCTAssertTrue(writeAll(client, [UInt8(index)])) }
        var guests = [Int32](repeating: -1, count: burst)
        for dialled in 0..<burst {
            let guest = try rig.guestEnd(dialled)
            let index = Int(try XCTUnwrap(readSome(guest)?.first))
            XCTAssertEqual(guests[index], -1)
            guests[index] = guest
        }
        XCTAssertEqual(rig.relay.counts.held, burst)

        // Half end from the host's side and half from the guest's.
        for index in 0..<burst {
            close(index % 2 == 0 ? clients[index] : guests[index])
        }
        XCTAssertTrue(eventually { rig.relay.counts.held == 0 }, "held \(rig.relay.counts.held) after the burst ended")
        for index in 0..<burst {
            close(index % 2 == 0 ? guests[index] : clients[index])
        }

        XCTAssertEqual(rig.relay.counts, Relay.Counts(held: 0, total: burst, refused: 0))
        let guest = rig.guest.state.withLock { $0 }
        XCTAssertEqual(guest.dials, burst)
        XCTAssertEqual(guest.hangUps, burst, "every guest connection is hung up")
        XCTAssertEqual(guest.strayHangUps, 0, "and only once")
        XCTAssertEqual(openDescriptors(), before, "the relay kept descriptors open")
    }

    // What the launcher does when the Mac wakes: a connection may have died on either side
    // while it slept, so every one is ended on both sides, and the relay goes on serving.
    func testDroppingEndsEveryConnectionOnBothSides() throws {
        let rig = try Rig()
        defer { rig.close() }
        let before = openDescriptors()
        let count = 16

        let clients = try (0..<count).map { _ in try rig.newClient() }
        for client in clients { XCTAssertTrue(writeAll(client, Array("ping".utf8))) }
        let guests = try (0..<count).map { try rig.guestEnd($0) }
        for guest in guests { XCTAssertEqual(readSome(guest), Array("ping".utf8)) }
        XCTAssertEqual(rig.relay.counts.held, count)

        XCTAssertEqual(rig.relay.dropAll(), count)

        for index in 0..<count {
            XCTAssertTrue(seesTheEnd(clients[index], within: 1), "host end \(index) is still open")
            XCTAssertTrue(seesTheEnd(guests[index], within: 1), "guest end \(index) is still open")
        }
        XCTAssertTrue(eventually { rig.relay.counts.held == 0 }, "held \(rig.relay.counts.held) after dropping")
        for index in 0..<count {
            close(clients[index])
            close(guests[index])
        }
        let guest = rig.guest.state.withLock { $0 }
        XCTAssertEqual(guest.hangUps, count, "every guest connection is hung up")
        XCTAssertEqual(guest.strayHangUps, 0, "and only once")
        XCTAssertEqual(openDescriptors(), before, "the relay kept descriptors open")
        XCTAssertEqual(rig.relay.dropAll(), 0, "nothing is left to drop")

        // The relay still serves.
        let client = try rig.newClient()
        defer { close(client) }
        XCTAssertTrue(writeAll(client, Array("again".utf8)))
        let again = try rig.guestEnd(count)
        defer { close(again) }
        XCTAssertEqual(readSome(again), Array("again".utf8))
    }

    // The launcher sees a wake up to two and a half seconds late. What isx connected in that
    // time was accepted after the sleep, and is not cut: only the connections stamped before
    // the limit are ended.
    func testDroppingWithALimitKeepsWhatWasAcceptedAfterIt() throws {
        let rig = try Rig()
        defer { rig.close() }
        let before = openDescriptors()
        let early = 4, late = 3

        // Four accepted before an hour's sleep, three after it.
        var clients: [Int32] = [], guests: [Int32] = []
        for index in 0..<(early + late) {
            if index == early { rig.guest.state.withLock { $0.stamp = .seconds(3600) } }
            let client = try rig.newClient()
            clients.append(client)
            XCTAssertTrue(writeAll(client, Array("ping".utf8)))
            // One at a time, so each is stamped before the next stamp is set.
            let guest = try rig.guestEnd(index)
            guests.append(guest)
            XCTAssertEqual(readSome(guest), Array("ping".utf8))
        }
        XCTAssertEqual(rig.relay.counts.held, early + late)

        XCTAssertEqual(rig.relay.dropAll(stampedBefore: .seconds(1800)), early)

        for index in 0..<early {
            XCTAssertTrue(seesTheEnd(clients[index], within: 1), "host end \(index), accepted before the sleep, is still open")
            XCTAssertTrue(seesTheEnd(guests[index], within: 1), "guest end \(index), accepted before the sleep, is still open")
        }
        XCTAssertTrue(eventually { rig.relay.counts.held == late }, "held \(rig.relay.counts.held) after dropping")
        // The later ones still carry bytes, both ways.
        for index in early..<(early + late) {
            XCTAssertTrue(writeAll(clients[index], Array("more".utf8)))
            XCTAssertEqual(readSome(guests[index]), Array("more".utf8), "connection \(index), accepted after the sleep, was cut")
            XCTAssertTrue(writeAll(guests[index], Array("back".utf8)))
            XCTAssertEqual(readSome(clients[index]), Array("back".utf8))
        }
        XCTAssertEqual(rig.relay.dropAll(stampedBefore: .seconds(1800)), 0, "nothing older is left")

        for index in 0..<(early + late) {
            close(clients[index])
            close(guests[index])
        }
        XCTAssertTrue(eventually { rig.relay.counts.held == 0 })
        XCTAssertTrue(eventually { rig.guest.state.withLock { $0.hangUps } == early + late }, "every guest connection is hung up")
        XCTAssertEqual(openDescriptors(), before, "the relay kept descriptors open")
    }

    // The sleep came while the guest was being dialled: the connection was accepted before it,
    // so it is ended when the dial returns, though the drop itself could not see it yet.
    func testAConnectionDialledAcrossTheDropIsEndedWhenItsDialReturns() throws {
        let rig = try Rig()
        defer { rig.close() }
        let answering = DispatchSemaphore(value: 0)
        rig.guest.state.withLock { $0.answering = answering }

        let early = try rig.newClient()
        defer { close(early) }
        XCTAssertTrue(eventually { rig.relay.counts.waiting == 1 })
        XCTAssertEqual(rig.relay.dropAll(stampedBefore: .seconds(1800)), 0, "nothing is relayed yet")
        answering.signal()
        let earlyGuest = try rig.guestEnd(0)
        defer { close(earlyGuest) }
        XCTAssertTrue(seesTheEnd(early, within: 1), "the host end, accepted before the sleep, is still open")
        XCTAssertTrue(seesTheEnd(earlyGuest, within: 1), "its guest end is still open")
        XCTAssertTrue(eventually { rig.relay.counts == Relay.Counts(held: 0, total: 1) }, "\(rig.relay.counts)")

        // One accepted after the wake is relayed as ever.
        rig.guest.state.withLock { $0.stamp = .seconds(3600) }
        let late = try rig.newClient()
        defer { close(late) }
        answering.signal()
        let lateGuest = try rig.guestEnd(1)
        defer { close(lateGuest) }
        XCTAssertTrue(writeAll(late, Array("ping".utf8)))
        XCTAssertEqual(readSome(lateGuest), Array("ping".utf8))
        XCTAssertEqual(rig.relay.counts.held, 1)
    }

    func testAClientIsClosedWhenTheGuestDoesNotAnswer() throws {
        let rig = try Rig()
        defer { rig.close() }
        rig.guest.state.withLock { $0.refuses = true }
        let client = try rig.newClient()
        defer { close(client) }

        XCTAssertTrue(seesTheEnd(client, within: 1), "the client is left waiting for a guest that refused")
        XCTAssertTrue(eventually { rig.relay.counts == Relay.Counts(held: 0, total: 0, refused: 1) })
    }

    // vfkit's accept loop ends on its first error, and its socket then takes connections that
    // nothing serves until the VM is restarted. Running out of descriptors is such an error.
    func testAcceptingGoesOnAfterRunningOutOfDescriptors() throws {
        let rig = try Rig()
        defer { rig.close() }
        let early = socket(AF_UNIX, sockStream, 0)  // made while there still is a descriptor for it

        var limit = rlimit()
        XCTAssertEqual(getrlimit(descriptorLimit, &limit), 0)
        let lowestFree = dup(0)
        close(lowestFree)
        var none = limit
        none.rlim_cur = rlim_t(lowestFree)
        XCTAssertEqual(setrlimit(descriptorLimit, &none), 0)
        // What becomes of this one is the kernel's business: Linux hands it to an accept() that
        // was already waiting, and the dial then fails; macOS leaves it queued for the retry.
        let connected = rig.connect(early)
        let reported = eventually { rig.guest.state.withLock { $0.log.contains { $0.contains("trying again") } } }
        XCTAssertEqual(setrlimit(descriptorLimit, &limit), 0)
        XCTAssertTrue(connected)
        XCTAssertTrue(reported, "accept() did not fail: \(rig.guest.state.withLock { $0.log })")
        close(early)

        let client = try rig.newClient()
        defer { close(client) }
        XCTAssertTrue(writeAll(client, Array("ping".utf8)))
        var said: [UInt8]? = []
        var dialled = 0
        while said == [] {  // the early connection's guest end, if it got one, only ends
            let guest = try rig.guestEnd(dialled)
            defer { close(guest) }
            said = readSome(guest)
            dialled += 1
        }
        XCTAssertEqual(said, Array("ping".utf8))
    }

    // A connection holds two descriptors, and macOS starts a program with room for 256.
    func testTheDescriptorLimitIsRaisedAsFarAsItGoes() throws {
        var before = rlimit()
        XCTAssertEqual(getrlimit(descriptorLimit, &before), 0)
        defer { _ = setrlimit(descriptorLimit, &before) }
        var low = before
        low.rlim_cur = 256
        try XCTSkipIf(before.rlim_max <= low.rlim_cur, "this process may not have more than 256 descriptors")
        XCTAssertEqual(setrlimit(descriptorLimit, &low), 0)

        let raised = Relay.raiseDescriptorLimit()

        var after = rlimit()
        XCTAssertEqual(getrlimit(descriptorLimit, &after), 0)
        XCTAssertEqual(UInt64(after.rlim_cur), raised)
        // Enough for the measurement's 400 held connections and a burst of 120 beside them.
        XCTAssertGreaterThanOrEqual(raised, min(UInt64(before.rlim_max), 2048))
        #if canImport(Glibc)
        XCTAssertEqual(after.rlim_cur, before.rlim_max)
        #endif
        // At the limit already, it changes nothing.
        XCTAssertEqual(Relay.raiseDescriptorLimit(), raised)
    }

    func testServingEndsWhenTheListenerIsGone() throws {
        let rig = try Rig()
        rig.close()
        XCTAssertEqual(rig.served.wait(timeout: .now() + 2), .success, "serve() is still accepting on a closed listener")
    }

    func testTheSocketIsTheUsersAlone() throws {
        let rig = try Rig()
        defer { rig.close() }
        var status = stat()
        XCTAssertEqual(lstat(rig.path, &status), 0)
        XCTAssertEqual(status.st_mode & 0o777, 0o600)
    }

    func testASocketLeftBehindIsReplaced() throws {
        let rig = try Rig()
        defer { rig.close() }
        let second = try Relay.listen(at: rig.path)
        close(second)
    }

    func testAFileInTheWayIsNotRemoved() throws {
        let rig = try Rig()
        defer { rig.close() }
        let file = rig.directory + "/file"
        XCTAssertTrue(FileManager.default.createFile(atPath: file, contents: Data("kept".utf8)))
        defer { unlink(file) }
        XCTAssertThrowsError(try Relay.listen(at: file)) { error in
            XCTAssertEqual("\(error)", "'\(file)' exists and is not a socket")
        }
        XCTAssertEqual(FileManager.default.contents(atPath: file), Data("kept".utf8))
    }

    // A path cut to fit would bind a socket somewhere else, and isx would wait on the real one.
    func testAPathTooLongForASocketIsRefused() throws {
        let rig = try Rig()
        defer { rig.close() }
        let long = rig.directory + "/" + String(repeating: "x", count: 120)
        XCTAssertThrowsError(try Relay.listen(at: long))
        XCTAssertThrowsError(try Relay.listen(at: ""))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: rig.directory), ["relay.sock"])
    }
}

/// The guest's side of every connection the relay dials.
final class Guest: Sendable {
    struct State {
        /// The test's end of each dial, in the order they were dialled.
        var farEnds: [Int32] = []
        /// The relay's ends it has not hung up yet.
        var dialled: Set<Int32> = []
        var dials = 0
        var hangUps = 0
        /// Hang-ups of a descriptor the relay does not hold: a second close, or someone else's.
        var strayHangUps = 0
        var refuses = false
        /// When set, a dial waits for it before it answers.
        var answering: DispatchSemaphore?
        var log: [String] = []
        /// What the relay's `stamp` reads for the next connections it accepts.
        var stamp: Duration = .zero
    }
    let state = Mutex(State())

    func dial() -> Int32 {
        state.withLock({ $0.answering })?.wait()
        if state.withLock({ $0.refuses }) { return -1 }
        var ends: [Int32] = [0, 0]
        guard socketpair(AF_UNIX, sockStream, 0, &ends) == 0 else { return -1 }
        state.withLock {
            $0.dials += 1
            $0.dialled.insert(ends[0])
            $0.farEnds.append(ends[1])
        }
        return ends[0]
    }

    func hangUp(_ descriptor: Int32) {
        let held = state.withLock { state -> Bool in
            state.hangUps += 1
            if state.dialled.remove(descriptor) == nil {
                state.strayHangUps += 1
                return false
            }
            return true
        }
        if held { close(descriptor) }
    }
}

/// A relay serving a socket in a directory of its own.
final class Rig: Sendable {
    let guest = Guest()
    let directory: String
    let path: String
    let listener: Int32
    let relay: Relay
    /// Signalled when `serve` returns.
    let served = DispatchSemaphore(value: 0)

    init() throws {
        // Under /tmp and not the user's temporary directory, which on macOS is too long a path.
        var template = Array("/tmp/isx-relay-XXXXXX".utf8CString)
        guard mkdtemp(&template) != nil else { throw RelayError.system(call: "mkdtemp", path: "/tmp", errno: errno) }
        directory = template.withUnsafeBufferPointer { String(cString: $0.baseAddress!) }
        path = directory + "/relay.sock"
        let guest = self.guest
        relay = Relay(
            dial: { guest.dial() },
            hangUp: { guest.hangUp($0) },
            log: { line in guest.state.withLock { $0.log.append(line) } },
            stamp: { guest.state.withLock { $0.stamp } })
        listener = try Relay.listen(at: path)
        let relay = self.relay, listener = self.listener, served = self.served
        Thread.detachNewThread {
            relay.serve(listener: listener)
            served.signal()
        }
    }

    func newClient() throws -> Int32 {
        let client = socket(AF_UNIX, sockStream, 0)
        guard client >= 0, connect(client) else {
            throw RelayError.system(call: "connect", path: path, errno: errno)
        }
        return client
    }

    func connect(_ client: Int32) -> Bool {
        var address = sockaddr_un()
        address.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &address.sun_path) { $0.copyBytes(from: Array(path.utf8)) }
        return withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Foundation.connect(client, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) == 0
            }
        }
    }

    /// The test's end of the `index`-th connection the relay dialled, once it has.
    func guestEnd(_ index: Int) throws -> Int32 {
        guard eventually({ self.guest.state.withLock { $0.farEnds.count > index } }) else {
            throw RelayError.system(call: "dial \(index)", path: path, errno: ETIMEDOUT)
        }
        return guest.state.withLock { $0.farEnds[index] }
    }

    func close() {
        shutdown(listener, Int32(SHUT_RDWR))
        _ = Foundation.close(listener)
        // Not before serve() has returned: where closing the listener ends accept() with an
        // error serve() retries, it would accept on whatever socket takes this descriptor's
        // number next, which is the next test's listener. Signalled again for whoever waits.
        if served.wait(timeout: .now() + 2) == .success { served.signal() }
        unlink(path)
        rmdir(directory)
    }
}

func eventually(within seconds: Double = 5, _ condition: () -> Bool) -> Bool {
    let deadline = Date().addingTimeInterval(seconds)
    while !condition() {
        if Date() > deadline { return false }
        usleep(2_000)
    }
    return true
}

// A write to a socket whose other end closed returns the error, with no SIGPIPE: the signal
// stays at its default, so the tests see a relay that raises it.
#if canImport(Glibc)
private func writeSome(_ descriptor: Int32, _ bytes: UnsafeRawPointer, _ count: Int) -> Int {
    send(descriptor, bytes, count, Int32(MSG_NOSIGNAL))
}
#else
private func writeSome(_ descriptor: Int32, _ bytes: UnsafeRawPointer, _ count: Int) -> Int {
    var on: Int32 = 1
    setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
    return write(descriptor, bytes, count)
}
#endif

func writeAll(_ descriptor: Int32, _ bytes: [UInt8]) -> Bool {
    var offset = 0
    while offset < bytes.count {
        let written = bytes.withUnsafeBytes { writeSome(descriptor, $0.baseAddress! + offset, bytes.count - offset) }
        if written < 0 && errno == EINTR { continue }
        if written <= 0 { return false }
        offset += written
    }
    return true
}

/// What arrives next: nil when nothing does in time, empty at the end of the stream.
func readSome(_ descriptor: Int32, within seconds: Double = 5) -> [UInt8]? {
    var poll = pollfd(fd: descriptor, events: Int16(POLLIN), revents: 0)
    guard Foundation.poll(&poll, 1, Int32(seconds * 1000)) > 0 else { return nil }
    var buffer = [UInt8](repeating: 0, count: 65536)
    let count = read(descriptor, &buffer, buffer.count)
    return count < 0 ? nil : Array(buffer[..<count])
}

/// The next `count` bytes, or fewer when the stream ends or stalls first.
func readExactly(_ descriptor: Int32, _ count: Int) -> [UInt8] {
    var received: [UInt8] = []
    var buffer = [UInt8](repeating: 0, count: 65536)
    while received.count < count {
        var poll = pollfd(fd: descriptor, events: Int16(POLLIN), revents: 0)
        guard Foundation.poll(&poll, 1, 5000) > 0 else { break }
        let some = read(descriptor, &buffer, min(buffer.count, count - received.count))
        guard some > 0 else { break }
        received += buffer[..<some]
    }
    return received
}

/// The processor time this process has used, all its threads together.
func processorSeconds() -> Double {
    var usage = rusage()
    getrusage(processUsage, &usage)
    func seconds(_ time: timeval) -> Double { Double(time.tv_sec) + Double(time.tv_usec) / 1_000_000 }
    return seconds(usage.ru_utime) + seconds(usage.ru_stime)
}

func seesTheEnd(_ descriptor: Int32, within seconds: Double) -> Bool {
    readSome(descriptor, within: seconds)?.isEmpty ?? false
}

/// How many descriptors this process has open.
func openDescriptors() -> Int {
    (try? FileManager.default.contentsOfDirectory(atPath: "/dev/fd").count) ?? -1
}
