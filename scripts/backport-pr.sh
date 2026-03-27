#!/usr/bin/env bash
# Cherry-picks a merged PR onto a release/X.Y._ maintenance branch and opens a backport PR.
#
# Usage: ./scripts/backport-pr.sh [--dry-run] <release-series> <pr-number-or-url>
# Example: ./scripts/backport-pr.sh 0.2._ 123
#          ./scripts/backport-pr.sh 0.2._ https://github.com/DataDog/java-reggie/pull/123
#
# Requires: gh CLI (authenticated), python3

set -euo pipefail

DRY_RUN=0
RELEASE_SERIES=""
PR_INPUT=""

for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        --help|-h)
            echo "Usage: $0 [--dry-run] <release-series> <pr-number-or-url>"
            echo "Example: $0 0.2._ 123"
            exit 0
            ;;
        *)
            if [ -z "$RELEASE_SERIES" ]; then
                RELEASE_SERIES="$arg"
            elif [ -z "$PR_INPUT" ]; then
                PR_INPUT="$arg"
            else
                echo "ERROR: unexpected argument: $arg"; exit 1
            fi
            ;;
    esac
done

if [ -z "$RELEASE_SERIES" ] || [ -z "$PR_INPUT" ]; then
    echo "Usage: $0 [--dry-run] <release-series> <pr-number-or-url>"
    exit 1
fi

if [[ ! "$RELEASE_SERIES" =~ ^[0-9]+\.[0-9]+\._ ]]; then
    echo "ERROR: release series must be X.Y._ (e.g. 0.2._)"
    exit 1
fi

if [[ "$PR_INPUT" =~ /pull/([0-9]+) ]]; then
    PR_NUMBER="${BASH_REMATCH[1]}"
elif [[ "$PR_INPUT" =~ ^[0-9]+$ ]]; then
    PR_NUMBER="$PR_INPUT"
else
    echo "ERROR: cannot parse PR number from: $PR_INPUT"; exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CURRENT_BRANCH=$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)
RELEASE_BRANCH="release/$RELEASE_SERIES"
BACKPORT_BRANCH="${USER:-ci}/backport-pr-$PR_NUMBER"
CHERRY_PICK_IN_PROGRESS=0
TMPJSON=""

cleanup() {
    local code=$?
    rm -f "${TMPJSON:-}" 2>/dev/null || true
    if [ $code -ne 0 ] && [ $CHERRY_PICK_IN_PROGRESS -eq 1 ]; then
        echo ""
        echo "ERROR: cherry-pick failed — likely a conflict."
        echo ""
        echo "  Resolve manually, then:"
        echo "    git add <resolved-files>"
        echo "    git cherry-pick --continue"
        echo "    git push -u origin $BACKPORT_BRANCH"
        echo "    gh pr create --base $RELEASE_BRANCH --head $BACKPORT_BRANCH ..."
        echo ""
        echo "  Or abort:"
        echo "    git cherry-pick --abort"
        echo "    git checkout $CURRENT_BRANCH"
        echo "    git branch -D $BACKPORT_BRANCH"
    elif [ $code -ne 0 ] && [ -n "$CURRENT_BRANCH" ]; then
        git -C "$ROOT" checkout "$CURRENT_BRANCH" 2>/dev/null || true
    fi
}
trap cleanup EXIT

for cmd in gh python3; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: $cmd is required"; exit 1; }
done

if [ -n "$(git -C "$ROOT" status --porcelain)" ]; then
    echo "ERROR: working tree is not clean; stash or commit first"
    exit 1
fi

git -C "$ROOT" fetch --quiet

git show-ref --verify --quiet "refs/remotes/origin/$RELEASE_BRANCH" 2>/dev/null || {
    echo "ERROR: branch $RELEASE_BRANCH does not exist on origin"
    exit 1
}

# Fetch PR details
echo "Fetching PR #$PR_NUMBER..."
TMPJSON=$(mktemp)
gh pr view "$PR_NUMBER" --json commits,mergeCommit,title,labels,state > "$TMPJSON"

eval "$(python3 - "$TMPJSON" <<'PYEOF'
import json, sys, shlex
with open(sys.argv[1]) as f:
    d = json.load(f)
print(f"PR_STATE={shlex.quote(d['state'])}")
print(f"PR_TITLE={shlex.quote(d['title'])}")
print(f"PR_COMMITS={shlex.quote(' '.join(c['oid'] for c in d['commits']))}")
mc = d.get('mergeCommit')
print(f"PR_MERGE_COMMIT={shlex.quote(mc['oid'] if mc else '')}")
PYEOF
)"

echo "PR: $PR_TITLE [$PR_STATE]"

if [ "$PR_STATE" != "MERGED" ]; then
    echo -n "WARNING: PR #$PR_NUMBER is $PR_STATE (not merged). Proceed? (y/n) "
    read -r ANSWER
    [ "$ANSWER" = "y" ] || { echo "Aborting."; exit 1; }
fi

# Determine commits to cherry-pick — fall back to merge commit if individual
# commits are unreachable (squash GC'd) or contain internal merge commits
USE_MERGE_COMMIT=0

for sha in $PR_COMMITS; do
    git -C "$ROOT" cat-file -e "$sha" 2>/dev/null || { USE_MERGE_COMMIT=1; break; }
done

if [ $USE_MERGE_COMMIT -eq 0 ]; then
    for sha in $PR_COMMITS; do
        PARENTS=$(git -C "$ROOT" rev-list --parents -n 1 "$sha" 2>/dev/null | wc -w)
        [ "$PARENTS" -gt 2 ] && { USE_MERGE_COMMIT=1; break; }
    done
fi

if [ $USE_MERGE_COMMIT -eq 1 ]; then
    [ -n "$PR_MERGE_COMMIT" ] || { echo "ERROR: need merge commit but PR has not been merged yet"; exit 1; }
    echo -n "Use merge commit instead of individual commits? (y/n) "
    read -r ANSWER
    [ "$ANSWER" = "y" ] || { echo "Aborting."; exit 1; }
    PR_COMMITS="$PR_MERGE_COMMIT"
fi

COMMIT_COUNT=$(echo "$PR_COMMITS" | wc -w | tr -d ' ')

# Check for an existing backport PR
EXISTING_PR_JSON=$(gh pr list --head "$BACKPORT_BRANCH" --base "$RELEASE_BRANCH" --json url 2>/dev/null || echo "[]")
EXISTING_PR_URL=$(echo "$EXISTING_PR_JSON" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['url'] if d else '')")
if [ -n "$EXISTING_PR_URL" ]; then
    echo "ERROR: backport PR already exists: $EXISTING_PR_URL"
    exit 1
fi

if [ $DRY_RUN -eq 1 ]; then
    echo ""
    echo "Dry-run — no changes made:"
    echo "  PR:      #$PR_NUMBER — $PR_TITLE"
    echo "  Target:  $RELEASE_BRANCH"
    echo "  Branch:  $BACKPORT_BRANCH"
    echo "  Commits: $COMMIT_COUNT ($PR_COMMITS)"
    exit 0
fi

# Cherry-pick onto the release branch
echo "Checking out $RELEASE_BRANCH..."
git -C "$ROOT" checkout "$RELEASE_BRANCH"
git -C "$ROOT" pull --quiet
git -C "$ROOT" checkout -b "$BACKPORT_BRANCH"

CHERRY_PICK_IN_PROGRESS=1
for sha in $PR_COMMITS; do
    git -C "$ROOT" cherry-pick -x "$sha"
done
CHERRY_PICK_IN_PROGRESS=0

git -C "$ROOT" push -u origin "$BACKPORT_BRANCH"

BACKPORT_PR_URL=$(gh pr create \
    --base "$RELEASE_BRANCH" \
    --head "$BACKPORT_BRANCH" \
    --title "🍒 $PR_NUMBER - $PR_TITLE" \
    --body "Backport of #$PR_NUMBER to \`$RELEASE_BRANCH\`.")
echo "Created: $BACKPORT_PR_URL"

gh pr comment "$PR_NUMBER" --body "Backported to \`$RELEASE_BRANCH\` via $BACKPORT_PR_URL" 2>/dev/null || true

git -C "$ROOT" checkout "$CURRENT_BRANCH"
echo ""
echo "Done! Backport PR: $BACKPORT_PR_URL"
