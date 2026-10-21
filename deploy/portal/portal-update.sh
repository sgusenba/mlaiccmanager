#!/usr/bin/env bash
# Updates the registration portal to the latest build, if there is one. Runs
# as root before every start of the portal (mlaic-portal-update.service,
# pulled in by mlaic-portal.service), and can be run by hand:
#
#   sudo /opt/mlaic-portal/portal-update.sh             check and update
#   sudo /opt/mlaic-portal/portal-update.sh --rollback  back to the previous jar, and stop updating
#
# It downloads SHA256SUMS from the "portal-latest" release and compares it
# with what is installed. A new jar is downloaded, checked against its
# checksum, test-run (--version) and only then put in place, after a backup
# of the data (if backups are set up); the old one is kept as
# mlaic-portal.jar.prev. The scripts here are updated the same way.
#
# It never stops the portal from starting: without a connection, or when
# anything goes wrong, the installed version starts. To freeze the version
# (e.g. in the last days before a deadline), create /etc/mlaic-portal/no-auto-update
# or set PORTAL_AUTO_UPDATE=false in /etc/mlaic-portal/portal.env.
#
# Settings (environment, or /etc/mlaic-portal/portal.env):
#   PORTAL_RELEASE_URL  default https://github.com/sgusenba/mlaiccmanager/releases/download/portal-latest
#   PORTAL_INSTALL_DIR  default /opt/mlaic-portal
#   GITHUB_TOKEN        only if the repository is private

set -uo pipefail

ENV_FILE="${PORTAL_ENV_FILE:-/etc/mlaic-portal/portal.env}"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi
RELEASE_URL="${PORTAL_RELEASE_URL:-https://github.com/sgusenba/mlaiccmanager/releases/download/portal-latest}"
INSTALL_DIR="${PORTAL_INSTALL_DIR:-/opt/mlaic-portal}"
CONFIG_DIR="$(dirname "$ENV_FILE")"
SERVICE_USER="${PORTAL_USER:-mlaic-portal}"
JAVA="${PORTAL_JAVA:-java}"
JAR="$INSTALL_DIR/mlaic-portal.jar"
# Scripts this one keeps up to date (itself included)
SCRIPTS=(portal-update.sh portal-install.sh portal-backup.sh portal-restore.sh portal-cli.sh)

log() { echo "[portal-update] $*"; }

if [ "${1:-}" = "--rollback" ]; then
  [ -f "$JAR.prev" ] || { log "No previous version ($JAR.prev)"; exit 1; }
  mv -f "$JAR.prev" "$JAR"
  touch "$CONFIG_DIR/no-auto-update"
  log "Back to the previous version: $("$JAVA" -jar "$JAR" --version 2>/dev/null)"
  log "Automatic updates are off until $CONFIG_DIR/no-auto-update is deleted. Restart: systemctl restart mlaic-portal"
  exit 0
fi

if [ -f "$CONFIG_DIR/no-auto-update" ] || [ "${PORTAL_AUTO_UPDATE:-true}" = "false" ]; then
  log "Automatic updates are off; starting the installed version"
  exit 0
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
CURL=(curl -fsSL --connect-timeout 10 --max-time 300)
if [ -n "${GITHUB_TOKEN:-}" ]; then
  CURL+=(-H "Authorization: Bearer $GITHUB_TOKEN")
fi

if ! "${CURL[@]}" -o "$TMP_DIR/SHA256SUMS" "$RELEASE_URL/SHA256SUMS"; then
  log "Cannot reach $RELEASE_URL; starting the installed version"
  exit 0
fi

# The checksum of FILE in SHA256SUMS
listed() { awk -v f="$1" '$2 == f || $2 == "*" f {print $1}' "$TMP_DIR/SHA256SUMS"; }
installed() { [ -f "$1" ] && sha256sum "$1" | awk '{print $1}'; }

# Downloads FILE into the temporary folder and checks it; prints nothing, fails on a mismatch
fetch() {
  "${CURL[@]}" -o "$TMP_DIR/$1" "$RELEASE_URL/$1" || return 1
  [ "$(installed "$TMP_DIR/$1")" = "$(listed "$1")" ] || { log "Checksum of $1 does not match: not installed"; return 1; }
}

want="$(listed mlaic-portal.jar)"
if [ -z "$want" ]; then
  log "SHA256SUMS lists no mlaic-portal.jar; starting the installed version"
  exit 0
fi

if [ "$want" = "$(installed "$JAR")" ]; then
  log "Up to date: $("$JAVA" -jar "$JAR" --version 2>/dev/null)"
else
  if fetch mlaic-portal.jar && new_version="$(timeout 60 "$JAVA" -jar "$TMP_DIR/mlaic-portal.jar" --version 2>/dev/null)"; then
    if [ -f "$JAR" ] && [ -x "$INSTALL_DIR/portal-backup.sh" ] && [ -s "${BACKUP_AGE_RECIPIENTS:-$CONFIG_DIR/backup-recipients.txt}" ] \
        && ! runuser -u "$SERVICE_USER" -- "$INSTALL_DIR/portal-backup.sh" pre-update; then
      log "Backup before the update failed: not updating, starting the installed version"
      exit 0
    fi
    old_version="$([ -f "$JAR" ] && "$JAVA" -jar "$JAR" --version 2>/dev/null)"
    [ -f "$JAR" ] && cp -p "$JAR" "$JAR.prev"
    install -m 0644 "$TMP_DIR/mlaic-portal.jar" "$JAR.new" && mv -f "$JAR.new" "$JAR"
    log "Updated: ${old_version:-none} -> $new_version"
  else
    log "The new build could not be downloaded or does not run; starting the installed version"
  fi
fi

for s in "${SCRIPTS[@]}"; do
  want="$(listed "$s")"
  if [ -n "$want" ] && [ "$want" != "$(installed "$INSTALL_DIR/$s")" ] && fetch "$s"; then
    install -m 0755 "$TMP_DIR/$s" "$INSTALL_DIR/$s.new" && mv -f "$INSTALL_DIR/$s.new" "$INSTALL_DIR/$s"
    log "Updated $s"
  fi
done

# Unit files and the Caddyfile are not changed while the portal starts; say when they differ
for unit in mlaic-portal.service mlaic-portal-update.service mlaic-portal-backup.service mlaic-portal-backup.timer; do
  want="$(listed "$unit")"
  if [ -n "$want" ] && [ -f "/etc/systemd/system/$unit" ] && [ "$want" != "$(installed "/etc/systemd/system/$unit")" ]; then
    log "Note: a newer $unit is available; install it with: sudo $INSTALL_DIR/portal-install.sh"
  fi
done
exit 0
