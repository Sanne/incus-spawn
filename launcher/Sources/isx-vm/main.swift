// isx-vm: starts isx's Linux VM on Virtualization.framework and relays its vsock ports (#2031).
//
// A prototype, to be measured against vfkit before anything ships: it reads the command line
// isx writes for vfkit, so the same isx can start either. It lives as long as the machine and
// exits when the guest powers off. SIGTERM and SIGINT stop the machine; SIGUSR1 prints each
// relay's counts.
import Darwin
import Foundation
import IsxVmCore
import Virtualization

// A report or a log line written to a pipe whose reader has gone must fail, not end the
// launcher and the machine with it.
signal(SIGPIPE, SIG_IGN)

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
