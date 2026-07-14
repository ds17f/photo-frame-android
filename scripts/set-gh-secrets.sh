#!/usr/bin/env bash
# set-gh-secrets.sh — push the release-signing values from .env into GitHub Actions
# secrets, so the CI `release` job can produce a signed APK (see .github/workflows/ci.yml).
#
# Sets these repo secrets:
#   RELEASE_KEYSTORE_BASE64  — the keystore file, base64-encoded
#   RELEASE_STORE_PASSWORD
#   RELEASE_KEY_PASSWORD
#   RELEASE_KEY_ALIAS
#
# Requires the GitHub CLI (`gh`) authenticated with repo admin rights. Run from the repo
# root after `scripts/init-signing.sh`.
set -euo pipefail

cd "$(dirname "$0")/.."   # repo root

command -v gh >/dev/null || { echo "GitHub CLI (gh) not found — https://cli.github.com" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh is not authenticated — run: gh auth login" >&2; exit 1; }
[[ -f .env ]] || { echo "No .env — run scripts/init-signing.sh first." >&2; exit 1; }

set -a
# shellcheck disable=SC1091
. ./.env
set +a

KEYSTORE_FILE="${RELEASE_KEYSTORE_FILE:-photoframe-release.keystore}"
[[ -f "$KEYSTORE_FILE" ]] || { echo "Keystore not found: $KEYSTORE_FILE — run scripts/init-signing.sh." >&2; exit 1; }
: "${RELEASE_STORE_PASSWORD:?set RELEASE_STORE_PASSWORD in .env}"
: "${RELEASE_KEY_PASSWORD:?set RELEASE_KEY_PASSWORD in .env}"
: "${RELEASE_KEY_ALIAS:?set RELEASE_KEY_ALIAS in .env}"

# Target repo (defaults to the one the current dir is linked to).
REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
echo "Pushing signing secrets to: $REPO"

base64 -w0 "$KEYSTORE_FILE" 2>/dev/null | gh secret set RELEASE_KEYSTORE_BASE64 --repo "$REPO" \
  || base64 "$KEYSTORE_FILE" | tr -d '\n' | gh secret set RELEASE_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$RELEASE_STORE_PASSWORD" | gh secret set RELEASE_STORE_PASSWORD --repo "$REPO"
printf '%s' "$RELEASE_KEY_PASSWORD"   | gh secret set RELEASE_KEY_PASSWORD   --repo "$REPO"
printf '%s' "$RELEASE_KEY_ALIAS"      | gh secret set RELEASE_KEY_ALIAS      --repo "$REPO"

echo "Done. CI will sign release builds with this key on the next run."
