package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.AccountUsage;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.proxy.InstanceRegistry;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Arguments;
import org.aesh.command.option.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Inspect and change which credential account an instance uses.
 *
 * <p>Accounts themselves are configured by {@code isx init}; this command only decides who
 * uses them. Re-pointing a running instance takes effect on its next request -- credentials
 * live in the proxy, never in the container, so nothing inside has to be restarted.
 */
@CommandDefinition(
        name = "account",
        description = "Show or change the credential accounts an instance uses",
        generateHelp = true,
        groupCommands = {
                AccountCommand.ListSub.class,
                AccountCommand.Show.class,
                AccountCommand.Set.class,
                AccountCommand.Unset.class
        }
)
public class AccountCommand extends BaseCommand {

    // Bare, the command is its list, so it takes list's --format.
    @Option(name = "format", description = "Output format: table (default), plain or json")
    String format;

    @Override
    protected CommandResult doExecute() throws Exception {
        var list = new ListSub();
        list.format = format;
        return list.doExecute();
    }

    // ── list ────────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "list",
            description = "List configured accounts per namespace, and the instances pinned to each",
            generateHelp = true)
    public static class ListSub extends BaseCommand {

        // Output for scripts (#1036): see OutputFormat for what plain and json promise.
        @Option(name = "format", description = "Output format: table (default), plain or json")
        String format;

        @Override
        protected CommandResult doExecute() throws Exception {
            var outputFormat = OutputFormat.parse(format);
            var config = SpawnConfig.load();
            var setups = AccountSelection.namespaceSetups(config);

            // Which instances pin what. Listing accounts must not need Incus -- on a fresh host,
            // or with the daemon down, the configured accounts are still worth seeing.
            Map<String, InstanceRegistry.AccountState> states;
            boolean pinsKnown = true;
            String pinsUnavailable = "";
            try {
                states = InstanceRegistry.accountStates(RuntimeServices.incus());
            } catch (Exception e) {
                states = Map.of();
                pinsKnown = false;
                pinsUnavailable = e.getMessage() == null || e.getMessage().isBlank()
                        ? e.getClass().getSimpleName() : e.getMessage();
            }

            var namespaces = new ArrayList<NamespaceListing>();
            for (var entry : setups.entrySet()) {
                namespaces.add(listNamespace(config, entry.getKey(), entry.getValue(), states));
            }
            if (outputFormat != OutputFormat.TABLE) {
                // Unknown pins print as null, never as "pinned by nobody"; the reason goes to stderr.
                if (!pinsKnown) {
                    System.err.println("Could not read which instances use each account: " + pinsUnavailable);
                }
                outputFormat.print(System.out, listRecords(namespaces, pinsKnown));
                return CommandResult.SUCCESS;
            }
            renderList(namespaces, pinsUnavailable).forEach(System.out::println);
            return CommandResult.SUCCESS;
        }
    }

    /** One configured account, as {@code isx account list} shows it. */
    /**
     * @param following for the default account, the instances that follow the default -- the
     *                  ones a change of default would move; otherwise empty
     */
    record AccountLine(String name, String description, boolean isDefault, String problem,
                       List<String> pinnedBy, List<String> following) {}

    /** A namespace's accounts, plus pins naming an account it no longer has. */
    record NamespaceListing(String namespace, List<AccountLine> accounts,
                            Map<String, List<String>> danglingPins) {}

    static NamespaceListing listNamespace(SpawnConfig config, String namespace, ToolSetup setup,
                                          Map<String, InstanceRegistry.AccountState> states) {
        var pins = new LinkedHashMap<String, Map<String, String>>();
        var following = new ArrayList<String>();
        states.forEach((instance, state) -> {
            if (!state.pins().isEmpty()) pins.put(instance, state.pins());
            if (state.followsDefault(namespace)) following.add(instance);
        });
        var tree = config.tree();
        var shape = setup.accountShape();
        var usable = AccountResolver.usableAccountNames(tree, namespace, shape);
        var defaultName = AccountResolver.effectiveAccount(tree, namespace, shape, null);
        var lines = new ArrayList<AccountLine>();
        var names = AccountResolver.accountNames(tree, namespace, shape);
        for (var name : names) {
            var problem = "";
            if (!usable.contains(name)) {
                try {
                    AccountResolver.effectiveAccount(tree, namespace, shape, name);
                } catch (AccountResolver.UnknownAccountException e) {
                    problem = "incomplete";
                }
            }
            var description = setup.describeAccount(config, name);
            var isDefault = name.equals(defaultName);
            lines.add(new AccountLine(name, description == null ? "" : description,
                    isDefault, problem, AccountUsage.pinnedTo(pins, namespace, name),
                    isDefault ? following : List.of()));
        }
        var dangling = new LinkedHashMap<String, List<String>>();
        pins.forEach((instance, selection) -> {
            var account = selection.get(namespace);
            if (account != null && !names.contains(account)) {
                dangling.computeIfAbsent(account, k -> new ArrayList<>()).add(instance);
            }
        });
        return new NamespaceListing(namespace, lines, dangling);
    }

    /**
     * The fields of {@code isx account list --format=plain|json}, in order: add to the end, never
     * rename. One record per account, and one per account a pin names but the config lacks
     * ({@code problem} {@code not-configured}); {@code pinned_by} and {@code following} are
     * {@code null} when the instances could not be read.
     */
    static List<Map<String, Object>> listRecords(List<NamespaceListing> namespaces, boolean pinsKnown) {
        var records = new ArrayList<Map<String, Object>>();
        for (var ns : namespaces) {
            for (var a : ns.accounts()) {
                records.add(accountRecord(ns.namespace(), a.name(), a.description(), a.isDefault(),
                        a.problem(), pinsKnown ? a.pinnedBy() : null, pinsKnown ? a.following() : null));
            }
            ns.danglingPins().forEach((account, instances) -> records.add(accountRecord(
                    ns.namespace(), account, "", false, "not-configured", instances, List.of())));
        }
        return records;
    }

    private static Map<String, Object> accountRecord(String namespace, String account, String description,
                                                     boolean isDefault, String problem,
                                                     List<String> pinnedBy, List<String> following) {
        var record = new LinkedHashMap<String, Object>();
        record.put("namespace", namespace);
        record.put("account", account);
        record.put("description", description.isEmpty() ? null : description);
        record.put("default", isDefault);
        record.put("problem", problem.isEmpty() ? null : problem);
        record.put("pinned_by", pinnedBy);
        record.put("following", following);
        return record;
    }

    static List<String> renderList(List<NamespaceListing> namespaces, String pinsUnavailable) {
        var out = new ArrayList<String>();
        for (var ns : namespaces) {
            if (ns.accounts().isEmpty() && ns.danglingPins().isEmpty()) continue;
            if (!out.isEmpty()) out.add("");
            out.add(ns.namespace() + ":");
            int width = ns.accounts().stream().mapToInt(a -> a.name().length()).max().orElse(0);
            for (var a : ns.accounts()) {
                var line = new StringBuilder("  ").append(pad(a.name(), width));
                if (!a.description().isEmpty()) line.append("  ").append(a.description());
                var tags = new ArrayList<String>();
                if (a.isDefault()) tags.add("default");
                if (!a.problem().isEmpty()) tags.add(a.problem());
                if (!tags.isEmpty()) line.append("  [").append(String.join(", ", tags)).append("]");
                out.add(line.toString().stripTrailing());
                if (!a.pinnedBy().isEmpty()) {
                    out.add("    pinned to it: " + String.join(", ", a.pinnedBy()));
                }
                if (!a.following().isEmpty()) {
                    out.add("    following the default (not pinned): " + String.join(", ", a.following()));
                }
            }
            ns.danglingPins().forEach((account, instances) -> out.add(
                    "  Problem: '" + account + "' is not configured, but " + String.join(", ", instances)
                            + (instances.size() == 1 ? " is" : " are") + " pinned to it"));
        }
        if (out.isEmpty()) {
            out.add("No accounts configured. Run 'isx init' to add one.");
        } else {
            out.add("");
            out.add("[default] is the global default: the account used by every instance that is not"
                    + " pinned to one. Changing it moves them all.");
        }
        if (!pinsUnavailable.isEmpty()) {
            out.add("(Could not read which instances use each account: " + pinsUnavailable + ")");
        }
        return out;
    }

    // ── show ────────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "show", description = "Show the account an instance uses for each credential, and why",
            generateHelp = true)
    public static class Show extends BaseCommand {

        @Argument(description = "Instance name", required = true)
        String instance;

        // Output for scripts (#1036): see OutputFormat for what plain and json promise.
        @Option(name = "format", description = "Output format: table (default), plain or json")
        String format;

        @Override
        protected CommandResult doExecute() throws Exception {
            var outputFormat = OutputFormat.parse(format);
            var incus = RuntimeServices.incus();
            if (!incus.exists(instance)) {
                System.err.println("Error: no instance named '" + instance + "' found.");
                return CommandResult.valueOf(1);
            }
            var config = SpawnConfig.load();
            var setups = AccountSelection.namespaceSetups(config);
            // One instance read for the pins, the identities and the template.
            var metadata = incus.configByPrefix(instance, Metadata.PREFIX);
            var pins = subMap(metadata, Metadata.ACCOUNT_PREFIX);
            var template = templateOf(instance, metadata);
            var uses = AccountUsage.of(config, setups, pins,
                    AccountSelection.originsFromMetadata(metadata), templateAccounts(template), instance,
                    subMap(metadata, Metadata.ACCOUNT_IDENTITY_PREFIX));
            var staleIdentities = pendingIdentityRefresh(config, incus, instance, uses);
            if (outputFormat != OutputFormat.TABLE) {
                outputFormat.print(System.out, showRecords(template, uses, staleIdentities));
                return CommandResult.SUCCESS;
            }
            renderShow(instance, template, uses, staleIdentities)
                    .forEach(System.out::println);
            return CommandResult.SUCCESS;
        }
    }

    /**
     * Identities the instance will re-derive on its next use. Resolving them resolves every pin,
     * so a pin to a missing account throws -- and that instance is exactly the one {@code show}
     * must still describe. Its problem is already on the account line; nothing is pending.
     */
    private static Map<String, String> pendingIdentityRefresh(SpawnConfig config, IncusClient incus,
                                                              String instance, List<AccountUsage.Use> uses) {
        if (uses.stream().anyMatch(u -> !u.problem().isEmpty())) return Map.of();
        try {
            return AccountSelection.staleIdentities(config, incus, instance);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** Entries of {@code metadata} (keyed without {@link Metadata#PREFIX}) under a longer prefix. */
    private static Map<String, String> subMap(Map<String, String> metadata, String fullPrefix) {
        var prefix = fullPrefix.substring(Metadata.PREFIX.length());
        var result = new LinkedHashMap<String, String>();
        metadata.forEach((key, value) -> {
            if (key.startsWith(prefix) && value != null && !value.isBlank()) {
                result.put(key.substring(prefix.length()), value.strip());
            }
        });
        return result;
    }

    /**
     * The template an instance was branched from: its recorded profile, or itself for a template
     * -- the rule {@code BranchFlow} uses to find the chain whose {@code accounts:} it stamped.
     */
    private static String templateOf(String instance, Map<String, String> metadata) {
        var profile = metadata.getOrDefault(Metadata.PROFILE.substring(Metadata.PREFIX.length()), "");
        return profile.isBlank() ? instance : profile.strip();
    }

    /** The template chain's {@code accounts:} as they read now, or empty if it is not defined. */
    private static Map<String, String> templateAccounts(String template) {
        try {
            var defs = ImageDef.loadAll(msg -> { });
            var def = defs.get(template);
            return def == null ? Map.of() : ImageDef.resolveAccounts(def, defs);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * The fields of {@code isx account show --format=plain|json}, in order: add to the end, never
     * rename. One record per namespace. {@code chosen_by} is who pinned the account
     * ({@code template}, {@code explicit}, {@code copied}, {@code unknown}), or {@code default}
     * when it follows the global default; {@code chosen_in} names the template or instance it
     * came from. {@code problem} is why requests using it fail, or are refused.
     * {@code identity_pending} is the git identity the instance takes on its next start or shell,
     * when it differs from the one it was stamped with. Read from the stamps alone: an instance
     * from a template an older isx built may also have an identity it lacks filled in on that
     * start, which only its guest can tell, so that is not listed. {@code template_problem} is why the template's own
     * choice cannot be used (it names an account that is not configured), which fails a branch
     * from it.
     */
    static List<Map<String, Object>> showRecords(String template, List<AccountUsage.Use> uses,
                                                 Map<String, String> staleIdentities) {
        var records = new ArrayList<Map<String, Object>>();
        for (var use : uses) {
            var record = new LinkedHashMap<String, Object>();
            record.put("namespace", use.namespace());
            record.put("account", emptyToNull(use.account()));
            record.put("description", emptyToNull(use.description()));
            record.put("chosen_by", use.pinned()
                    ? use.origin().kind().name().toLowerCase(java.util.Locale.ROOT) : "default");
            record.put("chosen_in", use.pinned() ? emptyToNull(use.origin().source()) : null);
            record.put("template", template);
            record.put("template_account", emptyToNull(use.templateAccount()));
            record.put("problem", emptyToNull(use.problem().isEmpty() ? use.refusal() : use.problem()));
            record.put("identity_pending", staleIdentities.get(use.namespace()));
            record.put("template_problem", emptyToNull(use.templateProblem()));
            records.add(record);
        }
        return records;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    static List<String> renderShow(String instance, String template, List<AccountUsage.Use> uses,
                                   Map<String, String> staleIdentities) {
        var out = new ArrayList<String>();
        var isTemplate = template.equals(instance);
        out.add(isTemplate ? instance + " (template):" : instance + " (branched from " + template + "):");
        if (uses.isEmpty()) {
            out.add("  No credential accounts are configured. Run 'isx init' to add one.");
            return out;
        }
        // A block per credential: the account and what it is on the first line, then in words
        // where it comes from -- and so what would change it -- then anything that needs action.
        int nsWidth = uses.stream().mapToInt(u -> u.namespace().length()).max().orElse(0);
        var indent = " ".repeat(2 + nsWidth + 2);
        for (var use : uses) {
            out.add("");
            out.add(("  " + pad(use.namespace(), nsWidth) + "  " + label(use)
                    + (use.description().isEmpty() ? "" : " -- " + use.description())).stripTrailing());
            out.add(indent + AccountUsage.explainSource(use, template, isTemplate));
            if (!use.problem().isEmpty()) {
                out.add(indent + "Problem: " + sentence(use.problem()) + " Requests using it fail until"
                        + " it is configured again, or 'isx account unset " + instance + " "
                        + use.namespace() + "' switches the instance to the global default.");
            } else if (!use.refusal().isEmpty()) {
                out.add(indent + "Problem: its " + use.namespace() + " requests are refused. " + sentence(use.refusal()));
            } else if (!use.templateProblem().isEmpty()) {
                // Never suggest following a template to an account that is gone.
                out.add(indent + "Problem: " + template + " chooses '" + use.templateAccount()
                        + "', which is not configured -- branching from it fails until its"
                        + " definition names an account that is.");
            } else if (use.differsFromTemplate() && isTemplate) {
                // A template's pins are what its build stamped from its own definition, so the
                // way to follow an edited definition is the build, which also re-bakes.
                out.add(indent + "Its definition now chooses '" + use.templateAccount()
                        + "', but it was built with '" + label(use) + "'. To apply: isx build " + instance);
            } else if (use.differsFromTemplate()) {
                var fromTemplate = use.pinned() && use.origin().kind() == dev.incusspawn.config.AccountOrigin.Kind.TEMPLATE
                        && use.origin().source().equals(template);
                out.add(indent + (fromTemplate || !use.pinned()
                        ? "Template " + template + " has since changed its accounts: to choose '"
                        : "Template " + template + " would choose '")
                        + use.templateAccount() + "'. To use that: isx account set " + instance + " "
                        + use.namespace() + "=" + use.templateAccount());
            }
            var identity = staleIdentities.get(use.namespace());
            if (identity != null) {
                out.add(indent + "The git identity inside is updated to '" + identity
                        + "' when the instance next starts or opens a shell.");
            }
        }
        return out;
    }

    /** Resolver messages end with or without a full stop; notes append a sentence after them. */
    private static String sentence(String message) {
        var m = message.strip();
        return m.endsWith(".") ? m : m + ".";
    }

    private static String label(AccountUsage.Use use) {
        return use.account().isEmpty() ? "(none)" : use.account();
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    // ── set ─────────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "set",
            description = "Point an instance at different credential accounts",
            generateHelp = true)
    public static class Set extends BaseCommand {

        @Arguments(description = "Instance name, then one or more <namespace>=<account>")
        List<String> args;

        @Override
        protected CommandResult doExecute() throws Exception {
            if (args == null || args.size() < 2) {
                System.err.println("Usage: isx account set <instance> <namespace>=<account> ...");
                System.err.println("Example: isx account set my-box claude=work");
                return CommandResult.valueOf(1);
            }
            var incus = RuntimeServices.incus();
            var instance = args.get(0);
            if (!incus.exists(instance)) {
                System.err.println("Error: no instance named '" + instance + "' found.");
                return CommandResult.valueOf(1);
            }

            var config = SpawnConfig.load();
            Map<String, String> requested;
            try {
                requested = AccountSelection.parse(args.subList(1, args.size()));
                change(incus, instance, config, requested);
            } catch (AccountSelection.InvalidSelectionException
                     | AccountResolver.UnknownAccountException e) {
                System.err.println("Error: " + e.getMessage());
                return CommandResult.valueOf(1);
            }

            System.out.println(instance + " now uses " + AccountSelection.describe(requested) + ".");
            // Pinning the current default is a choice to stop following it, and looks exactly
            // like following it until the default changes -- so say which one this was.
            requested.forEach((namespace, account) -> {
                if (account.equals(AccountResolver.effectiveAccount(config, namespace, null))) {
                    System.out.println("'" + account + "' is also the " + namespace + " default; the"
                            + " instance now keeps it even if the default changes"
                            + " ('isx account unset " + instance + " " + namespace + "' follows the default instead).");
                }
            });
            System.out.println("Takes effect on the instance's next request;"
                    + " nothing inside it needs restarting.");
            return CommandResult.SUCCESS;
        }
    }

    // ── unset ───────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "unset",
            description = "Make an instance follow the default account again",
            generateHelp = true)
    public static class Unset extends BaseCommand {

        @Arguments(description = "Instance name, then one or more namespaces (e.g. claude github)")
        List<String> args;

        @Override
        protected CommandResult doExecute() throws Exception {
            if (args == null || args.size() < 2) {
                System.err.println("Usage: isx account unset <instance> <namespace> ...");
                System.err.println("Example: isx account unset my-box claude");
                return CommandResult.valueOf(1);
            }
            var incus = RuntimeServices.incus();
            var instance = args.get(0);
            if (!incus.exists(instance)) {
                System.err.println("Error: no instance named '" + instance + "' found.");
                return CommandResult.valueOf(1);
            }
            var namespaces = new LinkedHashSet<String>();
            for (var arg : args.subList(1, args.size())) {
                for (var ns : arg.split(",")) {
                    ns = ns.strip();
                    if (ns.contains("=")) {
                        System.err.println("Error: '" + ns + "' names an account; 'unset' takes only the"
                                + " namespace, e.g. 'isx account unset " + instance + " "
                                + ns.substring(0, ns.indexOf('=')).strip() + "'.");
                        return CommandResult.valueOf(1);
                    }
                    if (!ns.isEmpty()) namespaces.add(ns);
                }
            }

            var current = AccountSelection.read(incus, instance);
            var changes = new LinkedHashMap<String, String>();
            for (var ns : namespaces) {
                if (current.containsKey(ns)) {
                    changes.put(ns, null);
                } else {
                    System.out.println(instance + " does not pin a " + ns + " account;"
                            + " it already follows the default.");
                }
            }
            if (changes.isEmpty()) return CommandResult.SUCCESS;

            var config = SpawnConfig.load();
            try {
                change(incus, instance, config, changes);
            } catch (AccountSelection.InvalidSelectionException
                     | AccountResolver.UnknownAccountException e) {
                System.err.println("Error: " + e.getMessage());
                return CommandResult.valueOf(1);
            }

            for (var ns : changes.keySet()) {
                var account = AccountResolver.effectiveAccount(config, ns, null);
                System.out.println(instance + " now follows the " + ns + " default"
                        + (account.isEmpty() ? " (none is configured)." : " (currently '" + account + "')."));
            }
            System.out.println("Takes effect on the instance's next request;"
                    + " nothing inside it needs restarting.");
            return CommandResult.SUCCESS;
        }
    }

    private static void change(IncusClient incus, String instance, SpawnConfig config,
                               Map<String, String> changes) {
        InstanceLifecycle.changeAccounts(incus, instance, config,
                AccountSelection.namespaceSetups(config), changes,
                dev.incusspawn.util.BuildOutput::step,
                msg -> System.err.println("Warning: " + msg));
    }
}
