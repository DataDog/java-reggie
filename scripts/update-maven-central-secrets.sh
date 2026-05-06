#!/usr/bin/env bash
# Writes Maven Central credentials to AWS SSM Parameter Store.
# The GitLab CI runner reads them via the java-reggie K8s service account IAM role.
#
# Credentials are resolved in order:
#   1. 1Password item "Maven Central Portal Token" (vault: Shared-Public-Software-Repositories)
#   2. macOS Keychain (service: java-reggie-maven-central)
#   3. Interactive prompt (offered to save to Keychain afterwards)
#
# Usage: ./scripts/update-maven-central-secrets.sh [--dry-run]
#
# AWS credentials are obtained automatically via aws-vault (sso-build-stable-developer).
# 1Password CLI (op) is optional but recommended.

set -euo pipefail

AWS_PROFILE="sso-build-stable-developer"
DRY_RUN=0
AWS_REGION="us-east-1"
SSM_PREFIX="ci.java-reggie"
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

# ── Re-exec via aws-vault if not already running inside it ────────────────────

if [[ -z "${AWS_VAULT:-}" ]]; then
    command -v aws-vault >/dev/null 2>&1 || { echo "ERROR: aws-vault is required"; exit 1; }
    echo "Re-executing via aws-vault ($AWS_PROFILE)..."
    exec aws-vault exec "$AWS_PROFILE" -- "$0" "$@"
fi

# ── Platform detection ────────────────────────────────────────────────────────

IS_MACOS=0
[[ "$(uname)" == "Darwin" ]] && IS_MACOS=1

# ── Prerequisites ─────────────────────────────────────────────────────────────

command -v aws >/dev/null 2>&1 || { echo "ERROR: aws CLI is required"; exit 1; }

# ── Helpers ───────────────────────────────────────────────────────────────────

op_read() {
    local field="$1"
    op item get "$OP_ITEM_NAME" --vault "$OP_VAULT" --field "$field" --reveal 2>/dev/null || true
}

op_signin() {
    if ! command -v op >/dev/null 2>&1; then
        return 1
    fi
    if op whoami &>/dev/null; then
        return 0
    fi
    echo "1Password CLI found but not signed in — signing in..."
    eval "$(op signin)" || return 1
    op whoami &>/dev/null
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

ssm_put() {
    local name="$1" value="$2"
    aws ssm put-parameter \
        --region "$AWS_REGION" \
        --name "$name" \
        --value "$value" \
        --type SecureString \
        --overwrite
}

# ── Resolve credentials ───────────────────────────────────────────────────────

USERNAME=""
PASSWORD=""
SOURCE=""

# 1. Try 1Password
if op_signin; then
    echo "Reading from 1Password (\"$OP_ITEM_NAME\" in $OP_VAULT)..."
    USERNAME=$(op_read "username")
    PASSWORD=$(op_read "password")
    if [ -n "$USERNAME" ] && [ -n "$PASSWORD" ]; then
        SOURCE="1password"
        echo "  ✓ Found"
    else
        echo "  Item not found or missing fields — falling back"
    fi
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

# ── Write to SSM ──────────────────────────────────────────────────────────────

echo ""
if [ $DRY_RUN -eq 1 ]; then
    echo "[dry-run] would write:"
    echo "  ${SSM_PREFIX}.maven_central_username"
    echo "  ${SSM_PREFIX}.maven_central_password"
else
    ssm_put "${SSM_PREFIX}.maven_central_username" "$USERNAME"
    echo "  ✓ ${SSM_PREFIX}.maven_central_username"
    ssm_put "${SSM_PREFIX}.maven_central_password" "$PASSWORD"
    echo "  ✓ ${SSM_PREFIX}.maven_central_password"
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
echo "Done. GitLab CI reads from: ${SSM_PREFIX}.maven_central_{username,password}"
