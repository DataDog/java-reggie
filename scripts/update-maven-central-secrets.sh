#!/usr/bin/env bash
# Writes Maven Central credentials to Vault at kv/k8s/gitlab-runner/java-reggie/maven-central.
# The GitLab CI runner reads them automatically from that path via its K8s service account.
#
# Credentials are resolved in order:
#   1. 1Password item "Maven Central Portal Token" (vault: Shared-Public-Software-Repositories)
#   2. macOS Keychain (service: java-reggie-maven-central)
#   3. Interactive prompt (offered to save to Keychain afterwards)
#
# Usage: ./scripts/update-maven-central-secrets.sh [--dry-run]
#
# Prerequisites:
#   - ddtool (for Vault OIDC login)
#   - vault CLI
#   - op (1Password CLI) — optional but recommended

set -euo pipefail

DRY_RUN=0
VAULT_ADDR_DEFAULT="https://vault.us1.ddbuild.io"
VAULT_PATH="kv/k8s/gitlab-runner/java-reggie/maven-central"
KEYCHAIN_SERVICE="java-reggie-maven-central"
OP_ITEM_NAME="Maven Central Portal Token"
OP_VAULT="Shared-Public-Software-Repositories"

for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        --help|-h)
            echo "Usage: $0 [--dry-run]"
            exit 0
            ;;
        *) echo "ERROR: unknown argument: $arg"; exit 1 ;;
    esac
done

export VAULT_ADDR="${VAULT_ADDR:-$VAULT_ADDR_DEFAULT}"

# ── Platform detection ────────────────────────────────────────────────────────

IS_MACOS=0
[[ "$(uname)" == "Darwin" ]] && IS_MACOS=1

# ── Prerequisites ─────────────────────────────────────────────────────────────

for cmd in vault ddtool; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: $cmd is required"; exit 1; }
done

# ── Helpers ───────────────────────────────────────────────────────────────────

op_read() {
    local field="$1"
    op item get "$OP_ITEM_NAME" --vault "$OP_VAULT" --field "$field" 2>/dev/null || true
}

op_available() {
    command -v op >/dev/null 2>&1 && op whoami &>/dev/null
}

keychain_read() {
    local account="$1"
    [ $IS_MACOS -eq 1 ] || return 0
    security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$account" -w 2>/dev/null || true
}

keychain_write() {
    local account="$1" value="$2"
    [ $IS_MACOS -eq 1 ] || return 0
    if security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$account" &>/dev/null; then
        security add-generic-password -U -s "$KEYCHAIN_SERVICE" -a "$account" -w "$value"
    else
        security add-generic-password    -s "$KEYCHAIN_SERVICE" -a "$account" -w "$value"
    fi
}

prompt_secret() {
    local prompt="$1"
    local value
    read -r -s -p "$prompt: " value
    echo ""
    printf '%s' "$value"
}

# ── Resolve credentials ───────────────────────────────────────────────────────

USERNAME=""
PASSWORD=""
SOURCE=""

# 1. Try 1Password
if op_available; then
    echo "Reading from 1Password (\"$OP_ITEM_NAME\" in $OP_VAULT)..."
    USERNAME=$(op_read "username")
    PASSWORD=$(op_read "password")
    if [ -n "$USERNAME" ] && [ -n "$PASSWORD" ]; then
        SOURCE="1password"
        echo "  ✓ Found"
    else
        echo "  Item not found or missing fields — falling back"
    fi
else
    command -v op >/dev/null 2>&1 && echo "1Password CLI found but not signed in (run: op signin). Falling back..."
fi

# 2. Try macOS Keychain
if [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; then
    if [ $IS_MACOS -eq 1 ]; then
        USERNAME=$(keychain_read "username")
        PASSWORD=$(keychain_read "password")
        if [ -n "$USERNAME" ] && [ -n "$PASSWORD" ]; then
            SOURCE="keychain"
            echo "  ✓ Found in Keychain (service: $KEYCHAIN_SERVICE)"
        fi
    fi
fi

# 3. Prompt
if [ -z "$USERNAME" ]; then
    USERNAME=$(prompt_secret "Maven Central token username")
fi
if [ -z "$PASSWORD" ]; then
    PASSWORD=$(prompt_secret "Maven Central token password")
fi

if [ -z "$USERNAME" ] || [ -z "$PASSWORD" ]; then
    echo "ERROR: username and password must not be empty"
    exit 1
fi

[ -n "$SOURCE" ] || SOURCE="prompt"

# ── Vault login (skip if already authenticated) ───────────────────────────────

echo ""
if vault token lookup &>/dev/null; then
    echo "Already authenticated to Vault ($VAULT_ADDR)"
else
    echo "Authenticating to Vault ($VAULT_ADDR)..."
    ddtool auth login
fi

# ── Write to Vault ────────────────────────────────────────────────────────────

echo ""
if [ $DRY_RUN -eq 1 ]; then
    echo "[dry-run] would write: vault kv put $VAULT_PATH username=<...> password=<...>"
else
    vault kv put "$VAULT_PATH" username="$USERNAME" password="$PASSWORD"
    echo "  ✓ $VAULT_PATH"
fi

# ── Offer to save to Keychain if values came from a prompt ───────────────────

if [ "$SOURCE" = "prompt" ] && [ $IS_MACOS -eq 1 ] && [ $DRY_RUN -eq 0 ]; then
    echo ""
    read -r -p "Save to Keychain for future runs? (y/n) " SAVE
    if [ "$SAVE" = "y" ]; then
        keychain_write "username" "$USERNAME"
        keychain_write "password" "$PASSWORD"
        echo "  ✓ Saved to Keychain (service: $KEYCHAIN_SERVICE)"
    fi
fi

echo ""
echo "Done. GitLab CI reads from: $VAULT_PATH"
