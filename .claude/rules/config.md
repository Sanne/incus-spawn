---
paths:
  - "common/src/main/java/dev/incusspawn/config/**"
  - "common/src/main/java/dev/incusspawn/lifecycle/**"
  - "common/src/main/resources/images/**"
---

# Configuration Loading

- `SpawnConfig`: global config from `~/.config/incus-spawn/config.yaml`
- `ImageDef.loadAll()`: discovers all image definitions across resolution layers
- `ToolDefLoader`: discovers tools across resolution layers
- `ProjectConfig`: per-project config from `incus-spawn.yaml` or `.incus-spawn/incus-spawn.yaml`

Resolution order for both images and tools (later overrides earlier): built-in -> user (`~/.config/incus-spawn/`) -> search paths -> project-local (`.incus-spawn/`).

**Secrets**: `SecretRegistry` is the single source of truth for *where secrets live* in `config.yaml`. It holds no list -- it collects every tool's `ToolDef.ConfigEntry` that declares `secret: true` with a `config-path`, resolved through `ProxyDef.fullConfigPath()`. So a new credential is made known to every secret-aware consumer by declaring it on the tool (Java `ToolSetup.proxy()` or tool YAML), never by editing a list here; the same declaration that makes the proxy inject it makes the redactor remove it. `SecretRedactor` is the first consumer (`isx doctor --bundle`: structural redaction of the config, then value/pattern scrubbing of logs, marker `<isx:redacted:<path>>`); a pluggable secrets backend would be the second. The split is deliberate: `SecretRegistry` knows *where declared secrets live*, `SecretRedactor` knows *what a credential looks like* -- its `looksSecret()` whole-word name heuristic (a backstop for undeclared keys landing in `SpawnConfig.extras`; keep it whole-word, since substring matching blanks out unrelated diagnostics like `monkey` and `keyboardLayout`; and the nearest key name governs — a secret-named *list* is redacted whole, a secret-named *object* is not, since its fields have their own names and an `auth: {endpoint, timeoutMs}` block is ordinary config) and its token-shape patterns. See DESIGN.md "Support bundles: redaction by construction".

**Name conflicts vs. overrides**: Overriding a definition from a *later* layer is intentional and supported. Two files declaring the same `name:` *within a single directory* (usually a copy-paste that forgot to update `name:`) is always a mistake and is reported as a conflict. `ImageDef.loadAllWithConflicts()` / `ToolDefLoader.conflicts()` return these same-directory collisions (all colliding files, so 3+ are listed together) plus the intentional cross-layer overrides. `isx build` aborts with a message naming the colliding files and refuses to build until you disambiguate; the TUI stays resilient (surfaces the conflict as a status warning but still lists templates); `isx doctor` reports both conflicts (warnings) and cross-layer overrides (informational -- this explains the "built image doesn't match the file I'm editing" confusion). Plain `ImageDef.loadAll()` still returns the resolved map (last-writer-wins) and emits a one-line conflict warning via its warnings consumer, so existing callers are unaffected.
