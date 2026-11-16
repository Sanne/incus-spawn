// The launcher's control socket (#2031): how isx asks `isx-vm` its state and counts, and asks
// it to stop the machine.
//
// One request a connection, as the guest's agent takes them: the client writes a verb and a
// newline, the server answers with lines and closes. It replaces vfkit's REST endpoint, which
// isx used for one thing (a stop), and the `lsof` count of host descriptors, which was a guess
// at what the relay holds; the relay now says it. It interprets nothing but its own verbs, and
// the socket is the user's alone (`Relay.listen`).
#if canImport(Glibc)
import Glibc
#else
import Darwin
#endif

public enum Control {
    /// The longest request read; a verb is a word.
    public static let requestLimit = 256

    /// Accepts on `listener` and answers each connection's one line with `answer`, one
    /// connection at a time: every answer is a few lines made at once. A client that sends no
    /// line within two seconds is closed unanswered, so it cannot keep the next one waiting.
    /// Returns only when `listener` no longer accepts.
    public static func serve(listener: Int32, answer: (String) -> String) {
        while true {
            let client = accept(listener, nil, nil)
            if client < 0 {
                let code = errno
                if code == EINTR || code == ECONNABORTED { continue }
                if code == EBADF || code == EINVAL || code == ENOTSOCK || code == EOPNOTSUPP { return }
                // Out of descriptors or memory, as `Relay.serve` takes it: the connection stays
                // in the backlog, so wait and take it then, rather than spin or end the launcher.
                usleep(100_000)
                continue
            }
            var limit = timeval(tv_sec: 2, tv_usec: 0)
            setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &limit, socklen_t(MemoryLayout<timeval>.size))
            #if !canImport(Glibc)
            var on: Int32 = 1
            setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
            #endif
            if let line = readLine(client) {
                let reply = Array(answer(line).utf8)
                var sent = 0
                while sent < reply.count {
                    let wrote = reply[sent...].withUnsafeBytes { send(client, $0.baseAddress, $0.count, sendFlags) }
                    if wrote <= 0 { break }
                    sent += wrote
                }
            }
            close(client)
        }
    }

    #if canImport(Glibc)
    private static let sendFlags = Int32(MSG_NOSIGNAL)
    #else
    private static let sendFlags: Int32 = 0
    #endif

    /// The request, without its newline, or nil when none came whole within the limits.
    /// The whole request has two seconds, not each byte: `SO_RCVTIMEO` bounds one `recv`, so a
    /// client sending a byte every second and a half would otherwise hold the loop for minutes.
    private static func readLine(_ client: Int32) -> String? {
        var line: [UInt8] = []
        var byte: UInt8 = 0
        let deadline = monotonicSeconds() + 2
        while line.count < requestLimit {
            // Each `recv` waits only for what is left of the two seconds, so the last cannot
            // carry the request past them.
            let left = deadline - monotonicSeconds()
            guard left > 0 else { return nil }
            var wait = timeval(tv_sec: Int(left), tv_usec: suseconds_t((left - left.rounded(.down)) * 1e6))
            if wait.tv_sec == 0 && wait.tv_usec == 0 { wait.tv_usec = 1 }
            setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &wait, socklen_t(MemoryLayout<timeval>.size))
            let got = recv(client, &byte, 1, 0)
            if got <= 0 { return nil }
            if byte == UInt8(ascii: "\n") { return String(decoding: line, as: UTF8.self) }
            line.append(byte)
        }
        return nil
    }

    private static func monotonicSeconds() -> Double {
        var now = timespec()
        clock_gettime(CLOCK_MONOTONIC, &now)
        return Double(now.tv_sec) + Double(now.tv_nsec) / 1e9
    }
}
