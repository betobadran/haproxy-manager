#!/usr/bin/env bash
# Instala/atualiza um nó HAProxy gerenciado pelo HAProxy Manager: HAProxy + Keepalived + hapm-agent (+ OTel Collector).
# Idempotente: pode ser executado de novo para atualizar o agente ou mudar parâmetros; só reinicia o que mudou.
#
# Uso (no próprio servidor, como root, a partir do bundle gerado por make-bundle.sh):
#   HAPM_JOIN_TOKEN='<token>' ./install-node.sh --manager-url https://hapm.interno:8080 --interface eth0
#   ./install-node.sh --manager-url URL --join-token-file /root/hapm-token --otel-gateway otel-gw:4317 --cluster-label dc1
#
# O token de join vem de --join-token-file ou da variável HAPM_JOIN_TOKEN (nunca como argumento, para não
# aparecer em `ps`). Ele está em: UI do manager > cluster > Visão geral > Adicionar nós.

# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() {
  cat <<'USAGE'
Opções:
  --manager-url URL          URL do HAProxy Manager (obrigatório)
  --join-token-file ARQ      arquivo com o token de join (ou variável HAPM_JOIN_TOKEN)
  --node-name NOME           nome do nó no manager (padrão: hostname curto)
  --interface IF             interface do VRRP/VIP (padrão: rota padrão)
  --address IP               IP do nó para o VRRP unicast (padrão: IPv4 da interface)
  --no-keepalived            não instala o keepalived (topologia aws-nlb)
  --manager-key CHAVE        chave pública de assinatura do manager (tela: Adicionar nós); sem ela, é fixada no 1º contato
  --aws-lifecycle            AWS Auto Scaling: no scale-in o agente avisa o manager, que remove o nó
  --asg-hook NOME:ASG        (com --aws-lifecycle) conclui o lifecycle hook NOME do ASG ao sair, sem esperar o timeout
  --vrrp-peers IP,IP         libera VRRP (protocolo 112) desses IPs no firewalld, se ativo
  --otel-gateway HOST:PORTA  instala o OTel Collector e envia métricas OTLP para o gateway
  --cluster-label NOME       label hapm_cluster das métricas (padrão: hapm)
  --otelcol-package ARQ      .deb/.rpm local do otelcol-contrib (ambientes sem internet)
  --otelcol-version VER      versão a baixar do GitHub se não houver pacote local (padrão: 0.114.0)
  --bundle-dir DIR           diretório do bundle (padrão: diretório deste script)
  --no-start                 instala e configura, mas não inicia serviços
  -h, --help
USAGE
}

MANAGER_KEY='' AWS_LIFECYCLE=0 ASG_HOOK='' MANAGER_URL='' TOKEN_FILE='' NODE_NAME='' IFACE='' ADDRESS='' KEEPALIVED=1 VRRP_PEERS='' OTEL_GW='' CLUSTER_LABEL=hapm
OTEL_PKG='' OTEL_VERSION=0.114.0 START=1
while (($#)); do
  case $1 in
    --manager-url) MANAGER_URL=$2; shift 2 ;;
    --join-token-file) TOKEN_FILE=$2; shift 2 ;;
    --node-name) NODE_NAME=$2; shift 2 ;;
    --interface) IFACE=$2; shift 2 ;;
    --address) ADDRESS=$2; shift 2 ;;
    --no-keepalived) KEEPALIVED=0; shift ;;
    --aws-lifecycle) AWS_LIFECYCLE=1; shift ;;
    --manager-key) MANAGER_KEY=$2; shift 2 ;;
    --asg-hook) ASG_HOOK=$2; shift 2 ;;
    --vrrp-peers) VRRP_PEERS=$2; shift 2 ;;
    --otel-gateway) OTEL_GW=$2; shift 2 ;;
    --cluster-label) CLUSTER_LABEL=$2; shift 2 ;;
    --otelcol-package) OTEL_PKG=$2; shift 2 ;;
    --otelcol-version) OTEL_VERSION=$2; shift 2 ;;
    --bundle-dir) HAPM_BUNDLE_DIR=$2; shift 2 ;;
    --no-start) START=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "opção desconhecida: $1" ;;
  esac
done

require_root
[[ -n $MANAGER_URL ]] || { usage; die "--manager-url é obrigatório"; }
[[ $MANAGER_URL =~ ^https?://[^[:space:]]+$ ]] || die "--manager-url inválida: $MANAGER_URL"
TOKEN=$(read_secret "$TOKEN_FILE" HAPM_JOIN_TOKEN)
[[ -n $TOKEN ]] || die "token de join ausente: use --join-token-file ou HAPM_JOIN_TOKEN"
[[ $TOKEN =~ ^[A-Za-z0-9-]+\.[A-Za-z0-9_-]+$ ]] || die "token de join com formato inesperado (esperado <cluster-id>.<segredo>)"
NODE_NAME=${NODE_NAME:-$(hostname -s)}
[[ $NODE_NAME =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$ ]] || die "nome de nó inválido: $NODE_NAME"
[[ -z $IFACE ]] && IFACE=$(ip -4 route show default 2>/dev/null | awk '{for (i=1;i<NF;i++) if ($i=="dev") {print $(i+1); exit}}')
[[ -n $IFACE ]] || die "não foi possível descobrir a interface; use --interface"
[[ -z $ADDRESS ]] && ADDRESS=$(ip -4 -o addr show dev "$IFACE" 2>/dev/null | awk '{split($4,a,"/"); print a[1]; exit}')
[[ -n $ADDRESS ]] || die "não foi possível descobrir o IP de $IFACE; use --address"

B=$HAPM_BUNDLE_DIR
for f in haproxy-manager-agent.jar scripts/check-haproxy.sh scripts/keepalived-notify.sh systemd/hapm-agent.service; do
  [[ -f $B/$f ]] || die "bundle incompleto: falta $B/$f (gere com make-bundle.sh)"
done
FAM=$(os_family)
log "nó $NODE_NAME ($ADDRESS em $IFACE) -> $MANAGER_URL  [$FAM]"

# ------------------------------------------------------------------ pacotes
pkgs=(haproxy "$JAVA_PKG" curl "iproute2:iproute" "procps:procps-ng")
((KEEPALIVED)) && pkgs+=(keepalived)
ensure_packages "${pkgs[@]}"

# ------------------------------------------------------------------ kernel
if printf '%s\n' \
  '# Gerenciado pelo HAProxy Manager' \
  '# bind no VIP mesmo sendo BACKUP' \
  'net.ipv4.ip_nonlocal_bind = 1' \
  'net.ipv6.ip_nonlocal_bind = 1' \
  'net.core.somaxconn = 65535' \
  'net.ipv4.ip_local_port_range = 1024 65000' \
  'net.ipv4.tcp_max_syn_backlog = 65535' | write_if_changed /etc/sysctl.d/90-hapm.conf 0644; then
  sysctl -q -e -p /etc/sysctl.d/90-hapm.conf || warn "não foi possível aplicar todos os sysctls (container?)"
fi

# ------------------------------------------------------------------ arquivos do HAProxy Manager
install -d -m 0750 /etc/haproxy/hapm /etc/hapm /opt/hapm-agent
install -d -m 0755 /var/lib/hapm /usr/local/lib/hapm
copy_if_changed "$B/scripts/check-haproxy.sh" /usr/local/lib/hapm/check-haproxy.sh 0755 || true
copy_if_changed "$B/scripts/keepalived-notify.sh" /usr/local/lib/hapm/keepalived-notify.sh 0755 || true

# HAProxy: o mesmo drop-in para Debian e RHEL; a config ativa é o symlink current -> releases/vN
HAPROXY_BIN=$(command -v haproxy)
haproxy_changed=0
cat <<UNIT | write_if_changed /etc/systemd/system/haproxy.service.d/hapm.conf 0644 && haproxy_changed=1
# Gerenciado pelo HAProxy Manager: a configuração ativa é /etc/haproxy/hapm/current/haproxy.cfg
[Service]
RuntimeDirectory=haproxy
ExecStartPre=
ExecStartPre=${HAPROXY_BIN} -f /etc/haproxy/hapm/current/haproxy.cfg -c -q
ExecStart=
ExecStart=${HAPROXY_BIN} -Ws -f /etc/haproxy/hapm/current/haproxy.cfg -p /run/haproxy.pid -S /run/haproxy-master.sock
ExecReload=
ExecReload=${HAPROXY_BIN} -Ws -f /etc/haproxy/hapm/current/haproxy.cfg -c -q
ExecReload=/bin/kill -USR2 \$MAINPID
LimitNOFILE=1048576
UNIT

agent_changed=0
copy_if_changed "$B/haproxy-manager-agent.jar" /opt/hapm-agent/hapm-agent.jar 0644 && agent_changed=1
copy_if_changed "$B/systemd/hapm-agent.service" /etc/systemd/system/hapm-agent.service 0644 && agent_changed=1
{
  echo '# Gerenciado pelo install-node.sh do HAProxy Manager'
  echo "HAPM_MANAGER_URL=$MANAGER_URL"
  echo "HAPM_JOIN_TOKEN=$TOKEN"
  echo "HAPM_NODE_NAME=$NODE_NAME"
  echo "HAPM_NODE_INTERFACE=$IFACE"
  echo "HAPM_NODE_ADDRESS=$ADDRESS"
  echo 'HAPM_RELOAD_MODE=systemd'
  echo 'HAPM_RELOAD_COMMAND=systemctl reload-or-restart haproxy'
  echo 'HAPM_KEEPALIVED_RELOAD_COMMAND=systemctl reload-or-restart keepalived'
  echo 'HAPM_METRICS_HOST=127.0.0.1'
  echo 'HAPM_METRICS_PORT=9101'
  if [[ -n $MANAGER_KEY ]]; then
    [[ $MANAGER_KEY =~ ^[A-Za-z0-9+/=]{40,200}$ ]] || die "--manager-key inválida"
    echo "HAPM_MANAGER_SIGNING_KEY=$MANAGER_KEY"
  fi
  if ((AWS_LIFECYCLE)); then
    echo 'HAPM_AWS_LIFECYCLE=1'
    if [[ -n $ASG_HOOK ]]; then
      echo "HAPM_ASG_HOOK_NAME=${ASG_HOOK%%:*}"
      echo "HAPM_ASG_NAME=${ASG_HOOK#*:}"
    fi
  fi
} | write_if_changed /etc/hapm/agent.env 0600 && agent_changed=1

# release bootstrap: config mínima para o HAProxy subir antes da primeira publicação
if [[ ! -e /etc/haproxy/hapm/current ]]; then
  log "criando release bootstrap"
  HAPM_HAPROXY_DIR=/etc/haproxy/hapm java -jar /opt/hapm-agent/hapm-agent.jar --bootstrap
fi

# ------------------------------------------------------------------ OTel Collector (opcional)
otel_changed=0
if [[ -n $OTEL_GW ]]; then
  [[ -f $B/otel/node.yaml ]] || die "bundle sem otel/node.yaml"
  if ! command -v otelcol-contrib >/dev/null 2>&1; then
    ext=deb; [[ $FAM == rhel ]] && ext=rpm
    if [[ -z $OTEL_PKG ]]; then
      OTEL_PKG=$(mktemp --suffix=".$ext")
      log "baixando otelcol-contrib $OTEL_VERSION"
      curl -fsSL -o "$OTEL_PKG" "https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v${OTEL_VERSION}/otelcol-contrib_${OTEL_VERSION}_linux_amd64.${ext}" \
        || die "falha ao baixar o otelcol-contrib; em rede sem internet use --otelcol-package"
    fi
    if [[ $FAM == debian ]]; then dpkg -i "$OTEL_PKG" >/dev/null; else rpm -U --replacepkgs "$OTEL_PKG"; fi
  fi
  copy_if_changed "$B/otel/node.yaml" /etc/otelcol-contrib/config.yaml 0644 && otel_changed=1
  printf '%s\n' \
    'OTELCOL_OPTIONS="--config=/etc/otelcol-contrib/config.yaml"' \
    "HAPM_CLUSTER=$CLUSTER_LABEL" \
    "HAPM_NODE=$NODE_NAME" \
    "OTEL_GATEWAY_ENDPOINT=$OTEL_GW" | write_if_changed /etc/otelcol-contrib/otelcol-contrib.conf 0644 && otel_changed=1
fi

# ------------------------------------------------------------------ firewall (VRRP)
if ((KEEPALIVED)) && [[ -n $VRRP_PEERS ]]; then
  if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
    IFS=, read -r -a peers <<< "$VRRP_PEERS"
    for p in "${peers[@]}"; do
      firewall-cmd -q --permanent --add-rich-rule="rule family=ipv4 source address=$p protocol value=vrrp accept" || true
    done
    firewall-cmd -q --reload
    info "firewalld: VRRP liberado para $VRRP_PEERS"
  else
    warn "libere o protocolo IP 112 (VRRP) entre os nós no seu firewall (ex.: iptables -A INPUT -p 112 -s <peer> -j ACCEPT)"
  fi
fi

# ------------------------------------------------------------------ serviços
if ! has_systemd; then
  warn "systemd não encontrado: arquivos instalados, serviços não iniciados"
  exit 0
fi
systemctl daemon-reload
systemctl enable -q haproxy hapm-agent
((KEEPALIVED)) && systemctl enable -q keepalived   # o agente o inicia quando a 1ª versão chegar
[[ -n $OTEL_GW ]] && systemctl enable -q otelcol-contrib
if ((!START)); then log "instalado (--no-start)"; exit 0; fi

if ((haproxy_changed)) || ! systemctl is-active -q haproxy; then systemctl restart haproxy; fi
if ((agent_changed)) || ! systemctl is-active -q hapm-agent; then systemctl restart hapm-agent; fi
if [[ -n $OTEL_GW ]] && { ((otel_changed)) || ! systemctl is-active -q otelcol-contrib; }; then systemctl restart otelcol-contrib; fi

# ------------------------------------------------------------------ verificação
wait_http http://127.0.0.1:8405/healthz 30 || die "HAProxy não respondeu em :8405/healthz (veja: journalctl -u haproxy)"
wait_http http://127.0.0.1:9101/healthz 60 || die "agente não subiu (veja: journalctl -u hapm-agent)"
log "nó $NODE_NAME pronto: HAProxy ok, agente ok (versão ativa: $(readlink /etc/haproxy/hapm/current))"
[[ $(readlink /etc/haproxy/hapm/current) == releases/v0 ]] && info "o nó aparece no manager em alguns segundos; publique uma versão para aplicar listeners/keepalived"
exit 0
