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
 * <p>The machine's SSH target is the instance's name, which its Host block in isx's managed SSH
 * config resolves. What isx did is recorded on the host, one file per instance name in
 * {@code records}: the id of the machine it added, or {@link #NOT_ADDED} when it decided not to
 * (an untrusted build) or could not (a failed add, whose warning says how to add it by hand). So a
 * later start reads one file and runs nothing, a copy or a renamed instance (another name) gets
 * its own, and a destroy removes only the machine isx added: never one the user added for a real
 * host that happens to share the instance's name. A destroy with no record runs nothing.
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
     * start its server there.
     */
    @FunctionalInterface
    public interface SshAlias {
        boolean resolves(String name, String staticIp);

        SshAlias MANAGED = (name, ip) -> SshKeyManager.hasSshConfigInclude() && SshKeyManager.hasHostEntry(name, ip);
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
            return !RETRY.equals(Files.readString(record).strip());
        } catch (IOException e) {
            return true;
        }
    }

    /** A recorded machine id: not {@link #NOT_ADDED}, not {@link #RETRY}. */
    @Override
    public boolean registered(String name) {
        try {
            return HERDR_ID_PATTERN.matcher(Files.readString(record(name)).strip()).matches();
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void started(IncusClient incus, String name, JqValue instance, Output out) {
        if (!host.onPath(TOOL)) return;
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
            var retrying = Files.exists(record(name));
            var added = host.run(ADD_TIMEOUT, List.of(TOOL, "machine", "add", name, "--label", name));
            var id = added.ok() ? addedId(added.output()) : null;
            if (id == null) {
                if (!retrying) {
                    // Once more on the next start: the guest's sshd may not have been up yet
                    out.step("Could not add " + shown + " to herdr yet (" + lastLine(added.output()) + "); isx tries again on its next start.");
                    record(name, RETRY);
                    return;
                }
                out.warn("could not add " + shown + " to herdr: " + lastLine(added.output()),
                        "Add it yourself: herdr machine add " + shown);
                record(name, NOT_ADDED);
                return;
            }
            record(name, id);
            out.step("Added " + shown + " to herdr.");
        } catch (HostLock.HostLockException | IOException e) {
            out.warn("could not add " + shown + " to herdr: " + e.getMessage());
        }
    }

    @Override
    public void destroyed(String name, Output out) {
        var record = record(name);
        if (!Files.exists(record)) return;
        var shown = OutputFormat.oneLine(name);
        try {
            var id = Files.readString(record).strip();
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
