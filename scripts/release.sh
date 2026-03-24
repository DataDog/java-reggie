#!/usr/bin/env bash
# Release script for java-reggie.
# Computes the release version by bumping the current SNAPSHOT version.
#
# Usage: ./scripts/release.sh <major|minor|patch>
# Example: ./scripts/release.sh minor

set -euo pipefail

BUMP="${1:-}"
if [ -z "$BUMP" ]; then
    echo "Usage: $0 <major|minor|patch>"
    exit 1
fi

case "$BUMP" in
    major|minor|patch) ;;
    *) echo "ERROR: bump type must be 'major', 'minor', or 'patch' (got: $BUMP)"; exit 1 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Read current version from build.gradle and strip -SNAPSHOT
CURRENT="$(grep '^project.version' "$ROOT/build.gradle" | cut -d'"' -f2)"
BASE="${CURRENT%-SNAPSHOT}"

IFS='.' read -r MAJOR MINOR PATCH <<< "$BASE"

case "$BUMP" in
    major) VERSION="$((MAJOR + 1)).0.0" ;;
    minor) VERSION="${MAJOR}.$((MINOR + 1)).0" ;;
    patch) VERSION="${MAJOR}.${MINOR}.$((PATCH + 1))" ;;
esac

TAG="v${VERSION}"

echo "Current: $CURRENT  ->  Release: $VERSION"

# Ensure working tree is clean
if [ -n "$(git -C "$ROOT" status --porcelain)" ]; then
    echo "ERROR: Working tree is dirty. Commit or stash changes first."
    exit 1
fi

# Ensure on main branch
BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" != "main" ]; then
    echo "ERROR: Must be on main branch (currently on: $BRANCH)"
    exit 1
fi

# Check CHANGELOG has an entry for this version
if ! grep -q "## \[$VERSION\]" "$ROOT/CHANGELOG.md"; then
    echo "ERROR: CHANGELOG.md has no entry for version $VERSION"
    echo "Add a '## [$VERSION] - $(date +%Y-%m-%d)' section before releasing."
    exit 1
fi

# Update version in build.gradle (remove -SNAPSHOT, apply bump)
sed -i.bak "s|^project.version = \".*\"|project.version = \"$VERSION\"|" "$ROOT/build.gradle"
rm -f "$ROOT/build.gradle.bak"

# Run spotless + build to verify everything is clean
echo "Running build verification..."
"$ROOT/gradlew" -p "$ROOT" spotlessApply build -x :reggie-benchmark:build -x :reggie-integration-tests:test --quiet

# Commit and tag
git -C "$ROOT" add build.gradle
git -C "$ROOT" commit -m "Release $VERSION"
git -C "$ROOT" tag -a "$TAG" -m "Release $VERSION"

echo ""
echo "Done. Push with:"
echo "  git push origin main $TAG"
echo ""
echo "This triggers the release workflow which publishes to Maven Central."
echo ""
echo "After the workflow completes, bump to the next snapshot:"
echo "  ./scripts/post-release.sh <major|minor|patch>"
