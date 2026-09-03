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

**Name conflicts vs. overrides**: Overriding a definition from a *later* layer is intentional and supported. Two files declaring the same `name:` *within a single directory* (usually a copy-paste that forgot to update `name:`) is always a mistake and is reported as a conflict. `ImageDef.loadAllWithConflicts()` / `ToolDefLoader.conflicts()` return these same-directory collisions (all colliding files, so 3+ are listed together) plus the intentional cross-layer overrides. `isx build` aborts with a message naming the colliding files and refuses to build until you disambiguate; the TUI stays resilient (surfaces the conflict as a status warning but still lists templates); `isx doctor` reports both conflicts (warnings) and cross-layer overrides (informational -- this explains the "built image doesn't match the file I'm editing" confusion). Plain `ImageDef.loadAll()` still returns the resolved map (last-writer-wins) and emits a one-line conflict warning via its warnings consumer, so existing callers are unaffected.
