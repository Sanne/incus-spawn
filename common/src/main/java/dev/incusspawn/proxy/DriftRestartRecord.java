package dev.incusspawn.proxy;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Remembers which installed {@code isx-proxy} the service was last restarted onto, and for which
 * CLI build, so version drift that survived that restart is not "fixed" by restarting again (#798).
 * <p>
 * The service runs the <em>installed</em> proxy, not a build matching the CLI. A CLI built from
 * another commit than the installed proxy (any source build, or a partial upgrade) otherwise saw
 * the same drift after every restart and restarted again on every command, cutting every
 * instance's connection each time. Nothing is executed to learn this: asking the binary its
 * version started a whole second proxy on releases that predate {@code --version}. The cost is at
 * most one futile restart per (CLI, installed proxy) pair; changing either allows one more.
 * <p>
 * The CLI build is part of the key although a restart runs the same binary whatever the CLI:
 * a launcher script (JBang, or install.sh's JVM wrapper) stays byte-identical when the jar it
 * runs changes, and install.sh's JVM mode rebuilds both jars and then runs
 * {@code isx proxy install}. The new CLI build is the only sign that a restart would now pick up
 * a new proxy. It costs one futile restart after a CLI-only rebuild.
 */
final class DriftRestartRecord {

    private DriftRestartRecord() {}

    record Stamp(String cliVersion, String cliSha, String proxyBin, long mtimeMillis, long size) {

        /** The stamp for {@code proxyBin} as it is on disk now, or null if it cannot be read. */
        static Stamp of(BuildInfo cli, String proxyBin) {
            if (proxyBin == null) return null;
            try {
                var path = Path.of(proxyBin);
                return new Stamp(cli.version(), cli.gitSha(), proxyBin,
                        Files.getLastModifiedTime(path).toMillis(), Files.size(path));
            } catch (Exception e) {
                return null;
            }
        }

        String serialize() {
            var props = new Properties();
            props.setProperty("cliVersion", cliVersion);
            props.setProperty("cliSha", cliSha);
            props.setProperty("proxyBin", proxyBin);
            props.setProperty("mtimeMillis", Long.toString(mtimeMillis));
            props.setProperty("size", Long.toString(size));
            var out = new StringWriter();
            try {
                props.store(out, "isx: restarted the proxy service onto this binary for this CLI (#798)");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return out.toString();
        }

        static Stamp parse(String text) {
            try {
                var props = new Properties();
                props.load(new StringReader(text));
                return new Stamp(props.getProperty("cliVersion"), props.getProperty("cliSha"),
                        props.getProperty("proxyBin"),
                        Long.parseLong(props.getProperty("mtimeMillis")),
                        Long.parseLong(props.getProperty("size")));
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * The record, if the service was already restarted onto the installed proxy for this CLI;
     * else null. The record is read first, so the {@code which} behind
     * {@link ProxyService#resolveProxyBinaryPath()} runs only when this CLI made it.
     */
    static Stamp restartAlreadyTried() {
        var cli = BuildInfo.instance();
        Stamp recorded;
        try {
            recorded = Stamp.parse(Files.readString(Environment.proxyDriftRestartFile()));
        } catch (Exception e) {
            return null;
        }
        if (recorded == null || !recorded.cliVersion().equals(cli.version())
                || !recorded.cliSha().equals(cli.gitSha())) {
            return null;
        }
        return recorded.equals(Stamp.of(cli, ProxyService.resolveProxyBinaryPath())) ? recorded : null;
    }

    /**
     * Record that the service was just started onto {@code proxyBin} by this CLI. Callers must
     * know the service files exec {@code proxyBin}. Best effort: losing the record costs one more
     * restart, never correctness.
     */
    static void write(String proxyBin) {
        var stamp = Stamp.of(BuildInfo.instance(), proxyBin);
        if (stamp == null) return;
        try {
            var file = Environment.proxyDriftRestartFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, stamp.serialize());
        } catch (Exception ignored) {}
    }
}
