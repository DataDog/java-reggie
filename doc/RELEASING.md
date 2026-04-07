# Release Process

## Prerequisites

Before releasing, ensure:
1. All changes are merged to `main`
2. The following AWS SSM parameters are populated (one-time infra setup):
   - `ci.java-reggie.central_username` — Sonatype Central Portal token username
   - `ci.java-reggie.central_password` — Sonatype Central Portal token password
   - `ci.java-reggie.signing.gpg_private_key` — armored GPG private key
   - `ci.java-reggie.signing.gpg_key_id` — 8-char GPG key ID
   - `ci.java-reggie.signing.gpg_passphrase` — GPG key passphrase

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
./scripts/release.sh minor             # dry-run: shows what would happen
./scripts/release.sh minor --no-dry-run  # actually performs the release
```

The script:
- Computes the new version by bumping the current SNAPSHOT:
  - `minor`: `0.1.0-SNAPSHOT` → `0.2.0`
  - `major`: `0.1.0-SNAPSHOT` → `1.0.0`
- Collects merged PRs since the last tag and generates a `CHANGELOG.md` entry. PRs labelled **`no release notes`** are excluded.
- Runs `spotlessApply` + `build`
- Creates a release commit + annotated tag
- Creates and pushes the `release/X.Y._` maintenance branch
- Pushes `main` and the tag — this triggers the release workflow

Pushing the tag triggers two pipelines:
- **GitLab CI** (`publish_to_maven_central` job) — publishes to Maven Central using credentials from AWS SSM
- **GitHub Actions** (`create-release` job) — creates a GitHub Release with the changelog excerpt

### 2. Bump to next SNAPSHOT

```bash
./scripts/post-release.sh minor
git push origin main
```

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
./scripts/release.sh patch             # dry-run first
./scripts/release.sh patch --no-dry-run
```

The script collects the merged backport PRs on this branch (resolving their original PR references from the `🍒` title format) and generates the CHANGELOG entry.

The script pushes the release branch and tag automatically, triggering the release workflow.

### 3. Bump to next SNAPSHOT on the maintenance branch

```bash
./scripts/post-release.sh patch
git push origin release/0.2._
```

## Version Numbering

Follow [Semantic Versioning](https://semver.org/):
- **Major**: Breaking API changes
- **Minor**: New features, backward compatible
- **Patch**: Bug fixes, backward compatible

Development versions use the `-SNAPSHOT` suffix.
