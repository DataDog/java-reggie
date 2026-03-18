#!/bin/bash

# Security Scan Script for Datadog Open Source Release
# Scans for hardcoded credentials, API keys, tokens, and sensitive data

set -e

RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Reggie Security Scan${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

FINDINGS=0
REPORT_FILE="security-scan-report.txt"

# Clear previous report
> "$REPORT_FILE"

echo "Security Scan Report - $(date)" >> "$REPORT_FILE"
echo "========================================" >> "$REPORT_FILE"
echo "" >> "$REPORT_FILE"

# Function to check files for patterns
check_pattern() {
    local pattern="$1"
    local description="$2"
    local severity="$3"

    echo -e "${BLUE}Checking for: ${description}${NC}"

    results=$(git grep -niE "$pattern" -- \
        ':!*.md' \
        ':!*.txt' \
        ':!scripts/security-scan.sh' \
        ':!NOTICE' \
        ':!LICENSE*' \
        2>/dev/null || true)

    if [ -n "$results" ]; then
        count=$(echo "$results" | wc -l | tr -d ' ')
        FINDINGS=$((FINDINGS + count))

        echo -e "${RED}  ⚠️  Found $count potential issue(s)${NC}"
        echo "" >> "$REPORT_FILE"
        echo "[$severity] $description" >> "$REPORT_FILE"
        echo "Found: $count occurrences" >> "$REPORT_FILE"
        echo "$results" >> "$REPORT_FILE"
        echo "" >> "$REPORT_FILE"
    else
        echo -e "${GREEN}  ✓ Clean${NC}"
    fi
}

# Function to check git history
check_git_history() {
    local pattern="$1"
    local description="$2"

    echo -e "${BLUE}Scanning git history for: ${description}${NC}"

    results=$(git log -p --all -S "$pattern" --pretty=format:"%h %ad %s" --date=short 2>/dev/null | head -20 || true)

    if [ -n "$results" ]; then
        echo -e "${YELLOW}  ⚠️  Found in git history${NC}"
        echo "" >> "$REPORT_FILE"
        echo "[HISTORY] $description" >> "$REPORT_FILE"
        echo "$results" >> "$REPORT_FILE"
        echo "" >> "$REPORT_FILE"
        FINDINGS=$((FINDINGS + 1))
    else
        echo -e "${GREEN}  ✓ Clean${NC}"
    fi
}

echo -e "${YELLOW}1. CREDENTIAL PATTERNS${NC}"
echo "===========================================" >> "$REPORT_FILE"
echo "1. CREDENTIAL PATTERNS" >> "$REPORT_FILE"
echo "===========================================" >> "$REPORT_FILE"

check_pattern "password\s*=\s*['\"][^'\"]{3,}" "Hardcoded passwords" "CRITICAL"
check_pattern "api[_-]?key\s*[=:]\s*['\"][a-zA-Z0-9]{20,}" "API keys" "CRITICAL"
check_pattern "secret[_-]?key\s*[=:]\s*['\"][^'\"]{10,}" "Secret keys" "CRITICAL"
check_pattern "access[_-]?token\s*[=:]\s*['\"][^'\"]{10,}" "Access tokens" "CRITICAL"
check_pattern "private[_-]?key\s*[=:]\s*['\"]" "Private keys" "CRITICAL"
check_pattern "aws[_-]?secret" "AWS credentials" "CRITICAL"
check_pattern "AKIA[0-9A-Z]{16}" "AWS Access Key ID" "CRITICAL"
check_pattern "[0-9a-zA-Z/+]{40}" "Generic 40-char secrets" "MEDIUM"

echo ""
echo -e "${YELLOW}2. DATADOG-SPECIFIC PATTERNS${NC}"
echo "===========================================" >> "$REPORT_FILE"
echo "2. DATADOG-SPECIFIC" >> "$REPORT_FILE"
echo "===========================================" >> "$REPORT_FILE"

check_pattern "DD_API_KEY|DATADOG_API_KEY" "Datadog API keys" "CRITICAL"
check_pattern "DD_APP_KEY|DATADOG_APP_KEY" "Datadog application keys" "CRITICAL"
check_pattern "datadoghq\.com[^/]*/api/v[0-9]" "Datadog API URLs with potential secrets" "MEDIUM"

echo ""
echo -e "${YELLOW}3. INTERNAL REFERENCES${NC}"
echo "===========================================" >> "$REPORT_FILE"
echo "3. INTERNAL REFERENCES" >> "$REPORT_FILE"
echo "===========================================" >> "$REPORT_FILE"

check_pattern "datadoghq\.atlassian\.net" "Internal Atlassian/Confluence links" "LOW"
check_pattern "dd\.datadoghq\.com" "Internal Datadog infrastructure" "MEDIUM"
check_pattern "localhost:[0-9]{4,5}" "Hardcoded localhost ports (may expose internal architecture)" "LOW"
check_pattern "192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\." "Private IP addresses" "LOW"

echo ""
echo -e "${YELLOW}4. OTHER SENSITIVE DATA${NC}"
echo "===========================================" >> "$REPORT_FILE"
echo "4. OTHER SENSITIVE DATA" >> "$REPORT_FILE"
echo "===========================================" >> "$REPORT_FILE"

check_pattern "jdbc:[^:]+://[^@]+:[^@]+@" "Database connection strings with credentials" "HIGH"
check_pattern "-----BEGIN (RSA |DSA )?PRIVATE KEY-----" "Private key blocks" "CRITICAL"
check_pattern "[a-zA-Z0-9._%+-]+@datadoghq\.com" "Internal Datadog email addresses" "LOW"
check_pattern "Bearer [a-zA-Z0-9_-]{20,}" "Bearer tokens" "HIGH"

echo ""
echo -e "${YELLOW}5. GIT HISTORY SCAN${NC}"
echo "===========================================" >> "$REPORT_FILE"
echo "5. GIT HISTORY SCAN" >> "$REPORT_FILE"
echo "===========================================" >> "$REPORT_FILE"

check_git_history "password" "Passwords in history"
check_git_history "api_key" "API keys in history"
check_git_history "secret" "Secrets in history"

echo ""
echo -e "${YELLOW}6. ENVIRONMENT FILES${NC}"
echo "===========================================" >> "$REPORT_FILE"
echo "6. ENVIRONMENT FILES" >> "$REPORT_FILE"
echo "===========================================" >> "$REPORT_FILE"

env_files=$(find . -name ".env*" -o -name "*.env" -o -name "credentials.*" -o -name "*secret*" | grep -v node_modules | grep -v ".gradle" | grep -v "build/" || true)

if [ -n "$env_files" ]; then
    echo -e "${YELLOW}  ⚠️  Found environment/credential files:${NC}"
    echo "$env_files" | while read file; do
        echo "    - $file"
        echo "  - $file" >> "$REPORT_FILE"
    done
    FINDINGS=$((FINDINGS + 1))
else
    echo -e "${GREEN}  ✓ No environment files found${NC}"
fi

echo "" >> "$REPORT_FILE"

echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}SCAN SUMMARY${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "SCAN SUMMARY" >> "$REPORT_FILE"
echo "========================================" >> "$REPORT_FILE"

if [ $FINDINGS -eq 0 ]; then
    echo -e "${GREEN}✓ PASSED: No security issues found${NC}"
    echo "✓ PASSED: No security issues found" >> "$REPORT_FILE"
    exit 0
else
    echo -e "${RED}⚠️  FAILED: Found $FINDINGS potential security issues${NC}"
    echo "⚠️  FAILED: Found $FINDINGS potential issues" >> "$REPORT_FILE"
    echo ""
    echo -e "${YELLOW}Review the detailed report: $REPORT_FILE${NC}"
    echo ""
    echo "Next steps:"
    echo "1. Review each finding in $REPORT_FILE"
    echo "2. Remove or redact sensitive data"
    echo "3. If secrets are in git history, use 'git filter-branch' or 'BFG Repo-Cleaner'"
    echo "4. Re-run this scan until clean"
    echo "5. Contact #sit if you need assistance"
    exit 1
fi
