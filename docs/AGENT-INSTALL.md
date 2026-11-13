# Install with your agent

Your coding agent can install isx and set it up for you, from one prompt.
It does the work, and you keep three steps: running commands as root, adding credentials, and choosing which templates agents may use.
The [Quick Start](../README.md#quick-start) is the same install by hand.

## The prompt

Paste this into the coding agent you use on your computer.
The prompt is the same for every agent: the agent puts itself in your first template ([Agents](AGENTS.md)).
It has been tested on a clean host with these agents: Claude Code, Codex CLI, Pi.

<!-- copy -->
```text
Install isx (https://isx.run) on this machine and set it up for me. I want to end up with a first isx instance branched from a template that runs the same coding agent as you, and with you able to drive isx over MCP afterwards.

isx gives coding agents isolated Linux workstations. When an agent installs it, you do the work and I hold the keys:

1. Never run sudo, or anything else as root. Run `isx init --no-sudo`: it writes every step that needs root into one script and names it. Hand me that script to read and run, and stop. Do not write a root script of your own.
2. Never ask me for a secret, and never put one on a command line, in the environment or on stdin. When a credential is missing, tell me which `isx account add` command to run at my own terminal, and stop.
3. Never choose which templates an agent may use, and never write ~/.config/incus-spawn/mcp.yaml. Ask me to run `isx mcp setup`, and stop.

Read https://isx.run/llms.txt first. Register isx's MCP server with yourself the way llms.txt says, with `isx mcp register` when it knows your agent. If you cannot tell which coding agent you are, or isx has no tool for it, ask me which agent to put in the template. When you need me, say exactly what to do and end your turn. I will tell you when it is done.
```

## What you do

The agent stops and hands over to you at three points:

| When | What the agent asks | What you do |
|------|---------------------|-------------|
| A step needs root | `sudo sh ~/.local/state/incus-spawn/init-privileged.sh` | Read the script, run it, and tell the agent |
| A credential is missing | `isx account add` and the service, such as `openai` for Codex | Run it at your own terminal and type the credential |
| Agents need templates | `isx mcp setup` | Choose the templates agents may create instances from |

It also asks you which agent to put in your first template when it cannot tell which agent it is, or isx has no tool for it.

`isx mcp setup` offers every template with a coding agent in it.
An agent can create instances from a template you approve and run commands in them.
It can hand a task only to Claude Code, Codex or Pi, so a template with another agent is marked create and exec only.

On macOS no step needs root, so the first handover does not happen.
A fresh Linux host usually needs the root script once.

On Linux the root script adds you to the `incus-admin` group, which an agent that is already running gets only when you start it again from a new login.
Until then it runs isx commands through `sg incus-admin`, and `isx mcp register` registers isx's MCP server through `sg incus-admin` too.

On a host without `sg`, the agent can use isx over MCP only once you start it again from a new login.

## Why these steps stay with you

An agent with a shell on your computer can already do whatever you can, and isx cannot change that.
What isx can change is what the install asks of the agent.
It never needs your password, a credential or your approval to finish its part, so you never have a reason to hand one over.

Each handover keeps one thing in your hands:

- **Root**: the script says why each command is there, so you read what runs as root before it runs.
- **Credentials**: you type a secret into `isx account add` at your own terminal, so it never passes through the agent's conversation.
  The proxy keeps it on your host, and isx never puts it into an instance.
- **Approvals**: an agent driving isx over MCP may branch only the templates you approved in `mcp.yaml`.
  isx never writes that file for an agent.

You give up a fully unattended install: the agent waits for you up to three times.
In return, you read every root command before it runs, and you alone decide which accounts and templates the agents get.

## What the agent does

The agent reads [llms.txt](https://isx.run/llms.txt), which points it at these docs, then:

1. Installs isx without root: the install script on Linux, or Homebrew on macOS.
2. Reads `isx init --plan`, which says what init would change and who each step needs, and changes nothing.
3. Runs `isx init --no-sudo`, hands you the root script, and runs it again once you ran the script.
4. Writes a template that adds its own isx tool to `tpl-minimal`, with `isx templates new --from`, and builds it.
   An agent that cannot tell which agent it is, or that isx has no tool for, asks you which agent to use instead.
5. Reads `isx init --plan` again, which now names the credential that template needs, and hands you `isx account add` when it is missing.
6. Creates the first instance, `my-first`, from that template.
7. Registers `isx mcp` with itself through `isx mcp register`, so it can create instances from the templates you approve.

When the agent first runs init, no template needs a credential yet, so init does not report one as missing.

`isx init --plan` and the summary of `isx init --format=json` say for each step who must finish it: `none`, `sudo`, `secret` or `approval` ([`isx init`](CLI.md#isx-init)).
An agent may do the steps marked `none`, and hands you the others.

isx's root script records what it ran, so the second run and `isx init --plan` know those steps are done.
A script the agent writes itself from the plan does not, so ask it to run `isx init --no-sudo` and hand you that script instead.

## Afterwards

Open the first instance with `isx shell my-first`, or `isx` for the interactive view of your templates and instances.
The agent on your computer can now create instances from the templates you approved, run commands in them, and hand them whole tasks ([MCP](MCP.md)).

On Linux, if isx refuses the agent's MCP calls right after the install, with "permission denied" on the Incus socket, restart your agent from a new login.
Logging out and back in also works.
The agent then has the `incus-admin` group the root script added.

The recommended place for an agent that coordinates other instances is an instance of its own, where it holds no credentials of yours.
[A coordinator in an instance](MCP.md#from-a-fresh-install) takes four more commands, starting from the `tpl-agent` template of the Quick Start.
The agent can write and build the coordinator's template, and you run `isx mcp setup` and open the coordinator.
