package dev.incusspawn.git;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Loopback remote transport for preparation tests; production never enables file:// for tests. */
public final class GitTestServer implements AutoCloseable {
    private final Path root;
    private final Process process;
    private final int port;

    public GitTestServer(Path root) throws Exception {
        this.root = root.toAbsolutePath();
        try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        process = new ProcessBuilder("git", "daemon", "--reuseaddr", "--export-all", "--enable=receive-pack", "--listen=127.0.0.1",
                "--port=" + port, "--base-path=" + this.root)
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            for (int attempt = 0; attempt < 200; attempt++) {
                if (!process.isAlive()) throw new IOException("Git test server exited");
                try (var socket = new Socket("127.0.0.1", port)) { return; }
                catch (IOException e) { Thread.sleep(10); }
            }
            throw new IOException("Git test server did not start");
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    public String url(Path repo) { return "git://127.0.0.1:" + port + "/" + root.relativize(repo.toAbsolutePath()); }

    @Override public void close() throws Exception {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
