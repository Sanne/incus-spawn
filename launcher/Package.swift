// swift-tools-version:6.0
// The macOS launcher's sources (#2031). So far: the relay between a host Unix socket and a guest
// vsock port, and the Linux bridge that puts the same relay in front of a QEMU guest in CI.
import PackageDescription

var targets: [Target] = [
    .target(name: "IsxRelay"),
    .testTarget(name: "IsxRelayTests", dependencies: ["IsxRelay"]),
]
#if os(Linux)
// AF_VSOCK from the host side exists only on Linux; on macOS the guest is reached through
// Virtualization.framework, by the launcher.
targets.append(.executableTarget(name: "isx-vsock-bridge", dependencies: ["IsxRelay"]))
#endif

let package = Package(
    name: "launcher",
    platforms: [.macOS(.v15)],
    targets: targets
)
