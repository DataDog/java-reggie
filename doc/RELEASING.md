# Release Process

## Prerequisites

Before releasing, ensure:
1. All changes are merged to `main`
2. `CHANGELOG.md` has an entry for the new version
3. The following GitHub secrets are configured:
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

## Release Steps

### 1. Update CHANGELOG.md

Add an entry for the new version:

```markdown
## [0.1.0] - 2026-04-01

### Added
- Feature X
```

### 2. Run the release script

```bash
./scripts/release.sh minor   # or: major | patch
```

The script computes the new version by bumping the current SNAPSHOT version:
- `patch`: `0.1.0-SNAPSHOT` → releases `0.1.1`
- `minor`: `0.1.0-SNAPSHOT` → releases `0.2.0`
- `major`: `0.1.0-SNAPSHOT` → releases `1.0.0`

It also validates the CHANGELOG entry, runs `spotlessApply` + `build`, and creates an annotated tag.

### 3. Push tag to trigger the workflow

```bash
git push origin main v0.2.0   # use the tag printed by release.sh
```

The GitHub Actions release workflow will:
1. Verify the version in `build.gradle` matches the tag
2. Publish `reggie-runtime` to Maven Central (signed)
3. Create a GitHub Release with changelog notes

### 4. Bump to next SNAPSHOT

After the workflow completes:

```bash
./scripts/post-release.sh minor   # or: major | patch
git push origin main
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
