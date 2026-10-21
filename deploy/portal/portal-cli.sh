#!/usr/bin/env bash
# Runs one of the portal's server commands as its user, with its settings:
#   sudo /opt/mlaic-portal/portal-cli.sh --add-organiser stefan
#   sudo /opt/mlaic-portal/portal-cli.sh --set-password stefan
#   sudo /opt/mlaic-portal/portal-cli.sh --reset-totp stefan
# The portal may keep running.

set -euo pipefail

ENV_FILE="${PORTAL_ENV_FILE:-/etc/mlaic-portal/portal.env}"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
INSTALL_DIR="${PORTAL_INSTALL_DIR:-/opt/mlaic-portal}"

umask 077 # files it creates hold personal data, like the service's
cd "$INSTALL_DIR"
exec runuser -u "${PORTAL_USER:-mlaic-portal}" -- env PORTAL_DATA_DIR="$PORTAL_DATA_DIR" \
  /usr/bin/java -jar "$INSTALL_DIR/mlaic-portal.jar" "$@"
