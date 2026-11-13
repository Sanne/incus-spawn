// What `isx-vm` is asked to start (#2031): the machine, read from the command line.
//
// The prototype reads the part of vfkit's command line that isx writes today
// (`VmManager.vfkitCommand`, and `boot_vfkit` of appliance/test-boot.sh), so that an unchanged
// isx can start it in vfkit's place and the two can be measured on one machine. That is all it
// is for: the launcher that ships takes arguments of its own. Everything else is refused by
// name, never skipped, so a device isx asked for is never silently missing from the machine.
// No Foundation, and nothing of Virtualization.framework: this is tested on Linux too.

public struct VmSpec: Equatable, Sendable {
    public struct Share: Equatable, Sendable {
        public var directory: String
        public var tag: String
        public init(directory: String, tag: String) {
            self.directory = directory
            self.tag = tag
        }
    }

    /// A guest vsock port that a Unix socket on the host leads to.
    public struct Socket: Equatable, Sendable {
        public var port: UInt32
        public var path: String
        public init(port: UInt32, path: String) {
            self.port = port
            self.path = path
        }
    }

    public var cpus = 0
    public var memoryMiB: UInt64 = 0
    public var kernel = ""
    public var commandLine = ""
    /// Disk images, in the order the guest sees them: /dev/vda first.
    public var disks: [String] = []
    /// Whether the machine has a network device behind the host's NAT, and its MAC address
    /// when one was given.
    public var nat = false
    public var mac: String?
    /// The file the guest's console is written to.
    public var consoleLog: String?
    public var shares: [Share] = []
    public var sockets: [Socket] = []
    /// The vsock port of the guest agent that sets the guest's clock, when there is one.
    public var clockPort: UInt32?

    public init() {}

    public enum Refused: Error, Equatable, CustomStringConvertible {
        case unknownOption(String)
        case missingValue(String)
        case repeated(String)
        case badValue(option: String, value: String)
        case unknownDevice(String)
        case badDevice(device: String, why: String)
        case missing(String)

        public var description: String {
            switch self {
            case .unknownOption(let option): return "unknown option '\(option)'"
            case .missingValue(let option): return "\(option) needs a value"
            case .repeated(let option): return "\(option) is given twice"
            case .badValue(let option, let value): return "\(option): '\(value)' is not a value it takes"
            case .unknownDevice(let device): return "unknown device '\(device)'"
            case .badDevice(let device, let why): return "--device \(device): \(why)"
            case .missing(let option): return "\(option) is required"
            }
        }
    }

    /// Reads the arguments after the program's name.
    public init(arguments: [String]) throws(Refused) {
        var rest = arguments[...]
        var seen: Set<String> = []
        while let option = rest.popFirst() {
            guard option.hasPrefix("--") else { throw .unknownOption(option) }
            guard let value = rest.popFirst() else { throw .missingValue(option) }
            if option != "--device" && !seen.insert(option).inserted { throw .repeated(option) }
            switch option {
            case "--cpus":
                guard let count = Int(value), count > 0 else { throw .badValue(option: option, value: value) }
                cpus = count
            case "--memory":
                guard let size = UInt64(value), size > 0 else { throw .badValue(option: option, value: value) }
                memoryMiB = size
            case "--kernel":
                guard !value.isEmpty else { throw .badValue(option: option, value: value) }
                kernel = value
            case "--kernel-cmdline":
                commandLine = value
            case "--device":
                try add(device: value)
            case "--timesync":
                guard value.hasPrefix("vsockPort="), let port = UInt32(value.dropFirst("vsockPort=".count)) else {
                    throw .badValue(option: option, value: value)
                }
                clockPort = port
            case "--initrd", "--restful-uri":
                // Read and not used. The appliance kernel is built without initrd support, so
                // the file isx passes is a placeholder vfkit insists on. And isx asks vfkit's
                // REST endpoint for one thing, to stop a guest that could not be asked itself,
                // which a signal does here.
                break
            default:
                throw .unknownOption(option)
            }
        }
        guard seen.contains("--cpus") else { throw .missing("--cpus") }
        guard seen.contains("--memory") else { throw .missing("--memory") }
        guard seen.contains("--kernel") else { throw .missing("--kernel") }
    }

    private mutating func add(device: String) throws(Refused) {
        // vfkit's form: a kind, then keys with values and bare words, between commas. So a
        // path that holds a comma cannot be written, and is refused as an unknown key.
        let parts = device.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
        let kind = parts[0]
        var values: [String: String] = [:]
        var words: Set<String> = []
        for part in parts.dropFirst() {
            if let equals = part.firstIndex(of: "=") {
                let key = String(part[..<equals])
                guard values.updateValue(String(part[part.index(after: equals)...]), forKey: key) == nil else {
                    throw .badDevice(device: device, why: "'\(key)' is given twice")
                }
            } else {
                words.insert(part)
            }
        }
        // Takes a key that must be there and must not be empty.
        func take(_ key: String) throws(Refused) -> String {
            guard let value = values.removeValue(forKey: key), !value.isEmpty else {
                throw .badDevice(device: device, why: "'\(key)' is required")
            }
            return value
        }
        switch kind {
        case "virtio-blk":
            disks.append(try take("path"))
        case "virtio-net":
            guard words.remove("nat") != nil else { throw .badDevice(device: device, why: "only 'nat' is supported") }
            guard !nat else { throw .badDevice(device: device, why: "a second network device") }
            nat = true
            mac = values.removeValue(forKey: "mac")
        case "virtio-serial":
            guard consoleLog == nil else { throw .badDevice(device: device, why: "a second console") }
            consoleLog = try take("logFilePath")
        case "virtio-fs":
            shares.append(Share(directory: try take("sharedDir"), tag: try take("mountTag")))
        case "virtio-vsock":
            // Only the direction isx uses: the host connects to a port the guest listens on.
            guard words.remove("connect") != nil else {
                throw .badDevice(device: device, why: "only 'connect' is supported")
            }
            guard let port = UInt32(try take("port")) else {
                throw .badDevice(device: device, why: "'port' is not a number")
            }
            sockets.append(Socket(port: port, path: try take("socketURL")))
        default:
            throw .unknownDevice(kind)
        }
        if let left = values.keys.sorted().first ?? words.sorted().first {
            throw .badDevice(device: device, why: "'\(left)' is not something \(kind) takes")
        }
    }
}
