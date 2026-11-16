class IncusSpawn < Formula
  desc "CLI tool for managing isolated Incus-based development environments"
  homepage "https://github.com/Sanne/incus-spawn"
  version "0.5.2"
  license "Apache-2.0"

  url "https://github.com/Sanne/incus-spawn/releases/download/v#{version}/incus-spawn-macos-aarch64"
  sha256 "3b26e7d6ffb93a420e55d3e45e7ec31ab1165ff250a10f2f7c4a0eb7b3499e7b"

  depends_on macos: :sequoia
  depends_on arch: :arm64
  depends_on "vfkit"

  resource "isx-proxy" do
    url "https://github.com/Sanne/incus-spawn/releases/download/v0.5.2/isx-proxy-macos-aarch64"
    sha256 "b4c752851fe731acd32286c1b18f8a9a04cb912cebc5e08d6f818c68a60fd079"
  end

  resource "git-remote-isx" do
    url "https://github.com/Sanne/incus-spawn/releases/download/v0.5.2/git-remote-isx"
    sha256 "3a0d717f18c6d35fc27266e291e3b91f725cdc17b272db7b2995abe4b72cf4b6"
  end

  resource "completions" do
    url "https://github.com/Sanne/incus-spawn/releases/download/v0.5.2/completions.tar.gz"
    sha256 "7cb7cf335328b21f58934b0c19f4c4c791d5f316652ce163547934b1eee19ccc"
  end

  def install
    bin.install "incus-spawn-macos-aarch64" => "isx"

    resource("isx-proxy").stage do
      bin.install "isx-proxy-macos-aarch64" => "isx-proxy"
    end

    resource("git-remote-isx").stage do
      bin.install "git-remote-isx"
    end

    resource("completions").stage do
      bash_completion.install "isx.bash" => "isx"
      zsh_completion.install "_isx"
      fish_completion.install "isx.fish"
    end
  end

  def caveats
    <<~EOS
      incus-spawn has been installed as 'isx'.

      First-time setup (required):
        isx init

      This will:
        - Generate MITM CA certificate
        - Configure Claude API and GitHub credentials
        - Install VM and proxy as macOS services (auto-start at login)

      To build your first template:
        isx build tpl-java

      To launch the interactive TUI:
        isx

      Documentation: https://github.com/Sanne/incus-spawn
    EOS
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/isx --version")
  end
end
