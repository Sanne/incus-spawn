# Standalone test: nix-build test-build.nix
# Builds incus-spawn using the package.nix definition against a nixpkgs checkout.
# Usage: nix-build test-build.nix [--arg nixpkgs '<nixpkgs>']
{ nixpkgs ? <nixpkgs> }:

let
  pkgs = import nixpkgs { };
  # Self-reference needed for passthru.tests.version; works due to Nix lazy evaluation.
  incus-spawn = pkgs.callPackage ./package.nix { inherit incus-spawn; };
in
incus-spawn
