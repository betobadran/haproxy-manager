# Nó do lab: HAProxy + Keepalived + hapm-agent no mesmo container.
# (em produção são serviços systemd — ver deploy/ansible). Contexto: raiz do repositório.
FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends \
      haproxy keepalived openjdk-21-jre-headless curl iproute2 procps busybox ca-certificates \
    && rm -rf /var/lib/apt/lists/* && mkdir -p /run/haproxy /var/lib/hapm /etc/hapm
COPY hapm-agent/target/hapm-agent.jar /opt/hapm-agent/hapm-agent.jar
COPY hapm-agent/src/main/dist/scripts/ /usr/local/lib/hapm/
COPY lab/node/entrypoint.sh /entrypoint.sh
COPY lab/node/demo-backend.sh /usr/local/bin/demo-backend.sh
RUN chmod 755 /usr/local/lib/hapm/*.sh /entrypoint.sh /usr/local/bin/demo-backend.sh
ENV HAPM_RELOAD_MODE=master-socket \
    HAPM_MASTER_SOCKET=/run/haproxy-master.sock \
    HAPM_KEEPALIVED_RELOAD_COMMAND="if pgrep -x keepalived >/dev/null; then pkill -HUP -x keepalived; else keepalived -f /etc/keepalived/keepalived.conf; fi" \
    JAVA_OPTS="-Xmx64m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
ENTRYPOINT ["/entrypoint.sh"]
