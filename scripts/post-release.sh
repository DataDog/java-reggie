#!/usr/bin/env bash
# Post-release script: bumps version to the next SNAPSHOT.
# Computes the next version by bumping the just-released version.
#
# Usage: ./scripts/post-release.sh <major|minor|patch>
# Example: ./scripts/post-release.sh minor

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

# Read the current (just-released) version from build.gradle
CURRENT="$(grep '^project.version' "$ROOT/build.gradle" | cut -d'"' -f2)"
if [[ "$CURRENT" == *-SNAPSHOT ]]; then
    echo "ERROR: build.gradle still has a SNAPSHOT version ($CURRENT). Run release.sh first."
    exit 1
fi

IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT"

case "$BUMP" in
    major) NEXT="$((MAJOR + 1)).0.0" ;;
    minor) NEXT="${MAJOR}.$((MINOR + 1)).0" ;;
    patch) NEXT="${MAJOR}.${MINOR}.$((PATCH + 1))" ;;
esac

SNAPSHOT="${NEXT}-SNAPSHOT"

echo "Released: $CURRENT  ->  Next: $SNAPSHOT"

sed -i.bak "s|^project.version = \".*\"|project.version = \"$SNAPSHOT\"|" "$ROOT/build.gradle"
rm -f "$ROOT/build.gradle.bak"

# Add [Unreleased] section to CHANGELOG if missing
CHANGELOG="$ROOT/CHANGELOG.md"
if [ ! -f "$CHANGELOG" ]; then
    printf '# Changelog\n\n## [Unreleased]\n' > "$CHANGELOG"
elif ! grep -q "## \[Unreleased\]" "$CHANGELOG"; then
    sed -i.bak "2s|^|\n## [Unreleased]\n|" "$CHANGELOG"
    rm -f "$CHANGELOG.bak"
fi

git -C "$ROOT" add build.gradle CHANGELOG.md
git -C "$ROOT" commit -m "Prepare for $SNAPSHOT"

echo "Done. Push with: git push origin main"
