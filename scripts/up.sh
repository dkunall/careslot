#!/usr/bin/env bash
# Starts the compose stack with secrets generated on first use and kept outside the repo (mode 600).
# Reruns reuse them, so the database in an existing volume still accepts its password, and the
# encrypted patient columns can still be read with the same PHI key.
set -euo pipefail
cd "$(dirname "$0")/.."
ENV_FILE=${CARESLOT_ENV_FILE:-$HOME/.config/careslot/compose.env}
if [[ ! -f $ENV_FILE ]]; then
  mkdir -p "$(dirname "$ENV_FILE")"
  umask 077
  {
    echo "CARESLOT_DB_PASSWORD=$(openssl rand -hex 24)"
    echo "CARESLOT_REDIS_PASSWORD=$(openssl rand -hex 24)"
    echo "CARESLOT_PHI_KEY=$(openssl rand -base64 32)"
  } > "$ENV_FILE"
  echo "wrote new secrets to $ENV_FILE"
fi
exec docker compose --env-file "$ENV_FILE" up -d --wait --wait-timeout "${WAIT:-300}" "$@"
