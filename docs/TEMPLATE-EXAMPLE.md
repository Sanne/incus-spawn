# A template with every field

This is the file `isx templates new --full` writes, with the name changed.
Every field is commented out except the name, each with a note on what it does.
Uncomment what you need and delete the rest.

For the day-to-day workflow, see [Templates](TEMPLATES.md).

```yaml
# yaml-language-server: $schema=https://isx.run/schema/template.json
# Template definition for incus-spawn: every field isx reads, one note each.
# Only 'name' is required; uncomment what you need.

# Required: unique name, conventionally prefixed with 'tpl-'
name: tpl-example

# Human-readable description, shown in the TUI and 'isx templates list'
# description: My custom template

# Parent template to inherit from (packages, tools, repos are additive)
# parent: tpl-minimal

# Instance type: container (default), vm, or kvm (a container that branches get
# /dev/kvm passed into). A template without it inherits its parent's.
# type: container

# Base image, for a root template only (one without a parent). The default is
# images:fedora/44; the other fields fetch a pre-baked tarball instead.
# image: images:fedora/44                  # Incus image, or the alias a download is stored under
# image_url: https://example.com/{tag}/fedora-{arch}.tar.xz  # container tarball; {arch}, {tag} filled in
# image_tag: v1                            # release tag that replaces {tag}
# image_sha256:                            # SHA256 of the image_url download, per architecture
#   x86_64: 0000000000000000000000000000000000000000000000000000000000000000
#   aarch64: 0000000000000000000000000000000000000000000000000000000000000000
# vm_image_url: https://example.com/{tag}/fedora-{arch}-vm.tar.xz  # VM image, used when type is vm
# vm_image_sha256:                         # SHA256 of the vm_image_url download, per architecture
#   x86_64: 0000000000000000000000000000000000000000000000000000000000000000
#   aarch64: 0000000000000000000000000000000000000000000000000000000000000000
# pinned: true                             # keep image_tag instead of tracking the newest release

# Extra package repositories to enable before installing packages
# package_repos:
#   - type: copr                           # repository kind; copr is the only one
#     name: owner/project                  # the repository to enable

# System packages to install via dnf
# packages:
#   - htop
#   - ripgrep

# System packages to remove via dnf, before packages are installed
# remove_packages:
#   - nano

# systemd units to mask in the image
# mask_services:
#   - packagekit.service

# Tool definitions to set up (run 'isx tools list' for all available tools)
# tools:
#   - podman
#   - idea-backend: {memory: "8g"}         # a tool with parameters

# Git repositories to clone as agentuser
# repos:
#   - url: https://github.com/owner/repo   # clone URL (HTTPS)
#     path: ~/repo                         # where to clone it; defaults to ~/<repo name>
#     branch: main                         # branch or tag to check out; defaults to the repo's default
#     depth: 1                             # keep only this many commits, on this branch only; omit for all
#     prime: mvn -B install -DskipTests    # command, or a list of them, run in the clone once cloned
#   - host-source: ~/work/private-repo      # instead of url; local branches/tags, including unpushed work
#     path: ~/private-repo                  # trusted templates only; no depth or upstream access

# Agent skills to bake into the image. Without bare names, a plain list of
# sources works too: skills: [owner/repo@skill-name]
# skills:
#   repo: owner/skills-repo                # catalog that bare skill names are resolved in
#   ref: v1.2.0                            # read bare names at this tag, each as its whole directory
#   commit: 0123456789abcdef0123456789abcdef01234567 # the commit the tag names: git ls-remote <url> 'refs/tags/<tag>^{}'
#   path: skills                           # directory holding <name>/SKILL.md; defaults to the repo's root
#   list:                                  # owner/repo@skill, owner/repo, ./local-path or a bare name
#     - owner/skills-repo@skill-name

# Host files and directories to share with instances
# host-resources:
#   - source: ~/.m2/repository             # host path, or a URL with mode copy
#     path: /home/agentuser/.m2/repository # path in the instance; defaults to the same path under /home/agentuser
#     mode: overlay                        # readonly (default), overlay or copy

# Environment variables, written to /etc/profile.d/isx-env.sh
# env:
#   - name: MAVEN_OPTS                     # variable name
#     value: "-Xmx2g"                      # its value
#     strategy: prepend                    # set (default), set-if-unset, prepend or append
#     separator: " "                       # between the value and the existing one, for prepend and append

# Credential account per namespace, overriding that namespace's default in config.yaml
# accounts:
#   claude: work
#   github: acme-bot

# GUI passthrough by default for container branches in a Wayland session (never
# for a project-local template); 'isx branch --no-gui' opts out
# gui: true

# Branches are coordinators that drive isx over MCP, controlling these templates of
# those mcp.yaml approves (never for a project-local template). The capability is on
# by default when control-over lists any; 'isx branch --no-mcp-client' opts out
# coordinator:
#   isx-control-capability: true
#   control-over: [tpl-dev]

# Directory a shell starts in; inherited from the parent, else the first repo
# workdir: ~/repo

# Command to run instead of the login shell
# shell-command: claude

# Tool action that Enter in the TUI and 'isx run' start: a tool, or tool:action-id,
# or - for a shell instead of the parent's
# default-action: claude

# Always-true fact an agent must know before acting. It costs tokens in
# every session and the agent follows it in every session, including tasks
# it wasn't written for, so add one only when leaving it out would cause a
# wrong action. Descriptions, lists of installed tools (already generated)
# and procedures don't belong here -- put procedures in a skill.
# agent_note: |
#   The build needs a boot JDK of 26/27/28; this box ships 26 and omits 25.

# Marks the template as on its way out: true, or what to use instead.
# It still builds, but builds of it and of its children warn.
# deprecated: use tpl-other instead
```
