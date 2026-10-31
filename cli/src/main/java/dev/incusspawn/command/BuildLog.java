package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.SecretRedactor;
import dev.incusspawn.incus.CommandLog;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One template build's record of every command its definitions declared, with all that each
 * printed and how it ended (#1742), on the host beside the failure reports. A failure report
 * holds only the command that failed; this holds the ones that ran before it too, and is
 * written as each ends, so it can be followed while the build runs. {@code isx build --verbose}
 * prints it once the template's build is over.
 *
 * <p>Guest text is made safe as it is for the terminal ({@link BuildOutput#guestText}), and
 * anything shaped like a credential, in the text as printed or as shown, is scrubbed
 * ({@link SecretRedactor#scrubGuest}).
 */
final class BuildLog implements CommandLog, AutoCloseable {

    private final Path file;
    /** The entries' lines, kept only for {@code --verbose}: the file holds them otherwise. */
    private final List<String> lines;
    private boolean recorded;
    private Writer writer;
    private String failure;
    /** Whether the file holds every entry: it opened, and no write to it failed. */
    private boolean whole;

    private BuildLog(Path file, boolean keep, Writer writer, String failure) {
        this.file = file;
        this.lines = keep ? new ArrayList<>() : null;
        this.writer = writer;
        this.failure = failure;
        this.whole = writer != null;
    }

    /**
     * A fresh log for {@code template}'s build at {@code file}, replacing the last build's, which
     * also keeps its entries for {@link #lines} when {@code keep}. One that cannot be written still
     * keeps them, and {@link #close} says why.
     */
    static BuildLog open(String template, boolean keep) {
        Path file;
        try {
            file = Environment.buildLogFile(template);
        } catch (RuntimeException e) {
            // A name the path guard refuses: the build goes on, and close() says it kept no log
            return new BuildLog(null, keep, null, BaseCommand.failure(e));
        }
        return open(file, template, keep);
    }

    static BuildLog open(Path file, String template, boolean keep) {
        try {
            Files.createDirectories(file.getParent());
            // Before it is opened, so no failure here leaves a writer open
            ownerOnly(file);
            var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            var log = new BuildLog(file, keep, writer, null);
            log.write("isx build " + OutputFormat.oneLine(template) + ", started " + Instant.now() + "\n\n");
            return log;
        } catch (IOException | RuntimeException e) {
            return new BuildLog(file, keep, null, BaseCommand.failure(e));
        }
    }

    /**
     * Makes {@code file} owner-only, creating it so when it does not exist: it is about to hold
     * what a build's commands printed (this log, or a failure report).
     */
    static void ownerOnly(Path file) throws IOException {
        var ownerOnly = PosixFilePermissions.fromString("rw-------");
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(ownerOnly));
        } catch (FileAlreadyExistsException e) {
            Files.setPosixFilePermissions(file, ownerOnly);
        }
    }

    /** Where the log is, or null when its file is missing or partial. */
    synchronized Path savedFile() {
        return whole ? file : null;
    }

    /** The failure report's pointer to this log, empty when nothing ran or its file is not whole. */
    synchronized String reportLine() {
        if (!whole || !recorded) return "";
        return "Every command the definitions ran, with its output: " + OutputFormat.oneLine(file.toString()) + "\n";
    }

    @Override
    public void record(String step, String command, IncusClient.ExecResult result, Duration took) {
        synchronized (this) {
            recorded = true;
            // Closed already (an interrupted build's hook), or never opened: the file misses this one
            if (writer == null) whole = false;
            if (writer == null && lines == null) return;
        }
        // Made before the lock: repositories prime in parallel, and the scrub reads all the output
        String entry;
        try {
            entry = entry(step, command, result, took);
        } catch (RuntimeException e) {
            // Keeping the log must never fail the command it records; close() says what is missing
            synchronized (this) {
                if (failure == null) failure = BaseCommand.failure(e);
                whole = false;
            }
            return;
        }
        synchronized (this) {
            if (lines != null) entry.lines().forEach(lines::add);
            write(entry);
        }
    }

    /** One command as a shell shows it, between a header naming its step and a footer saying how it ended. */
    static String entry(String step, String command, IncusClient.ExecResult result, Duration took) {
        var header = "─── " + OutputFormat.oneLine(step) + " ───\n"
                + RepoCloner.prompted(RepoCloner.commandSource(command)) + "\n";
        var printed = new ArrayList<String>();
        printed.add(header);
        printed.addAll(List.of(BuildOutput.guestStreams(result.stdout(), result.stderr())));
        // Scrubbed raw as well as shown, the command included: showing it can hide a secret's
        // shape (#1878, #1886). The footer is isx's own, after the scrub: a key header the command
        // printed with no end after it is redacted to the end of what it printed, never through
        // how the command ended
        return SecretRedactor.scrubGuest(printed.toArray(String[]::new))
                + "─── exit " + result.exitCode() + " after " + BuildOutput.formatElapsed(took.toMillis()) + " ───\n\n";
    }

    /** Every entry's lines, in the order the commands ended, when it keeps them; none otherwise. */
    synchronized List<String> lines() {
        return lines == null ? List.of() : List.copyOf(lines);
    }

    private void write(String text) {
        if (writer == null) return;
        try {
            writer.write(text);
            writer.flush();
        } catch (IOException e) {
            failure = BaseCommand.failure(e);
            whole = false;
            closeWriter();
        }
    }

    /** Closes the file, warning once if it could not be written in full. */
    @Override
    public synchronized void close() {
        closeWriter();
        if (failure != null) {
            BuildOutput.warn("Could not save the build log" + (file == null ? "" : " to " + OutputFormat.oneLine(file.toString())) + ": "
                    + OutputFormat.oneLine(failure));
            failure = null;
        }
    }

    private void closeWriter() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException e) {
            if (failure == null) failure = BaseCommand.failure(e);
            whole = false;
        }
        writer = null;
    }
}
