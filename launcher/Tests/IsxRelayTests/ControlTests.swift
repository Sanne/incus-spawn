// The launcher's control socket (#2031): one line in, the answer out, then the end.
import Foundation
import IsxRelay
import XCTest

#if canImport(Glibc)
private let stream = Int32(SOCK_STREAM.rawValue)
#else
private let stream = SOCK_STREAM
#endif

/// The verbs the server was asked, from its thread and the test's.
private final class Asked: @unchecked Sendable {
    private let lock = NSLock()
    private var verbs: [String] = []
    func add(_ verb: String) { lock.withLock { verbs.append(verb) } }
    var all: [String] { lock.withLock { verbs } }
}

final class ControlTests: XCTestCase {
    private var path = ""
    private var listener: Int32 = -1
    private let asked = Asked()

    override func setUpWithError() throws {
        // A client writing after the server closed (the trickle) must fail its write, not end the run.
        signal(SIGPIPE, SIG_IGN)
        var template = Array("/tmp/isx-control-XXXXXX".utf8CString)
        guard mkdtemp(&template) != nil else { throw RelayError.system(call: "mkdtemp", path: "/tmp", errno: errno) }
        path = template.withUnsafeBufferPointer { String(cString: $0.baseAddress!) } + "/control.sock"
        listener = try Relay.listen(at: path)
        let listener = self.listener
        let asked = self.asked
        Thread.detachNewThread {
            Control.serve(listener: listener) { line in
                asked.add(line)
                return line == "state" ? "running\n" : "unknown verb\n"
            }
        }
    }

    override func tearDown() {
        close(listener)
        unlink(path)
    }

    private func connected() throws -> Int32 {
        let client = socket(AF_UNIX, stream, 0)
        var address = sockaddr_un()
        address.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &address.sun_path) { $0.copyBytes(from: Array(path.utf8)) }
        let done = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(client, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
        guard client >= 0, done == 0 else { throw RelayError.system(call: "connect", path: path, errno: errno) }
        var limit = timeval(tv_sec: 5, tv_usec: 0)
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &limit, socklen_t(MemoryLayout<timeval>.size))
        return client
    }

    private func send(_ text: String, on client: Int32) {
        let bytes = Array(text.utf8)
        _ = bytes.withUnsafeBytes { write(client, $0.baseAddress, $0.count) }
    }

    /// Everything the server sent, up to its close.
    private func reply(_ client: Int32) -> String {
        var all: [UInt8] = []
        var buffer = [UInt8](repeating: 0, count: 256)
        while true {
            let got = read(client, &buffer, buffer.count)
            if got <= 0 { break }
            all += buffer[..<got]
        }
        close(client)
        return String(decoding: all, as: UTF8.self)
    }

    func testAVerbIsAnsweredAndTheConnectionEnds() throws {
        let client = try connected()
        send("state\n", on: client)
        XCTAssertEqual(reply(client), "running\n")
    }

    func testEachConnectionIsAnsweredInTurn() throws {
        for verb in ["state", "stop", "state"] {
            let client = try connected()
            send("\(verb)\n", on: client)
            XCTAssertEqual(reply(client), verb == "state" ? "running\n" : "unknown verb\n")
        }
        XCTAssertEqual(asked.all, ["state", "stop", "state"])
    }

    // A client that never finishes its line is closed unanswered, and the next one is served.
    func testAClientThatSendsNoLineDoesNotHoldTheNext() throws {
        let silent = try connected()
        send("sta", on: silent)
        let next = try connected()
        send("state\n", on: next)
        XCTAssertEqual(reply(next), "running\n")
        XCTAssertEqual(reply(silent), "")
        XCTAssertEqual(asked.all, ["state"])
    }

    // A byte at a time, each inside the per-recv timeout, is still cut at the request's deadline.
    func testATrickleIsCutAtTheRequestsDeadline() throws {
        let slow = try connected()
        let started = Date()
        for _ in 0..<4 {
            send("s", on: slow)
            Thread.sleep(forTimeInterval: 0.9)
        }
        XCTAssertEqual(reply(slow), "")
        XCTAssertLessThan(Date().timeIntervalSince(started), 4.5)
        XCTAssertEqual(asked.all, [])
    }

    func testARequestLongerThanTheLimitIsNotAnswered() throws {
        let client = try connected()
        send(String(repeating: "x", count: Control.requestLimit + 1) + "\n", on: client)
        XCTAssertEqual(reply(client), "")
        XCTAssertEqual(asked.all, [])
    }
}
