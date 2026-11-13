# Upstream watch: what Sanne sets up by hand

Maintainer notes for the upstream watch (design/upstream.md): the GitHub App, the environment, the secrets and the rulesets that nothing in the repository can create.
Each step says what to create, where, and how to check it.
Until the first two steps are done, `upstream-watch.yml` fails at its publish step and opens `[upstream] watch failing`; `upstream-verify` needs nothing here and works on every PR as soon as it is merged.

## 1. The GitHub App `isx-upstream`

The watch opens bump PRs as a GitHub App Sanne owns, never as his account or a PAT (design/upstream.md "Why not Sanne's own token").

Create it under Settings, Developer settings, GitHub Apps, New GitHub App:
- Name `isx-upstream`; the login the manifest expects is `isx-upstream[bot]` (`app.login` in `upstream/manifest.yaml`).
- Homepage URL: `https://github.com/Sanne/incus-spawn`.
- Webhook: untick Active; the app receives nothing.
- Repository permissions: Contents, read and write; Pull requests, read and write; Metadata, read (set automatically).
  Nothing else: no Issues (the watch opens those with the run's own token), no Workflows, no Administration.
- Where can this app be installed: Only on this account.

Then, on the app's page:
- Note the App ID.
- Generate a private key; GitHub downloads a `.pem` file, the only copy.
- Install App, on the `Sanne` account, Only select repositories: `incus-spawn`.
  Add `incus-spawn-images` and `incus-spawn-templates` when their phases start.

Check: `gh api 'users/isx-upstream%5Bbot%5D' --jq .id` prints a number; the watch resolves the commit author's noreply address from it.

## 2. The `upstream-watch` environment and its secrets

The app's key lives in an environment secret, not a repository secret, because a repository secret is readable by every workflow on `main` and by same-repository PR branches (design/upstream.md "Threat model").

In `Sanne/incus-spawn`, Settings, Environments, New environment `upstream-watch`:
- Deployment branches and tags: Selected branches and tags, add `main`, nothing else.
- No required reviewers and no wait timer: the watch runs unattended every six hours.
- Environment secrets: `UPSTREAM_APP_ID` (the App ID) and `UPSTREAM_APP_PRIVATE_KEY` (the whole `.pem` contents, header and footer included).

Check: `gh workflow run upstream-watch.yml -f item=kernel`, then `gh run watch`; the run's "Push the bumps and open their PRs as the app" step must pass, with or without a PR to open.
Delete the downloaded `.pem` once the secret is set, or keep it in a password manager only.

## 3. Rulesets

GitHub should enforce what the bot's merge script also checks, so a PR that fails a gate cannot be merged by anyone the ruleset binds.

In the `main` ruleset of `Sanne/incus-spawn`:
- Require status checks to pass: add `upstream-verify` and `kernel-mac-verified`.
  Both run on every PR and pass at once when they do not apply, so they can be required.
- Keep "Dismiss stale pull request approvals when new commits are pushed".
- Bypass list: confirm it names Sanne alone.
  The app must not be there, and neither must the `Sanne-ai-bot` account; the bot's token cannot see this list, so only Sanne can.

Check: open a PR that changes `common/src/main/resources/images/minimal.yaml` with one wrong digit; `upstream-verify` must fail it and the merge button must stay disabled for an account without bypass.

## 4. Secrets to remove once the PR is merged

- `BOT_TOKEN` in `Sanne/incus-spawn`: Sanne's account token, read only by the retired `update-kernel.yml` and `update-base-image.yml`.
  Check with `grep -rn BOT_TOKEN .github/` that no workflow names it, then delete the repository secret.
- `INCUS_SPAWN_DISPATCH_TOKEN` in `Sanne/incus-spawn-images`: the watch polls that repository's releases, so the `repository_dispatch` step in its `build-fedora.yml` has nothing to call.
  A companion PR there removes the step; delete the secret once it is merged.

## 5. Later phases

- `incus-spawn-images` and `incus-spawn-templates` each get a manifest and a verify job of their own, running the engine at a pinned incus-spawn commit (design/upstream.md "Why not one engine copy per repo").
  Each needs the app installed (step 1) and `upstream-verify` required in its ruleset.
- `incus-spawn-templates` has no pull request rule yet: it needs one with one required approval, and `Sanne-ai-bot` needs write access there, before the watch opens PRs on it.

## What to watch after the first runs

- `[upstream] watch failing`: a signature that did not verify, a sum the publisher does not confirm, a link off its allowlist, or a missing secret; the issue links the run.
- A PR on `upstream/<item>` by `isx-upstream[bot]` with `upstream-verify` green: the coordinator merges it through `bin/upstream-merge.sh`; nobody edits it in place.
- A PR on `upstream/<item>` whose text says the item is `class: review`: the bot leaves it alone, so you merge it once it is green, or close it to decline that version.
- Issues titled `[upstream] <item>: ...` labelled `go:design` or `go:build`: the judgement path, dispatched like any other `go:` issue.
