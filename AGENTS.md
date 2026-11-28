# AGENTS.md

This file is for agents that read `AGENTS.md` or `GEMINI.md`, such as Antigravity CLI, Codex, Pi and opencode.
Claude Code reads `CLAUDE.md` instead, which is the full guide.
Read `CLAUDE.md` before a non-trivial change, and the rule files below for the area you touch.

## What this is

incus-spawn (`isx`) manages isolated Incus-based development environments: full Linux system containers with copy-on-write branching, a MITM TLS proxy that keeps credentials out of the guest, and a TUI.
`docs/dev/CHARACTER.md` states the mission and design philosophy, and a change that loosens isolation, credential handling or fail-closed behaviour is not carried out without Sanne's decision.
`README.md` and `docs/` are the user documentation, `DESIGN.md` indexes the rationale in `design/`.

## Build and test

```shell
mvn package                    # build all modules
mvn test                       # unit tests, no Incus needed
mvn test -Dtest=ToolDefTest    # one test class
mvn verify -DskipITs=false     # integration tests, needs a running Incus
```

Java 25, Quarkus 4.x.
Modules: `common` (shared code), `cli` (the `isx` binary), `proxy` (the `isx-proxy` binary).
`launcher/` is a Swift package outside Maven.
`./install.sh` installs from this checkout, so never run it from a worktree you will delete.

## Rules that apply to every change

- Rationale goes in the area's `design/` page in the same change as the code.
  A `.claude/rules/` file changes only when a rule does, and no fact is stated in two files.
- A test is owed for behaviour, not for how the code is arranged today (design/testing.md "What earns a test").
- Markdown is one sentence per line, with short sentences (`DocsOneSentencePerLineTest`).
  `README.md`, `docs/*.md` and the guides in `docs/guides/` also keep to the budgets in `.claude/rules/writing.md`.
- A guide (`docs/guides/`) is one task per page; it links to the reference and never restates it.
- Every text a person reads follows `.claude/rules/writing.md`, and the text isx prints (prompts, output, warnings, errors, the TUI) also follows `.claude/rules/interface-text.md`.
  `TranscriptGate` checks the transcripts of the main flows against its mechanical rules, `HelpTextGateTest` every command's `--help` text, and `McpToolTextGateTest` the texts `isx mcp` gives a model.
- A divergence from stock Fedora is listed in `docs/FEDORA.md` in the same change.
- Never print to stdout or stderr directly; all output goes through `BuildOutput` (`RawOutputGateTest`).
- A regular expression is a `static final Pattern` (`PatternConstantGateTest`).
- A test waits through `Eventually`, never a loop of its own that sleeps (`PollingLoopGateTest`).
- Never write a model ID in source; it is declared in `common/src/main/resources/models.yaml`.
- Base-image and tool checksums are taken from the publisher, never from a build log or another PR.
- Latency on the branch, start and shell path is never noise.
- Project-local `.incus-spawn/` definitions are untrusted.
- The project spans several repositories (images, templates, Homebrew, apt); a fix that belongs in one opens its PR there.

## Where the details are

Each file in `.claude/rules/` covers one area and loads in Claude Code when its `paths:` globs match.
Other agents read the one for the area they touch.

| Area | File |
| --- | --- |
| Build, branch, project and update commands | `.claude/rules/build.md` |
| CI workflows, `bench/`, the upstream watch | `.claude/rules/ci.md` |
| Commands, `isx list` and other output contracts | `.claude/rules/commands.md` |
| `config.yaml`, accounts, base images | `.claude/rules/config.md` |
| Incus client, VMs, the appliance, the launcher | `.claude/rules/incus.md` |
| `isx mcp` | `.claude/rules/mcp.md` |
| Native image builds | `.claude/rules/native-image.md` |
| The MITM proxy and credential injection | `.claude/rules/proxy.md` |
| Terminal output and tool setup output | `.claude/rules/terminal-output.md` |
| Tools, environment, agent setup | `.claude/rules/tools-env.md` |
| The TUI | `.claude/rules/tui.md` |
| Writing for a person: docs, PR descriptions, every text | `.claude/rules/writing.md` |
| Wording prompts, output, warnings and the TUI | `.claude/rules/interface-text.md` |

## Skills

`.agents/skills/validate-on-real-incus` links to `.claude/skills/validate-on-real-incus`, so Claude Code and Antigravity CLI read one file.
Use it when a change touches how isx talks to Incus, and before calling such a change verified.
