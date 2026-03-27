#!/usr/bin/env bash
# Release script for java-reggie.
# Computes the release version by bumping the current SNAPSHOT, auto-generates
# release notes from merged PRs, and creates a tagged release commit.
#
# Usage: ./scripts/release.sh <major|minor|patch> [--no-dry-run]
# Example: ./scripts/release.sh minor             # dry-run: shows what would happen
#          ./scripts/release.sh minor --no-dry-run # actually performs the release
#
# Branch rules:
#   major / minor  — must be run from 'main'
#   patch          — must be run from 'release/X.Y._'
#
# PRs labelled 'no release notes' are excluded from the generated CHANGELOG entry.

set -euo pipefail

BUMP=""
DRY_RUN=1

for arg in "$@"; do
    case "$arg" in
        --no-dry-run) DRY_RUN=0 ;;
        --help|-h)
            echo "Usage: $0 <major|minor|patch> [--no-dry-run]"
            exit 0
            ;;
        major|minor|patch) BUMP="$arg" ;;
        *) echo "ERROR: unknown argument: $arg"; exit 1 ;;
    esac
done

if [ -z "$BUMP" ]; then
    echo "Usage: $0 <major|minor|patch> [--no-dry-run]"
    exit 1
fi

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

# Branch rules
BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
case "$BUMP" in
    major|minor)
        if [ "$BRANCH" != "main" ]; then
            echo "ERROR: major/minor releases must be run from 'main' (currently on: $BRANCH)"
            exit 1
        fi
        ;;
    patch)
        if [[ ! "$BRANCH" =~ ^release/[0-9]+\.[0-9]+\._ ]]; then
            echo "ERROR: patch releases must be run from a 'release/X.Y._' branch (currently on: $BRANCH)"
            echo "Use ./scripts/backport-pr.sh to cherry-pick fixes, then re-run from that branch."
            exit 1
        fi
        ;;
esac

# Dry-run summary
if [ $DRY_RUN -eq 1 ]; then
    echo "Dry-run — no changes will be made."
    echo ""
    echo "  Current version : $CURRENT"
    echo "  Release version : $VERSION"
    echo "  Tag             : $TAG"
    echo "  Branch          : $BRANCH"
    echo ""
    echo "Re-run with --no-dry-run to execute."
    exit 0
fi

echo "Current: $CURRENT  ->  Release: $VERSION"

# Ensure working tree is clean
if [ -n "$(git -C "$ROOT" status --porcelain)" ]; then
    echo "ERROR: Working tree is dirty. Commit or stash changes first."
    exit 1
fi

# ── Collect merged PRs for release notes ─────────────────────────────────────
# major/minor: PRs merged to main since the last tag
# patch:       PRs merged to the release branch since the last tag (backport PRs)
LAST_TAG=$(git -C "$ROOT" describe --tags --abbrev=0 2>/dev/null || echo "")
if [ -n "$LAST_TAG" ]; then
    LAST_DATE=$(git -C "$ROOT" log -1 --format=%aI "$LAST_TAG")
    SEARCH_FILTER="merged:>$LAST_DATE"
else
    SEARCH_FILTER="is:merged"
fi

TMPJSON=$(mktemp)
trap 'rm -f "$TMPJSON"' EXIT

if command -v gh >/dev/null 2>&1; then
    echo "Collecting merged PRs..."
    gh pr list \
        --state merged --base "$BRANCH" --limit 500 \
        --search "$SEARCH_FILTER" \
        --json number,title,labels > "$TMPJSON" 2>/dev/null || echo "[]" > "$TMPJSON"
else
    echo "WARNING: 'gh' CLI not found; release notes will have no PR entries."
    echo "[]" > "$TMPJSON"
fi

# ── Write CHANGELOG.md entry ──────────────────────────────────────────────────
CHANGELOG="$ROOT/CHANGELOG.md"

python3 - "$CHANGELOG" "$VERSION" "$(date +%Y-%m-%d)" "$TMPJSON" <<'PYEOF'
import json, sys, os, re

changelog_path, version, date, json_path = sys.argv[1:5]

with open(json_path) as f:
    prs = json.load(f)

lines = []
for pr in sorted(prs, key=lambda p: p['number']):
    labels = [l['name'] for l in pr.get('labels', [])]
    if 'no release notes' in labels:
        continue
    title = pr['title']
    # Backport PRs have title "🍒 <original_num> - <original_title>"; use original PR reference.
    m = re.match(r'[^\x00-\x7F]\s+(\d+)\s+-\s+(.*)', title)
    if m:
        lines.append(f"- #{m.group(1)} {m.group(2).strip()}")
    else:
        lines.append(f"- #{pr['number']} {title}")

entries = '\n'.join(lines) if lines else '- No user-facing changes.'
header = f'## [{version}] - {date}'
new_section = f'{header}\n\n{entries}\n'

if not os.path.exists(changelog_path):
    with open(changelog_path, 'w') as f:
        f.write(f'# Changelog\n\n{new_section}\n')
    sys.exit(0)

with open(changelog_path) as f:
    content = f.read()

if '## [Unreleased]' in content:
    # Replace the [Unreleased] block (up to the next versioned section or EOF)
    content = re.sub(
        r'## \[Unreleased\].*?(?=\n## \[|\Z)',
        new_section, content, count=1, flags=re.DOTALL
    )
else:
    pos = content.find('\n## [')
    if pos >= 0:
        content = content[:pos+1] + new_section + '\n' + content[pos+1:]
    else:
        content = content.rstrip('\n') + '\n\n' + new_section + '\n'

with open(changelog_path, 'w') as f:
    f.write(content)
PYEOF

echo "Updated CHANGELOG.md"

# Update version in build.gradle (remove -SNAPSHOT suffix)
sed -i.bak "s|^project.version = \".*\"|project.version = \"$VERSION\"|" "$ROOT/build.gradle"
rm -f "$ROOT/build.gradle.bak"

# Run spotless + build to verify everything is clean
echo "Running build verification..."
"$ROOT/gradlew" -p "$ROOT" spotlessApply build -x :reggie-benchmark:build -x :reggie-integration-tests:test --quiet

# Commit and tag
git -C "$ROOT" add build.gradle CHANGELOG.md
git -C "$ROOT" commit -m "Release $VERSION"
git -C "$ROOT" tag -a "$TAG" -m "Release $VERSION"

# For major/minor: create the release maintenance branch from this commit
if [ "$BUMP" != "patch" ]; then
    IFS='.' read -r REL_MAJOR REL_MINOR _ <<< "$VERSION"
    MAINT_BRANCH="release/${REL_MAJOR}.${REL_MINOR}._"
    git -C "$ROOT" branch "$MAINT_BRANCH"
    git -C "$ROOT" push origin "$MAINT_BRANCH"
    echo "Created maintenance branch: $MAINT_BRANCH"
fi

# Push branch and tag — this triggers the release workflow
if [ "$BUMP" = "patch" ]; then
    PUSH_BRANCH="$BRANCH"
else
    PUSH_BRANCH="main"
fi

echo "Pushing $PUSH_BRANCH and $TAG..."
git -C "$ROOT" push origin "$PUSH_BRANCH" "$TAG"

echo ""
echo "Done. Release workflow triggered — monitor at:"
echo "  https://github.com/DataDog/java-reggie/actions"
echo ""
echo "After the workflow completes, bump to the next snapshot:"
echo "  ./scripts/post-release.sh $BUMP"
