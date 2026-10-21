#!/usr/bin/env bash
# Updates the registration portal on the VM to the latest build, run by hand
# as root:  sudo /opt/mlaic-portal/portal-deploy.sh [--force]
#
# The bundle (jar + static/ + these scripts) is built by
# .github/workflows/portal-release.yml and published on the rolling
# "portal-latest" release, next to its SHA-256 checksum. The script:
#   1. downloads both and checks the checksum (and that the bundle is complete),
#   2. stops if this build is already deployed (unless --force),
#   3. takes a backup (portal-backup.sh pre-deploy); no backup, no update,
#   4. installs static/ and the scripts, the jar last, keeping the old ones as *.prev,
#   5. restarts the portal and waits for /api/health; if it does not answer,
#      puts the old jar and static/ back and restarts again.
# A restart logs everyone out (sessions are kept in memory): update outside
# the busy days before a deadline. Unit files and the Caddyfile are never
# changed; if the bundle's differ from the installed ones, it says so.
#
# Settings (environment, or /etc/mlaic-portal/portal.env):
#   RELEASE_URL   default https://github.com/sgusenba/mlaiccmanager/releases/download/portal-latest/mlaic-portal-dist.tar.gz
#   INSTALL_DIR   default /opt/mlaic-portal
#   GITHUB_TOKEN  only if the repository is private

set -euo pipefail

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

ENV_FILE="${PORTAL_ENV_FILE:-/etc/mlaic-portal/portal.env}"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi
RELEASE_URL="${RELEASE_URL:-https://github.com/sgusenba/mlaiccmanager/releases/download/portal-latest/mlaic-portal-dist.tar.gz}"
INSTALL_DIR="${INSTALL_DIR:-/opt/mlaic-portal}"
SERVICE="${PORTAL_SERVICE:-mlaic-portal}"
SERVICE_USER="${PORTAL_USER:-mlaic-portal}"
PORT="${PORTAL_PORT:-8080}"
STATE_FILE="$INSTALL_DIR/.deployed-sha256"
SCRIPTS=(portal-deploy.sh portal-backup.sh portal-restore.sh portal-cli.sh)

log() { echo "[$(date '+%F %T')] $*"; }
die() { log "ERROR: $*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root (sudo)"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

CURL_ARGS=(-fsSL --retry 3)
if [ -n "${GITHUB_TOKEN:-}" ]; then
  CURL_ARGS+=(-H "Authorization: Bearer $GITHUB_TOKEN")
fi

log "Downloading $RELEASE_URL"
curl "${CURL_ARGS[@]}" -o "$TMP_DIR/dist.tar.gz" "$RELEASE_URL"
curl "${CURL_ARGS[@]}" -o "$TMP_DIR/dist.tar.gz.sha256" "$RELEASE_URL.sha256"
NEW_SHA="$(sha256sum "$TMP_DIR/dist.tar.gz" | awk '{print $1}')"
[ "$NEW_SHA" = "$(awk '{print $1}' "$TMP_DIR/dist.tar.gz.sha256")" ] || die "checksum mismatch, download broken: nothing changed"

if [ "$NEW_SHA" = "$(cat "$STATE_FILE" 2>/dev/null || true)" ] && [ "$FORCE" -eq 0 ]; then
  log "Already running this build ($NEW_SHA), nothing to do"
  exit 0
fi

mkdir "$TMP_DIR/extract"
tar -xzf "$TMP_DIR/dist.tar.gz" -C "$TMP_DIR/extract"
NEW="$TMP_DIR/extract"
for f in mlaic-portal.jar static/login/index.html static/sheet/index.html static/admin/index.html; do
  [ -s "$NEW/$f" ] || die "the bundle has no $f: nothing changed"
done
[ -f "$NEW/build-info.json" ] && log "Build: $(cat "$NEW/build-info.json")"

if [ -f "$INSTALL_DIR/mlaic-portal.jar" ]; then
  log "Backup before the update"
  runuser -u "$SERVICE_USER" -- "$INSTALL_DIR/portal-backup.sh" pre-deploy || die "backup failed: nothing changed"
fi

log "Installing into $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
if [ -f "$INSTALL_DIR/mlaic-portal.jar" ]; then
  cp -p "$INSTALL_DIR/mlaic-portal.jar" "$INSTALL_DIR/mlaic-portal.jar.prev"
  rm -rf "$INSTALL_DIR/static.prev"
  [ -d "$INSTALL_DIR/static" ] && cp -a "$INSTALL_DIR/static" "$INSTALL_DIR/static.prev"
fi
rm -rf "$INSTALL_DIR/static.new"
cp -r "$NEW/static" "$INSTALL_DIR/static.new"
rm -rf "$INSTALL_DIR/static"
mv "$INSTALL_DIR/static.new" "$INSTALL_DIR/static"
for s in "${SCRIPTS[@]}"; do
  if [ -f "$NEW/deploy/$s" ]; then
    install -m 0755 "$NEW/deploy/$s" "$INSTALL_DIR/$s.new" && mv -f "$INSTALL_DIR/$s.new" "$INSTALL_DIR/$s"
  fi
done
# The bundle's unit files and Caddyfile, for comparing with the installed ones
rm -rf "$INSTALL_DIR/deploy"
[ -d "$NEW/deploy" ] && cp -r "$NEW/deploy" "$INSTALL_DIR/deploy"
cp "$NEW/mlaic-portal.jar" "$INSTALL_DIR/mlaic-portal.jar.new"
mv -f "$INSTALL_DIR/mlaic-portal.jar.new" "$INSTALL_DIR/mlaic-portal.jar"
chown -R root:root "$INSTALL_DIR"
chmod -R a+rX,go-w "$INSTALL_DIR"

healthy() {
  for _ in $(seq 1 40); do
    curl -fsS "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

log "Restarting $SERVICE"
systemctl restart "$SERVICE"
if ! healthy; then
  if [ -f "$INSTALL_DIR/mlaic-portal.jar.prev" ]; then
    log "The new build does not answer: going back to the previous one"
    mv -f "$INSTALL_DIR/mlaic-portal.jar.prev" "$INSTALL_DIR/mlaic-portal.jar"
    if [ -d "$INSTALL_DIR/static.prev" ]; then
      rm -rf "$INSTALL_DIR/static"
      mv "$INSTALL_DIR/static.prev" "$INSTALL_DIR/static"
    fi
    systemctl restart "$SERVICE"
    healthy && die "build $NEW_SHA failed to start; the previous one runs again (journalctl -u $SERVICE)"
  fi
  die "the portal does not answer (journalctl -u $SERVICE)"
fi
echo "$NEW_SHA" > "$STATE_FILE"
log "Deployed build $NEW_SHA"

for unit in mlaic-portal.service mlaic-portal-backup.service mlaic-portal-backup.timer; do
  if [ -f "$NEW/deploy/$unit" ] && [ -f "/etc/systemd/system/$unit" ] && ! cmp -s "$NEW/deploy/$unit" "/etc/systemd/system/$unit"; then
    log "Note: the bundle's $unit differs from /etc/systemd/system/$unit; compare: diff $INSTALL_DIR/deploy/$unit /etc/systemd/system/$unit"
  fi
done
