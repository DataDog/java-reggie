# Release Process

## Prerequisites

Before releasing, ensure:
1. All changes are merged to `main`
2. The following AWS SSM parameters are populated (one-time infra setup):
   - `ci.java-reggie.maven_central_username` — Sonatype Central Portal token username
   - `ci.java-reggie.maven_central_password` — Sonatype Central Portal token password
   - `ci.java-reggie.signing.gpg_private_key` — armored GPG private key (set by `create_key` CI job)
   - `ci.java-reggie.signing.gpg_passphrase` — GPG key passphrase (set by `create_key` CI job)

## Published Artifacts

The following artifact is published to Maven Central under `com.datadoghq`:

| Artifact | Description |
|---|---|
| `reggie-runtime` | All-in-one: runtime API, annotation, codegen engine, and annotation processor |

Not published: `reggie-annotations`, `reggie-codegen`, `reggie-processor`, `reggie-benchmark`, `reggie-integration-tests`.

## Release Types

| Type | Branch | When |
|---|---|---|
| `major` / `minor` | `main` | Normal feature/breaking releases |
| `patch` | `release/X.Y._` | Hotfixes cherry-picked onto a maintenance branch |

---

## Major / Minor Release

### 1. Run the release script

```bash
./scripts/release.sh minor                       # dry-run preview
./scripts/release.sh minor --no-dry-run          # actually performs the release
./scripts/release.sh minor --commit <sha>        # skip the interactive picker
```

By default the script shows an arrow-key picker over the last 10 commits on the current branch. Pass `--commit <sha>` to skip the picker (the chosen commit must equal the local HEAD, which must equal `origin/<branch>`).

The script:
- Reads the version from `build.gradle` and strips `-SNAPSHOT` for the release version.
- Collects merged PRs since the last tag and generates a `CHANGELOG.md` entry (PRs labelled **`no release notes`** are excluded).
- Runs `spotlessApply` + `build`.
- **Cuts the `release/X.Y._` branch from the chosen commit** and lands the `Release X.Y.Z` commit + tag there — the default branch never receives the release commit.
- Bumps the release branch to `X.Y.1-SNAPSHOT` (`Prepare for X.Y.1-SNAPSHOT`).
- Bumps the default branch to the next minor/major `-SNAPSHOT` and mirrors the CHANGELOG release entry back so history stays intact (`Prepare for X.Y.Z-SNAPSHOT`).
- Pushes the default branch, the release branch and the tag — this triggers the release workflow.

Pushing the tag triggers two pipelines:
- **GitLab CI** (`publish_to_maven_central` job) — publishes to Maven Central using credentials from AWS SSM
- **GitHub Actions** (`create-release` job) — creates a GitHub Release with the changelog excerpt

---

## Patch Release

### 1. Cherry-pick fixes onto the maintenance branch

```bash
./scripts/backport-pr.sh 0.2._ 123   # cherry-picks PR #123 onto release/0.2._
```

This creates a `$USER/backport-pr-123` branch, opens a PR against `release/0.2._`, and comments on the original PR for traceability. Repeat for each fix to include.

### 2. Run the release script from the maintenance branch

```bash
git checkout release/0.2._
git pull
./scripts/release.sh patch                       # dry-run preview
./scripts/release.sh patch --no-dry-run
./scripts/release.sh patch --commit <sha>        # skip the interactive picker
```

The script collects the merged backport PRs on this branch (resolving their original PR references from the `🍒` title format) and generates the CHANGELOG entry.

After tagging, the script bumps the release branch to `X.Y.(Z+1)-SNAPSHOT` (`Prepare for ...` commit) and pushes the branch and tag, triggering the release workflow.

## Version Numbering

Follow [Semantic Versioning](https://semver.org/):
- **Major**: Breaking API changes
- **Minor**: New features, backward compatible
- **Patch**: Bug fixes, backward compatible

Development versions use the `-SNAPSHOT` suffix.
