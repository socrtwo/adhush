#!/usr/bin/env bash
# Install AdHush on a Raspberry Pi (reference platform and passthrough box).
# Installs system deps, enables pigpiod (IR TX + relay control), creates a
# venv, installs the package, and registers a systemd unit.
#
# Usage: sudo scripts/install-pi.sh [install-dir]   (default /opt/adhush)
set -euo pipefail

if [[ $(id -u) -ne 0 ]]; then
    echo "run as root: sudo $0" >&2
    exit 1
fi

INSTALL_DIR="${1:-/opt/adhush}"
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVICE_USER="${SUDO_USER:-pi}"

echo "==> system packages"
apt-get update
apt-get install -y --no-install-recommends \
    python3-venv python3-dev \
    ffmpeg \
    pigpio \
    cec-utils \
    lirc || apt-get install -y --no-install-recommends \
    python3-venv python3-dev ffmpeg pigpio cec-utils   # lirc is optional

echo "==> pigpiod (IR waveforms and the passthrough relay)"
systemctl enable --now pigpiod

echo "==> venv at ${INSTALL_DIR}"
mkdir -p "${INSTALL_DIR}"
python3 -m venv "${INSTALL_DIR}/venv"
"${INSTALL_DIR}/venv/bin/pip" install --upgrade pip
"${INSTALL_DIR}/venv/bin/pip" install "${REPO_DIR}[pi]" pigpio

if [[ ! -f "${REPO_DIR}/config/adhush.toml" ]]; then
    echo "==> no config/adhush.toml yet; copy one of:"
    echo "      ${REPO_DIR}/config/adhush.example.toml            (TV-controlled setups)"
    echo "      ${REPO_DIR}/config/adhush-passthrough.example.toml (inline box)"
fi

echo "==> systemd unit"
cat > /etc/systemd/system/adhush.service <<UNIT
[Unit]
Description=AdHush commercial mute
After=network-online.target pigpiod.service
Wants=pigpiod.service

[Service]
Type=simple
User=${SERVICE_USER}
WorkingDirectory=${REPO_DIR}
ExecStart=${INSTALL_DIR}/venv/bin/adhush run --config ${REPO_DIR}/config/adhush.toml
Restart=on-failure
RestartSec=5
# The relay wiring fails unmuted on stop (docs/hardware-passthrough-box.md).

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload

echo
echo "installed. next steps:"
echo "  1. cp config/adhush*.example.toml config/adhush.toml   # and edit"
echo "  2. ${INSTALL_DIR}/venv/bin/adhush probe"
echo "  3. ${INSTALL_DIR}/venv/bin/adhush calibrate"
echo "  4. sudo systemctl enable --now adhush"
