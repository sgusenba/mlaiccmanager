#!/usr/bin/env bash
# One backup of the registration portal's data: packs portal.json and meets/,
# encrypts the archive with age (to the public keys in BACKUP_AGE_RECIPIENTS;
# the private key stays offline, so a stolen server or backup disk gives
# nothing away), keeps it in BACKUP_DIR and, if BACKUP_COPY_COMMAND is set,
# copies it somewhere else with that command (rclone, scp, ...). Copies in
# BACKUP_DIR older than BACKUP_KEEP_LOCAL_DAYS are deleted; the other place
# should delete its own after 30 days (see setup.md).
#
# Runs as the portal's user, normally via mlaic-portal-backup.service:
#   portal-backup.sh [LABEL]     LABEL goes into the file name (default: daily)
# The portal keeps running: every save is written to a .tmp file and renamed
# over the old one, so each file in the archive is one complete version.
#
# Settings: /etc/mlaic-portal/portal.env (or the environment), see
# portal.env.example. Restore with portal-restore.sh.

set -euo pipefail

ENV_FILE="${PORTAL_ENV_FILE:-/etc/mlaic-portal/portal.env}"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

DATA_DIR="${PORTAL_DATA_DIR:-/var/lib/mlaic-portal}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/mlaic-portal}"
RECIPIENTS="${BACKUP_AGE_RECIPIENTS:-/etc/mlaic-portal/backup-recipients.txt}"
KEEP_DAYS="${BACKUP_KEEP_LOCAL_DAYS:-30}"
COPY_COMMAND="${BACKUP_COPY_COMMAND:-}"
LABEL="${1:-daily}"

log() { echo "[$(date '+%F %T')] $*"; }
die() { log "ERROR: $*" >&2; exit 1; }

umask 077
[[ "$LABEL" =~ ^[a-z0-9-]+$ ]] || die "label must be lower-case letters, digits and dashes"
[ -s "$RECIPIENTS" ] || die "no age recipients in $RECIPIENTS"
[ -d "$DATA_DIR" ] || die "no data directory $DATA_DIR"
mkdir -p "$BACKUP_DIR"

# meets/ goes into the archive before portal.json: the meet-id counter in
# portal.json is then at least as new as every meet in the archive, so a
# restored portal never hands out a meet id twice
members=()
[ -d "$DATA_DIR/meets" ] && members+=(meets)
[ -f "$DATA_DIR/portal.json" ] && members+=(portal.json)
if [ ${#members[@]} -eq 0 ]; then
  log "Nothing to back up yet in $DATA_DIR"
  exit 0
fi

NAME="mlaic-portal-$(date -u +%Y%m%dT%H%M%SZ)-$LABEL.tar.gz.age"
PART="$BACKUP_DIR/.$NAME.part"
trap 'rm -f "$PART"' EXIT

# Unencrypted data never touches the disk: tar | age
set +o pipefail
tar -C "$DATA_DIR" --exclude='*.tmp' --ignore-failed-read --warning=no-file-changed \
  -czf - "${members[@]}" | age -R "$RECIPIENTS" -o "$PART"
status=("${PIPESTATUS[@]}")
set -o pipefail
# tar: 1 = a file changed while it was read (an audit line appended), harmless
[ "${status[0]}" -le 1 ] || die "tar failed (${status[0]})"
[ "${status[1]}" -eq 0 ] || die "age failed (${status[1]})"
[ -s "$PART" ] || die "empty backup"
mv "$PART" "$BACKUP_DIR/$NAME"
log "Backup $BACKUP_DIR/$NAME ($(du -h "$BACKUP_DIR/$NAME" | cut -f1))"

find "$BACKUP_DIR" -maxdepth 1 -name 'mlaic-portal-*.tar.gz.age' -mtime +"$KEEP_DAYS" -print -delete \
  | sed 's/^/Deleted old local backup /'

if [ -n "$COPY_COMMAND" ]; then
  # {} in the command stands for the file; without it the file is appended
  if [[ "$COPY_COMMAND" == *"{}"* ]]; then
    command="${COPY_COMMAND//\{\}/$(printf %q "$BACKUP_DIR/$NAME")}"
  else
    command="$COPY_COMMAND $(printf %q "$BACKUP_DIR/$NAME")"
  fi
  bash -c "$command" || die "copying the backup elsewhere failed (the local copy is kept)"
  log "Copied elsewhere"
else
  log "BACKUP_COPY_COMMAND is empty: local copy only"
fi
