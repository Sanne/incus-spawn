package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;

/**
 * A tool that can be installed into a template image during build.
 * Implementations are discovered automatically via CDI.
 */
public interface ToolSetup {

    /** Short name for display during build (e.g. "podman", "gh", "claude"). */
    String name();

    /** Human-readable description shown in menus (e.g. "GitHub — PAT for git operations"). */
    default String description() { return ""; }

    /**
     * Always-true fact about this tool that an agent must know <em>before</em> acting,
     * rendered into {@code /etc/claude-code/CLAUDE.md} by the build. Null for most tools.
     * Reserve it for things that cause a wrong action if unknown; procedural how-to
     * content belongs in a skill, which loads on demand instead of every session.
     */
    default String agentNote() { return null; }

    /**
     * Skills this tool ships, installed when the tool is. Use these for the procedures
     * that drive the tool — they load on demand, unlike {@link #agentNote()}, which is
     * in context for every session. Bare skill names resolve against this definition's
     * own {@code skills.repo}, not the image's.
     */
    default ImageDef.SkillsDef skills() { return ImageDef.SkillsDef.EMPTY; }

    /** Proxy definition for credential injection by the MITM proxy. Null if this tool has no proxy config. */
    default ToolDef.ProxyDef proxy() { return null; }

    /**
     * Whether this tool's {@link #proxy()} configuration is its own credential, rather than
     * borrowing another tool's (e.g. {@code CopilotSetup} reuses the {@code gh} tool's PAT via
     * {@code github.token}). {@code isx init}'s credential menu uses this to avoid listing a
     * "configure X" entry that silently edits some other tool's secret.
     */
    default boolean hasOwnCredentials() { return true; }

    /**
     * What this tool's credential accounts look like: which keys hold the credential, and when an
     * account is unusable. Derived from the {@code secret: true} entries of {@link #proxy()}, so
     * a tool -- including one defined only in YAML -- has to declare nothing further to get named
     * accounts. Override only when usability depends on more than those keys being set, as it
     * does for Claude, whose accounts each carry an auth type.
     */
    default dev.incusspawn.config.AccountShape accountShape() {
        return dev.incusspawn.config.AccountShape.declaredBy(proxy());
    }

    /**
     * The credential namespaces this tool spends -- the ones whose account choice matters to an
     * instance that has it. Derived from {@link #proxy()}: each entry's namespace, including one
     * it borrows from another tool (Copilot's {@code github.token}). Override for a tool that
     * uses another tool's credential without declaring a proxy entry of its own.
     */
    default java.util.Set<String> credentialNamespaces() {
        var proxy = proxy();
        var namespaces = new java.util.LinkedHashSet<String>();
        if (proxy == null) return namespaces;
        for (var entry : proxy.getConfiguration().values()) {
            var namespace = proxy.namespaceOf(entry);
            if (!namespace.isBlank()) namespaces.add(namespace);
        }
        return namespaces;
    }

    /**
     * As {@link #credentialNamespaces()}, for a template that sets {@code resolvedParams} --
     * override for a tool whose parameters decide which credential it spends.
     */
    default java.util.Set<String> credentialNamespaces(java.util.Map<String, String> resolvedParams) {
        return credentialNamespaces();
    }

    /**
     * What an instance with this tool would be missing, for its account {@code selection}, beyond
     * the declared {@code secret: true} entries of its credential namespaces -- which are checked
     * for every tool regardless. {@code ""} when nothing. Override when being ready takes more
     * than a key being set, as Claude's typed accounts do.
     */
    default String credentialProblem(dev.incusspawn.config.SpawnConfig config,
                                     java.util.Map<String, String> resolvedParams,
                                     java.util.Map<String, String> selection) {
        return "";
    }

    /**
     * A short, secret-free description of one of this tool's accounts -- what kind of
     * credential it is, or whose -- for listings such as {@code isx account list}; {@code ""}
     * when there is nothing to say beyond its name. Never include the credential itself.
     */
    default String describeAccount(dev.incusspawn.config.SpawnConfig config, String accountName) {
        return "";
    }

    /**
     * What the build <em>derived</em> from this account and wrote into the image, or {@code ""}
     * when the account makes no difference to what gets baked.
     *
     * <p>Credentials themselves never enter a container -- the proxy substitutes them -- so a
     * tool that only has a header injected on its behalf bakes nothing and its accounts are
     * freely interchangeable. Two tools are not like that. Claude's auth mode decides which
     * variables {@code envEntries} writes into {@code isx-env.sh}, so it returns the mode:
     * accounts of the same mode remain interchangeable. GitHub derives {@code user.name} and
     * {@code user.email} from whoever the token belongs to, so it returns the account name with
     * a fingerprint of its token and email: any change of either is a change of identity.
     *
     * <p>The build stamps this under {@link dev.incusspawn.incus.Metadata#accountIdentityKey}.
     * A later re-point compares against it and, when it differs, either asks the tool to bring
     * the instance in line ({@link #rebakeForAccount}) or refuses the swap.
     *
     * @param accountName the account being considered, as named in {@code <ns>.accounts}
     */
    default String bakedAccountIdentity(dev.incusspawn.config.SpawnConfig config, String accountName) {
        return "";
    }

    /**
     * Whether this tool can re-derive what it baked, so a re-point is reconciled rather than
     * refused. Answerable without a container, because selection has to decide before any
     * instance is started.
     */
    default boolean canRebakeForAccount() { return false; }

    /**
     * Bring an already-built instance in line with a different account, re-deriving whatever
     * {@link #bakedAccountIdentity} describes. Only called when
     * {@link #canRebakeForAccount()} is true.
     *
     * <p>GitHub clears the git identity and asks the API again, which resolves through the
     * proxy and therefore answers for the new account by itself. Claude declines: its auth mode
     * lives in the environment a running agent has already read.
     */
    default void rebakeForAccount(Container container, String accountName) {
        throw new UnsupportedOperationException(name() + " cannot re-derive its baked identity");
    }

    /**
     * Whether this tool is in the instance but what {@link #bakedAccountIdentity} describes is
     * not -- gh installed with no git identity. Asks the guest, so it is only called where a
     * guest exec is affordable: once per build, and once on the first use of an instance whose
     * template predates {@link dev.incusspawn.incus.Metadata#ACCOUNT_IDENTITY_VERIFIED}. Only
     * meaningful for a tool that {@linkplain #canRebakeForAccount can re-derive}.
     */
    default boolean lacksBakedIdentity(Container container) { return false; }

    /**
     * What a build says when it ends with this tool's identity missing and no account to supply
     * it ({@link #lacksBakedIdentity}), or {@code null} to say nothing.
     *
     * @param accountName the account the template uses, which has no credential to derive from,
     *                    or {@code ""} when the namespace has no account at all
     */
    default String unbakedIdentityWarning(String accountName) { return null; }

    /**
     * What an {@link #bakedAccountIdentity} stamp becomes when account {@code from} is renamed
     * to {@code to}, or {@code null} when the stamp does not name the account -- a Claude auth
     * mode, which an account's name could coincide with without being it. Pure string work, so
     * it also applies to an account that is incomplete when renamed.
     */
    default String renameBakedIdentity(String baked, String from, String to) {
        return null;
    }

    /** Feature flag that must be enabled for this tool to be available. Null means always available. */
    default String feature() { return null; }

    /** Packages this tool needs installed via dnf. Used to batch all installs into one call. */
    default java.util.List<String> packages() { return java.util.List.of(); }

    /** Package repositories this tool needs enabled before packages are installed. */
    default java.util.List<ImageDef.PackageRepo> packageRepos() { return java.util.List.of(); }

    /** Other tools that must be installed before this one. */
    default java.util.List<String> requires() { return java.util.List.of(); }

    /**
     * Adds {@code name} and everything it (transitively) {@linkplain #requires requires} to
     * {@code into}. A name missing from {@code allTools} is still added, just not expanded.
     */
    static void addWithRequires(String name, java.util.Map<String, ToolSetup> allTools,
                                java.util.Set<String> into) {
        if (!into.add(name)) return; // already visited -- also guards against a requires cycle
        var tool = allTools.get(name);
        if (tool == null) return;
        for (var dep : tool.requires()) {
            addWithRequires(dep, allTools, into);
        }
    }

    /**
     * Parameter definitions for this tool. Returns an empty map by default.
     * Tools can override this to declare parameters with validation rules.
     */
    default java.util.Map<String, ToolDef.ParameterDef> parameters() {
        return java.util.Map.of();
    }

    /** Runtime actions this tool contributes to the TUI actions menu. */
    default java.util.List<ToolDef.ActionEntry> actions() { return java.util.List.of(); }

    /**
     * Environment variable declarations contributed by this tool.
     * Returned entries are collected by the build system, merged with template
     * env entries, validated for conflicts, and written to
     * {@code /etc/profile.d/isx-env.sh}.
     */
    /**
     * Environment for a build that selected particular credential accounts.
     *
     * <p>Only tools whose baked environment depends on which account is in use need this --
     * Claude, whose auth mode decides the variable set. Everything else ignores the selection
     * and the default delegates, so a tool that does not care is unaffected.
     *
     * @param accountSelection config namespace → account name; a namespace absent from it
     *     uses the configured default
     */
    default java.util.List<EnvEntry> envEntries(java.util.Map<String, String> resolvedParams,
                                                java.util.Map<String, String> accountSelection) {
        return envEntries(resolvedParams);
    }

    /**
     * Install against a build that selected particular credential accounts. Only tools that
     * derive something from the account need this; the default ignores it.
     */
    default void install(Container container, java.util.Map<String, String> resolvedParams,
                         java.util.Map<String, String> accountSelection) {
        install(container, resolvedParams);
    }

    default java.util.List<EnvEntry> envEntries(java.util.Map<String, String> resolvedParams) {
        return java.util.List.of();
    }

    /**
     * Install and configure this tool inside the given container. Packages are already installed.
     *
     * @param container the container to install into
     * @param resolvedParams parameter values (already validated and with defaults applied)
     */
    void install(Container container, java.util.Map<String, String> resolvedParams);

    /**
     * A command that exits 0 when this tool works, its first output line reported as the
     * version -- or {@code null} for none. It runs after every tool is installed, as the image's
     * user in a login shell that loads {@code isx-env.sh}, so it may rely on another tool's
     * environment -- unless {@link #verifyAsRoot} says it needs root, when it gets root's own
     * environment instead and cannot; see {@link ToolVerifier}.
     */
    default String verifyCommand(java.util.Map<String, String> resolvedParams) {
        return null;
    }

    /**
     * Whether {@link #verifyCommand} needs root. By default it runs as the image's user in a login
     * shell -- what that user gets, and nothing a root-only run could leave behind in their home.
     * A check that reads root-only files (sshd's host keys) says so, and then runs in root's own
     * environment, without {@code isx-env.sh}: it cannot rely on another tool's env entries.
     */
    default boolean verifyAsRoot() {
        return false;
    }

    /**
     * Apply only reconfigurable parameter changes without a full reinstall.
     * Called when a child template overrides reconfigurable parameters of a
     * tool already installed by an ancestor. Defaults to {@link #install}.
     */
    default void reconfigure(Container container, java.util.Map<String, String> resolvedParams) {
        install(container, resolvedParams);
    }

    /**
     * Brings the guest files isx owns for this tool up to date in a child build that inherits the
     * tool from an ancestor without installing or reconfiguring it, so a parent built by an older
     * isx does not hand its children files this one writes differently (#1108). Each call rewrites
     * them; a tool whose files never change keeps the default, which does nothing.
     */
    default void refreshInherited(Container container) {}
}
