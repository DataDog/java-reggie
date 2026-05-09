#!/usr/bin/env bash
# Release script for java-reggie.
# Releases the current SNAPSHOT as-is: X.Y.Z-SNAPSHOT becomes X.Y.Z.
#
# Topology
# ────────
#   major / minor:
#     - Default branch (e.g. main) does NOT receive the release commit.
#     - A new release/X.Y._ branch is cut from the chosen commit.
#     - The "Release X.Y.Z" commit + tag live on the release branch.
#     - The release branch is then bumped to X.Y.1-SNAPSHOT.
#     - The default branch is bumped to the next minor/major SNAPSHOT
#       (the CHANGELOG release entry is mirrored back so history stays intact).
#
#   patch:
#     - Run from an existing release/X.Y._ branch.
#     - "Release X.Y.Z" commit + tag are made on the release branch.
#     - The release branch is bumped to X.Y.(Z+1)-SNAPSHOT.
#
# Usage
# ─────
#   ./scripts/release.sh <major|minor|patch> [--no-dry-run] [--commit <sha>]
#
# Options
#   --no-dry-run    Actually perform the release (default is dry-run preview).
#   --commit <sha>  Release a specific commit (must equal HEAD of the branch).
#                   Without this flag, an interactive picker is shown.
#
# PRs labelled 'no release notes' are excluded from the generated CHANGELOG entry.

set -euo pipefail

# ── Argument parsing ─────────────────────────────────────────────────────────
BUMP=""
DRY_RUN=1
COMMIT_OVERRIDE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --no-dry-run) DRY_RUN=0; shift ;;
        --commit)
            [ -n "${2:-}" ] || { echo "ERROR: --commit requires a SHA" >&2; exit 1; }
            COMMIT_OVERRIDE="$2"; shift 2 ;;
        --help|-h)
            sed -n '2,/^set -/p' "$0" | sed -e 's/^# \{0,1\}//' -e '$d'
            exit 0 ;;
        major|minor|patch) BUMP="$1"; shift ;;
        *) echo "ERROR: unknown argument: $1" >&2; exit 1 ;;
    esac
done

if [ -z "$BUMP" ]; then
    echo "Usage: $0 <major|minor|patch> [--no-dry-run] [--commit <sha>]" >&2
    exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# ── Helpers ──────────────────────────────────────────────────────────────────
die() { echo "ERROR: $*" >&2; exit 1; }

# Run a command, or print it (with [DRY-RUN] prefix) when DRY_RUN=1.
run() {
    if [ $DRY_RUN -eq 1 ]; then
        printf '[DRY-RUN] %s\n' "$*"
    else
        printf '==> %s\n' "$*"
        "$@"
    fi
}

# Echo a description of a side-effecting action; perform it only when not in dry-run.
do_action() {
    local desc="$1"; shift
    if [ $DRY_RUN -eq 1 ]; then
        printf '[DRY-RUN] %s\n' "$desc"
    else
        printf '==> %s\n' "$desc"
        "$@"
    fi
}

# Portable sed-in-place (BSD vs GNU).
if sed --version 2>/dev/null | grep -q GNU; then
    sed_i() { sed -i "$@"; }
else
    sed_i() { sed -i '' "$@"; }
fi

# Read a single keypress (arrow keys, enter, q) from /dev/tty.
read_key() {
    local key
    IFS= read -rsn1 key </dev/tty
    if [[ $key == $'\x1b' ]]; then
        read -rsn2 key </dev/tty
        case $key in
            '[A') echo up ;;
            '[B') echo down ;;
            *)    echo other ;;
        esac
    elif [[ $key == "" ]]; then
        echo enter
    elif [[ $key == "q" || $key == "Q" ]]; then
        echo quit
    else
        echo other
    fi
}

# Show the last 10 commits on the given branch in an interactive picker.
# Echoes the selected SHA on stdout. Diagnostics go to stderr.
select_commit() {
    local branch="$1"
    local lines
    mapfile -t lines < <(git -C "$ROOT" log "$branch" -n 10 --pretty=format:"%H|%ar|%an|%s")
    [ ${#lines[@]} -gt 0 ] || die "No commits on branch '$branch'"
    [ -t 0 ] || die "Interactive picker requires a terminal. Use --commit <sha>."

    local selected=0 total=${#lines[@]}
    local sha date author message short i key

    while true; do
        clear >&2
        {
            echo
            echo "Select commit to release on '$branch'"
            echo "↑/↓ to navigate, Enter to select, q to quit"
            echo
        } >&2
        for i in "${!lines[@]}"; do
            IFS='|' read -r sha date author message <<< "${lines[$i]}"
            short="${sha:0:8}"
            if [ "$i" -eq "$selected" ]; then
                printf "  → %s  %-15s  %-20s  %s\n" "$short" "$date" "${author:0:20}" "${message:0:60}" >&2
            else
                printf "    %s  %-15s  %-20s  %s\n" "$short" "$date" "${author:0:20}" "${message:0:60}" >&2
            fi
        done
        key=$(read_key)
        case "$key" in
            up)   [ "$selected" -gt 0 ] && ((selected--)) ;;
            down) [ "$selected" -lt $((total - 1)) ] && ((selected++)) ;;
            enter)
                IFS='|' read -r sha _ _ _ <<< "${lines[$selected]}"
                clear >&2
                echo "$sha"
                return 0 ;;
            quit) clear >&2; die "Aborted by user." ;;
        esac
    done
}

# Replace [Unreleased] in CHANGELOG.md with a [VERSION] release section.
# Args: $1 = version, $2 = ISO date, $3 = path to PR-list JSON.
apply_release_changelog() {
    python3 - "$ROOT/CHANGELOG.md" "$1" "$2" "$3" <<'PYEOF'
import json, sys, os, re

changelog_path, version, date, json_path = sys.argv[1:5]

with open(json_path) as f:
    prs = json.load(f)

lines = []
seen_issues = set()
for pr in sorted(prs, key=lambda p: p['number']):
    labels = [l['name'] for l in pr.get('labels', [])]
    if 'no release notes' in labels:
        continue
    issue_nodes = pr.get('closingIssuesReferences', {}).get('nodes', [])
    if issue_nodes:
        for issue in issue_nodes:
            issue_labels = [l['name'] for l in issue.get('labels', [])]
            if 'no release notes' in issue_labels:
                continue
            if issue['number'] not in seen_issues:
                seen_issues.add(issue['number'])
                lines.append(f"- #{issue['number']} {issue['title']}")
    else:
        title = pr['title']
        # Backport PRs use "🍒 <orig_num> - <orig_title>"; surface the original PR ref.
        m = re.match(r'[^\x00-\x7F]\s+(\d+)\s+-\s+(.*)', title)
        if m:
            lines.append(f"- #{m.group(1)} {m.group(2).strip()}")
        else:
            lines.append(f"- #{pr['number']} {title}")

entries = '\n'.join(lines) if lines else '- No user-facing changes.'
new_section = f'## [{version}] - {date}\n\n{entries}\n'

if not os.path.exists(changelog_path):
    with open(changelog_path, 'w') as f:
        f.write(f'# Changelog\n\n{new_section}\n')
    sys.exit(0)

with open(changelog_path) as f:
    content = f.read()

if '## [Unreleased]' in content:
    content = re.sub(
        r'## \[Unreleased\].*?(?=\n## \[|\Z)',
        new_section, content, count=1, flags=re.DOTALL,
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
}

# Replace occurrences of $1 with $2 in tracked *.md files.
update_md_versions() {
    local from="$1" to="$2"
    local from_esc="${from//./\\.}"
    while IFS= read -r -d '' f; do
        sed_i "s|${from_esc}|${to}|g" "$ROOT/$f"
        rm -f "$ROOT/${f}.bak" 2>/dev/null || true
    done < <(git -C "$ROOT" ls-files -z '*.md')
}

# Set project.version in build.gradle to $1.
update_gradle_version() {
    sed_i "s|^project.version = \".*\"|project.version = \"$1\"|" "$ROOT/build.gradle"
    rm -f "$ROOT/build.gradle.bak" 2>/dev/null || true
}

# Insert an empty [Unreleased] section into CHANGELOG.md if it isn't already there.
ensure_unreleased_changelog() {
    if ! grep -q "## \[Unreleased\]" "$ROOT/CHANGELOG.md"; then
        # Insert "## [Unreleased]" after the "# Changelog" header.
        local tmp; tmp=$(mktemp)
        awk 'NR==1{print; print ""; print "## [Unreleased]"; print ""; next} NR==2 && /^$/{next} {print}' \
            "$ROOT/CHANGELOG.md" > "$tmp"
        mv "$tmp" "$ROOT/CHANGELOG.md"
    fi
}

# Write the CHANGELOG.md body for VERSION to a file.
# Args: $1 = version, $2 = output file
write_changelog_notes() {
    python3 - "$ROOT/CHANGELOG.md" "$1" "$2" <<'PYEOF'
import sys, re
changelog_path, version, notes_file = sys.argv[1:4]
try:
    with open(changelog_path) as f:
        content = f.read()
    pattern = r'## \[' + re.escape(version) + r'\][^\n]*\n\n(.*?)(?=\n## \[|\Z)'
    m = re.search(pattern, content, re.DOTALL)
    notes = m.group(1).strip() if m else ''
    with open(notes_file, 'w') as nf:
        nf.write(notes)
except Exception:
    open(notes_file, 'w').close()
PYEOF
}

# ── GraphQL / milestone helpers ──────────────────────────────────────────────

# Collect merged PRs (with closingIssuesReferences) via GitHub GraphQL API.
# Args: $1=base_branch, $2=since_date(ISO8601 or ""), $3=output_json_path
collect_prs_with_issues() {
    python3 - "$REPO_OWNER" "$REPO_NAME" "$1" "$2" "$3" <<'PYEOF'
import json, subprocess, sys

owner, repo, base, since, outfile = sys.argv[1:6]

QUERY = """\
query($owner: String!, $repo: String!, $base: String!, $cursor: String) {
  repository(owner: $owner, name: $repo) {
    pullRequests(
      states: [MERGED], baseRefName: $base, first: 100, after: $cursor,
      orderBy: {field: UPDATED_AT, direction: DESC}
    ) {
      pageInfo { hasNextPage endCursor }
      nodes {
        number title mergedAt updatedAt
        labels(first: 20)                  { nodes { name } }
        closingIssuesReferences(first: 10) { nodes { number title labels(first: 10) { nodes { name } } } }
      }
    }
  }
}"""

cursor = None
results = []
page = 0

while page < 5:
    args = ['gh', 'api', 'graphql',
            '-F', f'owner={owner}',
            '-F', f'repo={repo}',
            '-F', f'base={base}',
            '-f', f'query={QUERY}']
    if cursor:
        args += ['-F', f'cursor={cursor}']
    try:
        out = subprocess.check_output(args, stderr=subprocess.DEVNULL)
        data = json.loads(out)
    except Exception:
        break

    pr_data = data.get('data', {}).get('repository', {}).get('pullRequests', {})
    nodes    = pr_data.get('nodes', [])
    page_info = pr_data.get('pageInfo', {})

    stop = False
    for n in nodes:
        if since and (n.get('updatedAt') or '') < since:
            stop = True
            break
        merged = n.get('mergedAt') or ''
        if merged and (not since or merged > since):
            results.append({
                'number':   n['number'],
                'title':    n['title'],
                'labels':   [{'name': l['name']} for l in n['labels']['nodes']],
                'closingIssuesReferences': {
                    'nodes': [{'number': i['number'], 'title': i['title'],
                                'labels': [{'name': l['name']} for l in i['labels']['nodes']]}
                               for i in n['closingIssuesReferences']['nodes']]
                },
            })

    page += 1
    if stop or not page_info.get('hasNextPage'):
        break
    cursor = page_info.get('endCursor')

with open(outfile, 'w') as f:
    json.dump(results, f)
PYEOF
}

# Return the number of an existing milestone named $1, creating it if absent.
# Queries ?state=all so previously-closed milestones are found (idempotency).
get_or_create_milestone() {
    local title="$1"
    local num
    num=$(gh api "repos/$REPO_OWNER/$REPO_NAME/milestones?state=all&per_page=100" \
          --jq ".[] | select(.title == \"$title\") | .number" 2>/dev/null | head -1)
    if [ -z "$num" ]; then
        num=$(gh api "repos/$REPO_OWNER/$REPO_NAME/milestones" \
              -X POST -f "title=$title" --jq '.number' 2>/dev/null)
    fi
    echo "$num"
}

# Assign all PRs in JSON file $1, and their linked closing issues, to milestone $2.
assign_prs_to_milestone() {
    local json_path="$1" milestone_num="$2"
    python3 - "$json_path" "$milestone_num" "$REPO_OWNER" "$REPO_NAME" <<'PYEOF'
import json, subprocess, sys
json_path, milestone_num, owner, repo = sys.argv[1:5]
with open(json_path) as f:
    prs = json.load(f)
seen = set()
for pr in prs:
    for num in ([pr['number']]
                + [i['number'] for i in pr.get('closingIssuesReferences', {}).get('nodes', [])]):
        if num not in seen:
            seen.add(num)
            subprocess.run(
                ['gh', 'api', f'repos/{owner}/{repo}/issues/{num}',
                 '-X', 'PATCH', '-F', f'milestone={milestone_num}'],
                capture_output=True, check=False)
PYEOF
}

# Create milestone $1 if it does not already exist (queries ?state=all for idempotency).
create_milestone_if_absent() {
    local title="$1"
    if [ $DRY_RUN -eq 1 ]; then
        printf '[DRY-RUN] Create milestone "%s" (if absent)\n' "$title"
        return
    fi
    local exists
    exists=$(gh api "repos/$REPO_OWNER/$REPO_NAME/milestones?state=all&per_page=100" \
             --jq ".[] | select(.title == \"$title\") | .number" 2>/dev/null | head -1)
    if [ -z "$exists" ]; then
        gh api "repos/$REPO_OWNER/$REPO_NAME/milestones" -X POST -f "title=$title" >/dev/null \
            && printf '==> Created milestone "%s"\n' "$title" || true
    else
        printf '    (milestone "%s" already exists — skipping)\n' "$title"
    fi
}

# ── Preflight ────────────────────────────────────────────────────────────────
command -v gh >/dev/null 2>&1 || die "GitHub CLI (gh) is not installed. See https://cli.github.com/"
gh auth status >/dev/null 2>&1 || die "Not authenticated with GitHub CLI. Run 'gh auth login'."

DEFAULT_BRANCH=$(gh repo view --json defaultBranchRef -q '.defaultBranchRef.name' 2>/dev/null) \
    || die "Failed to detect default branch via 'gh repo view'."

_REPO_NWO=$(gh repo view --json nameWithOwner -q '.nameWithOwner' 2>/dev/null) \
    || die "Failed to detect repo name via 'gh repo view'."
REPO_OWNER="${_REPO_NWO%%/*}"
REPO_NAME="${_REPO_NWO##*/}"

if [ $DRY_RUN -eq 0 ] && [ -n "$(git -C "$ROOT" status --porcelain)" ]; then
    die "Working tree is dirty. Commit or stash changes first."
fi

# ── Branch rules ─────────────────────────────────────────────────────────────
BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
case "$BUMP" in
    major|minor)
        [ "$BRANCH" = "$DEFAULT_BRANCH" ] \
            || die "$BUMP releases must run from '$DEFAULT_BRANCH' (currently on '$BRANCH')"
        ;;
    patch)
        [[ "$BRANCH" =~ ^release/[0-9]+\.[0-9]+\._$ ]] \
            || die "patch releases must run from a 'release/X.Y._' branch (currently on '$BRANCH'). Use ./scripts/backport-pr.sh first."
        ;;
esac

# ── Version detection ────────────────────────────────────────────────────────
CURRENT="$(grep '^project.version' "$ROOT/build.gradle" | cut -d'"' -f2)"
[[ "$CURRENT" == *-SNAPSHOT ]] || die "build.gradle version ($CURRENT) is not a SNAPSHOT — nothing to release."
VERSION="${CURRENT%-SNAPSHOT}"
TAG="v${VERSION}"

IFS='.' read -r REL_MAJOR REL_MINOR REL_PATCH <<< "$VERSION"

MAINT_BRANCH="release/${REL_MAJOR}.${REL_MINOR}._"
case "$BUMP" in
    major) MAIN_NEXT="$((REL_MAJOR + 1)).0.0-SNAPSHOT" ;;
    minor) MAIN_NEXT="${REL_MAJOR}.$((REL_MINOR + 1)).0-SNAPSHOT" ;;
    patch) MAIN_NEXT="" ;;
esac
MAINT_NEXT="${REL_MAJOR}.${REL_MINOR}.$((REL_PATCH + 1))-SNAPSHOT"

# ── Commit selection ─────────────────────────────────────────────────────────
LOCAL_HEAD=$(git -C "$ROOT" rev-parse HEAD)
REMOTE_HEAD=$(git -C "$ROOT" ls-remote origin "refs/heads/$BRANCH" 2>/dev/null | awk '{print $1}')
[ -n "$REMOTE_HEAD" ] || die "Branch '$BRANCH' not found on remote 'origin'."

if [ -n "$COMMIT_OVERRIDE" ]; then
    SELECTED_COMMIT=$(git -C "$ROOT" rev-parse --verify "$COMMIT_OVERRIDE^{commit}" 2>/dev/null) \
        || die "Invalid commit: $COMMIT_OVERRIDE"
else
    SELECTED_COMMIT=$(select_commit "$BRANCH")
fi

if [ "$SELECTED_COMMIT" != "$LOCAL_HEAD" ]; then
    die "Selected commit ${SELECTED_COMMIT:0:8} is not the HEAD of '$BRANCH' (HEAD=${LOCAL_HEAD:0:8}). Reset/checkout the branch to that commit first."
fi
if [ "$LOCAL_HEAD" != "$REMOTE_HEAD" ]; then
    if [ $DRY_RUN -eq 1 ]; then
        echo "WARNING: local HEAD (${LOCAL_HEAD:0:8}) differs from origin/$BRANCH (${REMOTE_HEAD:0:8}); push before --no-dry-run." >&2
    else
        die "Local HEAD (${LOCAL_HEAD:0:8}) differs from origin/$BRANCH (${REMOTE_HEAD:0:8}). Push first."
    fi
fi

SHORT_SHA="${SELECTED_COMMIT:0:8}"
COMMIT_MESSAGE=$(git -C "$ROOT" log -1 --pretty=format:"%s" "$SELECTED_COMMIT")
COMMIT_AUTHOR=$(git -C "$ROOT" log -1 --pretty=format:"%an" "$SELECTED_COMMIT")

# ── Maintenance branch sanity ────────────────────────────────────────────────
if [[ "$BUMP" =~ ^(major|minor)$ ]]; then
    if git -C "$ROOT" show-ref --verify --quiet "refs/heads/$MAINT_BRANCH"; then
        die "Local branch '$MAINT_BRANCH' already exists. Delete or rename it first."
    fi
fi

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════"
echo "  Release Summary"
echo "═══════════════════════════════════════════════════════"
printf "  Type             : %s\n" "$BUMP"
printf "  Default branch   : %s\n" "$DEFAULT_BRANCH"
printf "  Current branch   : %s\n" "$BRANCH"
printf "  Commit           : %s — %s (%s)\n" "$SHORT_SHA" "$COMMIT_MESSAGE" "$COMMIT_AUTHOR"
printf "  Current version  : %s\n" "$CURRENT"
printf "  Release version  : %s  (tag %s)\n" "$VERSION" "$TAG"
case "$BUMP" in
    major|minor)
        printf "  Release branch   : %s  (new — bumped to %s.%s.1-SNAPSHOT)\n" \
               "$MAINT_BRANCH" "$REL_MAJOR" "$REL_MINOR"
        printf "  %-16s : %s\n" "$DEFAULT_BRANCH bumped to" "$MAIN_NEXT"
        ;;
    patch)
        printf "  Release branch   : %s  (bumped to %s)\n" "$BRANCH" "$MAINT_NEXT"
        ;;
esac
printf "  Mode             : %s\n" "$([ $DRY_RUN -eq 1 ] && echo 'DRY-RUN (no changes)' || echo 'EXECUTE')"
echo "═══════════════════════════════════════════════════════"
echo ""

if [ $DRY_RUN -eq 0 ]; then
    read -p "Proceed with release? (yes/no): " -r </dev/tty
    [[ $REPLY =~ ^[Yy][Ee][Ss]$ ]] || die "Cancelled by user."
fi

# ── Collect merged PRs (with linked issues) for release notes ─────────────────
LAST_TAG=$(git -C "$ROOT" describe --tags --abbrev=0 2>/dev/null || echo "")
SINCE=""
[ -n "$LAST_TAG" ] && SINCE=$(git -C "$ROOT" log -1 --format=%aI "$LAST_TAG")
TMPJSON=$(mktemp)
NOTES_FILE=$(mktemp)
trap 'rm -f "$TMPJSON" "$NOTES_FILE"' EXIT

echo "Collecting merged PRs with linked issues..."
collect_prs_with_issues "$BRANCH" "$SINCE" "$TMPJSON"
DATE=$(date +%Y-%m-%d)

# ── Milestone: create/get VERSION and assign collected PRs + issues ────────────
if [ $DRY_RUN -eq 1 ]; then
    _PR_COUNT=$(python3 -c "import json; print(len(json.load(open('$TMPJSON'))))" 2>/dev/null || echo "?")
    printf '[DRY-RUN] Create/update milestone "%s"\n' "$VERSION"
    printf '[DRY-RUN] Assign %s PRs (and linked issues) to milestone "%s"\n' "$_PR_COUNT" "$VERSION"
    _MILESTONE_NUM=0
else
    printf '==> Managing milestone "%s"\n' "$VERSION"
    _MILESTONE_NUM=$(get_or_create_milestone "$VERSION")
    assign_prs_to_milestone "$TMPJSON" "$_MILESTONE_NUM"
fi

# ── Execute ──────────────────────────────────────────────────────────────────
if [ "$BUMP" = "patch" ]; then
    do_action "Apply CHANGELOG release entry for $VERSION" \
        apply_release_changelog "$VERSION" "$DATE" "$TMPJSON"
    do_action "Update build.gradle: $CURRENT -> $VERSION" \
        update_gradle_version "$VERSION"
    do_action "Update *.md version refs: $CURRENT -> $VERSION" \
        update_md_versions "$CURRENT" "$VERSION"
    run "$ROOT/gradlew" -p "$ROOT" spotlessApply build \
        -x :reggie-benchmark:build -x :reggie-integration-tests:test --quiet
    do_action "git add release files" \
        bash -c "git -C \"$ROOT\" add build.gradle CHANGELOG.md && git -C \"$ROOT\" ls-files -z -- '*.md' | xargs -0 git -C \"$ROOT\" add --"
    run git -C "$ROOT" commit -m "Release $VERSION"
    run git -C "$ROOT" tag -a "$TAG" -m "Release $VERSION"

    do_action "Update build.gradle: $VERSION -> $MAINT_NEXT" \
        update_gradle_version "$MAINT_NEXT"
    do_action "Re-insert [Unreleased] in CHANGELOG.md" \
        ensure_unreleased_changelog
    run git -C "$ROOT" add build.gradle CHANGELOG.md
    run git -C "$ROOT" commit -m "Prepare for $MAINT_NEXT"

    run git -C "$ROOT" push origin "$BRANCH" "$TAG"

    write_changelog_notes "$VERSION" "$NOTES_FILE"
    do_action "Create GitHub release $TAG" \
        gh release create "$TAG" --title "$TAG" --notes-file "$NOTES_FILE" --repo "$REPO_OWNER/$REPO_NAME"

    do_action "Close milestone \"$VERSION\"" \
        bash -c "gh api repos/$REPO_OWNER/$REPO_NAME/milestones/$_MILESTONE_NUM -X PATCH -f state=closed >/dev/null"
    create_milestone_if_absent "${MAINT_NEXT%-SNAPSHOT}"

else
    # major / minor: cut release branch FIRST so the release commit lives there, not on $DEFAULT_BRANCH.
    run git -C "$ROOT" checkout -b "$MAINT_BRANCH"

    do_action "Apply CHANGELOG release entry for $VERSION (release branch)" \
        apply_release_changelog "$VERSION" "$DATE" "$TMPJSON"
    do_action "Update build.gradle: $CURRENT -> $VERSION (release branch)" \
        update_gradle_version "$VERSION"
    do_action "Update *.md version refs: $CURRENT -> $VERSION (release branch)" \
        update_md_versions "$CURRENT" "$VERSION"
    run "$ROOT/gradlew" -p "$ROOT" spotlessApply build \
        -x :reggie-benchmark:build -x :reggie-integration-tests:test --quiet
    do_action "git add release files (release branch)" \
        bash -c "git -C \"$ROOT\" add build.gradle CHANGELOG.md && git -C \"$ROOT\" ls-files -z -- '*.md' | xargs -0 git -C \"$ROOT\" add --"
    run git -C "$ROOT" commit -m "Release $VERSION"
    run git -C "$ROOT" tag -a "$TAG" -m "Release $VERSION"

    MAINT_FIRST_SNAPSHOT="${REL_MAJOR}.${REL_MINOR}.1-SNAPSHOT"
    do_action "Update build.gradle: $VERSION -> $MAINT_FIRST_SNAPSHOT (release branch)" \
        update_gradle_version "$MAINT_FIRST_SNAPSHOT"
    do_action "Re-insert [Unreleased] in CHANGELOG.md (release branch)" \
        ensure_unreleased_changelog
    run git -C "$ROOT" add build.gradle CHANGELOG.md
    run git -C "$ROOT" commit -m "Prepare for $MAINT_FIRST_SNAPSHOT"

    # Switch back to default branch and prepare its bump commit.
    # Default branch never sees a "Release" commit; it gets a single commit that:
    #   - mirrors the CHANGELOG release entry (history continuity),
    #   - bumps build.gradle to the next major/minor SNAPSHOT,
    #   - updates *.md version refs to the just-released version.
    run git -C "$ROOT" checkout "$DEFAULT_BRANCH"

    do_action "Apply CHANGELOG release entry for $VERSION ($DEFAULT_BRANCH)" \
        apply_release_changelog "$VERSION" "$DATE" "$TMPJSON"
    do_action "Update *.md version refs: $CURRENT -> $VERSION ($DEFAULT_BRANCH)" \
        update_md_versions "$CURRENT" "$VERSION"
    do_action "Update build.gradle: $CURRENT -> $MAIN_NEXT ($DEFAULT_BRANCH)" \
        update_gradle_version "$MAIN_NEXT"
    do_action "Re-insert [Unreleased] in CHANGELOG.md ($DEFAULT_BRANCH)" \
        ensure_unreleased_changelog
    do_action "git add bump files ($DEFAULT_BRANCH)" \
        bash -c "git -C \"$ROOT\" add build.gradle CHANGELOG.md && git -C \"$ROOT\" ls-files -z -- '*.md' | xargs -0 git -C \"$ROOT\" add --"
    run git -C "$ROOT" commit -m "Prepare for $MAIN_NEXT"

    run git -C "$ROOT" push origin "$DEFAULT_BRANCH" "$MAINT_BRANCH" "$TAG"

    write_changelog_notes "$VERSION" "$NOTES_FILE"
    do_action "Create GitHub release $TAG" \
        gh release create "$TAG" --title "$TAG" --notes-file "$NOTES_FILE" --repo "$REPO_OWNER/$REPO_NAME"

    do_action "Close milestone \"$VERSION\"" \
        bash -c "gh api repos/$REPO_OWNER/$REPO_NAME/milestones/$_MILESTONE_NUM -X PATCH -f state=closed >/dev/null"
    create_milestone_if_absent "${MAINT_NEXT%-SNAPSHOT}"
    create_milestone_if_absent "${MAIN_NEXT%-SNAPSHOT}"

fi

echo ""
if [ $DRY_RUN -eq 1 ]; then
    echo "Dry-run complete. Re-run with --no-dry-run to execute."
else
    echo "Done. Release workflow triggered — monitor at:"
    echo "  https://github.com/DataDog/java-reggie/actions"
fi
