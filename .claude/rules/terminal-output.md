---
paths:
  - "common/src/main/java/dev/incusspawn/util/BuildOutput.java"
  - "common/src/main/java/dev/incusspawn/util/TerminalProgress.java"
---

# Terminal Output

All multi-step lifecycle commands use `BuildOutput` (`common/.../util/BuildOutput.java`) for terminal output formatting -- not just build/branch but also `vm` (start/stop/resize), `destroy`, `update-all`, `update-base`, `project`, and the shared `VmManager`. This centralizes ANSI constants and step patterns -- individual commands should not define their own. Key helpers: `header()` (generic bold bullet header), `buildHeader()`/`branchHeader()` (build/branch-specific), `step()` for complete lines, `stepWithList()` for a header followed by a comma-separated list that wraps at the terminal width with a hanging indent (used for packages, tools, skills), `stepStart()`/`stepDone()`/`stepDone(detail)` for inline completion on slow operations (never leave a `Doing X...` line dangling with its result on a separate line), `note()` for dim informational messages (blank messages are silently skipped), `success()` for green checkmark lines. Headers live in command classes; shared helpers like `VmManager` emit only steps so they nest correctly under whichever header the caller prints. `isx init`'s interactive first-run flow is the one exception (its own style for now). See DESIGN.md "Terminal Output Visual Language" for the full spec.
