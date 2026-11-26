package dev.incusspawn.lifecycle;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.HostLock;
import dev.incusspawn.util.Output;
import dev.incusspawn.util.OutputFormat;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The host half of the {@code rex} tool (#2647): an instance whose template installs Rex is added to
 * the host's Rex hosts on its first start, labelled with its name and reached at {@code ssh://<name>},
 * and removed when it is destroyed. So the host's Rex lists the instance and the session the tool
 * starts in it, with no {@code rex hosts add} by hand. Only when the host has {@code rex} on its
 * {@code PATH}, on Linux and macOS alike.
 *
 * <p>The endpoint is the instance's own Host block, never its start alias: a stopped instance stays
 * stopped when Rex tries to reach it, and {@code isx start} or {@code isx shell} starts it. Unlike
 * herdr, Rex has no way to leave a host alone, so a start alias would let any Rex client start
 * every stopped instance it lists.
 *
 * <p>What isx did is recorded on the host, one file per instance name in {@code records}: the id
 * Rex gave the host isx added, or {@link #NOT_ADDED} when it decided not to (an untrusted build, a
 * host of that label the user has) or could not, or {@link #RETRY} after a first failed add, which
 * the next start tries once more. A destroy removes only the recorded id, never a host the user
 * added under the same label, and a destroy with no record runs nothing. A destroy while Rex is not
 * on the {@code PATH} keeps the id in {@code .orphaned/}, and a later start or destroy with Rex
 * removes it.
 */
public final class RexIntegration implements HostIntegration {

    static final String TOOL = "rex";
    /** Recorded when isx added no host for the instance, so it neither retries nor removes one. */
    static final String NOT_ADDED = "-";
    /** Recorded after a first failed add: the next start tries once more, as herdr's does. */
    static final String RETRY = "retry";
    /** {@code rex hosts add} writes only to the home server, which is usually this machine's own. */
    static final Duration QUICK = Duration.ofSeconds(20);
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern HOST_ID = Pattern.compile("host:" + UUID);
    private static final Pattern ORPHAN = Pattern.compile(UUID);

    private final HostCommands host;
    private final Supplier<Path> records;
    private final BiPredicate<String, String> reachable;

    public RexIntegration(HostCommands host, Supplier<Path> records) {
        this(host, records, HerdrIntegration.SshAlias.MANAGED::resolves);
    }

    /**
     * @param reachable whether {@code ssh <name>} reaches the instance at its static address through
     *                  isx's SSH config with its host key pinned, which Rex's {@code BatchMode} ssh needs
     */
    RexIntegration(HostCommands host, Supplier<Path> records, BiPredicate<String, String> reachable) {
        this.host = host;
        this.records = records;
        this.reachable = reachable;
    }

    /** The endpoint Rex is given: the instance's Host block in isx's SSH config, which sets its user. */
    static String endpoint(String name) {
        return "ssh://" + name;
    }

    @Override
    public String tool() {
        return TOOL;
    }

    @Override
    public boolean done(String name) {
        var first = firstLine(name);
        return first != null && !RETRY.equals(first);
    }

    @Override
    public boolean registered(String name) {
        return recordedId(name) != null;
    }

    @Override
    public void started(IncusClient incus, String name, JqValue instance, Output out) {
        if (!host.onPath(TOOL)) return;
        removeOrphans(out);
        var shown = OutputFormat.oneLine(name);
        // One first start at a time: a branch and an orchestrator's start must not both add it
        try (var lock = HostLock.acquire(records.get().resolve(".lock"), "adding " + shown + " to Rex", out::step)) {
            if (done(name)) return;
            if (!HostIntegration.trusted(instance)) {
                out.step("Not adding " + shown + " to Rex: its template is project-local or untrusted.");
                record(name, NOT_ADDED);
                return;
            }
            // Nothing recorded, so a later start, once the Host block is in place, adds it
            if (!reachable.test(name, instance.path("config").pathText(Metadata.STATIC_IP, ""))) {
                out.step("Not adding " + shown + " to Rex yet: ssh " + shown + " does not reach it through isx's SSH config.");
                return;
            }
            var retrying = RETRY.equals(firstLine(name));
            var endpoint = endpoint(name);
            var added = host.run(QUICK, List.of(TOOL, "hosts", "add", name, endpoint));
            if (!added.ok()) {
                if (added.output().contains("already exists")) {
                    // The user's own host of that label, which isx never replaces or removes
                    out.step("Not adding " + shown + " to Rex: it already has a host of that name.");
                    record(name, NOT_ADDED);
                    return;
                }
                if (!retrying) {
                    out.step("Could not add " + shown + " to Rex yet (" + lastLine(added.output()) + "); isx tries again on its next start.");
                    record(name, RETRY);
                    return;
                }
                out.warn("could not add " + shown + " to Rex: " + lastLine(added.output()),
                        "Add it yourself: rex hosts add " + shown + " " + endpoint);
                record(name, NOT_ADDED);
                return;
            }
            // Rex added it: a list that fails now must not leave it unrecorded, or the next
            // start's add would find the label taken and take isx's own host for the user's
            String id;
            try {
                id = listedId(name, endpoint);
            } catch (RuntimeException e) {
                id = null;
            }
            if (id == null) {
                out.warn("added " + shown + " to Rex, but could not read the id Rex gave it.",
                        "isx does not remove it when the instance is destroyed: rex hosts remove " + shown);
                record(name, NOT_ADDED);
                return;
            }
            record(name, id);
            out.step("Added " + shown + " to Rex.");
        } catch (IOException | RuntimeException e) {
            out.warn("could not add " + shown + " to Rex: " + e.getMessage());
        }
    }

    @Override
    public void destroyed(String name, Output out) {
        Path record;
        try {
            record = record(name);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!Files.exists(record)) return;
        var shown = OutputFormat.oneLine(name);
        try {
            var id = recordedId(name);
            if (id != null && !host.onPath(TOOL)) {
                // Kept so a later isx with Rex removes it, but not under the name: a new instance
                // of that name must not be taken as already added
                var orphan = orphans().resolve(id.substring("host:".length()));
                Files.createDirectories(orphan.getParent());
                Files.writeString(orphan, name + "\n");
                Files.deleteIfExists(record);
                out.warn("could not remove " + shown + " from Rex: rex is not on the PATH.",
                        "Remove it yourself: rex hosts remove " + shown);
                return;
            }
            if (host.onPath(TOOL)) removeOrphans(out);
            if (id != null) {
                var label = listedLabel(id);
                if (label != null) {
                    var removed = host.run(QUICK, List.of(TOOL, "hosts", "remove", label));
                    if (!removed.ok()) {
                        out.warn("could not remove " + shown + " from Rex: " + lastLine(removed.output()),
                                "Remove it yourself: rex hosts remove " + OutputFormat.oneLine(label));
                        return;
                    }
                }
            }
            Files.deleteIfExists(record);
        } catch (IOException | RuntimeException e) {
            out.warn("could not remove " + shown + " from Rex: " + e.getMessage());
        }
    }

    /** Hosts isx added for instances destroyed while Rex was not on the PATH, one file per host id. */
    private Path orphans() {
        return records.get().resolve(".orphaned");
    }

    /** Remove from Rex the hosts {@link #orphans} holds, and forget them; one still failing stays. */
    private void removeOrphans(Output out) {
        var dir = orphans();
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            var orphaned = files.filter(f -> ORPHAN.matcher(f.getFileName().toString()).matches()).toList();
            if (orphaned.isEmpty()) return;
            var hosts = listed();
            for (var orphan : orphaned) {
                var label = labelOf(hosts, "host:" + orphan.getFileName());
                if (label != null && !host.run(QUICK, List.of(TOOL, "hosts", "remove", label)).ok()) continue;
                Files.deleteIfExists(orphan);
            }
        } catch (IOException | RuntimeException e) {
            out.warn("could not remove earlier instances' hosts from Rex: " + e.getMessage());
        }
    }

    /** The hosts {@code rex hosts list --json} lists, each a {@code {host, from, held}} object. */
    private List<JqValue> listed() {
        var listed = host.run(QUICK, List.of(TOOL, "hosts", "list", "--json"));
        if (!listed.ok()) throw new IllegalStateException("rex hosts list: " + lastLine(listed.output()));
        return Jjq.parseForeign(listed.output(), "rex hosts list --json").path("hosts").asList().stream()
                .map(entry -> entry.path("host")).toList();
    }

    /** The id of the host labelled {@code name} that is reached at {@code endpoint} alone, or null. */
    private String listedId(String name, String endpoint) {
        return listed().stream()
                .filter(h -> name.equalsIgnoreCase(h.pathText("label", "")))
                .filter(h -> h.path("endpoints").asList().stream().map(e -> e.asText("")).toList().equals(List.of(endpoint)))
                .map(h -> h.pathText("id", ""))
                .filter(id -> HOST_ID.matcher(id).matches())
                .findFirst().orElse(null);
    }

    /** The label of the host Rex lists with {@code id}, or null when it lists none: the user may have removed it. */
    private String listedLabel(String id) {
        return labelOf(listed(), id);
    }

    /** The label of the host in {@code hosts} with {@code id}, or null. */
    private static String labelOf(List<JqValue> hosts, String id) {
        return hosts.stream().filter(h -> id.equals(h.pathText("id", "")))
                .map(h -> h.pathText("label", "")).filter(label -> !label.isEmpty())
                .findFirst().orElse(null);
    }

    private String recordedId(String name) {
        var first = firstLine(name);
        return first != null && HOST_ID.matcher(first).matches() ? first : null;
    }

    /** The record's first line, or null when there is no record. */
    private String firstLine(String name) {
        try {
            var file = record(name);
            if (!Files.exists(file)) return null;
            var lines = Files.readString(file).strip().lines().toList();
            return lines.isEmpty() ? "" : lines.getFirst();
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private Path record(String name) {
        var dir = records.get();
        var file = dir.resolve(name).normalize();
        // Incus validates instance names, so this only guards against a caller's mistake
        if (!dir.equals(file.getParent()) || name.startsWith(".")) throw new IllegalArgumentException("not an instance name: " + name);
        return file;
    }

    private void record(String name, String value) throws IOException {
        var file = record(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, value + "\n");
    }

    private static String lastLine(String output) {
        var lines = output.strip().lines().toList();
        return lines.isEmpty() ? "(no output)" : OutputFormat.oneLine(lines.getLast());
    }
}
