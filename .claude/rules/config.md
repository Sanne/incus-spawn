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

**Project-local is untrusted**: it is whatever a cloned repo ships.
Project-local templates' host-resources are confined to the project directory (`ImageDef.projectRoot` stamped at load, never from YAML; enforced in `HostResourceSetup.collectEffective()`, re-checked via `verifyConfined()` at build, branch, and start; recorded in `BuildSource.projectRoots`).
A `file://` base image URL in a project-local template gets the same check (`BuildCommand.requireConfinedBaseImages`).
Project-local templates get no host repo references and are skipped by `HostRepoRefresh.collectAllRepos()`.
Their `image_url` may only replace an image their own project imported (`incus-spawn.project` image stamp; trusted builds refuse or re-import project-stamped images, `BuildCommand.requireImageTrustedFor`).
Project-local tools cannot declare `proxy:` (`ToolProxyResolver.rejectProjectLocalProxy()`).
Any new capability that reaches host state outside the project, such as host paths, host-only sources, or credentials, needs the same gate.
Host routes (#936) are one: only `config.yaml`'s `host-routes:` declares them, and a project-local instance never passes one.
`coordinator:` (#1812) is another: a project-local definition's is ignored with a warning at load and in `TemplateValidator`, makes no coordinator by default, and gives a coordinator no scope (`McpScope`).
See DESIGN.md "Project-local templates are confined to their project".

**Drivers** (`drivers:`, `Drivers`, #1720): a raw section like `host-routes:`; a tool name (`Metadata.ownerNameProblem`, never `mcp`) mapped to `destroy:` (a boolean, default false).
An unknown key, a wrong type or a bad name drops the entry with a warning, and a dropped entry grants nothing.
Only `isx destroy --owner` reads it, and only config.yaml declares it: never a template, never the tool.
**Host routes** (`host-routes:`, `HostRoutes`): a raw section like `artifact-cache:`, so any shape loads and is written back as it was; a name under `.internal` mapped to `target:` (`host:port` on loopback) and a required, non-empty `templates:` list (no route admits every instance; decided on #936).
`HostRoutes.routes` checks each entry against the intercepted domains and drops a wrong one with a warning naming it; an unknown key drops the entry, so a misspelt `templates:` drops it too, never widens it.
Keep the entry keyed by name with its filter as a field: #935's per-instance grants go beside `templates:`.

**Secrets**: `SecretRegistry` is the single source of truth for *where secrets live* in `config.yaml`.
It holds no list -- it collects every tool's `ToolDef.ConfigEntry` that declares `secret: true` with a `config-path`, resolved through `ProxyDef.fullConfigPath()`.
So a new credential is made known to every secret-aware consumer by declaring it on the tool (Java `ToolSetup.proxy()` or tool YAML), never by editing a list here.
The same declaration that makes the proxy inject it makes the redactor remove it.
`SecretRedactor` is the first consumer (`isx doctor --bundle`: structural redaction of the config, then value/pattern scrubbing of logs, marker `<isx:redacted:<path>>`); a pluggable secrets backend would be the second.
The split is deliberate: `SecretRegistry` knows *where declared secrets live*, `SecretRedactor` knows *what a credential looks like* -- its `looksSecret()` whole-word name heuristic (a backstop for undeclared keys landing in `SpawnConfig.getExtras()`, a typed namespace's `NamespaceConfig` extras or any key of its accounts; keep it whole-word, since substring matching blanks out unrelated diagnostics like `monkey` and `keyboardLayout`; and the nearest key name governs — a secret-named *list* is redacted whole, a secret-named *object* is not, since its fields have their own names and an `auth: {endpoint, timeoutMs}` block is ordinary config) and its token-shape patterns.
Its first pattern is `ProofToken.ANY_PROOF`, the marker and digest of every proof isx mints, so a proof is scrubbed even where its prefix borrows no known token shape (`gho_isx_`, `ya29.isx_`).
Every pattern stays linear on any input, since a guest's output reaches the scrub through a build's log: no open-ended run before a literal that another attempt can start inside (a private-key header's words may run on, since they end at the next `-` at the latest and every header starts with one), no lazy match that can run to the end from each of many starts (`SecretRedactorTest.manyKeyHeadersWithNoEndAreScrubbedInLinearTime`).
See DESIGN.md "Support bundles: redaction by construction".

**Credential accounts**: each credential namespace may hold `accounts:` (a named map) and `default:`.
`AccountResolver` resolves them off the serialized config tree, so any namespace gets named accounts without a Java change -- including a tool defined only in YAML, whose namespace lands in `SpawnConfig.getExtras()`.
**One model for every namespace, Claude included (#773)**: a namespace with no `accounts:` block but a flat credential (`github.token`, as every pre-accounts file has) *presents as the account `default`* -- listed, pinnable as `<ns>=default`, served -- with nothing synthesized into the file (`value()` falls back from the account's key to the flat one).
What the resolver knows about a namespace is its `AccountShape`, from `ToolSetup.accountShape()`: derived from the proxy definition by default (`secret: true` entries are the credential, so `apiKey: ""` or a lone `github.email` is not an account; user-supplied entries are per account; `confirm` entries stay shared at the namespace level), overridden by `GhSetup` (email is per account) and `ClaudeConfig.ACCOUNT_SHAPE` (flat `useVertex`, type-dependent completeness).
Its `problem()` hook is the only per-namespace judgement: unusable accounts are skipped for the default and a pin to one fails as "configured but incomplete" (#742).
Tree-taking resolver methods take the shape explicitly (the proxy passes the setups it holds -- never scan tool YAMLs on the event loop); `SpawnConfig`-taking ones look it up, built-in Java tools first.
**Do not add `if (namespace is claude)` branches** -- a namespace that needs different rules needs a different shape.
Namespaces that *do* have a class (`ClaudeConfig`, `GitHubConfig`, `BobConfig`, `OpenaiConfig`) extend `SpawnConfig.NamespaceConfig` to preserve undeclared keys; without that an `accounts:` block deserializes to nothing and the next `save()` **deletes it**.
The map lives in the package-private superclass `UndeclaredKeys`, which `SpawnConfig` and `ClaudeAccount` extend as well: the generated mapping counts every field a class declares itself as a known key, `@JqIgnore` or not, so a map declared there would drop a key named `extras` from the file.
The same holds for any field a class with a `@JqAnySetter` does not bind (#1889): `McpConfig`'s collected keys, `ImageDef`'s `source` and `projectRoot` and `ToolDef`'s fingerprint cache live in superclasses (`McpUndeclaredKeys`, `DefinitionOrigin`, `FingerprintCache`), and `JjqGeneratedMappingTest` fails on a `@JqIgnore` or `transient` field declared in such a class.
Any new nested config class holding credentials must extend it for the same reason, and its "is this configured" accessors must go through `NamespaceConfig.defaultValue()` -- the typed field is empty once the credential lives in an account.
`ClaudeConfig` overrides `defaultValue()` to apply the account type (a field of another type, such as an OAuth account's stale `apiKey`, reads as `""`), and refuses a key that is not a Claude credential field.
`ClaudeConfig` keeps a typed API (`accountNamed`, `accountFor`) only to *interpret* accounts (`effectiveType`, `servesDirectApi`).
Secrets under `<ns>.accounts.<name>.<path>` are already covered by `SecretRedactor`'s deep pass under a declared namespace, so no separate registration is needed.
`NamespaceAccounts` is the write side, and **always writes the accounts layout** (a single credential is `accounts.default` + `default: default`): `put`/`replaceAll`/`setDefault`/`remove`/`clear`.
`put` and `remove` first call `materialize` (`replaceAll` and `clear` discard the flat credential outright), which moves a flat credential into `accounts.default` -- only on a write with something to save, so an abandoned `isx init` leaves the file untouched (#740).
`isx init` drives it from the namespace name alone with one vocabulary for every namespace, `AccountTarget(name, replaceOthers)`, applied only once a credential is collected (`CredentialSetup.saveAccount`).
GitHub, Bob, OpenAI and YAML tools share `chooseAccountTarget`, Claude keeps its own menu for its typed listing.
`ImageDef.resolveAccounts()` merges a template chain's `accounts:` per key, and the selection is part of `contentFingerprint()` -- re-pointing an account changes what gets baked (Claude's auth mode, the git identity), so it must rebuild.
Files written by releases users are on (v0.3.7, v0.3.8, v0.3.12, v0.4.0) are pinned by `PreviousReleaseConfigCompatTest` against fixtures generated by each release's own serializer (`scripts/config-compat/generate-fixtures.sh <tag>`).
Add the tag to its `RELEASES` and to the CI loop whenever a release changes the format or users stay on it.
What only some releases write (the `accounts:` layout since v0.3.9, the `mcp:` section of v0.3.10 to v0.3.12, v0.4.0's hand-written sections) is a fixture of its own, from a `Generate*.java` beside the generator that the script runs only against a release with the API it calls, listed in the test's `RELEASE_FIXTURES`; `everyCommittedFixtureIsDeclaredForAListedRelease` fails for a committed fixture not declared there.
v0.3.7 still writes the old `host-path` key, beside `host-paths` naming the same path, which must load as one.

**The new-template skeleton is the format's reference** (#1386): `TemplatesCommand.SKELETON`, which `isx templates new` and the TUI write, shows every field `ImageDef` reads, each with a one-line note.
A new `ImageDef` field, or a new key in one of its entries, gets a line there; `TemplateSkeletonTest` takes the field names the mappings bind (`@JqName`, else the field's own) and the env and skills converters' keys, and fails without it.
It also goes in `ImageDefTest`'s `FINGERPRINTED`, with a value that must change `contentFingerprint()` (added only when set, so no existing template rebuilds: the test pins the fingerprint of a definition without it), or in `NOT_FINGERPRINTED` with the reason it never reaches the image; `everyDefinitionKeyIsFingerprintedOrDeliberatelyLeftOut` fails without either (#1571).
The skeleton's examples, uncommented, must stay a valid template.

**Unknown keys warn, wherever they are** (#1546): every class a template or tool definition maps to, entries included, extends `MappedDefinition`, so a key isx does not read is collected and `ImageDef.unknownKeyWarnings()` / `ToolDef.unknownKeyWarnings()` name it with its place (`repos[0].branch-name`) and the key it probably meant.
A new mapped class in a definition extends it, and a new list, map or nested entry gets a line in that walk; a converter that reads the tree itself hands its unknown keys to the entry it builds (`EnvEntryListConverter`, `SkillsDefConverter`).
They are warnings, never refusals, reported at load (`ImageDef.validate`, `ToolDefLoader`) and by `TemplateValidator`.
In `config.yaml`, a key in a tool's credential namespace that no tool reads (`openai.token`) is named by `UnreadConfigKeys` (#1733).
What a namespace reads is declared: its tools' `proxy.configuration` paths and `AccountShape` keys (`accountKeys()` for what an account holds), plus `accounts:` and `default:`.
The credential steps of `isx init` and `isx account add` warn before asking, `CredentialCheck` adds it to a missing credential's notes, and `isx doctor` reports it; isx never moves the key to where it reads one.
`ClaudeConfig` extends `NamespaceConfig` and `ClaudeAccount` keeps its own undeclared keys, so one under `claude` reaches the check (#1876); the phantom `oauthMode` older releases wrote is a declared write-only field, dropped on load.
Only trusted tools decide what is read: `UnreadConfigKeys.trustedTools` (no project `.incus-spawn/tools/`, no tool whose feature is off) for init, doctor and `CredentialCheck`, never the caller's tool map, which may hold a project's tools.

**Name conflicts vs. overrides**: Overriding a definition from a *later* layer is intentional and supported.
Two files declaring the same `name:` *within a single directory* (usually a copy-paste that forgot to update `name:`) is always a mistake and is reported as a conflict.
`ImageDef.loadAllWithConflicts()` / `ToolDefLoader.conflicts()` return these same-directory collisions (all colliding files, so 3+ are listed together) plus the intentional cross-layer overrides.
`isx build` aborts with a message naming the colliding files and refuses to build until you disambiguate.
The TUI stays resilient (surfaces the conflict as a status warning but still lists templates).
`isx doctor` reports both conflicts (warnings) and cross-layer overrides (informational -- this explains the "built image doesn't match the file I'm editing" confusion).
Plain `ImageDef.loadAll()` still returns the resolved map (last-writer-wins) and emits a one-line conflict warning via its warnings consumer, so existing callers are unaffected.
Files that fail to parse are recorded too (`LayeredDefinitions.parseFailures()`, `ToolDefLoader.parseFailures()`).
So are image definitions refused at load because their `name:` or `parent:` is not a valid Incus instance name (`InstanceNames`).
So are image definitions on a parent cycle (#1443), found once every layer is loaded since an override can close one: one warning names the cycle and its files, and every template on it is dropped (`LayeredDefinitions.refuse`), so no chain walk (`ImageDef.ancestors`, `inheritTypes`) can loop.
So are tool definitions refused at load because a credential namespace they name is not an identifier (`ToolDefValidator.invalidNamespace`).
So are tool files with no `name:`, or a blank one, as a template without one is.
`isx build` refuses while any exist.
The skipped file's name is unknown or unusable, so building would silently fall back to a lower layer or the existing template's build-source snapshot.
A build-source snapshot merged in after load (`isx build`, the TUI's `ListingLoader`) can still close one, so `ImageDef.ancestors` (and `chain`) also stops before a layer it has already seen, warning through `Warnings` once per cycle (named from its least template, so every walk into it gives the same line).
`isx build` refuses a target whose chain runs into one right after merging the snapshots, before any check, question or build (`ImageDef.parentCycle`, `BuildCommand.reportParentCycle` in `dispatch`): its chain has no root to build from.
Walk a parent chain through `ancestors` or `chain` so that guard covers it; a hand-written `getParent()` loop or recursion needs its own visited set (`BuildCommand.definitionChain`, `ActionResolver`, `DiskUsageModel`).
`YamlErrors.friendly()` names the offending field for shape mismatches (list vs. single value vs. mapping), with the line jjq reports on the binding failure.

Definitions and `config.yaml` bind through jjq (`Jjq`), not Jackson (DESIGN.md "jjq rather than Jackson"):
- A new DTO is `@JqMapped`; `jjq-mapper-processor` generates its mapping and `Jjq` registers it, so the native image needs no registration for it.
  That holds only while `Jjq` and the generated `*_JqMapping` classes are initialized at build time: their static initializers look up private `Field`s, which the image heap keeps; keep them off the `--initialize-at-run-time` lists in both `application.properties`.
  An enum a DTO binds is the exception: jjq reads its `@JqEnum`/`@JqName` by reflection, so it carries `@RegisterForReflection` (`ClaudeAccountType`), or the native image binds every value to null.
  `JjqGeneratedMappingTest` fails for a reachable DTO without a registered mapping, and for a reachable enum without the annotation (#1316).
- A converter that reads scalars from the tree uses `Jjq.text`, so `0022` stays `0022`; `asText()` gives the resolved `18`.
- An object is never iterated with `asList()` (empty for an object); use `JqObject.values()`/`forEach`.
- An `Integer` field takes `@JqConverter(IntegerConverter.class)`: jjq bound `.inf` and `.nan` as 0 before 0.2.4 (and `Jjq.exactInt` still refuses them, now non-finite) where a bad value must fail the file, refuses a blank string Jackson left unset, and names the field as a `$` path rather than as written.
- isx's own JSON (its stamps, its proxy's endpoints, `isx mcp`'s output, Incus's answers) is read with `JqValues.parseStrict`, and a parse failure is shown with `Jjq.brief`.
  JSON another program wrote (a tool's file, an agent's events, an MCP client's message, an API's answer) is read with `Jjq.parseForeign(json, source)`: a lone surrogate escape reads as U+FFFD, everything else malformed fails as `<source> is not valid JSON: <why> at line L, column C` (DESIGN.md "jjq rather than Jackson").
  Pass the bytes received (`BodyHandlers.ofByteArray()`, `ByteLines` for a line stream), never a decoded `String`: decoding turns invalid UTF-8 into U+FFFD, which jjq then accepts.
  The exceptions are readers handed a `String` that exec output already decoded, such as the Claude Code, Codex and Pi event tails, `ModelCheck.refusal`, `HerdrIntegration`, `GeminiSetup.selectApiKeyAuth` and `ClaudeSetup.parseClaudeJson`.
  `~/.claude.json` is read with `ClaudeSetup.parseClaudeJson`, which first drops the approval line the login script appends (a repeated key).
