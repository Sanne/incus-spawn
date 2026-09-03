---
paths:
  - "common/src/main/java/dev/incusspawn/tool/**"
  - "common/src/main/resources/tools/**"
  - "common/src/main/java/dev/incusspawn/config/Env*.java"
---

# Tool System

`ToolSetup` interface with two implementations:
- **YAML tools** (`ToolDef` + `YamlToolSetup`): declarative definitions in `common/src/main/resources/tools/`. Execution order: packages -> downloads -> run -> run_as_user -> files -> verify. Environment variables are declared via `env:` entries and collected centrally by `BuildCommand.writeEnvFile()`.
- **Java tools** (CDI `@Dependent` beans implementing `ToolSetup`): for tools needing programmatic logic (`ClaudeSetup`, `CodexSetup`, `GhSetup`, `PiSetup`, `BobSetup`). Declare env vars via `envEntries(Map<String,String>)` method. Tools can declare a `feature()` to gate themselves behind an opt-in feature flag in `SpawnConfig.features`.

Resolution via `ToolDefLoader` (later overrides earlier): built-in YAML -> user YAML -> search paths -> project-local YAML. Java CDI tools are used as fallback when no YAML tool matches.

Tools can declare runtime actions (`ActionEntry`) shown in the TUI's F9 actions menu and available via `RunCommand` (`isx run`). Both YAML tools (via `actions:` in the YAML) and Java/CDI tools (via `ToolSetup.actions()`) can contribute actions. Templates select a default action via `ImageDef.defaultAction` (`default-action` in YAML), which is run on Enter in the TUI or when executing `isx run <instance>`. The reference format is `tool-name` (single action) or `tool-name:action-id` (multiple actions). `default-action` inherits through the parent chain (child overrides parent) and is intentionally excluded from `contentFingerprint()` so changing it doesn't trigger template rebuilds.

Action resolution logic is centralized in `ActionResolver`, shared by both `ListCommand` (TUI) and `RunCommand` (CLI). `ActionResolver` handles discovering actions from installed tools, resolving default actions from template inheritance chains, finding specific actions by reference, and building `ActionContext` for execution.

**Important**: Built-in YAML files are listed in a hardcoded `BUILTIN_FILES` constant (not classpath scanning) because GraalVM native image makes classpath directory listing unreliable. When adding a built-in image or tool, you must update the corresponding `BUILTIN_FILES` list.

# Environment Variable System

`EnvEntry` (`config/EnvEntry.java`) models a declarative env var with four strategies: `SET`, `SET_IF_UNSET`, `PREPEND`, `APPEND`. Supports backward-compatible raw shell strings via a custom `ListDeserializer` that handles mixed-type YAML lists (strings and maps). Both `ToolDef.env` and `ImageDef.env` use this model.

`EnvResolver` (`config/EnvResolver.java`) collects sourced entries from the template parent chain and all tools, validates consistency (set+set with different values -> `EnvConflictException` naming both sources), and generates the shell script for `/etc/profile.d/isx-env.sh`.

`BuildCommand.writeEnvFile()` orchestrates collection: built-in entries (`ISX_CONTAINER`, `ISX_TEMPLATE`) -> template chain env -> tool `envEntries()`. Called after `runToolSetup()` in both `buildFromScratch` and `buildFromParent`. `linkJavaTrustStores()` runs after `writeEnvFile()` and symlinks any JDK `cacerts` under `/usr/lib/jvm` or `/opt` to the system trust store (`/etc/pki/java/cacerts`), so non-Fedora JDKs (GraalVM, labsjdk, etc.) trust the MITM CA without needing `JAVA_TOOL_OPTIONS`.
