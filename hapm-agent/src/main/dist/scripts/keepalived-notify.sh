#!/bin/bash
# Chamado pelo keepalived a cada transição:  $1=INSTANCE|GROUP  $2=nome  $3=MASTER|BACKUP|FAULT|STOP  $4=prioridade
# 1) grava o estado para o hapm-agent (métricas + rollout que deixa o MASTER por último)
# 2) no modo aws-eip, ao virar MASTER move o Elastic IP e/ou o IP privado secundário para esta instância
set -u
STATE="${3:-UNKNOWN}"
STATE_FILE="${HAPM_VRRP_STATE_FILE:-/var/lib/hapm/vrrp_state}"
ENV_FILE="/etc/keepalived/hapm-failover.env"

mkdir -p "$(dirname "$STATE_FILE")"
echo "$STATE" > "${STATE_FILE}.tmp" && mv "${STATE_FILE}.tmp" "$STATE_FILE"
logger -t hapm-keepalived "transição VRRP: $2 -> $STATE (prio ${4:-?})" 2>/dev/null || true

# shellcheck source=/dev/null
[ -f "$ENV_FILE" ] && . "$ENV_FILE"
[ "${HAPM_MODE:-}" = "aws-eip" ] || exit 0
[ "$STATE" = "MASTER" ] || exit 0

imds() {
  local token
  token=$(curl -fsS -m 2 -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 60")
  curl -fsS -m 2 -H "X-aws-ec2-metadata-token: $token" "http://169.254.169.254/latest/meta-data/$1"
}

MAC=$(imds mac)
ENI=$(imds "network/interfaces/macs/${MAC}/interface-id")
export AWS_DEFAULT_REGION="${HAPM_AWS_REGION}"

for attempt in 1 2 3 4 5; do
  ok=1
  if [ -n "${HAPM_PRIVATE_VIP:-}" ]; then
    aws ec2 assign-private-ip-addresses --network-interface-id "$ENI" \
      --private-ip-addresses "$HAPM_PRIVATE_VIP" --allow-reassignment || ok=0
  fi
  if [ -n "${HAPM_EIP_ALLOCATION_ID:-}" ]; then
    if [ -n "${HAPM_PRIVATE_VIP:-}" ]; then
      aws ec2 associate-address --allocation-id "$HAPM_EIP_ALLOCATION_ID" --network-interface-id "$ENI" \
        --private-ip-address "$HAPM_PRIVATE_VIP" --allow-reassociation || ok=0
    else
      aws ec2 associate-address --allocation-id "$HAPM_EIP_ALLOCATION_ID" --network-interface-id "$ENI" \
        --allow-reassociation || ok=0
    fi
  fi
  if [ "$ok" = 1 ]; then
    logger -t hapm-keepalived "failover AWS concluído (eni=$ENI)" 2>/dev/null || true
    exit 0
  fi
  sleep $((attempt * 2))
done
logger -t hapm-keepalived "ERRO: failover AWS falhou após 5 tentativas" 2>/dev/null || true
exit 1
