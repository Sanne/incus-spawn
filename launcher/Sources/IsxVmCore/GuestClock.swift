// Setting the guest's clock through its guest agent (qemu-ga, which the appliance starts on the
// vsock port of `isx.ga_vsock`). A guest does not run while its host sleeps, so its clock is
// behind by the time asleep when the host wakes.
//
// vfkit keeps one connection to the agent for as long as it runs, and when that connection
// cannot be made it stops setting the clock for good. Here every wake makes its own connection,
// which the caller hangs up, so one that failed costs that wake alone.
#if canImport(Glibc)
import Glibc
private let noSignal = Int32(MSG_NOSIGNAL)
private func noSigPipe(_ descriptor: Int32) {}
#else
import Darwin
private let noSignal: Int32 = 0
private func noSigPipe(_ descriptor: Int32) {
    var on: Int32 = 1
    setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
}
#endif

public enum GuestClock {
    /// The host's time now, in nanoseconds since 1970.
    public static func now() -> Int64 {
        var time = timespec()
        clock_gettime(CLOCK_REALTIME, &time)
        return Int64(time.tv_sec) * 1_000_000_000 + Int64(time.tv_nsec)
    }

    /// What the agent is sent, one line.
    public static func request(nanoseconds: Int64) -> String {
        "{\"execute\":\"guest-set-time\",\"arguments\":{\"time\":\(nanoseconds)}}\n"
    }

    /// Whether the agent's reply says it set the clock: `{"return": {}}`, where a refusal is
    /// `{"error": {...}}`.
    public static func accepted(_ reply: String) -> Bool {
        reply.filter { !$0.isWhitespace } == "{\"return\":{}}"
    }

    /// Sends the host's time on a connection to the agent and returns the agent's reply line,
    /// without its newline, or nil when none arrived whole within `milliseconds`. The time sent
    /// is read when the request is written, not before the connection was made.
    public static func set(on descriptor: Int32, within milliseconds: Int = 5000) -> String? {
        let deadline = ContinuousClock.now + .milliseconds(milliseconds)
        // How long is left, for poll(): nil once the time is up.
        func left() -> Int32? {
            let left = ContinuousClock.now.duration(to: deadline)
            guard left > .zero else { return nil }
            return Int32(left.components.seconds * 1000 + left.components.attoseconds / 1_000_000_000_000_000) + 1
        }
        func wait(for events: Int32) -> Bool {
            while let left = left() {
                var polled = pollfd(fd: descriptor, events: Int16(events), revents: 0)
                let ready = poll(&polled, 1, left)
                if ready > 0 { return true }
                if ready < 0 && errno != EINTR { return false }
            }
            return false
        }

        noSigPipe(descriptor)
        let request = Array(GuestClock.request(nanoseconds: now()).utf8)
        var offset = 0
        while offset < request.count {
            guard wait(for: POLLOUT) else { return nil }
            let written = request.withUnsafeBytes {
                send(descriptor, $0.baseAddress! + offset, request.count - offset, noSignal | Int32(MSG_DONTWAIT))
            }
            if written < 0 && (errno == EINTR || errno == EAGAIN) { continue }
            guard written > 0 else { return nil }
            offset += written
        }

        var reply: [UInt8] = []
        var buffer = [UInt8](repeating: 0, count: 4096)
        // A reply is some tens of bytes. One that never ends is not read for ever.
        while reply.count < 65536 {
            guard wait(for: POLLIN) else { return nil }
            let read = recv(descriptor, &buffer, buffer.count, Int32(MSG_DONTWAIT))
            if read < 0 && (errno == EINTR || errno == EAGAIN) { continue }
            guard read > 0 else { return nil }
            reply += buffer[..<read]
            if let end = reply.firstIndex(of: UInt8(ascii: "\n")) {
                return String(decoding: reply[..<end], as: UTF8.self)
            }
        }
        return nil
    }
}
