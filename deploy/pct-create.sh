#!/usr/bin/env bash
# Create the Proxmox LXC container for MLAICC Manager. Run this ON THE
# PROXMOX HOST (not inside a container), as root.
#
# Usage:
#   deploy/pct-create.sh
#
# Configure via environment variables (or a deploy/pct.env file next to
# this script, see deploy/pct.env.example). All have defaults except none
# are strictly required — review the defaults below before running.
#
#   CTID        Container ID                        (default: next free ID)
#   HOSTNAME    Container hostname                   (default: mlaiccmanager)
#   TEMPLATE    Template volume id; auto-downloaded  (default: debian-12-standard, latest)
#   STORAGE     Storage for the rootfs                (default: local-lvm)
#   TEMPLATE_STORAGE  Storage holding templates        (default: local)
#   DISK_GB     Root disk size in GB                  (default: 8)
#   CORES       vCPUs                                 (default: 1)
#   MEMORY_MB   RAM in MB                              (default: 1024)
#   SWAP_MB     Swap in MB                             (default: 512)
#   BRIDGE      Network bridge                         (default: vmbr0)
#   IP_CONFIG   net0 ip= value                         (default: dhcp)
#   GATEWAY     net0 gw= value, only used for static IP (optional)
#   UNPRIVILEGED  1 or 0                                (default: 1)
#   START_AFTER   1 to start after creation             (default: 1)
#   SSH_PUBKEY_FILE  Public key injected for root SSH login
#                     (default: first of ~/.ssh/id_ed25519.pub, ~/.ssh/id_rsa.pub
#                     found in the *Proxmox host's* root home)
#
# This should be the PUBLIC key of whatever machine will run deploy.sh /
# ssh into the container — usually your dev workstation, not the Proxmox
# host itself. If it's not already on the Proxmox host, copy it there
# first (e.g. `scp ~/.ssh/id_ed25519.pub root@proxmox-host:/root/.ssh/`)
# and point SSH_PUBKEY_FILE at it.
#
# No root password is set — access is key-only via the SSH public key
# above (pct enter <CTID> from the Proxmox host always works regardless).
#
# Defaults are sized for just running the app: GitHub Actions does the
# build, this container only downloads and runs the resulting jar (see
# deploy/setup.sh / deploy/download-deploy.sh).
#
# Example with a static IP:
#   IP_CONFIG=192.168.1.50/24 GATEWAY=192.168.1.1 deploy/pct-create.sh

set -euo pipefail

if ! command -v pct >/dev/null 2>&1; then
  echo "pct not found — this script must run on a Proxmox VE host." >&2
  exit 1
fi

if [ "$(id -u)" -ne 0 ]; then
  echo "This script must be run as root." >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/pct.env" ]; then
  # shellcheck disable=SC1090
  source "$SCRIPT_DIR/pct.env"
fi

HOSTNAME="${HOSTNAME:-mlaiccmanager}"
STORAGE="${STORAGE:-local-lvm}"
TEMPLATE_STORAGE="${TEMPLATE_STORAGE:-local}"
DISK_GB="${DISK_GB:-8}"
CORES="${CORES:-1}"
MEMORY_MB="${MEMORY_MB:-1024}"
SWAP_MB="${SWAP_MB:-512}"
BRIDGE="${BRIDGE:-vmbr0}"
IP_CONFIG="${IP_CONFIG:-dhcp}"
UNPRIVILEGED="${UNPRIVILEGED:-1}"
START_AFTER="${START_AFTER:-1}"

CTID="${CTID:-$(pvesh get /cluster/nextid)}"

if [ -z "${SSH_PUBKEY_FILE:-}" ]; then
  for candidate in "$HOME/.ssh/id_ed25519.pub" "$HOME/.ssh/id_rsa.pub"; do
    if [ -f "$candidate" ]; then
      SSH_PUBKEY_FILE="$candidate"
      break
    fi
  done
fi

if [ -z "${SSH_PUBKEY_FILE:-}" ] || [ ! -f "$SSH_PUBKEY_FILE" ]; then
  cat >&2 <<EOF
No SSH public key found. Set SSH_PUBKEY_FILE to an existing public key,
or generate one first, e.g.:
  ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519 -N ""
EOF
  exit 1
fi
echo "==> Using SSH public key: $SSH_PUBKEY_FILE"

if [ -z "${TEMPLATE:-}" ]; then
  echo "==> No TEMPLATE set, looking for a local debian-12-standard template"
  pveam update >/dev/null
  TEMPLATE="$(pveam list "$TEMPLATE_STORAGE" | awk '/debian-12-standard/{print $1}' | sort -V | tail -n1)"
  if [ -z "$TEMPLATE" ]; then
    echo "==> Not found locally, downloading the latest debian-12-standard template"
    LATEST="$(pveam available --section system | awk '/debian-12-standard/{print $2}' | sort -V | tail -n1)"
    pveam download "$TEMPLATE_STORAGE" "$LATEST"
    TEMPLATE="$TEMPLATE_STORAGE:vztmpl/$LATEST"
  fi
fi

echo "==> Creating CT $CTID ($HOSTNAME) from $TEMPLATE"

NET0="name=eth0,bridge=$BRIDGE"
if [ "$IP_CONFIG" = "dhcp" ]; then
  NET0="$NET0,ip=dhcp"
else
  NET0="$NET0,ip=$IP_CONFIG"
  if [ -n "${GATEWAY:-}" ]; then
    NET0="$NET0,gw=$GATEWAY"
  fi
fi

pct create "$CTID" "$TEMPLATE" \
  --hostname "$HOSTNAME" \
  --cores "$CORES" \
  --memory "$MEMORY_MB" \
  --swap "$SWAP_MB" \
  --rootfs "$STORAGE:$DISK_GB" \
  --net0 "$NET0" \
  --unprivileged "$UNPRIVILEGED" \
  --features nesting=0 \
  --ssh-public-keys "$SSH_PUBKEY_FILE" \
  --onboot 1

if [ "$START_AFTER" -eq 1 ]; then
  echo "==> Starting CT $CTID"
  pct start "$CTID"
  sleep 3
  echo "==> Container IP:"
  pct exec "$CTID" -- ip -4 -o addr show eth0 | awk '{print $4}'
fi

cat <<EOF

==> Done. CT $CTID ($HOSTNAME) created.

Root SSH login is key-only (no password set), using:
  $SSH_PUBKEY_FILE

Next steps (from the machine holding the matching private key):
  1. scp -r deploy root@<container-ip>:/tmp/deploy
  2. ssh root@<container-ip> 'bash /tmp/deploy/setup.sh'

setup.sh clones the repo, builds it, and starts the app — no further
manual deploy step needed. It also installs a timer that re-checks
GitHub every 5 minutes and redeploys automatically.
EOF
