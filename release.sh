#!/bin/bash
# Create and push a release tag.
#
# Usage: ./release.sh [version]
#   version  optional, e.g. "0.1.9" or "0.2.0-dev.1"
#
# Branch rules:
#   stable  → clean semver only (v0.3.0)       → publishes to stable channels
#   main    → -dev suffix required (v0.3.0-dev.1) → publishes to dev channels
set -e

branch=$(git symbolic-ref --short HEAD)

if [ -n "$1" ]; then
    version="${1#v}"
else
    git fetch origin --tags --quiet
    latest_tag=$(git tag --sort=-v:refname --list 'v*' | head -1)
    if [ -z "$latest_tag" ]; then
        echo "ERROR: No existing v* tags found — cannot derive next version." >&2
        echo "Pass the version explicitly: ./release.sh 0.1.9" >&2
        exit 1
    fi
    latest="${latest_tag#v}"
    if [[ "$latest" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)-dev\.([0-9]+)$ ]]; then
        # Latest tag is a dev tag — increment the dev number
        version="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}-dev.$((BASH_REMATCH[4] + 1))"
    elif [[ "$latest" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
        # Latest tag is a stable tag — increment patch
        major="${BASH_REMATCH[1]}"
        minor="${BASH_REMATCH[2]}"
        patch="${BASH_REMATCH[3]}"
        if [ "$branch" = "main" ]; then
            version="${major}.${minor}.$((patch + 1))-dev.1"
        else
            version="${major}.${minor}.$((patch + 1))"
        fi
    else
        echo "ERROR: Latest tag '$latest_tag' is not in supported format." >&2
        echo "Pass the version explicitly: ./release.sh 0.1.9" >&2
        exit 1
    fi
fi

tag="v$version"

# Validate: version format matches branch
is_dev=false
if [[ "$version" =~ -dev\. ]]; then
    is_dev=true
fi

if [ "$branch" = "stable" ]; then
    if [ "$is_dev" = true ]; then
        echo "ERROR: Dev versions (${version}) cannot be released from the stable branch." >&2
        echo "Use a clean semver version like: ./release.sh 0.3.0" >&2
        exit 1
    fi
elif [ "$branch" = "main" ]; then
    if [ "$is_dev" = false ]; then
        echo "ERROR: Stable versions (${version}) cannot be released from main." >&2
        echo "Use a -dev suffix like: ./release.sh ${version}-dev.1" >&2
        echo "For stable releases, switch to the stable branch." >&2
        exit 1
    fi
else
    echo "ERROR: Releases must be made from 'main' or 'stable' (currently on '$branch')." >&2
    exit 1
fi

# Validate: version string is well-formed
if [ "$is_dev" = true ]; then
    if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+-dev\.[0-9]+$ ]]; then
        echo "ERROR: Dev version must be MAJOR.MINOR.PATCH-dev.N (got: $version)" >&2
        exit 1
    fi
else
    if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "ERROR: Stable version must be MAJOR.MINOR.PATCH (got: $version)" >&2
        exit 1
    fi
fi

# Validate: clean working tree
if [ -n "$(git status --porcelain)" ]; then
    echo "ERROR: Working tree is not clean. Commit or stash changes first." >&2
    exit 1
fi

# Validate: up to date with remote
git fetch origin "$branch" --quiet
local_sha=$(git rev-parse HEAD)
remote_sha=$(git rev-parse "origin/$branch")
if [ "$local_sha" != "$remote_sha" ]; then
    echo "ERROR: Local $branch ($local_sha) differs from origin/$branch ($remote_sha)." >&2
    echo "Pull or push first." >&2
    exit 1
fi

# Validate: tag doesn't already exist
if git rev-parse "$tag" >/dev/null 2>&1; then
    echo "ERROR: Tag $tag already exists." >&2
    exit 1
fi

if [ "$is_dev" = true ]; then
    echo "Releasing $tag (dev channel)"
else
    echo "Releasing $tag (stable channel)"
fi
echo ""

git tag "$tag"
git push origin "$tag"

echo ""
echo "Tag $tag pushed. GitHub Actions will handle the rest:"
echo "  - Build uber-jar and native binaries (isx + isx-proxy)"
echo "  - Create GitHub Release$([ "$is_dev" = true ] && echo " (pre-release)")"
echo "  - Update Homebrew tap ($([ "$is_dev" = true ] && echo "incus-spawn-dev" || echo "incus-spawn"))"
echo "  - Publish RPM to COPR ($([ "$is_dev" = true ] && echo "incus-spawn-dev" || echo "incus-spawn"))"
echo "  - Publish .deb to APT repo ($([ "$is_dev" = true ] && echo "dev" || echo "stable") suite)"
