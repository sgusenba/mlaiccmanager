#!/usr/bin/env bash
# Installs the MLAIC registration portal on a Linux machine (Debian or
# Ubuntu; on other distributions install Java 17+ and curl first), or
# updates an existing installation's files. Run as root:
#
#   curl -fsSLO https://github.com/sgusenba/mlaiccmanager/releases/download/portal-latest/portal-install.sh
#   sudo bash portal-install.sh --domain anmeldung.example.org --email you@example.org
#
# Options:
#   --domain NAME      the portal's public host name: installs Caddy, which gets a
#                      Let's Encrypt certificate (ports 80 and 443 must be reachable
#                      and the name must point to this machine)
#   --email ADDRESS    for Let's Encrypt (recommended with --domain)
#   --base-url URL     the public address when you run your own reverse proxy
#                      (instead of --domain)
#   --bind ADDRESS     where the portal listens (default 127.0.0.1); 0.0.0.0 only for
#                      a test in your own network, e.g. with --base-url http://192.168.1.10:8080
#   --port PORT        default 8080
#   --backup-key KEY   an age public key (age1...): turns on the daily encrypted backup
#
# What it sets up (see setup.md):
#   /opt/mlaic-portal/            mlaic-portal.jar (with the pages) and the scripts
#   /etc/mlaic-portal/portal.env  settings, written once from the options above
#   /var/lib/mlaic-portal/        the data (personal data), user mlaic-portal, 0700
#   mlaic-portal.service          the portal; before every start
#   mlaic-portal-update.service   checks for a new build and installs it
#   mlaic-portal-backup.timer     daily backup (with --backup-key)
#
# Running it again installs the current scripts and unit files and keeps the
# data and settings; options given then change those settings (e.g. --domain
# when moving from a test to the real name). Settings: PORTAL_RELEASE_URL (default: the portal-latest
# release of github.com/sgusenba/mlaiccmanager), GITHUB_TOKEN for a private repository.

set -euo pipefail

RELEASE_URL="${PORTAL_RELEASE_URL:-https://github.com/sgusenba/mlaiccmanager/releases/download/portal-latest}"
INSTALL_DIR="${PORTAL_INSTALL_DIR:-/opt/mlaic-portal}"
CONFIG_DIR="${PORTAL_CONFIG_DIR:-/etc/mlaic-portal}"
DATA_DIR=/var/lib/mlaic-portal
BACKUP_DIR=/var/backups/mlaic-portal
UNIT_DIR="${PORTAL_UNIT_DIR:-/etc/systemd/system}"
SERVICE_USER=mlaic-portal
SCRIPTS=(portal-update.sh portal-install.sh portal-backup.sh portal-restore.sh portal-cli.sh)
UNITS=(mlaic-portal.service mlaic-portal-update.service mlaic-portal-backup.service mlaic-portal-backup.timer)

DOMAIN="" EMAIL="" BASE_URL="" BIND="" PORT="" BACKUP_KEY=""

log() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
usage() { sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --domain) DOMAIN="${2:-}"; shift 2 ;;
    --email) EMAIL="${2:-}"; shift 2 ;;
    --base-url) BASE_URL="${2:-}"; shift 2 ;;
    --bind) BIND="${2:-}"; shift 2 ;;
    --port) PORT="${2:-}"; shift 2 ;;
    --backup-key) BACKUP_KEY="${2:-}"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown option $1" >&2; usage ;;
  esac
done

[ "$(id -u)" -eq 0 ] || die "run as root (sudo bash portal-install.sh ...)"
[ -z "$DOMAIN" ] || [[ "$DOMAIN" =~ ^[A-Za-z0-9.-]+$ ]] || die "--domain: a host name like anmeldung.example.org"
[ -z "$DOMAIN" ] || [ -z "$BASE_URL" ] || die "give either --domain or --base-url"
[ -z "$BASE_URL" ] || [[ "$BASE_URL" =~ ^https?://[^/]+$ ]] || die "--base-url: like https://anmeldung.example.org (no path, no trailing slash)"
[ -z "$PORT" ] || [[ "$PORT" =~ ^[0-9]+$ ]] || die "--port: a number"
[ -z "$BACKUP_KEY" ] || [[ "$BACKUP_KEY" =~ ^age1[a-z0-9]+$ ]] || die "--backup-key: an age public key (age1...)"

have() { command -v "$1" >/dev/null 2>&1; }
apt_install() {
  have apt-get || die "please install $* yourself (no apt-get here)"
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$@" >/dev/null
}

# --- prerequisites -----------------------------------------------------------------
log "Prerequisites"
if have apt-get; then
  apt-get update -qq
  have curl || apt_install curl ca-certificates
fi
have curl || die "curl is missing"
java_major() { java -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit}'; }
if ! have java || [ "$(java_major)" -lt 17 ]; then
  log "Installing Java"
  apt_install default-jre-headless
  { have java && [ "$(java_major)" -ge 17 ]; } || die "Java 17 or newer is needed (found: $(java -version 2>&1 | head -1))"
fi
[ -z "$BACKUP_KEY" ] || have age || apt_install age

# --- download ----------------------------------------------------------------------
log "Downloading the portal from $RELEASE_URL"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
CURL=(curl -fsSL --connect-timeout 15 --retry 2)
[ -z "${GITHUB_TOKEN:-}" ] || CURL+=(-H "Authorization: Bearer $GITHUB_TOKEN")
"${CURL[@]}" -o "$TMP_DIR/SHA256SUMS" "$RELEASE_URL/SHA256SUMS"
for f in mlaic-portal.jar "${SCRIPTS[@]}" "${UNITS[@]}" Caddyfile.example portal.env.example; do
  "${CURL[@]}" -o "$TMP_DIR/$f" "$RELEASE_URL/$f"
done
(cd "$TMP_DIR" && sha256sum --quiet -c SHA256SUMS) || die "checksums do not match: download broken, nothing installed"
java -jar "$TMP_DIR/mlaic-portal.jar" --version >/dev/null || die "the downloaded jar does not run"

# --- user, folders, files ----------------------------------------------------------
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  log "Creating user $SERVICE_USER"
  useradd --system --home-dir "$DATA_DIR" --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"
fi
install -d -m 0755 "$INSTALL_DIR" "$CONFIG_DIR"
install -d -m 0700 -o "$SERVICE_USER" -g "$SERVICE_USER" "$BACKUP_DIR"

log "Installing into $INSTALL_DIR"
for s in "${SCRIPTS[@]}"; do
  install -m 0755 "$TMP_DIR/$s" "$INSTALL_DIR/$s"
done
install -m 0644 "$TMP_DIR/Caddyfile.example" "$TMP_DIR/portal.env.example" "$INSTALL_DIR/"
if [ -f "$INSTALL_DIR/mlaic-portal.jar" ] && ! cmp -s "$TMP_DIR/mlaic-portal.jar" "$INSTALL_DIR/mlaic-portal.jar"; then
  cp -p "$INSTALL_DIR/mlaic-portal.jar" "$INSTALL_DIR/mlaic-portal.jar.prev"
fi
install -m 0644 "$TMP_DIR/mlaic-portal.jar" "$INSTALL_DIR/mlaic-portal.jar"
# Leftovers of the earlier tarball layout
rm -rf "$INSTALL_DIR/static" "$INSTALL_DIR/static.prev" "$INSTALL_DIR/deploy" "$INSTALL_DIR/portal-deploy.sh"

# Settings: written once; on a later run only the options given change them
ENV_FILE="$CONFIG_DIR/portal.env"
url=""
if [ -n "$DOMAIN" ]; then
  url="https://$DOMAIN"
elif [ -n "$BASE_URL" ]; then
  url="$BASE_URL"
fi
if [ ! -f "$ENV_FILE" ]; then
  log "Writing $ENV_FILE"
  install -m 0644 "$TMP_DIR/portal.env.example" "$ENV_FILE"
  url="${url:-http://localhost:${PORT:-8080}}"
  BIND="${BIND:-127.0.0.1}"
  PORT="${PORT:-8080}"
else
  log "Keeping the settings in $ENV_FILE"
fi
set_setting() {
  if grep -q "^$1=" "$ENV_FILE"; then
    sed -i "s#^$1=.*#$1=$2#" "$ENV_FILE"
  else
    echo "$1=$2" >> "$ENV_FILE"
  fi
  log "  $1=$2"
}
[ -z "$url" ] || set_setting PORTAL_BASE_URL "$url"
[ -z "$BIND" ] || set_setting PORTAL_BIND "$BIND"
[ -z "$PORT" ] || set_setting PORTAL_PORT "$PORT"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

if [ -n "$BACKUP_KEY" ]; then
  log "Backup key in ${BACKUP_AGE_RECIPIENTS:-$CONFIG_DIR/backup-recipients.txt}"
  grep -qxF "$BACKUP_KEY" "${BACKUP_AGE_RECIPIENTS:-$CONFIG_DIR/backup-recipients.txt}" 2>/dev/null \
    || echo "$BACKUP_KEY" >> "${BACKUP_AGE_RECIPIENTS:-$CONFIG_DIR/backup-recipients.txt}"
fi

# --- systemd -----------------------------------------------------------------------
log "Installing the services"
for u in "${UNITS[@]}"; do
  install -m 0644 "$TMP_DIR/$u" "$UNIT_DIR/$u"
done
# The unit allows the portal only loopback traffic; listening on all addresses needs more
DROPIN="$UNIT_DIR/mlaic-portal.service.d"
if [ "${PORTAL_BIND:-127.0.0.1}" != "127.0.0.1" ] && [ "${PORTAL_BIND:-}" != "localhost" ]; then
  install -d -m 0755 "$DROPIN"
  printf '# Written by portal-install.sh: PORTAL_BIND=%s, so clients come from other addresses\n[Service]\nIPAddressDeny=\n' "$PORTAL_BIND" > "$DROPIN/listen.conf"
else
  rm -f "$DROPIN/listen.conf"
fi
mkdir -p /etc/systemd/journald.conf.d
printf '# Written by portal-install.sh: logs (with client addresses) kept 14 days, as the privacy notice says\n[Journal]\nMaxRetentionSec=14day\n' \
  > /etc/systemd/journald.conf.d/mlaic-portal.conf
systemctl daemon-reload
systemctl restart systemd-journald || true
systemctl enable mlaic-portal.service >/dev/null
if [ -s "${BACKUP_AGE_RECIPIENTS:-$CONFIG_DIR/backup-recipients.txt}" ]; then
  systemctl enable --now mlaic-portal-backup.timer >/dev/null
  log "Daily backup on (mlaic-portal-backup.timer)"
fi

# --- Caddy (HTTPS) -----------------------------------------------------------------
if [ -n "$DOMAIN" ]; then
  if ! have caddy; then
    log "Installing Caddy"
    have apt-get || die "install Caddy yourself (https://caddyserver.com/docs/install), then run this again"
    apt_install debian-keyring debian-archive-keyring apt-transport-https gnupg
    curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
    apt-get update -qq
    apt_install caddy
  fi
  CADDYFILE=/etc/caddy/Caddyfile
  if [ -f "$CADDYFILE" ] && ! grep -q "^# mlaic-portal" "$CADDYFILE"; then
    cp "$CADDYFILE" "$CADDYFILE.before-mlaic-portal"
    log "Your previous Caddyfile is kept as $CADDYFILE.before-mlaic-portal"
  fi
  log "Writing $CADDYFILE for $DOMAIN"
  {
    echo "# mlaic-portal: written by portal-install.sh from $INSTALL_DIR/Caddyfile.example"
    sed -e "s/^anmeldung\.example\.org {/$DOMAIN {/" \
        -e "s/127\.0\.0\.1:8080/127.0.0.1:${PORTAL_PORT:-8080}/" \
        -e "s/^\temail .*/\temail ${EMAIL:-webmaster@$DOMAIN}/" \
        "$INSTALL_DIR/Caddyfile.example"
  } > "$CADDYFILE"
  mkdir -p /var/log/caddy && chown caddy:caddy /var/log/caddy 2>/dev/null || true
  caddy validate --config "$CADDYFILE" --adapter caddyfile >/dev/null 2>&1 || die "Caddy does not accept $CADDYFILE"
  systemctl enable caddy >/dev/null 2>&1 || true
  systemctl reload-or-restart caddy
  if have ufw && ufw status | grep -q "Status: active"; then
    ufw allow 80/tcp >/dev/null && ufw allow 443/tcp >/dev/null
    log "Firewall (ufw): ports 80 and 443 opened"
  fi
fi

# --- start -------------------------------------------------------------------------
log "Starting the portal"
systemctl restart mlaic-portal.service
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${PORTAL_PORT:-8080}/api/health" >/dev/null 2>&1; then
    log "$(java -jar "$INSTALL_DIR/mlaic-portal.jar" --version) runs at ${PORTAL_BASE_URL}"
    if ! grep -q '"username"' "$DATA_DIR/portal.json" 2>/dev/null; then
      echo
      echo "Next: create the first organiser account (asks for a password):"
      echo "  sudo $INSTALL_DIR/portal-cli.sh --add-organiser <name>"
    fi
    [ -s "${BACKUP_AGE_RECIPIENTS:-$CONFIG_DIR/backup-recipients.txt}" ] || {
      echo
      echo "Backups are off: run this again with --backup-key age1... (see setup.md)"
    }
    exit 0
  fi
  sleep 1
done
die "the portal does not answer; see: journalctl -u mlaic-portal -u mlaic-portal-update"
