#!/bin/bash
set -e
# 1) config bootstrap para o haproxy subir antes do primeiro deploy
node /opt/hapm-agent/src/index.js --bootstrap
# 2) haproxy em master-worker (reload sem perder conexões via master socket)
haproxy -W -D -S /run/haproxy-master.sock -p /run/haproxy.pid -f /etc/haproxy/hapm/current/haproxy.cfg
# 3) o keepalived é iniciado pelo agente quando a primeira versão (com keepalived.conf) chega
if [ -f /etc/keepalived/keepalived.conf ]; then keepalived -f /etc/keepalived/keepalived.conf; fi
# 4) agente em primeiro plano
exec node /opt/hapm-agent/src/index.js
