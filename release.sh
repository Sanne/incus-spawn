#!/bin/bash
# Create and push a release tag.
# Usage: ./release.sh [version]
#   version  optional, e.g. "0.1.9" or "v0.1.9" (derived from latest git tag if omitted)
set -e

if [ -n "$1" ]; then
    version="${1#v}"
else
    latest_tag=$(git tag --sort=-v:refname --list 'v*' | head -1)
    if [ -z "$latest_tag" ]; then
        echo "ERROR: No existing v* tags found — cannot derive next version." >&2
        echo "Pass the version explicitly: ./release.sh 0.1.9" >&2
        exit 1
    fi
    latest="${latest_tag#v}"
    IFS='.' read -r major minor patch <<< "$latest"
    version="${major}.${minor}.$((patch + 1))"
fi

tag="v$version"

# Validate: clean working tree
if [ -n "$(git status --porcelain)" ]; then
    echo "ERROR: Working tree is not clean. Commit or stash changes first." >&2
    exit 1
fi

# Validate: on main branch
branch=$(git symbolic-ref --short HEAD)
if [ "$branch" != "main" ]; then
    echo "ERROR: Not on main branch (currently on '$branch')." >&2
    exit 1
fi

# Validate: up to date with remote
git fetch origin main --quiet
local_sha=$(git rev-parse HEAD)
remote_sha=$(git rev-parse origin/main)
if [ "$local_sha" != "$remote_sha" ]; then
    echo "ERROR: Local main ($local_sha) differs from origin/main ($remote_sha)." >&2
    echo "Pull or push first." >&2
    exit 1
fi

# Validate: tag doesn't already exist
if git rev-parse "$tag" >/dev/null 2>&1; then
    echo "ERROR: Tag $tag already exists." >&2
    exit 1
fi

echo "Releasing $tag"
echo ""

git tag "$tag"
git push origin "$tag"

echo ""
echo "Tag $tag pushed. GitHub Actions will handle the rest:"
echo "  - Build uber-jar and native binary"
echo "  - Create GitHub Release"
echo "  - Publish RPM to COPR"
