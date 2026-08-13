#!/bin/bash

# Usage: ./scripts/release.sh v1.1.0

set -e

TAG="$1"

if [ -z "$TAG" ]; then
    echo "Usage: ./scripts/release.sh <version-tag>"
    echo "Example: ./scripts/release.sh v1.1.0"
    exit 1
fi

if ! echo "$TAG" | grep -qE '^v[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "ERROR: Tag must be in format vX.Y.Z (e.g. v1.1.0)"
    exit 1
fi

if git tag -l | grep -q "^${TAG}$"; then
    echo "ERROR: Tag $TAG already exists"
    exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
    echo "ERROR: Working tree is not clean — commit or stash first"
    git status --short
    exit 1
fi

# Strip 'v' prefix for pubspec version (v1.1.0 -> 1.1.0)
VERSION="${TAG#v}"

# Update version in pubspec.yaml
sed -i '' "s/^version: .*/version: $VERSION/" pubspec.yaml

# Update ref in README.md git dependency. Matches both an existing version tag and the
# `master` placeholder used before the first release.
sed -i '' -E "s/ref: (v[0-9]+\.[0-9]+\.[0-9]+|master)/ref: $TAG/" README.md

echo "Updated pubspec.yaml version to $VERSION and README.md ref to $TAG"

# Commit and tag
git add pubspec.yaml README.md
git commit -m "Release $TAG"
git tag "$TAG"

# Push commit and tag
git push origin HEAD
git push origin "$TAG"

echo ""
echo "Done! Released $TAG (commit and tag pushed)."
