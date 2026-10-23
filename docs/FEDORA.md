# What differs from a stock Fedora install

An isx instance is a Fedora system with a few deliberate deviations.
This is where they are listed; a change to what a box does that a Fedora user would not expect belongs here.

**Trust.**
Traffic to intercepted domains passes through the host proxy, which signs with the isx CA.
The CA is installed in the system anchors (`update-ca-trust`), so everything that reads the system store trusts it.
A runtime that ships its own root bundle does not, so isx binds it to the system store:

- *Environment bindings.*
  Every login shell (`/etc/profile.d/isx-env.sh`) and systemd user session (`/etc/environment.d/00-isx-trust.conf`) gets `SSL_CERT_FILE`, `SSL_CERT_DIR`, `REQUESTS_CA_BUNDLE`, `CURL_CA_BUNDLE`, `PIP_CERT`, `NODE_EXTRA_CA_CERTS`, `CARGO_HTTP_CAINFO`, `AWS_CA_BUNDLE`, `NIX_SSL_CERT_FILE` (the system bundle, `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem`; `SSL_CERT_DIR` its hashed directory beside it), `DENO_TLS_CA_STORE=system` and `UV_NATIVE_TLS=1`.
  A value already exported, or set by the template or a tool, wins in a login shell.
  In a systemd user session any other `environment.d` file overrides the drop-in, because it is named `00-isx-trust.conf` and sorts first (for example `~/.config/environment.d/10-mine.conf`).
  Java is never bound through a variable: `JAVA_TOOL_OPTIONS` prints a "Picked up" line that breaks tools parsing `java -version`.
- *File bindings.*
  A JDK's `lib/security/cacerts` and an installed Python certifi's `site-packages/certifi/cacert.pem` are replaced by a symlink to the system store (`/etc/pki/ca-trust/extracted/java/cacerts`, `/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem`).
  Such a runtime then trusts the Fedora/Mozilla root set plus the isx CA instead of its vendor's bundle.
  The build binds what the image already carries; after that, two systemd path units watch where runtimes land and bind each new one within seconds, with no resident process: `isx-trust-system.path` (as root) watches `/usr/lib/jvm`, `/opt` and `/usr/local`, and `isx-trust-home.path` (as `agentuser`) watches `~/opt`, `~/.jdks`, `~/.sdkman/candidates/java`, `~/.gradle/jdks`, `~/.jbang/cache/jdks`, `~/.local/share/mise/installs/java` and `~/.asdf/installs/java`.
  A watch is not recursive, so a runtime is caught when it lands directly in one of those directories, and each costs one inotify instance.
  A bare `isx-trust` binds all of those directories.
  For a runtime anywhere else (a JDK unpacked into `~/tools`, a project's `.venv`), run `isx-trust <dir>`; the symptoms are `certificate_unknown`, `PKIX path building failed` or `CERTIFICATE_VERIFY_FAILED`, and the agent context pairs them with that command.

No verification is turned off anywhere: every runtime still verifies chains and hostnames, against the system store.

*Host CAs (opt-in).*
With

```yaml
host-ca:
  propagate: true
```

in `~/.config/incus-spawn/config.yaml`, the certificates in the host's `/etc/pki/ca-trust/source/anchors/` (Fedora/RHEL; other hosts have none there to propagate) are installed beside the isx CA in every instance, of every template, as `isx-<sha256>.crt`.
This is for a host behind TLS inspection, or an isx inside another isx: the outer proxy's CA must be trusted for the domains the inner one does not intercept.
Only certificates cross, re-encoded from each file; a file holding anything else, such as a private key, is refused with a warning.
Builds install the set; a branch, `isx shell` and the TUI bring each instance up to date on use (an `--airgap` instance keeps what its template was built with), which also removes a CA taken off the host or every host CA once propagation is turned off.
In an instance's anchors directory isx owns only `incus-spawn-mitm.crt` and names of exactly the form `isx-` + 64 lowercase hex digits + `.crt`; any other file there, such as an `isx-company.crt` a template adds, is left alone.
It is off by default, and then instances trust only the isx CA beside Fedora's roots.

**`/tmp` is on disk.**
Fedora mounts a tmpfs on `/tmp`, which every restart empties.
An instance restarts more often than a desktop install (F7 in the TUI, `incus restart`, autostart after the host reboots), and agents clone into `/tmp`, so `tpl-minimal` masks `tmp.mount` and every template inherits a `/tmp` on the instance's disk.
Files there survive restarts, count against disk instead of memory, and are removed by `systemd-tmpfiles-clean` once untouched for 10 days, as on stock Fedora.
Before a template is saved (`isx build`, `isx update-all`, `isx project create` and `update`), `/tmp` and `/var/tmp` are emptied, hidden files included.
A base image pinned with `isx update-base <tag>` before this change keeps the tmpfs until `isx update-base --latest` and pinning again.
A template that wants the tmpfs back can install a tool whose `run` is `systemctl unmask tmp.mount`.
