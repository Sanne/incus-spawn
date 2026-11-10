package dev.incusspawn.lifecycle;

import dev.incusspawn.util.RecordingOutput;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.OwnDirectory;
import dev.incusspawn.Platform;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.*;

/**
 * Host side of the {@code zmx-sockets} disk device.  Its source is the
 * instance's own directory, which nobody else can change; the host zmx dir,
 * which another user can have made first, holds only links (#1821).  Incus
 * refuses to start an instance whose disk source is missing, so the repair
 * has to happen before every start — without slowing the common case down.
 */
class ZmxSocketForwardTest {

    private static final String DEVICE = "zmx-sockets";
    private static final String NAME = "demo";
    @TempDir
    Path zmxDir;
    @TempDir
    Path stateDir;

    private FakeIncusDaemon daemon;
    private IncusClient incus;
    private ZmxSocketForward.HostDirs dirs;

    @BeforeEach
    void setUp() {
        assumeFalse(Platform.isMacOS(), "host socket sharing is Linux-only");
        daemon = new FakeIncusDaemon().container(NAME, Map.of());
        incus = daemon.client();
        dirs = new ZmxSocketForward.HostDirs(stateDir.resolve("zmx"), zmxDir, OwnDirectory::processUid);
    }

    private Path containerDir() {
        return dirs.instances().resolve(NAME);
    }

    private Path sessionTarget() {
        return containerDir().resolve("isx");
    }

    private Path symlink() {
        return zmxDir.resolve("isx-" + NAME);
    }

    /** An instance as {@code IncusClient.instanceMetadata} returns it. */
    private JqValue instanceWithZmxDevice(String source) {
        var device = JqObject.builder(3).put("type", "disk").put("source", source).put("required", "false").build();
        return JqObject.builder(1)
                .put("devices", JqObject.builder(1).put(DEVICE, device).build())
                .build();
    }

    /** An instance branched before the device was optional (#1044): no {@code required} key. */
    private JqValue legacyInstanceWithZmxDevice(String source) {
        var device = JqObject.builder(2).put("type", "disk").put("source", source).build();
        return JqObject.builder(1)
                .put("devices", JqObject.builder(1).put(DEVICE, device).build())
                .build();
    }

    @Test
    void ensureHostDirForStart_directoryPresent_touchesNothing() throws IOException {
        Files.createDirectories(containerDir());

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, dirs,
                instanceWithZmxDevice(containerDir().toAbsolutePath().toString()), RecordingOutput.FAIL_ON_WARN);

        // The healthy path must cost a few stats and no Incus call at all,
        // or every start pays for the repair.
        assertEquals(List.of(), daemon.requests());
    }

    /**
     * A device added before #1044 is required, so a plain {@code incus start}
     * after a reboot fails on the wiped tmpfs.  The isx start that finds it
     * re-adds it as optional, from the metadata it already has.
     */
    @Test
    void ensureHostDirForStart_requiredDevice_upgradesToOptional() throws IOException {
        Files.createDirectories(containerDir());

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, dirs,
                legacyInstanceWithZmxDevice(containerDir().toAbsolutePath().toString()), RecordingOutput.FAIL_ON_WARN);

        assertEquals("false", device().get("required"));
    }

    @Test
    void ensureHostDirForStart_noZmxDevice_createsNothing() {
        ZmxSocketForward.ensureHostDirForStart(incus, NAME, dirs, JqObject.builder(0).build(), RecordingOutput.FAIL_ON_WARN);

        assertFalse(Files.exists(containerDir()),
                "instances without the device must not get a stray directory");
        assertFalse(Files.exists(symlink()));
        assertEquals(List.of(), daemon.requests());
    }

    @Test
    void ensureHostDirForStart_missingDirectory_recreatesDirectoryAndSymlink() throws IOException {
        ZmxSocketForward.ensureHostDirForStart(incus, NAME, dirs,
                instanceWithZmxDevice(containerDir().toAbsolutePath().toString()), RecordingOutput.FAIL_ON_WARN);

        assertTrue(Files.isDirectory(containerDir()));
        assertTrue(Files.isSymbolicLink(symlink()));
        assertEquals(sessionTarget(), Files.readSymbolicLink(symlink()));
        assertEquals(containerDir().toString(), device().get("source"));
    }

    @Test
    void ensureHostDirForStart_staleSourceAfterRename_repointsDevice() {
        var oldDir = dirs.instances().resolve("previous-name");

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, dirs,
                instanceWithZmxDevice(oldDir.toString()), RecordingOutput.FAIL_ON_WARN);

        assertTrue(Files.isDirectory(containerDir()));
        assertEquals(containerDir().toString(), device().get("source"));
    }

    /**
     * The directory for this name exists but the device still points at the
     * one a rename left behind: Incus would fail start validation, so the
     * present directory must not be taken as proof of health.
     */
    @Test
    void ensureHostDirForStart_rightDirectoryWrongSource_repointsDevice() throws IOException {
        Files.createDirectories(containerDir());
        var oldDir = dirs.instances().resolve("previous-name");

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, dirs,
                instanceWithZmxDevice(oldDir.toString()), RecordingOutput.FAIL_ON_WARN);

        assertEquals(containerDir().toString(), device().get("source"));
    }

    /**
     * A stale source that still exists on disk — the old name was recreated
     * since — would otherwise let the instance start against another
     * instance's sockets.
     */
    @Test
    void ensureHostDirForStart_staleSourceStillOnDisk_repointsDevice() throws IOException {
        var oldDir = dirs.instances().resolve("previous-name");
        Files.createDirectories(oldDir);

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, dirs,
                instanceWithZmxDevice(oldDir.toString()), RecordingOutput.FAIL_ON_WARN);

        assertTrue(Files.isDirectory(containerDir()));
        assertEquals(containerDir().toString(), device().get("source"));
    }

    @Test
    void configure_addsDiskDeviceForContainerZmxDir() throws IOException {
        ZmxSocketForward.configure(incus, NAME, dirs, RecordingOutput.FAIL_ON_WARN);

        assertTrue(Files.isDirectory(containerDir()));
        assertEquals(sessionTarget(), Files.readSymbolicLink(symlink()));
        assertEquals(
                Map.of("type", "disk",
                        "source", containerDir().toString(),
                        "path", "/home/agentuser/.zmx",
                        // A start that bypasses isx must not fail on a missing source (#1044)
                        "required", "false",
                        "shift", "true"),
                device());
    }

    /**
     * The pre-start hook runs both repairs — a host-resource source that
     * vanished and the zmx directory — off a single instance fetch, since
     * either one failing start validation is what it exists to prevent.
     */
    @Test
    void prepareForStart_bothRepairsShareOneFetch() {
        // A name no real host directory can collide with: this call resolves
        // the zmx dir from the environment, unlike the tests above.
        var name = "wiring-" + java.util.UUID.randomUUID();
        var instance = JqObject.builder(1)
                .put("config", JqObject.builder(1).put(Metadata.HOST_RESOURCES, "").build())
                .build();
        var incus = mock(IncusClient.class);
        when(incus.instanceMetadata(name)).thenReturn(instance);

        InstanceLifecycle.prepareForStart(incus, name);

        verify(incus).instanceMetadata(name);
        verifyNoMoreInteractions(incus);
    }

    private Map<String, String> device() {
        return daemon.instanceDevices(NAME).get(DEVICE);
    }

    /** The host dir resolves as zmx 0.6.0 and 0.8.1 resolve their socket dir, {@code $TMPDIR/zmx-<uid>} included. */
    @Test
    void hostZmxDirResolvesAsZmxDoes() {
        Supplier<String> uid = () -> "1000";
        var all = Map.of("ZMX_DIR", "/z", "XDG_RUNTIME_DIR", "/run/user/1000", "TMPDIR", "/tmp/td");
        assertEquals(Path.of("/z"), ZmxSocketForward.hostZmxDir(all::get, uid));
        var blankZmxDir = Map.of("ZMX_DIR", " ", "XDG_RUNTIME_DIR", "/run/user/1000", "TMPDIR", "/tmp/td");
        assertEquals(Path.of("/run/user/1000", "zmx"), ZmxSocketForward.hostZmxDir(blankZmxDir::get, uid));
        var tmpdirOnly = Map.of("TMPDIR", "/tmp/td");
        assertEquals(Path.of("/tmp/td", "zmx-1000"), ZmxSocketForward.hostZmxDir(tmpdirOnly::get, uid));
        assertEquals(Path.of("/tmp", "zmx-1000"), ZmxSocketForward.hostZmxDir(Map.<String, String>of()::get, uid));
    }

    /** macOS shares no zmx sockets, so it must not resolve the dir, which can fork {@code id -u}. */
    @Test
    void sharedHostZmxDirResolvesNothingOnMacOS() {
        assertNull(ZmxSocketForward.sharedHostZmxDir(true, () -> fail("host zmx dir resolved on macOS")));
        assertEquals(zmxDir, ZmxSocketForward.sharedHostZmxDir(false, () -> zmxDir));
    }

    /**
     * zmx writes {@code logs/} under its dir, the instance's directory, after
     * any session, so cleanup has to remove it recursively, and through no
     * symlink a guest left there (#1772).
     */
    @Test
    void cleanupRemovesTheSessionLogsWithoutFollowingSymlinks(@TempDir Path outside) throws IOException {
        var kept = Files.writeString(outside.resolve("kept"), "x");
        var logs = Files.createDirectories(containerDir().resolve("logs"));
        Files.writeString(logs.resolve("zmx.log"), "log");
        Files.writeString(logs.resolve("isx.log"), "log");
        Files.createSymbolicLink(containerDir().resolve("escape"), outside);
        Files.createSymbolicLink(symlink(), sessionTarget());

        var out = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, dirs, out);

        assertEquals(List.of(), out.warnings());
        assertFalse(Files.exists(containerDir(), LinkOption.NOFOLLOW_LINKS), "container dir left behind");
        assertFalse(Files.exists(symlink(), LinkOption.NOFOLLOW_LINKS), "session symlink left behind");
        assertTrue(Files.exists(kept), "cleanup followed a symlink out of the container dir");
    }

    /** A user's ZMX_DIR may be a link of theirs, and cleanup still removes the session link through it. */
    @Test
    void cleanupGoesThroughTheUsersOwnLink(@TempDir Path elsewhere) throws IOException {
        Files.createDirectories(containerDir());
        Files.createSymbolicLink(symlink(), sessionTarget());
        var link = Files.createSymbolicLink(elsewhere.resolve("zmx"), zmxDir);

        var out = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, new ZmxSocketForward.HostDirs(dirs.instances(), link, OwnDirectory::processUid), out);

        assertEquals(List.of(), out.warnings());
        assertFalse(Files.exists(symlink(), LinkOption.NOFOLLOW_LINKS), "session symlink left behind");
        assertFalse(Files.exists(containerDir()), "container dir left behind");
    }

    /**
     * A host zmx dir another user made is not tidied, and does not stop the
     * instance's own directory from going: the two are separate (#1821).
     */
    @Test
    void cleanupRemovesTheInstanceDirWhateverTheHostZmxDirIs() throws IOException {
        Files.createDirectories(containerDir().resolve("logs"));
        Files.createSymbolicLink(symlink(), sessionTarget());
        Files.setPosixFilePermissions(zmxDir, PosixFilePermissions.fromString("rwxrwxrwx"));

        var out = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, dirs, out);

        assertEquals(List.of(), out.warnings());
        assertFalse(Files.exists(containerDir()), "container dir left behind");
        // The dir is the user's, so nobody else can have put the link in its way
        assertFalse(Files.exists(symlink(), LinkOption.NOFOLLOW_LINKS), "session symlink left behind");
    }

    /** An instance that never shared zmx sessions has nothing to warn of, whatever the dirs are like. */
    @Test
    void cleanupSaysNothingOfAnInstanceWithoutZmxSessions() throws IOException {
        Files.createDirectories(dirs.instances().resolve("other"));
        Files.setPosixFilePermissions(dirs.instances(), PosixFilePermissions.fromString("rwxrwxrwx"));

        var out = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, dirs, out);

        assertEquals(List.of(), out.warnings());
    }

    /** Nor through a directory of instances others can write to, where they could swap the instance's dir for a link. */
    @Test
    void cleanupDoesNotWalkAnInstancesDirOthersCanWrite() throws IOException {
        var logs = Files.createDirectories(containerDir().resolve("logs"));
        Files.writeString(logs.resolve("zmx.log"), "log");
        Files.setPosixFilePermissions(dirs.instances(), PosixFilePermissions.fromString("rwxrwxrwx"));

        var out = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, dirs, out);

        assertTrue(Files.exists(logs.resolve("zmx.log")), "cleanup walked an instances dir others can write to");
        assertTrue(out.warnings().getFirst().contains("which others can write to"), out.warnings().toString());
    }

    /** Nor one that is a link, which isx did not make. */
    @Test
    void cleanupDoesNotWalkAnInstancesLink(@TempDir Path elsewhere) throws IOException {
        var kept = Files.writeString(Files.createDirectories(elsewhere.resolve(NAME)).resolve("kept"), "x");
        Files.createSymbolicLink(dirs.instances(), elsewhere);

        var out = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, dirs, out);

        assertTrue(Files.exists(kept), "cleanup deleted through a link");
        assertTrue(out.warnings().getFirst().contains("which is a link (to "), out.warnings().toString());
    }

    /**
     * Ownership is checked against the uid isx runs as, never a guess: a uid
     * that cannot be read walks nothing, and a dir of another uid's is refused.
     */
    @Test
    void cleanupWalksNothingWithoutTheProcessUid() throws IOException {
        var logs = Files.createDirectories(containerDir().resolve("logs"));
        Files.writeString(logs.resolve("zmx.log"), "log");
        Files.createSymbolicLink(symlink(), sessionTarget());

        var unreadable = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, withUid(() -> { throw new IOException("no /proc"); }), unreadable);
        var another = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, withUid(() -> OwnDirectory.processUid() + 1), another);

        assertTrue(Files.exists(logs.resolve("zmx.log")), "cleanup walked a dir without knowing whose it is");
        assertTrue(Files.exists(symlink(), LinkOption.NOFOLLOW_LINKS), "session symlink removed from another uid's dir");
        assertTrue(unreadable.warnings().getFirst().contains("cannot tell which uid"), unreadable.warnings().toString());
        assertTrue(another.warnings().getFirst().contains("which belongs to uid"), another.warnings().toString());
    }

    private ZmxSocketForward.HostDirs withUid(java.util.concurrent.Callable<Integer> uid) {
        return new ZmxSocketForward.HostDirs(dirs.instances(), zmxDir, uid);
    }

    /**
     * #1821: on the {@code /tmp/zmx-<uid>} fallback another user can make the
     * host zmx dir first.  The guest's sessions never go there: the device
     * stays, mounting the instance's own directory, and no link is made in a
     * dir someone else can change, which the shell says.
     */
    @Test
    void aHostZmxDirOthersCanWriteKeepsTheDeviceButGetsNoLink() throws IOException {
        Files.setPosixFilePermissions(zmxDir, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertTrue(ZmxSocketForward.configure(incus, NAME, dirs, RecordingOutput.FAIL_ON_WARN));
        var out = new RecordingOutput();
        ZmxSocketForward.ensureSymlink(NAME, dirs, out);

        assertEquals(containerDir().toString(), device().get("source"));
        assertFalse(Files.exists(symlink(), LinkOption.NOFOLLOW_LINKS), "linked from a dir others can write to");
        assertTrue(out.warnings().getFirst().contains("host zmx cannot see the sessions of 'demo'"), out.warnings().toString());
        assertTrue(out.warnings().getFirst().contains("which others can write to"), out.warnings().toString());
    }

    /** The instances' directory and each instance's are made private, and a missing host zmx dir too. */
    @Test
    void configureMakesEveryDirectoryItCreatesPrivate() throws IOException {
        Files.delete(zmxDir);

        ZmxSocketForward.configure(incus, NAME, dirs, RecordingOutput.FAIL_ON_WARN);

        for (var dir : List.of(dirs.instances(), containerDir(), zmxDir)) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)), dir.toString());
        }
        assertEquals(sessionTarget(), Files.readSymbolicLink(symlink()));
    }

    /**
     * The guest owns its directory through the shifted mount and can open it
     * to anyone, so {@code zmx/} has to stay closed: one that grants others
     * any access is made {@code 0700} again, on the start's healthy path
     * too, where it costs no Incus request.
     */
    @Test
    void aReadableInstancesDirIsClosedAgain() throws IOException {
        Files.createDirectories(containerDir());
        Files.setPosixFilePermissions(containerDir(), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(dirs.instances(), PosixFilePermissions.fromString("rwxr-xr-x"));

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, dirs,
                instanceWithZmxDevice(containerDir().toString()), RecordingOutput.FAIL_ON_WARN);

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dirs.instances())));
        assertEquals(List.of(), daemon.requests());

        // Group access alone, which the ownership check lets through, is closed too
        Files.setPosixFilePermissions(dirs.instances(), PosixFilePermissions.fromString("rwxr-x---"));
        ZmxSocketForward.configure(incus, NAME, dirs, RecordingOutput.FAIL_ON_WARN);

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dirs.instances())));
        assertEquals(containerDir().toString(), device().get("source"));
    }

    /** An instance that shares no sessions gets no link, and its shell creates nothing. */
    @Test
    void ensureSymlinkLeavesAnInstanceWithoutZmxAlone() throws IOException {
        Files.delete(zmxDir);

        ZmxSocketForward.ensureSymlink(NAME, dirs, RecordingOutput.FAIL_ON_WARN);

        assertFalse(Files.exists(zmxDir, LinkOption.NOFOLLOW_LINKS));
    }

    /** A device from before #1821 points into the host zmx dir, and the next start moves it to the instance's own. */
    @Test
    void ensureHostDirForStart_deviceInTheHostZmxDir_movesToTheInstancesOwn() {
        var legacy = zmxDir.resolve("containers").resolve(NAME);

        ZmxSocketForward.ensureHostDirForStart(incus, NAME, dirs,
                instanceWithZmxDevice(legacy.toString()), RecordingOutput.FAIL_ON_WARN);

        assertEquals(containerDir().toString(), device().get("source"));
    }

    /**
     * When the instance's own directory is not the user's alone either, it is
     * option (a) of #1821: the start goes ahead without the device, and says
     * why.  The stamp lets the first start that finds the directory right add
     * it back, and a start that still finds it wrong writes nothing.
     */
    @Test
    void anInstanceDirOthersCanWriteWithholdsTheDeviceUntilItIsFixed() throws IOException {
        Files.createDirectories(containerDir());
        daemon.device(NAME, DEVICE, Map.of("type", "disk", "source", containerDir().toString(),
                "path", "/home/agentuser/.zmx", "required", "false"));
        var instances = dirs.instances();
        Files.setPosixFilePermissions(instances, PosixFilePermissions.fromString("rwxrwxrwx"));

        var refused = new RecordingOutput();
        ZmxSocketForward.ensureHostDirForStart(incus, NAME, dirs, incus.instanceMetadata(NAME), refused);

        assertFalse(daemon.instanceDevices(NAME).containsKey(DEVICE), "device mounted from a dir others can write to");
        assertEquals("true", daemon.instance(NAME).path("config").path("user.incus-spawn.zmx-withheld").asText(""));
        assertTrue(refused.warnings().getFirst().contains("'demo' shares no zmx sessions with the host"),
                refused.warnings().toString());
        assertTrue(refused.warnings().getFirst().contains("which others can write to"), refused.warnings().toString());

        daemon.clearRequests();
        var again = new RecordingOutput();
        ZmxSocketForward.ensureHostDirForStart(incus, NAME, dirs, incus.instanceMetadata(NAME), again);
        assertFalse(again.warnings().isEmpty());
        assertTrue(daemon.requests().stream().noneMatch(r -> r.startsWith("PUT") || r.startsWith("PATCH")),
                daemon.requests().toString());

        Files.setPosixFilePermissions(instances, PosixFilePermissions.fromString("rwx------"));
        ZmxSocketForward.ensureHostDirForStart(incus, NAME, dirs, incus.instanceMetadata(NAME), RecordingOutput.FAIL_ON_WARN);

        assertEquals(containerDir().toString(), daemon.instanceDevices(NAME).get(DEVICE).get("source"));
        assertEquals("", daemon.instance(NAME).path("config").path("user.incus-spawn.zmx-withheld").asText(""));
    }

    /** A withheld instance's directory is not linked either: others can change what it holds. */
    @Test
    void ensureSymlinkDoesNotLinkIntoAnInstanceDirOthersCanWrite() throws IOException {
        Files.createDirectories(containerDir());
        Files.setPosixFilePermissions(containerDir(), PosixFilePermissions.fromString("rwxrwxrwx"));

        ZmxSocketForward.ensureSymlink(NAME, dirs, RecordingOutput.FAIL_ON_WARN);

        assertFalse(Files.exists(symlink(), LinkOption.NOFOLLOW_LINKS));
    }

    /** An instance of the user's own that someone else could change is not mounted either, even on the healthy path. */
    @Test
    void ensureHostDirForStart_healthyLookingDirOthersCanWrite_withholdsTheDevice() throws IOException {
        Files.createDirectories(containerDir());
        Files.setPosixFilePermissions(containerDir(), PosixFilePermissions.fromString("rwxrwxrwx"));
        daemon.device(NAME, DEVICE, Map.of("type", "disk", "source", containerDir().toString(),
                "path", "/home/agentuser/.zmx", "required", "false"));

        var out = new RecordingOutput();
        ZmxSocketForward.ensureHostDirForStart(incus, NAME, dirs, incus.instanceMetadata(NAME), out);

        assertFalse(daemon.instanceDevices(NAME).containsKey(DEVICE));
        assertTrue(out.warnings().getFirst().contains("which others can write to"), out.warnings().toString());
    }

    /** A real Unix socket at {@code path}, as zmx leaves one per session. */
    private static void socket(Path path) throws IOException {
        try (var channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.bind(UnixDomainSocketAddress.of(path));
        }
    }

    private Path sessionLink(String instance, String session) {
        return zmxDir.resolve("isx-" + instance + "." + session);
    }

    /**
     * Every session of an instance shows in host zmx, not only its default one (#2059): each socket
     * gets {@code isx-<name>.<session>}, and only a socket with a name a shell could ask for.
     */
    @Test
    void everySessionSocketIsLinkedBesideTheDefaultOne(@TempDir Path outside) throws IOException {
        ZmxSocketForward.configure(incus, NAME, dirs, RecordingOutput.FAIL_ON_WARN);
        socket(containerDir().resolve("isx"));
        socket(containerDir().resolve("work"));
        socket(containerDir().resolve("2"));
        socket(containerDir().resolve("a.b"));
        Files.writeString(containerDir().resolve("notes"), "not a socket");
        socket(outside.resolve("elsewhere"));
        Files.createSymbolicLink(containerDir().resolve("escape"), outside.resolve("elsewhere"));
        Files.createDirectories(containerDir().resolve("logs"));

        ZmxSocketForward.ensureSymlink(NAME, dirs, RecordingOutput.FAIL_ON_WARN);

        assertEquals(sessionTarget(), Files.readSymbolicLink(symlink()));
        assertEquals(containerDir().resolve("work"), Files.readSymbolicLink(sessionLink(NAME, "work")));
        assertEquals(containerDir().resolve("2"), Files.readSymbolicLink(sessionLink(NAME, "2")));
        for (var none : List.of("isx", "a.b", "notes", "escape", "logs")) {
            assertFalse(Files.exists(sessionLink(NAME, none), LinkOption.NOFOLLOW_LINKS), none + " was linked");
        }
    }

    /** A session that ended loses its link at the next shell; the default one keeps its own. */
    @Test
    void theLinkOfASessionThatEndedIsDropped() throws IOException {
        ZmxSocketForward.configure(incus, NAME, dirs, RecordingOutput.FAIL_ON_WARN);
        socket(containerDir().resolve("work"));
        ZmxSocketForward.ensureSymlink(NAME, dirs, RecordingOutput.FAIL_ON_WARN);
        assertTrue(Files.isSymbolicLink(sessionLink(NAME, "work")));

        Files.delete(containerDir().resolve("work"));
        ZmxSocketForward.ensureSymlink(NAME, dirs, RecordingOutput.FAIL_ON_WARN);

        assertFalse(Files.exists(sessionLink(NAME, "work"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isSymbolicLink(symlink()));
    }

    /** The guest makes the sessions, so it cannot fill the host zmx dir with links. */
    @Test
    void sessionLinksAreBounded() throws IOException {
        ZmxSocketForward.configure(incus, NAME, dirs, RecordingOutput.FAIL_ON_WARN);
        for (int i = 0; i < ZmxSocketForward.MAX_SESSION_LINKS + 5; i++) socket(containerDir().resolve("s" + i));

        ZmxSocketForward.ensureSymlink(NAME, dirs, RecordingOutput.FAIL_ON_WARN);

        try (var links = Files.list(zmxDir)) {
            assertEquals(ZmxSocketForward.MAX_SESSION_LINKS,
                    links.filter(link -> link.getFileName().toString().startsWith("isx-" + NAME + ".")).count());
        }
    }

    /**
     * Destroy removes every session link of the instance, and none of another whose name starts
     * the same: {@code demo-2} is not a session of {@code demo}.
     */
    @Test
    void cleanupRemovesEverySessionLinkAndNoOtherInstances() throws IOException {
        Files.createDirectories(containerDir());
        Files.createSymbolicLink(symlink(), sessionTarget());
        Files.createSymbolicLink(sessionLink(NAME, "work"), containerDir().resolve("work"));
        Files.createSymbolicLink(sessionLink(NAME, "2"), containerDir().resolve("2"));
        var other = Files.createSymbolicLink(zmxDir.resolve("isx-" + NAME + "-2"), dirs.instanceDir(NAME + "-2").resolve("isx"));
        var othersSession = Files.createSymbolicLink(sessionLink(NAME + "-2", "work"), dirs.instanceDir(NAME + "-2").resolve("work"));

        var out = new RecordingOutput();
        ZmxSocketForward.cleanup(NAME, dirs, out);

        assertEquals(List.of(), out.warnings());
        assertFalse(Files.exists(symlink(), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(sessionLink(NAME, "work"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(sessionLink(NAME, "2"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isSymbolicLink(other));
        assertTrue(Files.isSymbolicLink(othersSession));
    }
}
