package dev.incusspawn.config;

import dev.incusspawn.tool.ToolSetup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Which account an instance actually uses for each credential namespace, and why.
 *
 * <p>An instance records only its pins ({@code user.incus-spawn.account.<ns>}); a namespace it
 * does not pin follows the configured default, which can change under it. Showing the pins alone
 * therefore answers "what was chosen" but not "what is being spent", and the second is the
 * question a user asks before trusting an instance with a client's work. This resolves both,
 * through the same {@link AccountResolver} the proxy serves from, so what it reports is what
 * the proxy does.
 *
 * <p>Who chose each pin is read from its recorded {@link AccountOrigin}, never inferred. The
 * template is also compared as it reads <em>now</em>, which answers what no record can: a
 * template whose {@code accounts:} was edited after the instance was branched, which the
 * instance does not follow.
 */
public final class AccountUsage {

    private AccountUsage() {}

    /**
     * @param account         the account served, or {@code ""} when the namespace has none usable
     * @param origin          who chose the pin, or {@code null} when the instance is not pinned
     *                        and follows the global default
     * @param templateAccount what the instance's template chain names for this namespace today,
     *                        or {@code ""}
     * @param description     {@link ToolSetup#describeAccount}, never a credential
     * @param problem         why a pinned account cannot be served, or {@code ""}
     * @param templateProblem why the template's account cannot be served -- renamed or removed
     *                        since the template was written -- or {@code ""}
     * @param refusal         why the proxy refuses to serve this account to this instance -- it
     *                        was built for another auth mode -- and how to fix it, or {@code ""}
     */
    public record Use(String namespace, String account, AccountOrigin origin, String templateAccount,
                      String description, String problem, String templateProblem, String refusal) {

        public Use(String namespace, String account, AccountOrigin origin, String templateAccount,
                   String description, String problem, String templateProblem) {
            this(namespace, account, origin, templateAccount, description, problem, templateProblem, "");
        }

        public boolean pinned() {
            return origin != null;
        }

        /** The template names a different account than the one this instance uses. */
        public boolean differsFromTemplate() {
            return !templateAccount.isEmpty() && !templateAccount.equals(account);
        }
    }

    /**
     * One entry per namespace the instance has anything to do with: every namespace with a
     * configured account, pinned, or named by its template, in the order tools declare them.
     *
     * @param pins             the instance's recorded selection ({@link AccountSelection#read})
     * @param origins          who chose each pin ({@link AccountSelection#readOrigins})
     * @param templateAccounts its template chain's {@code accounts:} as they read now
     * @param bakedIdentities  its {@code account-identity} stamps, to report an account the
     *                         proxy refuses because the instance was not built for it
     */
    public static List<Use> of(SpawnConfig config, Map<String, ToolSetup> setups,
                               Map<String, String> pins, Map<String, AccountOrigin> origins,
                               Map<String, String> templateAccounts, String instance,
                               Map<String, String> bakedIdentities) {
        var refused = AccountSelection.servingMismatches(config, setups, instance, pins, bakedIdentities);
        var namespaces = new LinkedHashSet<>(setups.keySet());
        namespaces.addAll(pins.keySet());
        namespaces.addAll(templateAccounts.keySet());

        var tree = config.tree();
        var uses = new ArrayList<Use>();
        for (var namespace : namespaces) {
            var setup = setups.get(namespace);
            var shape = setup != null ? setup.accountShape() : AccountResolver.shapeOf(config, namespace);
            var pin = pins.getOrDefault(namespace, "");
            var fromTemplate = templateAccounts.getOrDefault(namespace, "");

            var problem = "";
            String account;
            try {
                account = AccountResolver.effectiveAccount(tree, namespace, shape, pin);
            } catch (AccountResolver.UnknownAccountException e) {
                account = pin;
                problem = e.getMessage();
            }
            if (pin.isEmpty() && account.isEmpty() && fromTemplate.isEmpty()) continue;

            var templateProblem = "";
            if (!fromTemplate.isEmpty() && !fromTemplate.equals(pin)) {
                try {
                    AccountResolver.effectiveAccount(tree, namespace, shape, fromTemplate);
                } catch (AccountResolver.UnknownAccountException e) {
                    templateProblem = e.getMessage();
                }
            }

            var origin = pin.isEmpty() ? null : origins.getOrDefault(namespace, AccountOrigin.UNKNOWN);
            var description = setup == null || !problem.isEmpty() || account.isEmpty()
                    ? "" : setup.describeAccount(config, account);
            uses.add(new Use(namespace, account, origin, fromTemplate,
                    description == null ? "" : description, problem, templateProblem,
                    problem.isEmpty() ? refused.getOrDefault(namespace, "") : ""));
        }
        return uses;
    }

    /**
     * Where an instance's account comes from, as one plain sentence saying who chose it -- the
     * template or someone choosing for the instance -- and so what would change it. Never a bare
     * label: "pinned" or "default" alone leave the reader to guess both.
     *
     * @param template   the template the instance was branched from
     * @param isTemplate whether the instance <em>is</em> that template
     */
    public static String explainSource(Use use, String template, boolean isTemplate) {
        var ns = use.namespace();
        if (!use.pinned()) {
            return "Not pinned: follows the global default (" + ns + ".default in config.yaml),"
                    + " so it changes if that default is changed.";
        }
        var origin = use.origin();
        return switch (origin.kind()) {
            case TEMPLATE -> isTemplate && origin.source().equals(template)
                    ? "Pinned by this template's own accounts: setting, when it was built."
                    : "Pinned by template " + origin.source() + "'s accounts: setting, copied onto this"
                            + " instance when it was branched.";
            case EXPLICIT -> "Pinned by an explicit choice for this instance (isx branch --account,"
                    + " isx account set, or the TUI).";
            case COPIED -> "Pinned on " + origin.source() + ", and copied onto this instance when it"
                    + " was branched from it.";
            case UNKNOWN -> "Pinned before isx recorded who chose it"
                    + (use.templateAccount().equals(use.account())
                            ? "; it is the account template " + template + " chooses."
                            : ".");
        };
    }

    /**
     * Instances pinned to {@code namespace=account}, from a map of every instance's pins such
     * as {@code InstanceRegistry.accountsByInstance} returns.
     */
    public static List<String> pinnedTo(Map<String, Map<String, String>> pinsByInstance,
                                        String namespace, String account) {
        var pinned = new ArrayList<String>();
        pinsByInstance.forEach((instance, pins) -> {
            if (account.equals(pins.get(namespace))) pinned.add(instance);
        });
        return pinned;
    }
}
