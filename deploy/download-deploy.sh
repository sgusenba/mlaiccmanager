#!/usr/bin/env bash
# Download the latest built release of MLAICC Manager from GitHub and
# (re)deploy it if it's different from what's currently running. Runs ON
# the container — normally via mlaiccmanager-deploy.timer, but can be run
# manually or on demand.
#
# The jar (+ static/ + disciplines.json) is built by
# .github/workflows/release.yml on every push to main and published as a
# tarball attached to a rolling "latest" release. This script just
# downloads that one URL — no git, Maven, or JDK needed on the container,
# only a JRE to run the jar.
#
# Usage: deploy/download-deploy.sh [--force]
#   --force   redeploy even if the downloaded bundle is unchanged
#
# Configure via environment variables, or /opt/mlaiccmanager/download-deploy.env:
#   RELEASE_URL   Bundle URL to download        (default: https://github.com/sgusenba/mlaiccmanager/releases/download/latest/mlaiccmanager-dist.tar.gz)
#   RUN_DIR       Persistent runtime dir          (default: /opt/mlaiccmanager/run)
#   SERVICE_USER  Service account                 (default: mlaicc)
#   DEPLOY_SVC    systemd service to restart      (default: mlaiccmanager)
#   GITHUB_TOKEN  Optional bearer token, only needed if the repo is
#                 private (a public repo's release assets download with
#                 no auth at all)

set -euo pipefail

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

ENV_FILE="/opt/mlaiccmanager/download-deploy.env"
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  source "$ENV_FILE"
fi

RELEASE_URL="${RELEASE_URL:-https://github.com/sgusenba/mlaiccmanager/releases/download/latest/mlaiccmanager-dist.tar.gz}"
RUN_DIR="${RUN_DIR:-/opt/mlaiccmanager/run}"
SERVICE_USER="${SERVICE_USER:-mlaicc}"
DEPLOY_SVC="${DEPLOY_SVC:-mlaiccmanager}"
STATE_FILE="$RUN_DIR/.deployed-sha256"

log() { echo "[$(date '+%F %T')] $*"; }

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

CURL_ARGS=(-fsSL)
if [ -n "${GITHUB_TOKEN:-}" ]; then
  CURL_ARGS+=(-H "Authorization: Bearer $GITHUB_TOKEN")
fi

log "Downloading $RELEASE_URL"
curl "${CURL_ARGS[@]}" -o "$TMP_DIR/dist.tar.gz" "$RELEASE_URL"

NEW_SHA="$(sha256sum "$TMP_DIR/dist.tar.gz" | awk '{print $1}')"
OLD_SHA="$(cat "$STATE_FILE" 2>/dev/null || true)"

if [ "$NEW_SHA" = "$OLD_SHA" ] && [ "$FORCE" -eq 0 ]; then
  log "Already running this build ($NEW_SHA), nothing to do"
  exit 0
fi

log "Extracting"
mkdir -p "$TMP_DIR/extract"
tar -xzf "$TMP_DIR/dist.tar.gz" -C "$TMP_DIR/extract"

if [ ! -f "$TMP_DIR/extract/mlaiccmanager.jar" ]; then
  log "Downloaded bundle has no mlaiccmanager.jar, aborting deploy (leaving current deployment running)"
  exit 1
fi

log "Deploying to $RUN_DIR"
mkdir -p "$RUN_DIR/logs"
rm -rf "$RUN_DIR/static"
cp -r "$TMP_DIR/extract/static" "$RUN_DIR/static"
cp "$TMP_DIR/extract/disciplines.json" "$RUN_DIR/disciplines.json"
cp "$TMP_DIR/extract/mlaiccmanager.jar" "$RUN_DIR/mlaiccmanager.jar"
echo "$NEW_SHA" > "$STATE_FILE"

chown -R "$SERVICE_USER:$SERVICE_USER" "$RUN_DIR"

log "Restarting $DEPLOY_SVC"
systemctl restart "$DEPLOY_SVC"
log "Deployed build $NEW_SHA"
