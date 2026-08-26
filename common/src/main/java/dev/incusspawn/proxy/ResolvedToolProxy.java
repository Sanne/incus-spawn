package dev.incusspawn.proxy;

import dev.incusspawn.tool.ToolDef;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A fully resolved tool proxy entry ready for the MITM proxy.
 * Contains the domain, auth rule, and actual configuration values
 * (read from config.yaml, not placeholder definitions).
 */
public record ResolvedToolProxy(
        String toolName,
        String domain,
        ToolDef.AuthDef auth,
        Map<String, String> configValues
) {

    /**
     * Return the HTTP header name to set.
     * "Authorization" for basic/bearer, or the custom name for header type.
     */
    public String headerName() {
        if (auth == null) return null;
        return "header".equals(auth.getType()) ? auth.getName() : "Authorization";
    }

    /**
     * Compute the header value from the auth rule and configuration values.
     * Auth fields use ${configKey} template references resolved against configValues.
     */
    public String computeHeaderValue() {
        if (auth == null) return null;
        return switch (auth.getType()) {
            case "basic" -> {
                var user = substituteRefs(auth.getUsername());
                var pass = substituteRefs(auth.getPassword());
                if (user.isEmpty() && pass.isEmpty()) yield null;
                var encoded = java.util.Base64.getEncoder()
                        .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                yield "Basic " + encoded;
            }
            case "bearer" -> {
                var token = substituteRefs(auth.getToken());
                yield token.isEmpty() ? null : "Bearer " + token;
            }
            case "header" -> {
                var result = substituteRefs(auth.getValue());
                yield result.isEmpty() ? null : result;
            }
            default -> null;
        };
    }

    private String substituteRefs(String template) {
        if (template == null) return "";
        var result = template;
        for (var entry : configValues.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
