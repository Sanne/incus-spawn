class IncusSpawnDev < Formula
  desc "CLI tool for managing isolated Incus-based development environments (dev channel)"
  homepage "https://github.com/Sanne/incus-spawn"
  version "0.5.3-dev.1"
  license "Apache-2.0"

  url "https://github.com/Sanne/incus-spawn/releases/download/v#{version}/incus-spawn-macos-aarch64"
  sha256 "645998a292c7eb9c06c28af250168e6e7523851054348bbe36b53a907dadd8aa"

  depends_on macos: :sequoia
  depends_on arch: :arm64
  depends_on "vfkit"

  conflicts_with "incus-spawn", because: "both install the `isx` binary"

  resource "isx-proxy" do
    url "https://github.com/Sanne/incus-spawn/releases/download/v0.5.3-dev.1/isx-proxy-macos-aarch64"
    sha256 "33812dd362267672d78547b6fd0dbf6e0d0dccdad9e293253a81f5d71f81d58e"
  end

  resource "git-remote-isx" do
    url "https://github.com/Sanne/incus-spawn/releases/download/v0.5.3-dev.1/git-remote-isx"
    sha256 "3a0d717f18c6d35fc27266e291e3b91f725cdc17b272db7b2995abe4b72cf4b6"
  end

  resource "completions" do
    url "https://github.com/Sanne/incus-spawn/releases/download/v0.5.3-dev.1/completions.tar.gz"
    sha256 "8aab36c293745d61ebcfa2bef6fc43f936a0ecd26765250dcfe25c2f35b4d14d"
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
      incus-spawn (dev) has been installed as 'isx'.

      This is the development channel — expect frequent updates.
      For the stable release, use: brew install Sanne/tap/incus-spawn

      First-time setup (required):
        isx init

      Documentation: https://github.com/Sanne/incus-spawn
    EOS
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/isx --version")
  end
end
