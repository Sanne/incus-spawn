package dev.incusspawn.lifecycle;

import dev.incusspawn.Environment;
import dev.incusspawn.OwnDirectory;
import dev.incusspawn.tool.DownloadCache;
import dev.incusspawn.util.OwnSession;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * PROTOTYPE (design/macos-and-vms.md "GUI on macOS: the waypipe prototype"): the Wayland compositor
 * isx runs on a Mac for {@code isx gui}, so nobody starts one by hand. It is Cocoa-Way, downloaded
 * at a pinned release and checked against the publisher's sum, kept under isx's data directory,
 * and started in its Rootless mode with a runtime directory of isx's own. It is isx's instance
 * alone: a Cocoa-Way the person runs themselves has another runtime directory and is left as it is.
 */
public final class HostCompositor {

    private HostCompositor() {}

    /** The pinned release. The sum is the release's own {@code SHA256SUMS} line, which GitHub's asset digest repeats. */
    static final String VERSION = "2.1.3";
    static final String URL = "https://github.com/J-x-Z/cocoa-way/releases/download/v" + VERSION
            + "/Cocoa-Way-v" + VERSION + "-arm64-macos.zip";
    static final String SHA256 = "02856ee48e947c34f8f0e716e7b411c05b8545eb9c80c6f77bec6da7d3b1ebc3";

    /** How long a started compositor has to open its socket. */
    private static final long START_SECONDS = 20;

    /** Where this release is unpacked: a directory per version, so a new pin never runs old files. */
    static Path installDir() {
        return Environment.dataDir().resolve("gui").resolve("cocoa-way-" + VERSION);
    }

    static Path binary(Path installDir) {
        return installDir.resolve("Cocoa-Way.app/Contents/MacOS/cocoa-way");
    }

    /**
     * The compositor's runtime directory, isx's alone. Short on purpose: a Unix socket path holds
     * 103 bytes on macOS, and {@code TMPDIR} there is already half of that.
     */
    public static Path runtimeDir() {
        return Environment.vmStateDir().resolve("gui");
    }

    /** What the compositor is started with: its mode and where its socket goes, read once at its start. */
    static Map<String, String> startEnvironment(Path runtimeDir) {
        return Map.of("COCOA_WAY_PRESENTATION", "rootless",
                "COCOA_WAY_DISPLAY_RUNTIME_DIR", runtimeDir.toString());
    }

    /** What unpacks the release: {@code ditto}, which keeps the bundle's links and signature as Finder would. */
    static List<String> unpackCommand(Path zip, Path installDir) {
        return List.of("/usr/bin/ditto", "-x", "-k", zip.toString(), installDir.toString());
    }

    /** The running compositor's display, or null when isx's compositor is not running. */
    public static GuiBridge.HostDisplay running() {
        var display = GuiBridge.displayIn(runtimeDir().toString());
        return display != null && accepts(Path.of(display.path())) ? display : null;
    }

    /** Whether the pinned release is unpacked here. */
    public static boolean installed() {
        return Files.isExecutable(binary(installDir()));
    }

    /**
     * Downloads the pinned release, checked against {@link #SHA256}, and unpacks it. A download
     * isx makes carries no quarantine mark, so macOS runs the ad-hoc signed bundle without asking.
     */
    public static void install() throws IOException, InterruptedException {
        var zip = new DownloadCache().download(URL, SHA256);
        var dir = installDir();
        var unpacking = dir.resolveSibling(dir.getFileName() + ".unpacking");
        dev.incusspawn.FileTrees.deleteQuietly(unpacking);
        Files.createDirectories(unpacking);
        var process = new ProcessBuilder(unpackCommand(zip, unpacking)).redirectErrorStream(true).start();
        process.getOutputStream().close();
        var output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IOException("could not unpack Cocoa-Way: " + output.strip());
        if (!Files.isExecutable(binary(unpacking))) {
            throw new IOException("the Cocoa-Way release holds no " + binary(Path.of("")) + ".");
        }
        dev.incusspawn.FileTrees.deleteQuietly(dir);
        Files.move(unpacking, dir);
    }

    /**
     * Starts the compositor in a session of its own, so it outlives the command that started it,
     * and waits for its socket.
     *
     * @return its display
     * @throws IOException when it did not open a socket in time; its log says why
     */
    public static GuiBridge.HostDisplay start() throws IOException, InterruptedException {
        var runtime = runtimeDir();
        OwnDirectory.create(runtime);
        // A socket nobody listens on is a compositor that died: its name would be taken for a display
        try (var entries = Files.list(runtime)) {
            for (var entry : entries.filter(path -> path.getFileName().toString().startsWith("wayland-")).toList()) {
                Files.deleteIfExists(entry);
            }
        }
        var builder = OwnSession.apply(new ProcessBuilder(binary(installDir()).toString()));
        builder.environment().putAll(startEnvironment(runtime));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile().toFile()));
        var process = builder.start();
        process.getOutputStream().close();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_SECONDS);
        while (System.nanoTime() < deadline) {
            var display = running();
            if (display != null) return display;
            if (!process.isAlive() && !OwnSession.available()) break; // under a wrapper the first process execs on
            Thread.sleep(100);
        }
        throw new IOException("Cocoa-Way did not open a display within " + START_SECONDS + " s; see " + logFile());
    }

    /** What the compositor prints, kept beside the launcher's log. */
    public static Path logFile() {
        return Environment.vmStateDir().resolve("gui-compositor.log");
    }

    private static boolean accepts(Path socket) {
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socket));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
