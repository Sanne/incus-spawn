// The relay between a host Unix socket and a guest vsock port (#2031).
//
// isx reaches the Incus daemon in the macOS VM through a Unix socket on the host. Whatever
// serves that socket opens one connection to the guest for each one it accepts and copies bytes
// both ways. vfkit's relay keeps both open until *both* directions have ended and cannot tell
// the guest that the host side closed, so a connection isx closes stays open in the guest until
// the guest gives up on it: the leak of design/macos-and-vms.md "Where the vsock leak comes from".
//
// This relay ends a connection as soon as either side ends, and closes both sides.
// Nothing here knows Virtualization.framework: a connection to the guest is whatever file
// descriptor `dial` returns (on macOS a VZVirtioSocketConnection's, on Linux an AF_VSOCK
// socket to a QEMU guest), and `hangUp` gives it back to whoever owns it.
// No Foundation: the Linux bridge links what it imports statically, and Foundation is 50 MB of it.
import Synchronization

#if canImport(Glibc)
import Glibc
private let sockStream = Int32(SOCK_STREAM.rawValue)
#else
import Darwin
private let sockStream = SOCK_STREAM
#endif

// The C library's, which `Relay.listen(at:)` hides inside the class.
private func listenOn(_ descriptor: Int32, _ backlog: Int32) -> Int32 { listen(descriptor, backlog) }

public enum RelayError: Error, CustomStringConvertible {
    /// The path does not fit `sockaddr_un`, or is empty. Never truncated: that would bind another path.
    case unusablePath(String)
    /// Something that is not a socket is in the way; a socket left behind is replaced.
    case notASocket(String)
    case system(call: String, path: String, errno: Int32)

    public var description: String {
        switch self {
        case .unusablePath(let path):
            return "'\(path)' is empty or too long for a Unix socket address"
        case .notASocket(let path):
            return "'\(path)' exists and is not a socket"
        case .system(let call, let path, let code):
            return "\(call) '\(path)': \(String(cString: strerror(code)))"
        }
    }
}

public final class Relay: Sendable {
    public struct Counts: Sendable, Equatable {
        /// Host connections accepted whose guest connection is not open yet: each is inside `dial`.
        public var waiting: Int
        /// Connections being relayed now: one host connection and one guest connection each.
        public var held: Int
        /// Connections relayed since the start, the held ones included.
        public var total: Int
        /// Host connections closed unanswered: the guest could not be dialled, or there was no
        /// thread to relay on.
        public var refused: Int

        public init(waiting: Int = 0, held: Int = 0, total: Int = 0, refused: Int = 0) {
            self.waiting = waiting
            self.held = held
            self.total = total
            self.refused = refused
        }
    }

    private let dial: @Sendable () -> Int32
    private let hangUp: @Sendable (Int32) -> Void
    private let log: @Sendable (String) -> Void
    private let stamp: @Sendable () -> Duration
    private struct State {
        var counts = Counts()
        /// The guest's descriptor of every connection being relayed, and the stamp taken when
        /// the host's was accepted, by the host's descriptor. An entry leaves here before
        /// either descriptor is closed, so both are open while it is here.
        var live: [Int32: (guest: Int32, stamp: Duration)] = [:]
        /// The highest limit `dropAll` was given: a connection stamped before it whose dial
        /// had not returned then is ended when it does.
        var dropBefore: Duration?
    }
    private let state = Mutex(State())

    /// - Parameters:
    ///   - dial: opens a connection to the guest and returns its descriptor, or a negative
    ///     number when the guest does not answer. It may block. The descriptor is a connected
    ///     stream socket, blocking or not: the relay never waits inside a read or a write of it.
    ///   - hangUp: closes a descriptor `dial` returned. Called once for each, after the relay
    ///     has let go of it.
    ///   - log: where an accept failure is reported.
    ///   - stamp: read once for each host connection, when it is accepted and before the guest
    ///     is dialled. `dropAll(stampedBefore:)` compares it. Called on the connection's thread.
    public init(dial: @escaping @Sendable () -> Int32,
                hangUp: @escaping @Sendable (Int32) -> Void,
                log: @escaping @Sendable (String) -> Void = { _ in },
                stamp: @escaping @Sendable () -> Duration = { .zero }) {
        self.dial = dial
        self.hangUp = hangUp
        self.log = log
        self.stamp = stamp
    }

    public var counts: Counts { state.withLock { $0.counts } }

    /// Ends the connections being relayed now, as if both of their sides had closed, and
    /// returns how many it ended. For a host that slept: a connection may have died on either
    /// side meanwhile without this relay being told, and one that isx still holds would then
    /// wait on a guest that no longer knows it. A connection still waiting in `dial` is left alone.
    ///
    /// With a limit only the connections stamped before it are ended, so that one accepted
    /// after the wake, in the time the launcher took to notice it, is kept. Without one, all.
    /// One stamped before the limit and still waiting in `dial` is not counted here, and is
    /// ended as soon as its dial returns: the sleep came between its two halves.
    @discardableResult
    public func dropAll(stampedBefore limit: Duration? = nil) -> Int {
        state.withLock { state in
            if let limit { state.dropBefore = max(state.dropBefore ?? limit, limit) }
            // Shut down, not closed: each connection's own thread wakes from poll(), sees the
            // end and closes its two descriptors, once, as for any other end.
            var dropped = 0
            for (client, one) in state.live {
                if let limit, one.stamp >= limit { continue }
                shutdown(client, Int32(SHUT_RDWR))
                shutdown(one.guest, Int32(SHUT_RDWR))
                dropped += 1
            }
            return dropped
        }
    }

    /// Runs `body` on a thread of its own, or returns false when there is none to be had. Never
    /// a Dispatch queue: its pool is bounded, and a connection's thread waits in poll() for as
    /// long as the connection lives, so a few dozen held connections would stop every later one.
    public static func detach(_ body: @escaping @Sendable () -> Void) -> Bool {
        final class Body {
            let run: @Sendable () -> Void
            init(_ run: @escaping @Sendable () -> Void) { self.run = run }
        }
        let boxed = Unmanaged.passRetained(Body(body))
        #if canImport(Glibc)
        var thread = pthread_t()
        let created = pthread_create(&thread, nil, { argument in
            Unmanaged<Body>.fromOpaque(argument!).takeRetainedValue().run()
            return nil
        }, boxed.toOpaque())
        guard created == 0 else {
            boxed.release()
            return false
        }
        pthread_detach(thread)
        #else
        var thread: pthread_t?
        let created = pthread_create(&thread, nil, { argument in
            Unmanaged<Body>.fromOpaque(argument).takeRetainedValue().run()
            return nil
        }, boxed.toOpaque())
        guard created == 0, let thread else {
            boxed.release()
            return false
        }
        pthread_detach(thread)
        #endif
        return true
    }

    /// Listens on a Unix socket at `path`, for this user only, replacing a socket a killed
    /// process left there.
    public static func listen(at path: String, backlog: Int32 = 128) throws -> Int32 {
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let bytes = Array(path.utf8)
        // One byte stays zero: the address is NUL-terminated.
        guard !bytes.isEmpty, bytes.count < MemoryLayout.size(ofValue: addr.sun_path) else {
            throw RelayError.unusablePath(path)
        }
        withUnsafeMutableBytes(of: &addr.sun_path) { $0.copyBytes(from: bytes) }

        var existing = stat()
        if lstat(path, &existing) == 0 {
            guard existing.st_mode & S_IFMT == S_IFSOCK else { throw RelayError.notASocket(path) }
            unlink(path)
        }
        let fd = socket(AF_UNIX, sockStream, 0)
        guard fd >= 0 else { throw RelayError.system(call: "socket", path: path, errno: errno) }
        func fail(_ call: String) -> RelayError {
            let code = errno
            close(fd)
            return .system(call: call, path: path, errno: code)
        }
        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
        guard bound == 0 else { throw fail("bind") }
        // The socket at the path is now this one, so a failure from here on takes it away again.
        func failBound(_ call: String) -> RelayError {
            let error = fail(call)
            unlink(path)
            return error
        }
        // Before listen(), so nobody connects while the mode is still the umask's.
        guard chmod(path, 0o600) == 0 else { throw failBound("chmod") }
        guard listenOn(fd, backlog) == 0 else { throw failBound("listen") }
        return fd
    }

    /// Accepts on `listener` and relays each connection on a thread of its own. It returns only
    /// when `listener` is no longer a listening socket, which a caller treats as fatal: a
    /// failed accept is otherwise tried again, because a loop that ends on one leaves a socket
    /// that still takes connections and serves none of them.
    public func serve(listener: Int32) {
        var reported: Int32 = 0
        while true {
            let client = accept(listener, nil, nil)
            if client >= 0 {
                reported = 0
                if !Relay.detach({ self.relay(client) }) {
                    close(client)
                    state.withLock { $0.counts.refused += 1 }
                    log("no thread for a connection, closed it")
                }
                continue
            }
            let code = errno
            if code == EINTR || code == ECONNABORTED { continue }
            if code == EBADF || code == EINVAL || code == ENOTSOCK || code == EOPNOTSUPP {
                log("accept: \(String(cString: strerror(code))), no longer listening")
                return
            }
            // Out of descriptors or memory: the connection stays in the backlog, so wait and
            // take it then. Said once per run of failures, not ten times a second.
            if code != reported {
                reported = code
                log("accept: \(String(cString: strerror(code))), trying again")
            }
            usleep(100_000)
        }
    }

    // The first side to end is the end of the connection, and both sides are closed.
    // The tunnel has no use for a half-close: isx never sends one, since Incus cancels a
    // request on it. Each descriptor is closed once, here.
    //
    // One thread carries both directions, and it waits in poll() on the two descriptors and
    // nowhere else. Two threads, one blocking read each, with shutdown() to end the other's
    // read, lost connections on macOS: a read() on a Unix socket that is about to sleep does not
    // see a shutdown() from another thread (XNU's soreceive() lets go of the socket's lock in
    // uipc_rcvd() and then sleeps without looking at the socket's state again), so the relay
    // held a connection the guest had closed until the host closed it too.
    private func relay(_ client: Int32) {
        state.withLock { $0.counts.waiting += 1 }
        // Before the dial: a guest connection asked for before a sleep counts as made before it.
        let accepted = stamp()
        let guest = dial()
        guard guest >= 0 else {
            close(client)
            state.withLock { $0.counts.waiting -= 1; $0.counts.refused += 1 }
            return
        }
        state.withLock {
            $0.counts.waiting -= 1
            $0.counts.held += 1
            $0.counts.total += 1
            $0.live[client] = (guest, accepted)
            // `dropAll` ran while this one was in `dial`: ended as it would have been there, and
            // closed below as for any other end.
            if let limit = $0.dropBefore, accepted < limit {
                shutdown(client, Int32(SHUT_RDWR))
                shutdown(guest, Int32(SHUT_RDWR))
            }
        }
        Relay.noSigPipe(client)
        Relay.noSigPipe(guest)
        Relay.copyBothWays(client, guest)
        // Before either descriptor is closed: `dropAll` must never shut down a number that
        // already names another connection.
        state.withLock { $0.live[client] = nil }
        // The guest is told now, whatever `hangUp` then does with the descriptor.
        shutdown(guest, Int32(SHUT_RDWR))
        close(client)
        hangUp(guest)
        state.withLock { $0.counts.held -= 1 }
    }

    private static let chunk = 65536

    // One direction of a connection, between the two descriptors of `copyBothWays`.
    private struct Way {
        /// Which of the two descriptors this way reads, and which it writes.
        let from: Int
        let to: Int
        let buffer: UnsafeMutableRawPointer
        /// What was read into `buffer`, and how much of it `to` has taken.
        var count = 0
        var offset = 0
        /// False once `to` refused a write: what is read from then on is dropped.
        var taken = true

        var unwritten: Bool { offset < count }

        // Reads once when nothing is left to write, then writes as much as `to` takes now.
        // False when `from` has ended: its end of stream, or an error reading it.
        //
        // What was read is written whole before the next read, so a reply the guest sent
        // before closing arrives complete, and `from`'s end is not looked at before then.
        //
        // A write `to` refuses does not end the connection. The side that refused it has
        // stopped reading and may have answered first, as Incus does when it turns an upload
        // down, and the other way may not have carried that answer across yet. So this way
        // reads on and drops what it reads, until either side's read ends.
        mutating func advance(_ descriptors: [Int32], _ polled: [pollfd]) -> Bool {
            if !unwritten {
                // Anything but "writable", which the other way asked for: data, the end, or
                // an error, which the read then reports.
                guard polled[from].revents & ~Int16(POLLOUT) != 0 else { return true }
                let read = recv(descriptors[from], buffer, Relay.chunk, Int32(MSG_DONTWAIT))
                if read < 0 && (errno == EAGAIN || errno == EINTR) { return true }
                if read <= 0 { return false }
                guard taken else { return true }
                count = read
                offset = 0
            } else if polled[to].revents & ~Int16(POLLIN) == 0 {
                return true
            }
            while unwritten {
                let written = Relay.write(descriptors[to], buffer + offset, count - offset)
                if written < 0 && errno == EINTR { continue }
                if written < 0 && errno == EAGAIN { break }
                if written <= 0 {
                    taken = false
                    count = 0
                    offset = 0
                    break
                }
                offset += written
            }
            return true
        }
    }

    // Copies both ways until either side's read ends. No read and no write waits: the one
    // place this thread sleeps is poll(), for whichever each way needs next, its source
    // readable or, while something read is still unwritten, its destination writable.
    private static func copyBothWays(_ client: Int32, _ guest: Int32) {
        let buffers = UnsafeMutableRawPointer.allocate(byteCount: 2 * chunk, alignment: 16)
        defer { buffers.deallocate() }
        let descriptors = [client, guest]
        var ways = [Way(from: 0, to: 1, buffer: buffers), Way(from: 1, to: 0, buffer: buffers + chunk)]
        var polled = descriptors.map { pollfd(fd: $0, events: 0, revents: 0) }
        while true {
            polled[0].events = 0
            polled[1].events = 0
            for way in ways {
                if way.unwritten {
                    polled[way.to].events |= Int16(POLLOUT)
                } else {
                    polled[way.from].events |= Int16(POLLIN)
                }
            }
            // A descriptor neither way waits for is left out, which a negative one is: poll()
            // reports a hang-up whether asked or not, and would return at once, again and
            // again, for a closed side whose end must wait for the bytes still unwritten.
            for side in 0..<2 {
                polled[side].fd = polled[side].events == 0 ? -1 : descriptors[side]
                polled[side].revents = 0
            }
            if poll(&polled, 2, -1) < 0 {
                if errno == EINTR { continue }
                return
            }
            for index in 0..<2 where !ways[index].advance(descriptors, polled) { return }
        }
    }

    // A write to a side that already closed must fail, not raise SIGPIPE and end the process,
    // and one to a side that is not reading must fail with EAGAIN, not wait.
    #if canImport(Glibc)
    private static func write(_ fd: Int32, _ bytes: UnsafeRawPointer, _ count: Int) -> Int {
        send(fd, bytes, count, Int32(MSG_NOSIGNAL) | Int32(MSG_DONTWAIT))
    }
    private static func noSigPipe(_ fd: Int32) {}
    #else
    private static func write(_ fd: Int32, _ bytes: UnsafeRawPointer, _ count: Int) -> Int {
        send(fd, bytes, count, Int32(MSG_DONTWAIT))
    }
    private static func noSigPipe(_ fd: Int32) {
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
    }
    #endif
}
