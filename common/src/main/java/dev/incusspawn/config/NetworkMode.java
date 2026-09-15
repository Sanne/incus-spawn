package dev.incusspawn.config;

/**
 * Network isolation mode for branched instances.
 */
public enum NetworkMode {

    /** Full internet access via Incus bridge with NAT masquerading. */
    FULL("Full internet"),

    /** Network restricted to the host proxy only (Claude API + GitHub via proxy, nothing else). */
    PROXY_ONLY("Proxy only"),

    /** Complete network isolation — no network device attached. */
    AIRGAP("Airgapped");

    private final String label;

    NetworkMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
