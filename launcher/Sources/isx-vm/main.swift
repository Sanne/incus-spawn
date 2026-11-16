// isx-vm: starts isx's Linux VM on Virtualization.framework and relays its vsock ports (#2031).
//
// isx starts it with arguments of its own (`VmManager.launcherCommand`, read by `VmSpec`). It
// lives as long as the machine and exits when the guest powers off. SIGTERM and SIGINT stop the
// machine; SIGUSR1 prints each relay's counts, which the control socket also answers.
import Darwin
import Foundation
import IsxRelay
import IsxVmCore
import Virtualization

// A report or a log line written to a pipe whose reader has gone must fail, not end the
// launcher and the machine with it.
signal(SIGPIPE, SIG_IGN)

// Two descriptors for each connection a relay holds, where macOS starts a program with 256.
Relay.raiseDescriptorLimit()

let spec: VmSpec
do {
    spec = try VmSpec(arguments: Array(CommandLine.arguments.dropFirst()))
} catch {
    fail("\(error)", status: 64)
}
guard VZVirtualMachine.isSupported else { fail("this Mac cannot run virtual machines", status: 1) }

let launcher: Launcher
do {
    launcher = try Launcher(spec)
} catch {
    fail("\(error)", status: 1)
}
launcher.watchSignals()
launcher.start()
// The main thread serves the main queue, where the machine lives. Not dispatchMain(), which
// gives the main thread up and serves that queue from others.
RunLoop.main.run()
fail("the main run loop ended", status: 1)
