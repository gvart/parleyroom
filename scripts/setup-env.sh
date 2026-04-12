#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=".env"
EXAMPLE_FILE=".env.example"

if [[ ! -f "$ENV_FILE" ]]; then
  cp "$EXAMPLE_FILE" "$ENV_FILE"
fi

# shellcheck disable=SC1090
source "$ENV_FILE"

resolve_ip() {
  local host="$1"
  if command -v dscacheutil >/dev/null 2>&1; then
    dscacheutil -q host -a name "$host" | awk '/^ip_address:/ {print $2; exit}'
  elif command -v getent >/dev/null 2>&1; then
    getent ahostsv4 "$host" | awk '{print $1; exit}'
  else
    # Fallback: parse `ping`
    ping -c 1 -W 1 "$host" 2>/dev/null | awk -F'[()]' '/PING/ {print $2; exit}'
  fi
}

IP="$(resolve_ip "${APP_HOST:-role.local}" || true)"

if [[ -z "${IP:-}" ]]; then
  echo "Could not resolve ${APP_HOST}. Set HOST_IP manually in .env" >&2
  exit 1
fi

# Update HOST_IP in place
if grep -q '^HOST_IP=' "$ENV_FILE"; then
  # macOS sed needs an empty -i arg; use a portable two-step instead
  tmp="$(mktemp)"
  awk -v ip="$IP" '/^HOST_IP=/{print "HOST_IP=" ip; next} {print}' "$ENV_FILE" > "$tmp"
  mv "$tmp" "$ENV_FILE"
else
  echo "HOST_IP=$IP" >> "$ENV_FILE"
fi

echo "APP_HOST=${APP_HOST}"
echo "HOST_IP=${IP}"