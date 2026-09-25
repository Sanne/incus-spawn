package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.proxy.ProxyService;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Arguments;

import java.util.List;

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
                AccountCommand.Set.class
        }
)
public class AccountCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() throws Exception {
        return new ListSub().doExecute();
    }

    // ── list ────────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "list", description = "List configured accounts per namespace",
            generateHelp = true)
    public static class ListSub extends BaseCommand {

        @Override
        protected CommandResult doExecute() throws Exception {
            var config = SpawnConfig.load();

            boolean any = false;
            for (var namespace : AccountSelection.knownNamespaces(config)) {
                var listing = AccountSelection.listAccounts(config, namespace);
                var names = listing.names();
                if (names.isEmpty()) continue;
                any = true;
                var defaultName = listing.defaultName();
                System.out.println(namespace + ":");
                for (var name : names) {
                    System.out.println("  " + name + (name.equals(defaultName) ? "  (default)" : ""));
                }
            }
            if (!any) {
                System.out.println("No named accounts configured. Run 'isx init' to add one.");
            }
            return CommandResult.SUCCESS;
        }
    }

    // ── show ────────────────────────────────────────────────────────────────────

    @CommandDefinition(name = "show", description = "Show the accounts an instance uses",
            generateHelp = true)
    public static class Show extends BaseCommand {

        @Argument(description = "Instance name", required = true)
        String instance;

        @Override
        protected CommandResult doExecute() throws Exception {
            var incus = RuntimeServices.incus();
            if (!incus.exists(instance)) {
                System.err.println("Error: no instance named '" + instance + "' found.");
                return CommandResult.valueOf(1);
            }
            var selection = AccountSelection.read(incus, instance);
            if (selection.isEmpty()) {
                System.out.println(instance + " uses the configured default account"
                        + " for every credential.");
                return CommandResult.SUCCESS;
            }
            var identities = incus.configByPrefix(instance, Metadata.ACCOUNT_IDENTITY_PREFIX);
            selection.forEach((namespace, account) -> {
                var identity = identities.get(namespace);
                System.out.println(namespace + " = " + account
                        + (identity == null || identity.isBlank() ? "" : "  (built for " + identity + ")"));
            });
            return CommandResult.SUCCESS;
        }
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
            java.util.Map<String, String> requested;
            try {
                requested = AccountSelection.parse(args.subList(1, args.size()));
                AccountSelection.validate(config, requested);
            } catch (AccountSelection.InvalidSelectionException
                     | AccountResolver.UnknownAccountException e) {
                System.err.println("Error: " + e.getMessage());
                return CommandResult.valueOf(1);
            }

            // Refuse a swap the built container could not honour, while the user is still
            // choosing -- rather than letting it surface later as a failing request inside.
            var reason = AccountSelection.incompatibilityReason(config, incus, instance, requested);
            if (!reason.isEmpty()) {
                System.err.println("Error: " + reason);
                return CommandResult.valueOf(1);
            }

            // Merge rather than replace: naming one namespace must not silently unpin others.
            var current = AccountSelection.read(incus, instance);
            var merged = new java.util.LinkedHashMap<>(current);
            merged.putAll(requested);
            AccountSelection.stamp(incus, instance, merged, current);
            ProxyService.signalAccountRefresh();

            // The token swaps live, but anything the build *baked* from the old account -- the
            // git identity -- would still be the old one, so the instance would push with one
            // account and commit as another. Re-derived here while it is running; a stopped
            // instance is reconciled by InstancePrep on its next use instead.
            if (!"Stopped".equalsIgnoreCase(incus.getInstanceStatus(instance))) {
                InstanceLifecycle.reconcileAccountIdentities(incus, instance);
            }

            System.out.println(instance + " now uses " + AccountSelection.describe(merged) + ".");
            System.out.println("Takes effect on the instance's next request;"
                    + " nothing inside it needs restarting.");
            return CommandResult.SUCCESS;
        }
    }
}
