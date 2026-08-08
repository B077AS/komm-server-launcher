#!/usr/bin/env bash
# Removes the komm-server-launcher CLI. Run as root: sudo ./uninstall.sh
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Run this as root: sudo ./uninstall.sh" >&2
  exit 1
fi

if systemctl list-unit-files 2>/dev/null | grep -q '^komm-server\.service'; then
  echo "komm-server.service is still registered — run 'kommserver uninstall-service' first." >&2
  exit 1
fi

rm -f /usr/local/bin/kommserver
rm -f /opt/komm-server/komm-server-launcher.jar
echo "Launcher removed. /opt/komm-server (server jar, data, logs) was left untouched — remove manually if you want it gone too."
