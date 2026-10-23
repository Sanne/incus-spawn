# What differs from a stock Fedora install

An isx instance is a Fedora system with a few deliberate deviations.
This is where they are listed; a change to what an instance does that a Fedora user would not expect belongs here.
Some come from isx at build time, some from the `tpl-minimal` template, and some from the base image every template starts from.

| What differs | Why | Where |
| --- | --- | --- |
| The isx CA is a system trust anchor | The host proxy signs traffic to intercepted domains with it | `update-ca-trust` |
| Trust variables point runtimes at the system store | A runtime that ships its own root bundle does not read the system anchors | `/etc/profile.d/isx-env.sh`, `/etc/environment.d/00-isx-trust.conf` |
| JDK and certifi bundles are symlinks to the system store | A JDK and certifi ship their own root bundle | `isx-trust-system.path`, `isx-trust-home.path`, `isx-trust` |
| Host CAs are installed beside the isx CA | A host behind TLS inspection, or an isx inside another isx | `host-ca: { propagate: true }` in `config.yaml`, off by default |
| `/tmp` is on disk | Instances restart often, and agents clone into `/tmp` | `tmp.mount` masked in `tpl-minimal` |
| `agentuser` has passwordless sudo | The agent must be free to install what it needs | `/etc/sudoers.d/agentuser` |
| systemd-networkd with a static address | A branch boots straight onto its address, which the proxy identifies it by | `/etc/systemd/network/10-eth0.network`, `isx-network-watchdog.timer` |
| isx writes `/etc/resolv.conf` and drops `resolve` from `nsswitch.conf` | Names resolve at the bridge gateway, `.local` ones included | `/etc/resolv.conf`, `/etc/nsswitch.conf` |
| About twenty units are masked | Time sync, TPM, boot and update units have no place in an instance | base image |
| SELinux is off | The filesystem is unlabelled; an enforcing VM loses its agent | `/etc/selinux/config` |
| No documentation files, English locale only | A smaller image | base image, build-time `dnf` |
| Every instance has its own machine ID | Branches are copies of one template | `/etc/machine-id` |
| A VM's `dnf` refuses kernel packages | The VM boots the image's own kernel | `/etc/dnf/libdnf5.conf.d/isx-kernel.conf` |

## Trust

Traffic to intercepted domains passes through the host proxy, which signs with the isx CA.
The CA is installed in the system anchors as `/etc/pki/ca-trust/source/anchors/isx-<sha256>.crt` (`update-ca-trust`), so everything that reads the system store trusts it.

A runtime that ships its own root bundle does not, so isx binds it to the system store, through the environment or by replacing its bundle file.
No verification is turned off anywhere: every runtime still verifies chains and hostnames, against the system store.

### Environment bindings

Every login shell (`/etc/profile.d/isx-env.sh`) and systemd user session (`/etc/environment.d/00-isx-trust.conf`) gets these variables.

| Variable | Value |
| --- | --- |
| `SSL_CERT_FILE`, `REQUESTS_CA_BUNDLE`, `CURL_CA_BUNDLE`, `PIP_CERT`, `NODE_EXTRA_CA_CERTS`, `CARGO_HTTP_CAINFO`, `AWS_CA_BUNDLE`, `NIX_SSL_CERT_FILE` | The system bundle, `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem` |
| `SSL_CERT_DIR` | The bundle's hashed directory beside it |
| `DENO_TLS_CA_STORE` | `system` |
| `UV_NATIVE_TLS` | `1` |

A value already exported, or set by the template or a tool, wins in a login shell.
In a systemd user session any other `environment.d` file overrides the drop-in, because it is named `00-isx-trust.conf` and sorts first.
`~/.config/environment.d/10-mine.conf` is such a file.
Java is never bound through a variable: `JAVA_TOOL_OPTIONS` prints a "Picked up" line that breaks tools parsing `java -version`.

### File bindings

isx replaces a JDK's `lib/security/cacerts` and an installed Python certifi's `site-packages/certifi/cacert.pem` with a symlink to the system store (`/etc/pki/ca-trust/extracted/java/cacerts`, `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem`).
The certifi copy a package vendors under `site-packages`, pip's included, is bound the same way; a source checkout is never touched.
Such a runtime then trusts the Fedora/Mozilla root set plus the isx CA instead of its vendor's bundle.

The build binds what the image already carries.
After that, two systemd path units watch where runtimes land and bind each new one within seconds, with no resident process.

| Unit | Runs as | Watches |
| --- | --- | --- |
| `isx-trust-system.path` | root | `/usr/lib/jvm`, `/opt`, `/usr/local` |
| `isx-trust-home.path` | `agentuser` | `~/opt`, `~/.jdks`, `~/.sdkman/candidates/java`, `~/.gradle/jdks`, `~/.jbang/cache/jdks`, `~/.local/share/mise/installs/java`, `~/.asdf/installs/java` |

A watch is not recursive, so a runtime is caught when it lands directly in one of those directories, and each costs one inotify instance.

A bare `isx-trust` binds all of those directories.
For a runtime anywhere else (a JDK unpacked into `~/tools`, a project's `.venv`), run `isx-trust <dir>`.
The symptoms are `certificate_unknown`, `PKIX path building failed` or `CERTIFICATE_VERIFY_FAILED`, and the agent context pairs them with that command.

### Host CAs (opt-in)

Host CA propagation is for a host behind TLS inspection, or an isx inside another isx: the outer proxy's CA must be trusted for the domains the inner one does not intercept.
It is off by default, and then instances trust only the isx CA beside Fedora's roots.
To turn it on, put this in `~/.config/incus-spawn/config.yaml`:

```yaml
host-ca:
  propagate: true
```

With that, the certificates in the `.crt` and `.pem` files of the host's `/etc/pki/ca-trust/source/anchors/` are installed beside the isx CA in every instance, of every template, as `isx-<sha256>.crt`.
That directory is Fedora's and RHEL's; other hosts have none there to propagate.

Only certificates cross, re-encoded from each file; a file holding anything else, such as a private key, is refused with a warning.

Builds install the set.
A branch, `isx shell`, `isx run` and the TUI bring each instance up to date on use, which also removes a CA taken off the host or every host CA once propagation is turned off.
An `--airgap` instance keeps what its template was built with.

In an instance's anchors directory isx owns only `incus-spawn-mitm.crt` and names of exactly the form `isx-` + 64 lowercase hex digits + `.crt`.
Any other file there, such as an `isx-company.crt` a template adds, is left alone.

## `/tmp` is on disk

Fedora mounts a tmpfs on `/tmp`, which every restart empties.
An instance restarts more often than a desktop install (F7 in the TUI, `incus restart`, autostart after the host reboots), and agents clone into `/tmp`.
So `tpl-minimal` masks `tmp.mount` and every template inherits a `/tmp` on the instance's disk.

Files there survive restarts, count against disk instead of memory, and are removed by `systemd-tmpfiles-clean` once untouched for 10 days, as on stock Fedora.

Before a template is saved (`isx build`, `isx update-all`, `isx project create` and `update`), isx empties `/tmp` and `/var/tmp`, hidden files included.
A base image pinned with `isx update-base <tag>` from before this deviation keeps the tmpfs until `isx update-base --latest` and pinning again.
A template that wants the tmpfs back can install a tool whose `run` is `systemctl unmask tmp.mount`.

## The agent user

Every instance has one user, `agentuser` (UID 1000), and no password for it or for root: isx enters through Incus, never a login prompt.
It has passwordless sudo (`/etc/sudoers.d/agentuser`), reads the journal (group `systemd-journal`) and owns `~/inbox`.
Its `.bashrc` sets the terminal title to `isx:<hostname>` and sources bash completion.

It holds the subordinate ID range `100000:65536` in `/etc/subuid` and `/etc/subgid`, for rootless containers inside the instance.

## Networking

systemd-networkd manages the network; NetworkManager is not installed.
Each branch gets a static address, gateway and DNS in `/etc/systemd/network/10-eth0.network`, written into a container before its first boot, so it boots onto its address with no DHCP round trip.
The proxy identifies an instance by that address, which is why the file is isx's to write.

A VM's file is pushed once the VM is up, applies at its next boot or `networkctl reload`, and matches its NIC by MAC address rather than by name.

A watchdog (`isx-network-watchdog.timer`, 10 seconds after boot and every 30 seconds) restarts systemd-networkd when the address is missing or the gateway does not answer, as after the host sleeps.

Nothing in the instance manages `/etc/resolv.conf`: isx writes it, naming the bridge gateway as the one nameserver, and checks it on use.
The `resolve` entry is removed from `/etc/nsswitch.conf`, so `.local` names go to the gateway's DNS instead of multicast DNS.

## Masked units

The base image masks units an instance has no use for, which would otherwise fail at every boot and leave it degraded.

| Masked | What it means |
| --- | --- |
| `systemd-timesyncd`, `systemd-time-wait-sync` | No time sync in the instance; a container has the host's clock |
| `systemd-homed`, `systemd-homed-firstboot`, `systemd-firstboot` | No home-directory service, no first-boot prompts |
| `systemd-pcrlock-*`, `systemd-tpm2-clear` | No TPM measurement |
| `systemd-boot-update`, `systemd-boot-check-no-failures`, `systemd-boot-clear-sysfail`, `systemd-sysupdate.timer`, `systemd-sysupdate-reboot.timer` | No boot loader or system update management |
| `dnf-makecache.timer` | `dnf` refreshes its metadata when it runs, not in the background |
| `fstrim.timer`, `unbound-anchor.timer`, `selinux-autorelabel-mark` | Nothing to trim, anchor or relabel |
| `console-getty` (containers) | No login prompt on the console |
| `systemd-machine-id-commit` (VMs) | The machine ID is taken afresh at every boot, see below |

In a container, `systemd-logind` runs only once a template installs a system bus (`dbus-broker`), as the `podman` tool does; until then it is skipped, not failed.
The VM image ships `dbus-broker` and `systemd-pam`, so logind and `systemctl --user` work there from the start.
A template adds its own masks with `mask_services:`, as `tpl-minimal` masks `tmp.mount`.

## SELinux is off

The base image ships no SELinux policy, so nothing on the filesystem is labelled, and `/etc/selinux/config` says `SELINUX=disabled`.
A package that pulls a policy in later, as `perl` does, cannot turn it on: the file is only written when missing or empty.
In a container the setting is inert anyway.

In a VM it matters: every VM build rewrites the file before installing anything and fails if it ends up `enforcing`, since an enforcing guest denies its agent and is unreachable.

## No documentation files

The base image installs its packages without weak dependencies and without documentation, and strips `/usr/share/doc`, `man`, `info`, `licenses` and `groff`.
Packages a template installs during a build come without documentation too.
A `dnf install` you run inside an instance installs them as on stock Fedora.
Only the English locale is installed (`glibc-langpack-en`).

## Machine ID

Every branch is a copy of its template, so without care every branch would share one `/etc/machine-id`: one host to the journal, one DHCP client identity.
The base image ships the file empty, isx empties it again before saving a template, and gives each container branch a fresh ID before its first boot.

A VM takes its ID from its SMBIOS UUID at every boot, which Incus makes anew for every copy.

## Containers

Device nodes such as `/dev/net/tun` and `/dev/fuse` are provided by Incus and cannot have their permissions changed inside the container.
So `/etc/tmpfiles.d/static-nodes-permissions.conf` is an empty override, and isx overrides every `tmpfiles.d` file that names those devices the same way, which keeps package scriptlets from failing on them.

## VMs

A VM boots the image's own kernel, built with no modules and no initrd, started by the firmware straight from the EFI partition.
`/etc/dnf/libdnf5.conf.d/isx-kernel.conf` excludes `kernel`, `kernel-core`, `kernel-modules` and every other package that carries a kernel or its modules; `kernel-headers` and `kernel-devel` stay installable.
`dnf install kernel-core` answers that it matches only excluded packages.

`dnf --setopt=excludepkgs= install ...` lifts it for one command and deleting the file lifts it for good, but neither makes the VM boot that kernel.
Kernel updates arrive with a new base image.

The root filesystem is found by its GPT partition name (`PARTLABEL=isx-root`) and there is no `/boot/efi` mount: Linux never mounts the EFI partition.
The image's `/etc/systemd/network/enp5s0.network` asks for DHCP on the first NIC; isx's static file, matched by MAC, takes precedence.
