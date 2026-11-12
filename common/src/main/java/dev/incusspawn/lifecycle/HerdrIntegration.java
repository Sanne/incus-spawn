package dev.incusspawn.lifecycle;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.util.HostLock;
import dev.incusspawn.util.Output;
import dev.incusspawn.util.OutputFormat;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The host half of the {@code herdr} tool (#1720): an instance whose template installs herdr is
 * added to the host's herdr as an SSH machine on its first start, and removed when it is
 * destroyed, so the user never runs {@code herdr machine add} and herdr never installs itself
 * over SSH (the tool did). Only when the host has {@code herdr} on its {@code PATH}.
 *
 * <p>The machine's SSH target is the instance's {@link SshKeyManager#startAlias}, whose Host block in
 * isx's managed SSH config starts a stopped instance (#2038): herdr keeps its machines across a stop
 * and reconnects to them by itself, which the instance's own block never answers. What isx did is
 * recorded on the host, one file per instance name in {@code records}: the id of the machine it
 * added and, on a second line, its target, or {@link #NOT_ADDED} when it decided not to
 * (an untrusted build) or could not (a failed add, whose warning says how to add it by hand). So a
 * later start reads one file and runs nothing, a copy or a renamed instance (another name) gets
 * its own, and a destroy removes only the machine isx added: never one the user added for a real
 * host that happens to share the instance's name. A destroy with no record runs nothing. A record
 * with no target is a machine an older isx added for the instance's name, which never starts it:
 * the next start replaces it once.
 *
 * <p>herdr reconnects to a machine it lost by itself, through the start alias, so an open herdr
 * would start a stopped instance again within two minutes (#2041). So before isx stops an instance
 * it {@code herdr machine disable}s its machine, which herdr then draws dimmed and leaves alone,
 * and records {@link #DISABLED} as a third line; the next start isx makes enables it again, and so
 * does the user enabling it in herdr, whose connection then starts the instance. A stop isx does
 * not make, a guest's poweroff or the host's reboot, leaves the machine enabled.
 */
public final class HerdrIntegration implements HostIntegration {

    static final String TOOL = "herdr";
    /** Recorded when isx added no machine for the instance, so it neither retries nor removes one. */
    static final String NOT_ADDED = "-";
    /**
     * Recorded after a first failed add (a timeout, a refused connection): the next start tries once
     * more, and a second failure is {@link #NOT_ADDED} (Sanne's decision on #1823).
     */
    static final String RETRY = "retry";
    /** Recorded as a third line after isx disabled the machine for a stop; the next start enables it. */
    static final String DISABLED = "disabled";
    /** Its first connection starts herdr's server in the instance, about a second when it works. */
    static final Duration ADD_TIMEOUT = Duration.ofSeconds(30);
    static final Duration QUICK = Duration.ofSeconds(10);
    private static final String HERDR_ID = "[0-9a-f]{32}";
    private static final Pattern HERDR_ID_PATTERN = Pattern.compile(HERDR_ID);
    private static final Pattern ADDED_ID = Pattern.compile("\\b(" + HERDR_ID + ")\\b");

    private final HostCommands host;
    private final Supplier<Path> records;
    private final SshAlias alias;

    /**
     * Whether {@code ssh <name>} on this host reaches the instance: the managed config holds its Host
     * block for its static address, and ~/.ssh/config includes that config. Without both, herdr
     * would resolve the name as any host, possibly a real machine of that name, and install and
     * start its server there. And its host key is pinned (#1863): herdr forces
     * {@code StrictHostKeyChecking=yes}, so without the pin it saves a machine it never reaches.
     */
    public interface SshAlias {
        boolean resolves(String name, String staticIp);

        /**
         * The target herdr is given, which starts the instance when it is stopped, or null when its
         * Host block could not be written (reported).
         */
        default String startTarget(String name) {
            return SshKeyManager.startAlias(name);
        }

        SshAlias MANAGED = new SshAlias() {
            @Override
            public boolean resolves(String name, String staticIp) {
                return SshKeyManager.hasSshConfigInclude() && SshKeyManager.hasHostEntry(name, staticIp)
                        && SshKeyManager.hasHostKeys(name);
            }

            @Override
            public String startTarget(String name) {
                return SshKeyManager.hasStartEntry(name) || SshKeyManager.addStartEntry(name)
                        ? SshKeyManager.startAlias(name) : null;
            }
        };
    }

    public HerdrIntegration(HostCommands host, Supplier<Path> records) {
        this(host, records, SshAlias.MANAGED);
    }

    HerdrIntegration(HostCommands host, Supplier<Path> records, SshAlias alias) {
        this.host = host;
        this.records = records;
        this.alias = alias;
    }

    @Override
    public String tool() {
        return TOOL;
    }

    @Override
    public boolean done(String name) {
        var record = record(name);
        if (!Files.exists(record)) return false;
        try {
            var lines = Files.readString(record).strip().lines().toList();
            var first = lines.isEmpty() ? "" : lines.getFirst();
            // A machine an older isx added has no target, and is replaced once
            return !RETRY.equals(first) && !(HERDR_ID_PATTERN.matcher(first).matches() && lines.size() < 2)
                    && !disabled(lines);
        } catch (IOException e) {
            return true;
        }
    }

    /** A recorded machine id: not {@link #NOT_ADDED}, not {@link #RETRY}. */
    @Override
    public boolean registered(String name) {
        return recordedId(name) != null;
    }

    /** The machine isx added targets the instance's start alias. */
    @Override
    public boolean usesStartAlias(String name) {
        return usesStartAlias(name, lines(name));
    }

    private static boolean usesStartAlias(String name, List<String> lines) {
        return lines.size() > 1 && lines.get(1).equals(SshKeyManager.startAlias(name));
    }

    /** Whether {@code lines}, a record, says isx disabled the machine for a stop. */
    private static boolean disabled(List<String> lines) {
        return lines.size() > 2 && DISABLED.equals(lines.get(2));
    }

    /** The record's lines, none when there is no record. */
    private List<String> lines(String name) {
        try {
            return Files.readString(record(name)).strip().lines().toList();
        } catch (IOException | IllegalArgumentException e) {
            return List.of();
        }
    }

    /**
     * Disable the machine isx added, so herdr does not start the instance again: only one that
     * targets its start alias, which is the one herdr would start it through.
     */
    @Override
    public void stopping(String name, Output out) {
        var lines = lines(name);
        var id = recordedId(lines);
        if (id == null || disabled(lines) || !usesStartAlias(name, lines) || !host.onPath(TOOL)) return;
        var shown = OutputFormat.oneLine(name);
        var disabled = host.run(QUICK, List.of(TOOL, "machine", "disable", id));
        if (!disabled.ok()) {
            out.warn("could not disable " + shown + " in herdr: " + lastLine(disabled.output()),
                    "An open herdr may start it again; disable it yourself: herdr machine disable " + id);
            return;
        }
        try {
            record(name, lines.get(0) + "\n" + lines.get(1) + "\n" + DISABLED);
        } catch (IOException e) {
            out.warn("could not record that " + shown + " is disabled in herdr: " + e.getMessage(),
                    "Enable it again after starting it: herdr machine enable " + id);
        }
    }

    @Override
    public void stopFailed(String name, Output out) {
        var lines = lines(name);
        if (disabled(lines)) enable(name, lines, out);
    }

    /**
     * Enable the machine isx {@link #stopping disabled}, and forget that it did: once, so a machine
     * the user removed in the meantime is not asked for on every start.
     */
    private void enable(String name, List<String> lines, Output out) {
        var id = recordedId(lines);
        var shown = OutputFormat.oneLine(name);
        try {
            record(name, lines.get(0) + "\n" + lines.get(1));
        } catch (IOException e) {
            out.warn("could not record that " + shown + " is enabled in herdr: " + e.getMessage());
            return;
        }
        var enabled = host.run(QUICK, List.of(TOOL, "machine", "enable", id));
        if (!enabled.ok()) {
            out.warn("could not enable " + shown + " in herdr: " + lastLine(enabled.output()),
                    "Enable it yourself: herdr machine enable " + id);
        }
    }

    /** The id of the machine isx added for {@code name}, or null when it added none. */
    private String recordedId(String name) {
        return recordedId(lines(name));
    }

    /** The machine id {@code lines}, a record, holds, or null. */
    private static String recordedId(List<String> lines) {
        return !lines.isEmpty() && HERDR_ID_PATTERN.matcher(lines.getFirst()).matches() ? lines.getFirst() : null;
    }

    @Override
    public void started(IncusClient incus, String name, JqValue instance, Output out) {
        if (!host.onPath(TOOL)) return;
        var lines = lines(name);
        if (disabled(lines)) {
            enable(name, lines, out);
            return;
        }
        removeOrphans(out);
        var shown = OutputFormat.oneLine(name);
        // One first start at a time: a branch and an orchestrator's start must not both add it
        try (var lock = HostLock.acquire(records.get().resolve(".lock"), "adding " + shown + " to herdr", out::step)) {
            if (done(name)) return;
            if (!HostIntegration.trusted(instance)) {
                out.step("Not adding " + shown + " to herdr: its template is project-local or untrusted.");
                record(name, NOT_ADDED);
                return;
            }
            // Nothing recorded, so a later start, once the alias is in place, adds it
            if (!alias.resolves(name, instance.path("config").pathText(Metadata.STATIC_IP, ""))) {
                out.step("Not adding " + shown + " to herdr yet: ssh " + shown + " does not reach it through isx's SSH config.");
                return;
            }
            var target = alias.startTarget(name);
            if (target == null) {
                out.step("Not adding " + shown + " to herdr yet: isx could not write its SSH alias.");
                return;
            }
            var previous = recordedId(name);
            if (previous != null) {
                var listed = listedOrNull(previous);
                if (listed == null) {
                    // Asked again on the next start, as a first failed add is
                    out.step("Could not replace " + shown + " in herdr yet (herdr machine list failed); isx tries again on its next start.");
                    return;
                }
                if (listed && !removed(previous, shown, out)) {
                    // Kept as it is, and not tried again: its record now says what it reaches
                    record(name, previous + "\n" + name);
                    return;
                }
            }
            var retrying = Files.exists(record(name)) && RETRY.equals(Files.readString(record(name)).strip());
            var added = host.run(ADD_TIMEOUT, List.of(TOOL, "machine", "add", target, "--label", name));
            var id = added.ok() ? addedId(added.output()) : null;
            if (id == null) {
                if (!retrying) {
                    // Once more on the next start: the guest's sshd may not have been up yet
                    out.step("Could not add " + shown + " to herdr yet (" + lastLine(added.output()) + "); isx tries again on its next start.");
                    record(name, RETRY);
                    return;
                }
                out.warn("could not add " + shown + " to herdr: " + lastLine(added.output()),
                        "Add it yourself: herdr machine add " + OutputFormat.oneLine(target) + " --label " + shown);
                record(name, NOT_ADDED);
                return;
            }
            record(name, id + "\n" + target);
            out.step("Added " + shown + " to herdr.");
        } catch (HostLock.HostLockException | IOException e) {
            out.warn("could not add " + shown + " to herdr: " + e.getMessage());
        }
    }

    /**
     * Remove {@code id}, a machine an older isx added for the instance's name, whose connection never
     * starts it, so the instance can be added again under its {@link SshKeyManager#startAlias}.
     * herdr cannot change a machine's target. Whether it is gone; why not is warned.
     */
    private boolean removed(String id, String shown, Output out) {
        var removed = host.run(QUICK, List.of(TOOL, "machine", "remove", id));
        if (removed.ok()) return true;
        out.warn("could not replace " + shown + " in herdr: " + lastLine(removed.output()),
                "While it is stopped, herdr cannot reach it: start it with isx start " + shown);
        return false;
    }

    /** {@link #listed}, or null when herdr could not say. */
    private Boolean listedOrNull(String id) {
        try {
            return listed(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void destroyed(String name, Output out) {
        var record = record(name);
        if (!Files.exists(record)) return;
        var shown = OutputFormat.oneLine(name);
        try {
            var id = recordedId(name);
            if (id == null) id = "";
            if (HERDR_ID_PATTERN.matcher(id).matches() && !host.onPath(TOOL)) {
                // Kept so a later isx with herdr removes it, but not under the name: a new instance
                // of that name must not be taken as already added (review of #1823)
                var orphan = orphans().resolve(id);
                Files.createDirectories(orphan.getParent());
                Files.writeString(orphan, name + "\n");
                Files.deleteIfExists(record);
                out.warn("could not remove " + shown + " from herdr: herdr is not on the PATH.",
                        "Remove it yourself: herdr machine remove " + id);
                return;
            }
            if (host.onPath(TOOL)) removeOrphans(out);
            if (HERDR_ID_PATTERN.matcher(id).matches() && listed(id)) {
                var removed = host.run(QUICK, List.of(TOOL, "machine", "remove", id));
                if (!removed.ok()) {
                    out.warn("could not remove " + shown + " from herdr: " + lastLine(removed.output()),
                            "Remove it yourself: herdr machine remove " + id);
                    return;
                }
            }
            Files.deleteIfExists(record);
        } catch (IOException | RuntimeException e) {
            out.warn("could not remove " + shown + " from herdr: " + e.getMessage());
        }
    }

    /**
     * Machines isx added for instances destroyed while herdr was not on the PATH, one file per
     * machine id. A dot name, which no instance can have, so it never shadows an instance's record.
     */
    private Path orphans() {
        return records.get().resolve(".orphaned");
    }

    /** Remove from herdr the machines {@link #orphans} holds, and forget them; one still failing stays. */
    private void removeOrphans(Output out) {
        var dir = orphans();
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (var orphan : files.toList()) {
                var id = orphan.getFileName().toString();
                if (!HERDR_ID_PATTERN.matcher(id).matches()) continue;
                if (listed(id) && !host.run(QUICK, List.of(TOOL, "machine", "remove", id)).ok()) continue;
                Files.deleteIfExists(orphan);
            }
        } catch (IOException | RuntimeException e) {
            out.warn("could not remove earlier instances' machines from herdr: " + e.getMessage());
        }
    }

    /** The machine id herdr printed for the one it saved: {@code Saved SSH machine <id>.} */
    static String addedId(String output) {
        var matcher = ADDED_ID.matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Whether the host's herdr still has machine {@code id}: the user may have removed it by hand. */
    private boolean listed(String id) {
        var listed = host.run(QUICK, List.of(TOOL, "machine", "list", "--json"));
        if (!listed.ok()) throw new IllegalStateException("herdr machine list: " + lastLine(listed.output()));
        return Jjq.parseForeign(listed.output(), "herdr machine list --json").asList().stream()
                .anyMatch(machine -> id.equals(machine.pathText("id", "")));
    }

    private Path record(String name) {
        var dir = records.get();
        var file = dir.resolve(name).normalize();
        // Incus validates instance names, so this only guards against a caller's mistake
        if (!file.getParent().equals(dir)) throw new IllegalArgumentException("not an instance name: " + name);
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
