#!/bin/bash
set -e
AGENT="java $JAVA_OPTS -jar /opt/hapm-agent/hapm-agent.jar"
# 1) config bootstrap para o haproxy subir antes do primeiro deploy
$AGENT --bootstrap
# 2) haproxy em master-worker (reload sem perder conexões via master socket)
haproxy -W -D -S /run/haproxy-master.sock -p /run/haproxy.pid -f /etc/haproxy/hapm/current/haproxy.cfg
# 3) o keepalived é iniciado pelo agente quando a primeira versão (com keepalived.conf) chega
if [ -f /etc/keepalived/keepalived.conf ]; then keepalived -f /etc/keepalived/keepalived.conf; fi
# 4) agente em primeiro plano
exec $AGENT
