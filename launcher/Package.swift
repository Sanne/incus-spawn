// swift-tools-version:6.0
// The macOS launcher's sources (#2031): the relay between a host Unix socket and a guest vsock
// port, the Linux bridge that puts the same relay in front of a QEMU guest in CI, and `isx-vm`,
// a prototype of the launcher on Virtualization.framework, with what it needs that is not the
// framework's (its arguments, the wake) in a library that is tested on Linux too.
import PackageDescription

var targets: [Target] = [
    .target(name: "IsxRelay"),
    .testTarget(name: "IsxRelayTests", dependencies: ["IsxRelay"]),
    .target(name: "IsxVmCore"),
    .testTarget(name: "IsxVmCoreTests", dependencies: ["IsxVmCore"]),
]
#if os(Linux)
// AF_VSOCK from the host side exists only on Linux; on macOS the guest is reached through
// Virtualization.framework, by the launcher.
targets.append(.executableTarget(name: "isx-vsock-bridge", dependencies: ["IsxRelay"]))
#endif
#if os(macOS)
targets.append(.executableTarget(name: "isx-vm", dependencies: ["IsxRelay", "IsxVmCore"]))
#endif

let package = Package(
    name: "launcher",
    platforms: [.macOS(.v15)],
    targets: targets
)
