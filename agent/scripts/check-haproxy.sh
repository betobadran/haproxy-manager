#!/bin/bash
# vrrp_script do keepalived: o nó só pode ser MASTER se o HAProxy local responder.
# Uso: check-haproxy.sh [porta-stats]
PORT="${1:-8405}"
if command -v curl >/dev/null 2>&1; then
  exec curl -fsS -o /dev/null -m 2 "http://127.0.0.1:${PORT}/healthz"
fi
# fallback sem curl: apenas verifica se a porta aceita conexão
exec 3<>"/dev/tcp/127.0.0.1/${PORT}"
