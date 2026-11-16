// What `isx-vm` is asked to start (#2031): the machine, read from the command line.
//
// The arguments are isx's own, shaped for isx as the only caller (`VmManager.launcherCommand`,
// and `boot_launcher` of appliance/test-boot.sh). Every option takes one value. Anything else is
// refused by name, never skipped, so a device isx asked for is never silently missing from the
// machine.
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
    /// The MAC address of the network device behind the host's NAT, when the machine has one.
    public var mac: String?
    /// The file the guest's console is written to.
    public var consoleLog: String?
    /// Directories shared with the guest, every one read-only.
    public var shares: [Share] = []
    public var sockets: [Socket] = []
    /// The vsock port of the guest agent that sets the guest's clock, when there is one.
    public var clockPort: UInt32?
    /// The Unix socket isx asks the launcher's state and counts on, and asks it to stop.
    public var control: String?

    public init() {}

    public enum Refused: Error, Equatable, CustomStringConvertible {
        case unknownOption(String)
        case missingValue(String)
        case repeated(String)
        case badValue(option: String, value: String)
        case missing(String)

        public var description: String {
            switch self {
            case .unknownOption(let option): return "unknown option '\(option)'"
            case .missingValue(let option): return "\(option) needs a value"
            case .repeated(let option): return "\(option) is given twice"
            case .badValue(let option, let value): return "\(option): '\(value)' is not a value it takes"
            case .missing(let option): return "\(option) is required"
            }
        }
    }

    /// The options that may be given more than once, each time for one more of the thing.
    private static let repeatable: Set<String> = ["--disk", "--share", "--vsock"]
    /// Every option read, each with one value.
    private static let options: Set<String> = repeatable.union([
        "--cpus", "--memory", "--kernel", "--cmdline", "--nat", "--console", "--clock-port", "--control",
    ])

    /// Reads the arguments after the program's name.
    public init(arguments: [String]) throws(Refused) {
        var rest = arguments[...]
        var seen: Set<String> = []
        while let option = rest.popFirst() {
            // An option this does not read is named as that, before a value is asked of it.
            guard VmSpec.options.contains(option) else { throw .unknownOption(option) }
            guard let value = rest.popFirst() else { throw .missingValue(option) }
            if !seen.insert(option).inserted && !VmSpec.repeatable.contains(option) { throw .repeated(option) }
            func bad() -> Refused { .badValue(option: option, value: value) }
            switch option {
            case "--cpus":
                guard let count = Int(value), count > 0 else { throw bad() }
                cpus = count
            case "--memory":
                // In MiB, and the machine is given bytes: a size that does not fit in them is refused.
                guard let size = UInt64(value), size > 0, size <= UInt64.max >> 20 else { throw bad() }
                memoryMiB = size
            case "--kernel":
                guard !value.isEmpty else { throw bad() }
                kernel = value
            case "--cmdline":
                commandLine = value
            case "--disk":
                guard !value.isEmpty else { throw bad() }
                disks.append(value)
            case "--nat":
                // A MAC address, checked by the framework when the machine is made.
                guard !value.isEmpty else { throw bad() }
                mac = value
            case "--console":
                guard !value.isEmpty else { throw bad() }
                consoleLog = value
            case "--share":
                // TAG=DIRECTORY, split at the first '=': a tag never holds one, a path may.
                guard let (tag, directory) = VmSpec.pair(value), !tag.isEmpty, !directory.isEmpty else { throw bad() }
                shares.append(Share(directory: directory, tag: tag))
            case "--vsock":
                // PORT=PATH: the host's socket at PATH leads to the guest's PORT.
                guard let (port, path) = VmSpec.pair(value), let number = UInt32(port), !path.isEmpty else { throw bad() }
                guard !sockets.contains(where: { $0.port == number || $0.path == path }) else { throw bad() }
                sockets.append(Socket(port: number, path: path))
            case "--clock-port":
                guard let port = UInt32(value) else { throw bad() }
                clockPort = port
            case "--control":
                guard !value.isEmpty else { throw bad() }
                control = value
            default:
                throw .unknownOption(option)
            }
        }
        guard seen.contains("--cpus") else { throw .missing("--cpus") }
        guard seen.contains("--memory") else { throw .missing("--memory") }
        guard seen.contains("--kernel") else { throw .missing("--kernel") }
    }

    private static func pair(_ value: String) -> (String, String)? {
        guard let equals = value.firstIndex(of: "=") else { return nil }
        return (String(value[..<equals]), String(value[value.index(after: equals)...]))
    }
}
