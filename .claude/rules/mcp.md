---
paths:
  - cli/src/main/java/dev/incusspawn/mcp/**
  - cli/src/test/java/dev/incusspawn/mcp/**
  - cli/src/main/java/dev/incusspawn/command/McpCommand.java
  - common/src/main/java/dev/incusspawn/lifecycle/BranchFlow.java
  - common/src/main/java/dev/incusspawn/config/McpConfig.java
---

# MCP server (`isx mcp`)

`isx mcp` serves the Model Context Protocol over stdio so an agent on the host can use isx instances. DESIGN.md "MCP server: delegating work to isx instances" has the rationale; these are the invariants.

**No Quarkus extensions for it.** The protocol is hand-rolled (`JsonRpc`, `McpServer`, `StdioTransport`) because the Quarkus MCP extension, even initialized on demand, added ~1.7 ms (+55%) to every isx command's startup. Anything new here must not add runtime-init work to other commands: measure native `hyperfine 'isx --help'` against main if in doubt. Messages are Jackson trees; no `@RegisterForReflection` DTOs.

**Stdout is the protocol channel.** `StdioGuard.install()` runs first: the transport owns fd 0/1, `System.out` goes to stderr, `System.in` is empty, `Headless.enable()` turns off `TerminalProgress` animation and makes `Prompts.console()` return null. Code reachable from a tool must never read the console, call `InitCommand.requireInit()`, `ProxyHealthCheck.requireProxy` or anything else that prompts or `System.exit`s -- turn the condition into a `ToolError` saying what to ask the user. A `ToolError` is an `isError` result for the model; JSON-RPC errors are for protocol faults only.

**Trust model.** Definitions come from `ImageDef.loadTrusted()` only -- `isx mcp` runs in the agent's repository, whose `.incus-spawn/` must not even override a parent of an approved template -- and are passed to `BranchFlow.preflight`. Templates: listed in `mcp.templates`, not built from a project-local definition (`BuildSource.usedProjectLocal()`), `TYPE_BASE`, built (`TemplatePolicy`); config re-read on every call. Instances: created only through `BranchFlow` with `Request.defaults()` -- never expose network mode, accounts, GUI, KVM, inbox or resources as tool arguments. Every tool naming an instance calls `McpSession.requireOwned()` (registry *and* the `mcp-session` stamp from Incus) once, at the tool boundary, and passes what it checked down; every task goes through `Tasks.require()`. `McpNoWritePathTest` forbids config/definition writes, builds, account changes, host processes and host file writes (except the audit log) in the package -- a change that needs one of those changes the trust model and should have to edit that test.

**Nothing an agent sends reaches a shell unquoted.** `ExecScript.quote()` for values, environment names validated, task run scripts sent as base64, instructions and prompts on stdin. Test hostile input in a real bash (`ExecScriptTest`, `TaskScriptsTest` run the generated scripts locally, the latter with stubs for `sudo`/`systemd-run`/`systemctl`/`su`/`claude`).

**Lifecycles.** Session id is `<pid>-<processStartMillis>` (`SessionId`); names are reserved before the copy; `reap()` on end of input and in a shutdown hook; `OrphanReaper` for dead sessions of the same host user, skipping kept and busy instances. Exec has no default timeout; cancellation kills the command's `setsid` session. Tasks are systemd units in the guest (`TaskScripts`), their state is the files under `~/.isx-mcp/tasks/<id>/`, and unit state is read with `sudo -n systemctl` (an unprivileged session cannot reach the system bus in a container). Poll with `TaskScripts.states()` (one cheap exec per instance), not `status()`, which carries the output tail. A status read must exit 0 even before the run has written anything. Destroys signal the proxy once per batch (`InstanceBackend.refreshProxy()`).

**Tests.** `McpServerProtocolTest` (protocol over a captured transport), `McpToolsTest`/`DelegationToolsTest` (tools against `FakeBackend`), `McpSessionTest`, `OrphanReaperTest`, `TemplatePolicyTest`, `ExecScriptTest`, `TaskScriptsTest`, `StreamJsonEventsTest`, `TailBufferTest`, `McpNoWritePathTest`; `InstanceLifecycleRequestBudgetTest` pins that ownership stamps cost no requests. A change to what Incus does with a request, or to the guest scripts, needs the `validate-on-real-incus` skill too.
