// What the launcher does when the Mac wakes (#2031): it tells that the host slept, and it sets
// the guest's clock through the guest agent, played here by the test on a socketpair.
import Foundation
import IsxVmCore
import XCTest

#if canImport(Glibc)
import Glibc
private let sockStream = Int32(SOCK_STREAM.rawValue)
#else
import Darwin
private let sockStream = SOCK_STREAM
#endif

final class SleepWatchTests: XCTestCase {
    func testClocksThatRunTogetherAreNotASleep() {
        var watch = SleepWatch()
        XCTAssertNil(watch.slept(continuous: .seconds(2), suspending: .seconds(2)))
        // The two are read one after the other, so they differ by a little.
        XCTAssertNil(watch.slept(continuous: .milliseconds(4003), suspending: .milliseconds(4000)))
        XCTAssertNil(watch.slept(continuous: .milliseconds(6001), suspending: .milliseconds(6000)))
    }

    func testTheClockThatGoesOnGainingIsASleepReportedOnce() {
        var watch = SleepWatch()
        XCTAssertNil(watch.slept(continuous: .seconds(2), suspending: .seconds(2)))

        // An hour asleep between two readings two seconds of running time apart.
        XCTAssertEqual(watch.slept(continuous: .seconds(3604), suspending: .seconds(4)), .seconds(3600))
        XCTAssertNil(watch.slept(continuous: .seconds(3606), suspending: .seconds(6)), "the same sleep, again")

        XCTAssertEqual(watch.slept(continuous: .seconds(3618), suspending: .seconds(8)), .seconds(10))
    }

    // What parts a connection accepted before a sleep from one accepted after it: the stamp
    // of the first is the lead before the sleep, of the second the lead after it.
    func testTheWakeLimitIsJustBelowTheLeadAfterTheSleep() {
        var watch = SleepWatch()
        XCTAssertNil(watch.slept(continuous: .seconds(2), suspending: .seconds(2)))
        let before = watch.lead

        XCTAssertEqual(watch.slept(continuous: .seconds(64), suspending: .seconds(4)), .seconds(60))
        XCTAssertEqual(watch.wakeLimit, .milliseconds(59_500))
        XCTAssertLessThan(before, watch.wakeLimit, "accepted before the sleep")
        XCTAssertGreaterThanOrEqual(watch.lead, watch.wakeLimit, "accepted after the wake, before it was seen")
        // The two clocks are read one after the other, so a stamp is a little off the lead.
        XCTAssertGreaterThanOrEqual(watch.lead - .milliseconds(3), watch.wakeLimit)

        // A second sleep: what was accepted after the first is before this one.
        let after = watch.lead
        XCTAssertEqual(watch.slept(continuous: .seconds(76), suspending: .seconds(6)), .seconds(10))
        XCTAssertLessThan(after, watch.wakeLimit)
    }

    // Asleep for a minute, awake for a second, asleep for ten more, all between two readings:
    // what was accepted in that second is before a sleep, the last one.
    func testTwoSleepsBetweenTwoReadingsPartAtTheLastOne() {
        var watch = SleepWatch()
        XCTAssertNil(watch.slept(continuous: .seconds(2), suspending: .seconds(2)))
        let betweenTheTwo: Duration = .seconds(60)

        XCTAssertEqual(watch.slept(continuous: .seconds(74), suspending: .seconds(4)), .seconds(70))
        XCTAssertLessThan(betweenTheTwo, watch.wakeLimit)
    }

    func testACopyOfTheWatchReadsTheSameLead() {
        let watch = SleepWatch()
        let copy = watch
        usleep(20_000)
        // Neither slept, and each reads its two clocks one after the other.
        let apart = watch.ahead() - copy.ahead()
        XCTAssertLessThan(apart < .zero ? .zero - apart : apart, .milliseconds(500))
        XCTAssertLessThan(watch.ahead(), .milliseconds(500), "a process that only runs has no lead")
    }

    func testAProcessThatOnlyRunsIsNotAsleep() {
        var watch = SleepWatch()
        XCTAssertNil(watch.slept())
        usleep(20_000)
        XCTAssertNil(watch.slept())
    }
}

final class GuestClockTests: XCTestCase {
    func testTheRequestIsTheGuestAgentsSetTime() {
        XCTAssertEqual(GuestClock.request(nanoseconds: 1_794_562_000_123_456_789),
                       "{\"execute\":\"guest-set-time\",\"arguments\":{\"time\":1794562000123456789}}\n")
    }

    func testOnlyAnEmptyReturnIsAccepted() {
        XCTAssertTrue(GuestClock.accepted("{\"return\": {}}"))
        XCTAssertTrue(GuestClock.accepted("{\"return\":{}}\r"))
        XCTAssertFalse(GuestClock.accepted("{\"error\": {\"class\": \"GenericError\", \"desc\": \"hwclock failed\"}}"))
        XCTAssertFalse(GuestClock.accepted(""))
    }

    // An agent that reads one request line and answers with `reply`, or with nothing.
    private func agent(replying reply: String?, closing: Bool = false) throws -> (launcher: Int32, asked: () -> String?) {
        var ends: [Int32] = [0, 0]
        guard socketpair(AF_UNIX, sockStream, 0, &ends) == 0 else { throw POSIXError(.EMFILE) }
        let far = ends[1]
        let asked = DispatchSemaphore(value: 0)
        nonisolated(unsafe) var request: String?
        Thread.detachNewThread {
            var received: [UInt8] = []
            var buffer = [UInt8](repeating: 0, count: 4096)
            while !received.contains(UInt8(ascii: "\n")) {
                let count = read(far, &buffer, buffer.count)
                guard count > 0 else { break }
                received += buffer[..<count]
            }
            request = String(decoding: received, as: UTF8.self)
            if let reply {
                // In two writes: a reply is whole at its newline, not at the first bytes.
                let bytes = Array(reply.utf8)
                _ = bytes.withUnsafeBytes { write(far, $0.baseAddress, bytes.count / 2) }
                usleep(20_000)
                _ = bytes.withUnsafeBytes { write(far, $0.baseAddress! + bytes.count / 2, bytes.count - bytes.count / 2) }
            }
            if closing { close(far) }
            asked.signal()
        }
        return (ends[0], {
            guard asked.wait(timeout: .now() + 5) == .success else { return nil }
            if !closing { close(far) }
            return request
        })
    }

    func testTheHostsTimeIsSentAndTheReplyReturned() throws {
        let (launcher, asked) = try agent(replying: "{\"return\": {}}\n")
        defer { close(launcher) }
        let before = GuestClock.now()

        let reply = GuestClock.set(on: launcher)

        XCTAssertEqual(reply, "{\"return\": {}}")
        let request = try XCTUnwrap(asked())
        let prefix = "{\"execute\":\"guest-set-time\",\"arguments\":{\"time\":"
        XCTAssertTrue(request.hasPrefix(prefix) && request.hasSuffix("}}\n"), request)
        let sent = try XCTUnwrap(Int64(request.dropFirst(prefix.count).dropLast(3)))
        XCTAssertTrue((before...GuestClock.now()).contains(sent), "\(sent) is not the time of this call")
    }

    func testARefusalIsReturnedAsItIs() throws {
        let refusal = "{\"error\": {\"class\": \"GenericError\", \"desc\": \"no\"}}"
        let (launcher, asked) = try agent(replying: refusal + "\n")
        defer { close(launcher) }

        let reply = try XCTUnwrap(GuestClock.set(on: launcher))

        XCTAssertEqual(reply, refusal)
        XCTAssertFalse(GuestClock.accepted(reply))
        _ = asked()
    }

    func testAnAgentThatDoesNotAnswerIsNotWaitedForLong() throws {
        let (launcher, asked) = try agent(replying: nil)
        defer { close(launcher) }
        let started = Date()

        XCTAssertNil(GuestClock.set(on: launcher, within: 200))

        let took = Date().timeIntervalSince(started)
        XCTAssertTrue((0.15...2).contains(took), "gave up after \(took) s")
        _ = asked()
    }

    func testAnAgentThatClosesHalfwayThroughAReplyIsNoReply() throws {
        let (launcher, asked) = try agent(replying: "{\"return\"", closing: true)
        defer { close(launcher) }

        XCTAssertNil(GuestClock.set(on: launcher))
        _ = asked()
    }
}
