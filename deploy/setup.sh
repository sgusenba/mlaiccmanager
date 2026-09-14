#!/usr/bin/env bash
# One-time target setup for MLAICC Manager: installs a JRE, creates the
# service user/directories, installs deploy/download-deploy.sh and the
# systemd units, then runs an initial download + start.
#
# Builds happen in GitHub Actions (.github/workflows/release.yml), not
# here — this container only ever downloads a prebuilt jar, so it needs
# no git, Maven, or JDK.
#
# Usage (from your dev machine, or wherever holds this repo checkout):
#   scp -r deploy root@<container-ip>:/tmp/deploy
#   ssh root@<container-ip> 'bash /tmp/deploy/setup.sh'
#
# Or run directly on the target if you've cloned the repo there:
#   sudo deploy/setup.sh
#
# After this, the container looks after itself: mlaiccmanager-deploy.timer
# checks GitHub every 5 minutes (see deploy/mlaiccmanager-deploy.timer)
# and redeploys whenever a new build is published. To deploy immediately
# after a push instead of waiting for the timer:
#   ssh root@<container-ip> systemctl start mlaiccmanager-deploy.service
#
# Installs the systemd units under the fixed names mlaiccmanager.service /
# mlaiccmanager-deploy.service / mlaiccmanager-deploy.timer.
#
# Configure via environment variables (all optional):
#   DEPLOY_PATH   Base app directory              (default: /opt/mlaiccmanager)
#   SERVICE_USER  Service account                  (default: mlaicc)
#   RELEASE_URL   Release bundle to download       (default: https://github.com/sgusenba/mlaiccmanager/releases/download/latest/mlaiccmanager-dist.tar.gz)
#   JRE_PACKAGE   Adoptium JRE package to install  (default: temurin-21-jre)
#   GITHUB_TOKEN  Optional, only needed for a private repo

set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "This script must be run as root (use sudo)." >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

DEPLOY_PATH="${DEPLOY_PATH:-/opt/mlaiccmanager}"
DEPLOY_SVC="mlaiccmanager"
SERVICE_USER="${SERVICE_USER:-mlaicc}"
RELEASE_URL="${RELEASE_URL:-https://github.com/sgusenba/mlaiccmanager/releases/download/latest/mlaiccmanager-dist.tar.gz}"
JRE_PACKAGE="${JRE_PACKAGE:-temurin-21-jre}"

echo "==> Installing prerequisites"
apt-get update -qq
apt-get install -y -qq wget curl gnupg apt-transport-https ca-certificates >/dev/null

if ! command -v java >/dev/null 2>&1; then
  echo "==> Adding Adoptium apt repository"
  wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
  echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print $2}' /etc/os-release) main" \
    > /etc/apt/sources.list.d/adoptium.list
  apt-get update -qq
  echo "==> Installing $JRE_PACKAGE"
  apt-get install -y -qq "$JRE_PACKAGE" >/dev/null
else
  echo "==> java already present ($(java -version 2>&1 | head -n1)), skipping JRE install"
fi

echo "==> Creating service user '$SERVICE_USER'"
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  useradd -r -m -d "$DEPLOY_PATH/run" -s /usr/sbin/nologin "$SERVICE_USER"
else
  echo "    user already exists, skipping"
fi

echo "==> Creating $DEPLOY_PATH layout"
mkdir -p "$DEPLOY_PATH/deploy" "$DEPLOY_PATH/run/logs"
chown -R "$SERVICE_USER:$SERVICE_USER" "$DEPLOY_PATH/run"

echo "==> Installing deploy/download-deploy.sh"
if [ ! -f "$SCRIPT_DIR/download-deploy.sh" ]; then
  echo "download-deploy.sh not found next to setup.sh (in $SCRIPT_DIR)" >&2
  exit 1
fi
cp "$SCRIPT_DIR/download-deploy.sh" "$DEPLOY_PATH/deploy/download-deploy.sh"
chmod +x "$DEPLOY_PATH/deploy/download-deploy.sh"

cat > "$DEPLOY_PATH/download-deploy.env" <<EOF
RELEASE_URL=$RELEASE_URL
RUN_DIR=$DEPLOY_PATH/run
SERVICE_USER=$SERVICE_USER
DEPLOY_SVC=$DEPLOY_SVC
EOF
if [ -n "${GITHUB_TOKEN:-}" ]; then
  echo "GITHUB_TOKEN=$GITHUB_TOKEN" >> "$DEPLOY_PATH/download-deploy.env"
  chmod 600 "$DEPLOY_PATH/download-deploy.env"
fi

echo "==> Installing systemd units"
for unit in mlaiccmanager.service mlaiccmanager-deploy.service mlaiccmanager-deploy.timer; do
  if [ ! -f "$SCRIPT_DIR/$unit" ]; then
    echo "$unit not found next to setup.sh (in $SCRIPT_DIR)" >&2
    exit 1
  fi
  sed -e "s#/opt/mlaiccmanager#$DEPLOY_PATH#g" \
      -e "s#User=mlaicc#User=$SERVICE_USER#" \
      -e "s#Group=mlaicc#Group=$SERVICE_USER#" \
      "$SCRIPT_DIR/$unit" > "/etc/systemd/system/$unit"
done

systemctl daemon-reload
systemctl enable "$DEPLOY_SVC" >/dev/null
systemctl enable --now "$DEPLOY_SVC-deploy.timer" >/dev/null

echo "==> Running initial download + deploy"
"$DEPLOY_PATH/deploy/download-deploy.sh" --force

cat <<EOF

==> Setup complete.

$DEPLOY_SVC is running, downloaded from:
  $RELEASE_URL
$DEPLOY_SVC-deploy.timer will re-check every 5 minutes and redeploy
automatically whenever GitHub Actions publishes a new build.

Useful commands on the container:
  systemctl status $DEPLOY_SVC
  systemctl start $DEPLOY_SVC-deploy.service   # deploy right now
  journalctl -u $DEPLOY_SVC-deploy.service -f  # watch a deploy run
  journalctl -u $DEPLOY_SVC -f                 # app logs
EOF
