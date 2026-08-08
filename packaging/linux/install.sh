#!/usr/bin/env bash
# Installs the komm-server-launcher CLI. Run as root: sudo ./install.sh
#
# When this script is run from a plain "build from source" tarball (just the launcher
# jar + these two scripts), it installs the launcher only, then — interactively — either
# points you at `kommserver update` for a fresh install, or lets you adopt a komm-server
# that's only ever been run as a bare jar (pre-launcher, or same-vintage but never wired
# up) by handing it the existing jar's path. See adopt_existing_install below.
#
# When run from the tarball komm-server's own release workflow builds (which also
# seeds komm-server.jar and a SERVER_VERSION file alongside this script — mirrors the
# Windows installer bundling a server jar so the .exe produces a *running* service, not
# just an installed CLI), it also registers and starts the service immediately.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Run this as root: sudo ./install.sh" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR=/opt/komm-server
CONFIG_DIR=/etc/komm-server
CONFIG_FILE="$CONFIG_DIR/launcher.properties"

mkdir -p "$INSTALL_DIR"
install -m 644 "$SCRIPT_DIR/komm-server-launcher.jar" "$INSTALL_DIR/komm-server-launcher.jar"

cat > /usr/local/bin/kommserver <<'EOF'
#!/usr/bin/env bash
exec java -jar /opt/komm-server/komm-server-launcher.jar "$@"
EOF
chmod +x /usr/local/bin/kommserver

if [[ -f "$SCRIPT_DIR/komm-server.jar" ]]; then
  install -m 644 "$SCRIPT_DIR/komm-server.jar" "$INSTALL_DIR/komm-server.jar"
  if [[ -f "$SCRIPT_DIR/SERVER_VERSION" ]]; then
    mkdir -p "$CONFIG_DIR"
    echo "server.version=$(cat "$SCRIPT_DIR/SERVER_VERSION")" > "$CONFIG_FILE"
  fi
  echo "Installed with a bundled komm-server $( [[ -f "$SCRIPT_DIR/SERVER_VERSION" ]] && cat "$SCRIPT_DIR/SERVER_VERSION" ) jar. Registering the service..."
  kommserver install-service
  kommserver start
  echo "Done. 'kommserver status' to check on it."
  exit 0
fi

echo "Installed the kommserver CLI (no bundled server jar in this tarball)."

# ── Adopt an existing bare-jar installation ─────────────────────────────────────
#
# Only offered when there's an actual terminal to ask on — a piped/unattended run
# (curl | sudo bash, CI, etc.) falls straight through to the plain message below,
# same as before this existed.
EXISTING_JAR=""
if [[ -t 0 ]]; then
  echo
  echo "Is this a fresh install, or are you adopting a komm-server that's already"
  echo "running as a bare jar (started by hand or by your own unit file, no launcher)?"
  read -rp "Path to the existing jar (leave blank for a fresh install): " EXISTING_JAR
fi

if [[ -n "$EXISTING_JAR" ]]; then
  if [[ ! -f "$EXISTING_JAR" ]]; then
    echo "No file found at $EXISTING_JAR — aborting adoption. Nothing else was changed." >&2
    exit 1
  fi
  EXISTING_JAR="$(cd "$(dirname "$EXISTING_JAR")" && pwd)/$(basename "$EXISTING_JAR")"
  JAR_DIR="$(dirname "$EXISTING_JAR")"

  mkdir -p "$CONFIG_DIR"
  {
    echo "server.jar=$EXISTING_JAR"

    if command -v unzip >/dev/null 2>&1; then
      VERSION="$(unzip -p "$EXISTING_JAR" META-INF/MANIFEST.MF 2>/dev/null \
        | grep -i '^Implementation-Version:' | sed 's/^[^:]*: *//;s/\r$//' || true)"
      [[ -n "$VERSION" ]] && echo "server.version=$VERSION"

      # Ports: prefer installation-ports.properties next to the jar — current
      # komm-server writes this itself post-activation, and the launcher already
      # reads it directly at runtime, so this is only a fallback for `status`'s
      # port probe. If it's missing, this predates that mechanism: older jars
      # served by komm-hub had their real ports baked directly into their own
      # application.properties at build time (see injectPropertiesIntoJar in
      # komm-hub's InstallationService), so read it back out of the jar itself.
      if [[ ! -f "$JAR_DIR/installation-ports.properties" ]]; then
        PORT="$(unzip -p "$EXISTING_JAR" BOOT-INF/classes/application.properties 2>/dev/null \
          | grep -m1 '^server.port=' | cut -d= -f2 || true)"
        [[ -z "$PORT" ]] && PORT="$(unzip -p "$EXISTING_JAR" application.properties 2>/dev/null \
          | grep -m1 '^server.port=' | cut -d= -f2 || true)"
        [[ -n "$PORT" ]] && echo "server.port=$PORT"
      fi
    fi
  } > "$CONFIG_FILE"

  echo "Adopted $EXISTING_JAR — wrote $CONFIG_FILE."
  if ! command -v unzip >/dev/null 2>&1; then
    echo "Note: 'unzip' isn't installed, so version/port detection was skipped —"
    echo "harmless, just cosmetic for 'kommserver status'. Install unzip and re-run"
    echo "this script to pick them up, or edit $CONFIG_FILE by hand."
  fi

  if [[ -f /etc/systemd/system/komm-server.service ]]; then
    echo
    if systemctl is-active --quiet komm-server 2>/dev/null; then
      echo "A 'komm-server' systemd service is currently running and will be"
      echo "overwritten by 'kommserver install-service'. Stop it first:"
      echo "  sudo systemctl stop komm-server"
    else
      echo "A 'komm-server' systemd service is already registered (not running)"
      echo "and will be overwritten by 'kommserver install-service'."
    fi
  fi

  echo
  echo "Next: 'kommserver install-service' (skips activation automatically if"
  echo "keys/tls-cert.pem is already next to the jar) then 'kommserver start'."
else
  echo "Run 'kommserver update' to download komm-server, then"
  echo "'kommserver install-service' and 'kommserver start'."
fi
