#!/usr/bin/env bash
# The operator's commands, run against the live database in a throwaway container:
#
#   ./admin.sh reset-password 09xxxxxxxxx   someone forgot their password: prints a new one
#   ./admin.sh unlock 09xxxxxxxxx           someone locked themselves out with wrong passwords
#   ./admin.sh shops                        every shop on this server
set -euo pipefail
cd "$(dirname "$0")"
docker compose run --rm --no-deps -T backend admin "$@"
