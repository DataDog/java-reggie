# Release Process

## Prerequisites

Before releasing, ensure:
1. All changes are merged to `main`
2. The following GitHub secrets are configured:
   - `ORG_GRADLE_PROJECT_MAVENCENTRALUSERNAME` - Sonatype Central Portal token username
   - `ORG_GRADLE_PROJECT_MAVENCENTRALPASSWORD` - Sonatype Central Portal token password
   - `ORG_GRADLE_PROJECT_SIGNINGINMEMORYKEY` - GPG private key (armored)
   - `ORG_GRADLE_PROJECT_SIGNINGINMEMORYKEYID` - GPG key ID (8 hex chars)
   - `ORG_GRADLE_PROJECT_SIGNINGINMEMORYKEYPASSWORD` - GPG key passphrase

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

The release workflow verifies the tag, publishes to Maven Central, and creates a GitHub Release.

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

## One-time Setup: GPG Key

```bash
# Create key (if needed)
gpg --gen-key

# List keys
gpg --list-secret-keys --keyid-format=long

# Export private key
gpg --export-secret-keys --armor <KEY_ID> > private-key.asc

# Upload public key to keyserver
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# Add secrets to GitHub
gh secret set ORG_GRADLE_PROJECT_MAVENCENTRALUSERNAME
gh secret set ORG_GRADLE_PROJECT_MAVENCENTRALPASSWORD
gh secret set ORG_GRADLE_PROJECT_SIGNINGINMEMORYKEY < private-key.asc
gh secret set ORG_GRADLE_PROJECT_SIGNINGINMEMORYKEYID   # last 8 hex chars of KEY_ID
gh secret set ORG_GRADLE_PROJECT_SIGNINGINMEMORYKEYPASSWORD
```

## One-time Setup: Sonatype Central Portal

1. Create account at https://central.sonatype.com
2. Go to **Account** → **Generate User Token**
3. Use the token username/password as the `MAVENCENTRALUSERNAME` / `MAVENCENTRALPASSWORD` secrets
4. Register your `com.datadoghq` namespace (requires DNS or GitHub org verification)

## Manual Publish (emergency)

```bash
export ORG_GRADLE_PROJECT_mavenCentralUsername=<token-user>
export ORG_GRADLE_PROJECT_mavenCentralPassword=<token-pass>
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat private-key.asc)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyId=<8-char-key-id>
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=<passphrase>

./gradlew \
  :reggie-runtime:publishAllPublicationsToMavenCentralRepository \
  --no-daemon
```

## Version Numbering

Follow [Semantic Versioning](https://semver.org/):
- **Major**: Breaking API changes
- **Minor**: New features, backward compatible
- **Patch**: Bug fixes, backward compatible

Development versions use the `-SNAPSHOT` suffix.
