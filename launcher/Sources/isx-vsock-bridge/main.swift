// The launcher's relay in front of AF_VSOCK: the bridge between host Unix sockets and the
// vsock ports of a QEMU guest, on Linux. CI's `integration-tests` runs it where a Mac runs the
// launcher, so the relay isx ships is the one `appliance/test-tunnel.sh` is run through.
//
//   isx-vsock-bridge <guest-cid> <unix-socket>=<vsock-port> ...
//
// It prints one line once every socket is listening. SIGUSR1 prints each relay's counts.
import Dispatch
import Glibc
import IsxRelay

// A line written whole and unbuffered, so a script that waits for one sees it when it is said.
@Sendable func write(_ line: String, to descriptor: Int32) {
    let bytes = Array("\(line)\n".utf8)
    _ = bytes.withUnsafeBytes { Glibc.write(descriptor, $0.baseAddress, $0.count) }
}

@Sendable func say(_ line: String) { write(line, to: STDOUT_FILENO) }

@Sendable func fail(_ message: String, status: Int32) -> Never {
    write("isx-vsock-bridge: \(message)", to: STDERR_FILENO)
    exit(status)
}

// A report or a log line written to a pipe whose reader has gone must fail, not end the bridge
// and every connection it relays. SIGUSR1 is ignored from the start, before the source below
// takes it, so one sent early does not end the bridge either.
signal(SIGPIPE, SIG_IGN)
signal(SIGUSR1, SIG_IGN)

let usage = "usage: isx-vsock-bridge <guest-cid> <unix-socket>=<vsock-port> ..."
let arguments = CommandLine.arguments.dropFirst()
guard arguments.count >= 2, let cid = UInt32(arguments.first!) else { fail(usage, status: 64) }

@Sendable func dialVsock(cid: UInt32, port: UInt32) -> Int32 {
    let fd = socket(AF_VSOCK, Int32(SOCK_STREAM.rawValue), 0)
    guard fd >= 0 else { return -1 }
    // struct sockaddr_vm (linux/vm_sockets.h), which the Glibc module does not export:
    // family u16, reserved u16, port u32, cid u32, then flags and padding up to 16 bytes.
    var address = [UInt8](repeating: 0, count: 16)
    withUnsafeBytes(of: UInt16(AF_VSOCK)) { address.replaceSubrange(0..<2, with: $0) }
    withUnsafeBytes(of: port) { address.replaceSubrange(4..<8, with: $0) }
    withUnsafeBytes(of: cid) { address.replaceSubrange(8..<12, with: $0) }
    let connected = address.withUnsafeBytes {
        connect(fd, $0.baseAddress!.assumingMemoryBound(to: sockaddr.self), 16)
    }
    guard connected == 0 else {
        close(fd)
        return -1
    }
    return fd
}

struct Bridged {
    let path: String
    let relay: Relay
    let listener: Int32
}

var bridged: [Bridged] = []
for argument in arguments.dropFirst() {
    // At the last "=": a port holds none, and a path may.
    guard let equals = argument.lastIndex(of: "="),
          let port = UInt32(argument[argument.index(after: equals)...]) else { fail(usage, status: 64) }
    let path = String(argument[..<equals])
    let relay = Relay(
        dial: { dialVsock(cid: cid, port: port) },
        hangUp: { close($0) },
        log: { message in write("isx-vsock-bridge: \(path): \(message)", to: STDERR_FILENO) })
    do {
        bridged.append(Bridged(path: path, relay: relay, listener: try Relay.listen(at: path)))
    } catch {
        fail("\(error)", status: 1)
    }
}

let all = bridged
for one in all {
    let serving = Relay.detach {
        one.relay.serve(listener: one.listener)
        fail("\(one.path): stopped accepting", status: 1)
    }
    guard serving else { fail("\(one.path): no thread to accept on", status: 1) }
}

let report = DispatchSource.makeSignalSource(signal: SIGUSR1, queue: .main)
report.setEventHandler {
    for one in all {
        say("\(one.path) \(one.relay.counts)")
    }
}
report.resume()

say("relaying \(all.count) sockets to guest cid \(cid)")
dispatchMain()
