#!/usr/bin/env bash
# Restores a backup written by portal-backup.sh.
#
#   portal-restore.sh check   BACKUP [-i KEY]       decrypt and list it; changes nothing
#   portal-restore.sh extract BACKUP DIR [-i KEY]   unpack into a new, empty DIR (the restore drill)
#   portal-restore.sh install BACKUP [-i KEY]       on the VM, as root: stop the portal, put the
#                                                   backup in place of its data, start it again
#
# BACKUP is a .tar.gz.age file (then KEY, the age private key file, is needed)
# or a .tar.gz already decrypted elsewhere. The private key belongs offline:
# decrypt on your own computer (age -d -i key.txt -o x.tar.gz x.tar.gz.age),
# copy the .tar.gz to the VM, install it, delete it.
#
# install keeps the data it replaces in PORTAL_DATA_DIR.before-restore-<time>;
# delete that once the portal works (it holds personal data). Meets past their
# deletion date in an older backup are deleted again by the portal a minute
# after it starts.

set -euo pipefail

ENV_FILE="${PORTAL_ENV_FILE:-/etc/mlaic-portal/portal.env}"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi
DATA_DIR="${PORTAL_DATA_DIR:-/var/lib/mlaic-portal}"
PORT="${PORTAL_PORT:-8080}"
SERVICE="${PORTAL_SERVICE:-mlaic-portal}"
SERVICE_USER="${PORTAL_USER:-mlaic-portal}"

log() { echo "[$(date '+%F %T')] $*"; }
die() { log "ERROR: $*" >&2; exit 1; }
usage() { sed -n '4,9p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }

[ $# -ge 2 ] || usage
CMD="$1"; BACKUP="$2"; shift 2
TARGET=""
if [ "$CMD" = "extract" ]; then
  [ $# -ge 1 ] || usage
  TARGET="$1"; shift
fi
KEY=""
while [ $# -gt 0 ]; do
  case "$1" in
    -i) [ $# -ge 2 ] || usage; KEY="$2"; shift 2 ;;
    *) usage ;;
  esac
done
[ -f "$BACKUP" ] || die "no file $BACKUP"
umask 077 # personal data: readable by the owner only

# The archive, decrypted, on standard output
archive() {
  case "$BACKUP" in
    *.age) [ -n "$KEY" ] || die "$BACKUP is encrypted: give the private key with -i KEY"
           age -d -i "$KEY" "$BACKUP" ;;
    *) cat "$BACKUP" ;;
  esac
}

# Unpacks into the empty directory $1 and checks that it looks like portal data
unpack() {
  local dir="$1"
  archive | tar -C "$dir" --no-same-owner --no-same-permissions -xzf -
  [ -f "$dir/portal.json" ] || die "the backup has no portal.json"
  find "$dir" -name '*.json' -print0 | while IFS= read -r -d '' f; do
    python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$f" || die "$f is not valid JSON"
  done
}

case "$CMD" in
  check)
    archive | tar -tzvf -
    ;;
  extract)
    [ ! -e "$TARGET" ] || [ -z "$(ls -A "$TARGET")" ] || die "$TARGET is not empty"
    mkdir -p "$TARGET"
    unpack "$TARGET"
    log "Unpacked into $TARGET; try it with:"
    log "  PORTAL_DATA_DIR=$TARGET PORTAL_PORT=8099 java -jar mlaic-portal.jar"
    ;;
  install)
    [ "$(id -u)" -eq 0 ] || die "install must run as root"
    NEW="$DATA_DIR.restore-$$"
    OLD="$DATA_DIR.before-restore-$(date +%Y%m%dT%H%M%S)"
    mkdir -m 0700 "$NEW"
    trap 'rm -rf "$NEW"' EXIT
    unpack "$NEW"
    chown -R "$SERVICE_USER:$SERVICE_USER" "$NEW"
    log "Stopping $SERVICE"
    systemctl stop "$SERVICE"
    [ -d "$DATA_DIR" ] && mv "$DATA_DIR" "$OLD"
    mv "$NEW" "$DATA_DIR"
    trap - EXIT
    log "Starting $SERVICE"
    systemctl start "$SERVICE"
    for _ in $(seq 1 30); do
      if curl -fsS "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; then
        log "Restored; the portal answers. The data it had before is in $OLD: delete it once all is well."
        exit 0
      fi
      sleep 1
    done
    die "the portal does not answer after the restore; the data it had before is in $OLD (journalctl -u $SERVICE)"
    ;;
  *) usage ;;
esac
