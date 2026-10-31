package dev.incusspawn.command;

import dev.incusspawn.IncusSpawn;
import dev.incusspawn.Environment;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.AeshCommandRuntimeBuilder;
import org.aesh.command.Command;
import org.aesh.command.completer.CompleterInvocation;
import org.aesh.command.completer.OptionCompleter;
import org.aesh.command.impl.parser.CommandLineParser;
import org.aesh.command.impl.registry.AeshCommandRegistryBuilder;
import org.aesh.complete.AeshCompleteOperation;
import org.aesh.console.DefaultAeshContext;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The values aesh's completion engine offers for isx's arguments and options (#1744).
 * <p>
 * A shell asks {@code isx --aesh-complete -- <words>}, and aesh walks the command tree this
 * binary registered, so a command that exists only on one platform (the macOS {@code vm} group)
 * is offered only there. These completers fill in what the tree cannot know: the instances,
 * templates, tools and accounts on this host.
 * <p>
 * Each runs on every TAB, so each costs at most one Incus request and never starts anything
 * (on macOS a stopped VM stays stopped, as for {@code isx list -q}). Each offers names only,
 * never a credential's value, and offers nothing when its source fails: a TAB must not print an
 * error over the command line being typed.
 */
public final class Completers {

    private Completers() {}

    /** The flag a shell's completion script passes, then {@code --}, then the words typed after {@code isx}. */
    public static final String REQUEST = "--aesh-complete";

    /**
     * Answers {@code isx --aesh-complete -- <words>}: one candidate per line, followed by a TAB
     * and its description when it is a command, an option or a value its completer describes.
     * The last word is the one being completed, empty after a space. Words and candidates are plain values, never quoted or
     * escaped for a shell: the script quotes what it inserts. A candidate holding a control
     * character is dropped, since a newline or TAB in it would split the line.
     * <p>
     * This replaces aesh's own handler, which in 3.18 hands completers no working directory (so a
     * path option threw) and told the shell to add files after every command without a
     * positional argument. Paths come from {@link HostPath}, unescaped.
     */
    public static void answer(Class<? extends Command> topCommand, List<String> words, PrintStream out) {
        try {
            var registry = AeshCommandRegistryBuilder.builder().command(topCommand).create();
            String top = registry.getAllCommandNames().iterator().next();
            var buffer = new StringBuilder(top);
            for (var word : words) buffer.append(' ').append(escape(word));
            if (words.isEmpty()) buffer.append(' ');
            var line = buffer.toString();
            var request = new Request();
            var operation = new AeshCompleteOperation(request, line, line.length());
            AeshCommandRuntimeBuilder.builder().commandRegistry(registry).build().complete(operation);

            var scope = scope(registry.getCommand(top, "").getParser(), words);
            var typed = words.isEmpty() ? "" : words.getLast();
            // aesh replaces the line from its offset on: a path's last segment, a value after
            // "--format=" or after a list's comma. What the word holds before it is kept.
            int wordStart = line.length() - escape(typed).length();
            var kept = operation.isIgnoreOffset() || operation.getOffset() < wordStart || operation.getOffset() > line.length()
                    ? "" : unescape(line.substring(wordStart, operation.getOffset()));
            boolean topLevel = words.size() <= 1;
            // Each whole word, with the description its completer gave the value, if any.
            var described = new TreeMap<String, String>();
            if (request.pathValue != null) {
                request.paths.forEach(path -> described.putIfAbsent(pathWord(typed, request.pathValue, path), ""));
            } else {
                for (var candidate : operation.getCompletionCandidates()) {
                    var value = candidate.getCharacters().strip();
                    described.putIfAbsent(wholeWord(typed, kept, value), request.descriptions.getOrDefault(value, ""));
                }
            }
            described.forEach((candidate, valueDescription) -> {
                if (candidate.chars().anyMatch(Character::isISOControl)) return;
                if (topLevel && IncusSpawn.NOT_COMPLETED.contains(candidate)) return;
                var description = valueDescription.isEmpty() ? description(scope, candidate) : valueDescription;
                out.println(description.isEmpty() ? candidate : candidate + "\t" + oneLine(description));
            });
        } catch (Exception e) {
            // A TAB offers nothing rather than print over the line being typed.
        }
    }

    /**
     * A candidate as the whole word it completes, so every shell can replace the word with it:
     * aesh answers {@code --format=j} with {@code json} from the offset after the {@code =}, and a
     * word typed in full with a blank.
     */
    static String wholeWord(String typed, String kept, String candidate) {
        var value = candidate.strip();
        return value.isEmpty() ? typed : kept + value;
    }

    /**
     * A path candidate as the whole word: what the word holds before the value ({@code --inbox=}),
     * then the path, unescaped.
     */
    static String pathWord(String typed, String value, String path) {
        return typed.endsWith(value) ? typed.substring(0, typed.length() - value.length()) + path : path;
    }

    /** A word as aesh's parser reads it back: a backslash keeps a space, quote or backslash literal. */
    private static String escape(String word) {
        return word.replaceAll("([\\\\ '\"])", "\\\\$1");
    }

    private static String unescape(String text) {
        return text.replaceAll("\\\\(.)", "$1");
    }

    /** The deepest command the complete words name, whose subcommands and options the candidates are. */
    private static CommandLineParser<?> scope(CommandLineParser<?> parser, List<String> words) {
        for (int i = 0; i < words.size() - 1 && parser.isGroupCommand(); i++) {
            var child = parser.getChildParser(words.get(i));
            if (child != null) parser = child;
        }
        return parser;
    }

    private static String description(CommandLineParser<?> scope, String candidate) {
        if (scope.isGroupCommand()) {
            var child = scope.getChildParser(candidate);
            if (child != null) return oneLine(child.getProcessedCommand().description());
        }
        if (candidate.startsWith("--")) {
            var option = scope.getProcessedCommand().findLongOptionNoActivatorCheck(candidate.substring(2));
            if (option != null) return oneLine(option.description());
        }
        return "";
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    /**
     * The context of one request, through which {@link HostPath} hands {@link #answer} its paths
     * and a {@link Names} completer the descriptions of its values: aesh's completer values carry
     * none.
     */
    private static final class Request extends DefaultAeshContext {
        String pathValue;
        final List<String> paths = new ArrayList<>();
        final Map<String, String> descriptions = new HashMap<>();
    }

    /**
     * A host path, as {@code --inbox} and {@code project --config} take.
     * <p>
     * aesh's own file completer escapes a lone candidate for its readline (a backslash before each
     * space and double quote, but not before a leading space), a form no other shell reads back and
     * that cannot be undone exactly. So the paths are listed here and reach {@link #answer} as they
     * are, through its {@link Request}; quoting them is each shell script's job. A leading
     * {@code ~/} is offered as the home directory, which reads the same quoted or not.
     */
    public static class HostPath implements OptionCompleter<CompleterInvocation> {
        @Override
        public void complete(CompleterInvocation invocation) {
            if (!(invocation.getAeshContext() instanceof Request request)) return;
            var value = invocation.getGivenCompleteValue() == null ? "" : invocation.getGivenCompleteValue();
            request.pathValue = value;
            if (value.equals("~")) value = "~/";
            var typedDir = value.substring(0, value.lastIndexOf('/') + 1);
            var prefix = value.substring(typedDir.length());
            var dir = typedDir.startsWith("~/") ? Environment.home() + typedDir.substring(1) : typedDir;
            try (var entries = Files.list(Path.of(dir.isEmpty() ? "." : dir))) {
                entries.forEach(entry -> {
                    var name = entry.getFileName().toString();
                    if (!name.startsWith(prefix) || (name.startsWith(".") && !prefix.startsWith("."))) return;
                    request.paths.add(dir + name + (Files.isDirectory(entry) ? "/" : ""));
                });
            } catch (IOException | RuntimeException e) {
                // An unreadable or missing directory offers nothing.
            }
        }
    }

    /** A completer offering, of its names, each that starts with what was typed. */
    abstract static class Names implements OptionCompleter<CompleterInvocation> {

        abstract Collection<String> names(CompleterInvocation invocation);

        /** What {@link #answer} shows beside a name {@link #names} returned, as fish and zsh list it. */
        static void describe(CompleterInvocation invocation, String name, String description) {
            if (invocation.getAeshContext() instanceof Request request) request.descriptions.put(name, description);
        }

        @Override
        public final void complete(CompleterInvocation invocation) {
            Collection<String> all;
            try {
                all = names(invocation);
            } catch (RuntimeException e) {
                return;
            }
            var typed = invocation.getGivenCompleteValue() == null ? "" : invocation.getGivenCompleteValue();
            all.stream()
                    .filter(name -> AccountCommand.typable(name) && name.startsWith(typed))
                    .sorted()
                    .forEach(invocation::addCompleterValue);
        }
    }

    /** The names of the instances isx made whose type stamp {@code type} accepts, from one Incus request. */
    static List<String> instanceNames(IncusClient incus, Predicate<String> type) {
        return InstanceListing.collectEntries(incus.listJsonConfig()).stream()
                .filter(i -> type.test(i.type()))
                .map(InstanceListing.InstanceInfo::name)
                .toList();
    }

    /** What {@code isx list -q} prints: everything but the built templates. */
    static final Predicate<String> NOT_A_TEMPLATE = type -> !Metadata.TYPE_BASE.equals(type);

    /** An instance, as {@code isx shell} or {@code start} take. */
    public static class Instance extends Names {
        @Override
        Collection<String> names(CompleterInvocation invocation) {
            return instanceNames(RuntimeServices.incus(), NOT_A_TEMPLATE);
        }
    }

    /** An instance or a built template, as {@code isx branch --from} and {@code isx destroy} take. */
    public static class Source extends Names {
        @Override
        Collection<String> names(CompleterInvocation invocation) {
            return instanceNames(RuntimeServices.incus(), type -> true);
        }
    }

    /** A project template, as {@code isx project update} takes. */
    public static class Project extends Names {
        @Override
        Collection<String> names(CompleterInvocation invocation) {
            return instanceNames(RuntimeServices.incus(), Metadata.TYPE_PROJECT::equals);
        }
    }

    /** A template definition, built or not, as {@code isx build} and {@code isx templates edit} take. */
    public static class Template extends Names {
        @Override
        Collection<String> names(CompleterInvocation invocation) {
            return ImageDef.loadAll().keySet();
        }
    }

    /** A tool definition, as {@code isx tools show} takes. */
    public static class Tool extends Names {
        @Override
        Collection<String> names(CompleterInvocation invocation) {
            return RuntimeServices.toolDefLoader().allToolSetups().keySet();
        }
    }

    /** A credential by namespace or by tool, as {@code isx account add} takes. */
    public static class Credential extends Names {
        @Override
        Collection<String> names(CompleterInvocation invocation) {
            return AccountCommand.addCompletionLines(RuntimeServices.toolDefLoader().allToolSetups());
        }
    }

    /**
     * A {@code <namespace>=<account>} selection, as {@code --account} takes: {@code ns=} for each
     * namespace with a usable account, then each usable account.
     */
    public static class Account extends Names {
        @Override
        Collection<String> names(CompleterInvocation invocation) {
            return accountNames(invocation, true);
        }
    }

    /**
     * {@code isx account set|unset}'s words: the instance first, then a selection per word for
     * {@code set} and a namespace per word for {@code unset}.
     */
    public static class InstanceThenAccounts extends Names {
        @Override
        Collection<String> names(CompleterInvocation invocation) {
            List<String> args = switch (invocation.getCommand()) {
                case AccountCommand.Set set -> set.args;
                case AccountCommand.Unset unset -> unset.args;
                default -> List.of();
            };
            if (args == null) args = List.of();
            // aesh may already hold the word being completed as the last one.
            var typed = invocation.getGivenCompleteValue();
            boolean typedIsLast = typed != null && !typed.isEmpty() && !args.isEmpty() && typed.equals(args.getLast());
            if (args.size() == (typedIsLast ? 1 : 0)) return instanceNames(RuntimeServices.incus(), NOT_A_TEMPLATE);
            return accountNames(invocation, invocation.getCommand() instanceof AccountCommand.Set);
        }
    }

    /**
     * With {@code selections}, what {@code --account} takes, each namespace's default described
     * as such; once a namespace is typed, its accounts but not the bare {@code ns=} again.
     * Otherwise every namespace, which {@code account unset} takes whatever its accounts look
     * like now. Read with the config's own tool search paths, so the config is loaded once.
     */
    private static List<String> accountNames(CompleterInvocation invocation, boolean selections) {
        var config = SpawnConfig.load();
        var setups = AccountSelection.namespaceSetups(config, new ToolDefLoader(config.getSearchPaths()));
        var names = AccountCommand.completionNames(config, setups);
        if (!selections) return names.namespaces();
        names.defaults().forEach(selection -> Names.describe(invocation, selection, "default"));
        var typed = invocation.getGivenCompleteValue();
        if (typed == null || !typed.contains("=")) return names.selections();
        return names.selections().stream().filter(selection -> !selection.endsWith("=")).toList();
    }

    /** A value of {@code --format}. */
    public static class Format extends Names {
        @Override
        Collection<String> names(CompleterInvocation invocation) {
            return Arrays.stream(OutputFormat.values()).map(f -> f.name().toLowerCase(Locale.ROOT)).toList();
        }
    }

    /** A shell {@code isx completion} writes a script for. */
    public static class Shell extends Names {
        @Override
        Collection<String> names(CompleterInvocation invocation) {
            return Arrays.stream(CompletionCommand.Shell.values()).map(Enum::name).toList();
        }
    }

    /** {@code running} or {@code stopped}, as {@code isx list --status} takes. */
    public static class Status extends Names {
        @Override
        Collection<String> names(CompleterInvocation invocation) {
            return List.of("running", "stopped");
        }
    }
}
