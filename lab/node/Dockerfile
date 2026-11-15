# Nó do lab: HAProxy + Keepalived + hapm-agent no mesmo container.
# (em produção eles rodam como serviços systemd — ver deploy/ansible)
FROM node:22-bookworm-slim AS node

FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends \
      haproxy keepalived curl iproute2 procps ca-certificates \
    && rm -rf /var/lib/apt/lists/* && mkdir -p /run/haproxy /var/lib/hapm /etc/hapm
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY agent/src /opt/hapm-agent/src
COPY agent/package.json /opt/hapm-agent/
COPY agent/scripts/ /usr/local/lib/hapm/
COPY lab/node/entrypoint.sh /entrypoint.sh
RUN chmod 755 /usr/local/lib/hapm/*.sh /entrypoint.sh
ENV HAPM_RELOAD_MODE=master-socket \
    HAPM_MASTER_SOCKET=/run/haproxy-master.sock \
    HAPM_KEEPALIVED_RELOAD_COMMAND="if pgrep -x keepalived >/dev/null; then pkill -HUP -x keepalived; else keepalived -f /etc/keepalived/keepalived.conf; fi"
ENTRYPOINT ["/entrypoint.sh"]
