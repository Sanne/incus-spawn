package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.tool.ToolDef;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/**
 * The last reading of each instance's guest ({@link GuestRead}), kept on the host so a stopped
 * instance shows what it held without being started (#2752). Every read of a running guest the
 * overview makes that ends well is kept, and so is one made just before each stop isx makes ({@link #beforeStop}),
 * since a stopped instance's clones and sessions cannot change until it starts again.
 *
 * <p>One file per instance incarnation, so a new instance of the same name never shows an older
 * one's reading. What is kept is the guest's answer as the exec gave it, already bounded, and it is
 * parsed again by {@link GuestRead#parse} when shown, so it stays the guest's untrusted text; an
 * answer to another version of the script is not read back. Each file is written whole or not at
 * all, readable by the host user alone.
 */
public record KeptReads(Path dir) {

    /**
     * How long the read before a stop may take: a person waits for the stop, and the read
     * usually takes well under a second (docs/dev/PERFORMANCE-NOTES.md "The overview's guest read, on Linux").
     */
    static final Duration STOP_LIMIT = Duration.ofSeconds(2);

    /** A temporary file older than this is a write that never finished. */
    private static final Duration STALE_TEMP = Duration.ofHours(1);

    /** Names the script a kept answer came from, so a changed script never parses an older one's answer. */
    static final String SCRIPT_SHA = ToolDef.sha256hex(GuestRead.SCRIPT);

    /** A file larger than any answer the bound lets through, with its JSON escapes, is not read. */
    private static final long MAX_FILE = 8L * (GuestRead.MAX_OUTPUT + 1024);

    public static KeptReads host() {
        return new KeptReads(Environment.cacheDir().resolve("overview-reads"));
    }

    static String key(String name, String incarnation) {
        return ToolDef.sha256hex(name + "\n" + incarnation);
    }

    /** The kept reading of the instance, with its time; null when there is none or it cannot be read. */
    public GuestRead get(String name, String incarnation) {
        var answer = answer(name, incarnation);
        return answer == null ? null : answer.parsed();
    }

    /** The kept answer as the guest gave it; null when there is none or it cannot be read. */
    private GuestRead.Answer answer(String name, String incarnation) {
        if (incarnation.isEmpty()) return null;
        var file = dir.resolve(key(name, incarnation) + ".json");
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE) return null;
            var json = JqValues.parseStrict(Files.readString(file));
            if (!name.equals(json.pathText("name", null)) || !incarnation.equals(json.pathText("incarnation", null))
                    || !SCRIPT_SHA.equals(json.pathText("script", null))) return null;
            var output = json.pathText("output", null);
            if (output == null || output.length() > GuestRead.MAX_OUTPUT) return null;
            var cut = json.getField("cut");
            return new GuestRead.Answer((int) Jjq.longOr(json.getField("exit"), -1), output,
                    cut == null || !cut.isBoolean() || cut.booleanValue(), Instant.parse(json.pathText("at", "")));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Keeps {@code answer} as the instance's last reading, only when the script ended well (exit 0):
     * a read killed at its limit, cut short by a stop or failed would replace a good earlier
     * reading with unknown. One that cannot be written is only not kept.
     */
    void put(String name, String incarnation, GuestRead.Answer answer) {
        if (incarnation.isEmpty() || answer.exit() != 0) return;
        var json = JqObject.builder(7)
                .put("name", name)
                .put("incarnation", incarnation)
                .put("script", SCRIPT_SHA)
                .put("at", answer.at().toString())
                .put("exit", (long) answer.exit())
                .put("cut", answer.cut())
                .put("output", answer.output())
                .build().toJsonString();
        try {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            var temp = Files.createTempFile(dir, ".read", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.writeString(temp, json);
                Files.move(temp, dir.resolve(key(name, incarnation) + ".json"),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException | RuntimeException e) {
            // The overview still shows the read it made; only a later stopped view lacks it
        }
    }

    /**
     * Reads the running instance's guest and keeps the reading, just before isx stops it. Never
     * throws: a guest that does not answer in {@link #STOP_LIMIT}, or whose read fails, is stopped
     * all the same, and its earlier reading, if any, stays.
     */
    public void beforeStop(IncusClient incus, String name, String incarnation) {
        if (incarnation == null || incarnation.isBlank()) return;
        try {
            put(name, incarnation.strip(), GuestRead.ask(incus, name, STOP_LIMIT));
        } catch (RuntimeException e) {
            // Not kept: the stop is what was asked for
        }
    }

    /**
     * Moves the instance's reading to its new name, after a rename: it is the same instance, with
     * the same incarnation. Never throws; a reading that cannot be moved is only lost.
     */
    public void renamed(String from, String to, String incarnation) {
        var answer = answer(from, incarnation);
        if (answer == null) return;
        put(to, incarnation, answer);
        try {
            Files.deleteIfExists(dir.resolve(key(from, incarnation) + ".json"));
        } catch (IOException | RuntimeException e) {
            // Shown for no instance once the old name is gone: the next full overview removes it
        }
    }

    /** Removes the readings of instances not in {@code instances}: they were destroyed. */
    public void prune(List<InstanceInfo> instances) {
        if (!Files.isDirectory(dir)) return;
        var keep = new HashSet<String>();
        for (var i : instances) keep.add(key(i.name(), OverviewCommand.incarnation(i)) + ".json");
        try (var files = Files.list(dir)) {
            for (var file : files.toList()) {
                var fileName = file.getFileName().toString();
                if (fileName.endsWith(".json") && !keep.contains(fileName)) Files.deleteIfExists(file);
                // A write a crash interrupted left the guest's answer behind; one under way is younger
                else if (fileName.startsWith(".read") && fileName.endsWith(".tmp") && staleTemp(file)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException | RuntimeException e) {
            // A reading left behind is shown for no instance: only its file stays
        }
    }

    /** Whether the temporary file is older than {@link #STALE_TEMP}; one a write just moved away is not. */
    private static boolean staleTemp(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant().isBefore(Instant.now().minus(STALE_TEMP));
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether {@code read}, kept, is older than the instance's last start: it may have changed since. */
    public static boolean beforeLastStart(GuestRead read, InstanceInfo instance) {
        var started = OverviewCommand.lastUsed(instance);
        return read.at() != null && started != null && read.at().isBefore(started);
    }
}
