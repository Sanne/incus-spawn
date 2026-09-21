package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public record ProxyCredentials(
        String anthropicApiKey,
        String oauthToken,
        boolean useVertex,
        String vertexRegion,
        String vertexProjectId,
        List<ResolvedToolProxy> toolProxies
) {
    public ProxyCredentials {
        anthropicApiKey = anthropicApiKey != null ? anthropicApiKey : "";
        oauthToken = oauthToken != null ? oauthToken : "";
        vertexRegion = vertexRegion != null ? vertexRegion : "";
        vertexProjectId = vertexProjectId != null ? vertexProjectId : "";
        toolProxies = toolProxies != null ? toolProxies : List.of();
    }

    /** Credentials for the configured defaults -- used for host-side work and template builds. */
    public static ProxyCredentials fromConfig(SpawnConfig config) {
        return forAccounts(config, java.util.Map.of());
    }

    /**
     * Credentials for one instance's account selection.
     *
     * <p>Claude is resolved through {@link SpawnConfig.ClaudeConfig#accountNamed} rather than
     * the generic tree walk, because its {@code type: anthropic} auth is handled directly in
     * {@code MitmProxy} instead of via tool proxies, and because {@code ClaudeConfig} also
     * synthesizes an account from the pre-accounts flat layout. Every other namespace goes
     * through {@link ToolProxyResolver#resolveForAccounts}, which needs no per-tool code.
     *
     * @param accountsByNamespace config namespace → account name, as recorded on the instance
     * @throws dev.incusspawn.config.AccountResolver.UnknownAccountException if a named account
     *     is not configured -- callers must surface this rather than serve another account
     */
    public static ProxyCredentials forAccounts(SpawnConfig config,
                                               java.util.Map<String, String> accountsByNamespace) {
        return forAccounts(config, accountsByNamespace,
                ToolProxyResolver.proxyToolSetups(config));
    }

    /**
     * As {@link #forAccounts(SpawnConfig, java.util.Map)}, but against tool setups the caller
     * already loaded. The proxy resolves a selection on the event loop, where discovering tool
     * YAMLs -- a filesystem scan -- does not belong, so it loads them once per config reload
     * and passes them in here.
     */
    public static ProxyCredentials forAccounts(SpawnConfig config,
                                               java.util.Map<String, String> accountsByNamespace,
                                               java.util.Map<String, dev.incusspawn.tool.ToolSetup> toolSetups) {
        var claude = config.getClaude();
        var resolved = ToolProxyResolver.resolve(config, toolSetups, accountsByNamespace);
        // Each ClaudeConfig accessor re-resolves the account, rebuilding the account map every
        // time; resolve once and read the fields off it. Also makes it explicit that all five
        // values describe a single account rather than being independently sourced.
        var account = claude.accountNamed(
                accountsByNamespace.get(SpawnConfig.ClaudeConfig.NAMESPACE));
        var vertex = account != null && account.effectiveType() == SpawnConfig.ClaudeAccountType.VERTEX;
        var oauth = account != null && account.effectiveType() == SpawnConfig.ClaudeAccountType.OAUTH;
        var apiKey = account != null && account.effectiveType() == SpawnConfig.ClaudeAccountType.API_KEY;
        return new ProxyCredentials(
                apiKey ? account.getApiKey() : "",
                oauth ? account.getOauthToken() : "",
                vertex,
                account == null ? "" : account.getCloudMlRegion(),
                account == null ? "" : account.getVertexProjectId(),
                resolved
        );
    }

    public List<String> toolProxyNames() {
        return toolProxies.stream()
                .filter(tp -> tp.auth() == null || !"anthropic".equals(tp.auth().getType()))
                .map(ResolvedToolProxy::toolName)
                .distinct().sorted().toList();
    }
}
