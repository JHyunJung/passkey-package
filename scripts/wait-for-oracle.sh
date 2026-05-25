#!/usr/bin/env bash
set -e
echo "Waiting for Oracle XE to be healthy..."
for i in {1..60}; do
  status=$(docker inspect -f '{{.State.Health.Status}}' passkey-oracle 2>/dev/null || echo "unknown")
  if [ "$status" = "healthy" ]; then
    echo "Oracle is healthy."
    exit 0
  fi
  echo "  attempt $i/60 - status=$status"
  sleep 5
done
echo "Oracle did not become healthy in time."
exit 1
