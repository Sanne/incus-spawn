---
paths:
  - "proxy/**"
  - "common/src/main/java/dev/incusspawn/proxy/**"
  - "common/src/main/java/dev/incusspawn/DerEncoder.java"
---

# MITM TLS Proxy

`MitmProxy` (in `common/src/main/java/dev/incusspawn/proxy/`) is a TLS-terminating proxy that intercepts HTTPS to specific domains and injects real auth credentials, so containers only hold placeholder values. Key design:
- Listens on gateway IP:18443 (iptables redirects 443->18443 on the bridge)
- Per-domain certs signed by a custom CA (installed in templates during build). The CA lives at `~/.config/incus-spawn/ca.{crt,key}`; leaf certs are persisted by `CertStore` under `~/.config/incus-spawn/certs/` (`<domain>.crt`/`.key`, wildcards as `_wildcard.<domain>`) and reused across proxy restarts, re-minting only on miss/CA-rotation/near-expiry. Persisting is what keeps each leaf's `notBefore` stable: the proxy is relaunched frequently (macOS launchd `KeepAlive`), and re-minting on a host whose clock has jumped ahead of a lagging container clock (e.g. an Incus VM after macOS resume) produced certs the container rejected as "not yet valid". Certs are keyed by domain, never by container (a leaf is a function of `(domain, CA)`), so this composes with future per-container interception, which is a routing/DNS concern. `CertificateAuthority.BACKDATE_MS` backdates `notBefore` as a skew margin for the rare fresh-mint moments.
- Both CA and leaf certs carry RFC 5280 key identifiers: SKI on the CA, SKI + AKI on leaves. Strict validators (OpenSSL 3.5, and so Python 3.13+, which turns on `VERIFY_X509_STRICT` by default) reject a chain without them -- including the trust anchor, so leaf-only extensions are not enough. A CA generated before this is re-issued on load over its **existing key** (`reissueWithSki`), which keeps every leaf valid and un-re-minted; the replaced cert is kept as `ca-superseded.crt`. Images stamped with that superseded fingerprint carry a stale-but-not-foreign anchor: `BranchCommand` lets them branch (the new cert is pushed into the instance by `InstancePrep`/`fixContainerCaIfNeeded` on first use) instead of demanding a rebuild the way a real CA rotation does.
- Three auth modes for Anthropic domains (priority: Vertex > OAuth > API key): OAuth mode strips `x-api-key` and injects `Authorization: Bearer <token>` for Claude Pro/Max users; Vertex mode does three-way routing -- passthrough for Vertex-formatted requests, standard-to-Vertex translation for `/v1/messages` (using `VERTEX_ALLOWED_FIELDS` body allowlist), and direct forwarding for non-messages endpoints; API key mode replaces `x-api-key` with the real key
- OpenAI support (behind `openai` feature flag): intercepts `api.openai.com` and injects `Authorization: Bearer <openai-api-key>`
- WebSocket passthrough: handles Upgrade requests by establishing an upstream WebSocket connection (with credential injection), then relaying frames bidirectionally with keepalive pings and close-code propagation. Used by Codex CLI for `api.openai.com`
- Caches OCI blobs by SHA256, Maven artifacts by coordinate, and npm tarballs from `registry.npmjs.org` with ETag-based packument verification
