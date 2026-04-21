Name:           incus-spawn
Version:        @VERSION@
Release:        1%{?dist}
Summary:        CLI tool for managing isolated Incus development environments
License:        Apache-2.0
URL:            https://github.com/Sanne/incus-spawn
Source0:        incus-spawn-linux-amd64

ExclusiveArch:  x86_64

%global debug_package %{nil}

Requires:       openssl
Recommends:     incus
Recommends:     btrfs-progs

%description
incus-spawn (isx) creates isolated Linux development environments using
Incus system containers with copy-on-write branching, a MITM TLS proxy
for credential isolation, and an interactive TUI.

%prep

%build

%install
mkdir -p %{buildroot}%{_bindir}
install -m 755 %{SOURCE0} %{buildroot}%{_bindir}/isx

mkdir -p %{buildroot}%{_datadir}/bash-completion/completions
mkdir -p %{buildroot}%{_datadir}/zsh/site-functions
mkdir -p %{buildroot}%{_datadir}/fish/vendor_completions.d
%{buildroot}%{_bindir}/isx completion bash > %{buildroot}%{_datadir}/bash-completion/completions/isx
%{buildroot}%{_bindir}/isx completion zsh  > %{buildroot}%{_datadir}/zsh/site-functions/_isx
%{buildroot}%{_bindir}/isx completion fish > %{buildroot}%{_datadir}/fish/vendor_completions.d/isx.fish

%files
%{_bindir}/isx
%{_datadir}/bash-completion/completions/isx
%{_datadir}/zsh/site-functions/_isx
%{_datadir}/fish/vendor_completions.d/isx.fish

%changelog
@CHANGELOG@
