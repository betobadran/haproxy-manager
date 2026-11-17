#!/bin/bash
# HAPM — script de notificação do keepalived e failover de IP na AWS.
#
#   keepalived-notify.sh INSTANCE <nome> MASTER|BACKUP|FAULT|STOP <prioridade>   (chamado pelo keepalived)
#   keepalived-notify.sh --preflight                                              (chamado pelo hapm-agent)
#
# 1) grava o estado VRRP para o hapm-agent (métricas e rollout que deixa o MASTER por último);
# 2) no modo aws-eip, ao virar MASTER, traz os IPs para a ENI DESTE nó:
#    - HAPM_PRIVATE_VIP  : IP privado secundário (nós na MESMA subnet)
#    - HAPM_ROUTE_VIP    : IP de overlay; a rota /32 em HAPM_ROUTE_TABLES passa a apontar para esta ENI
#                          (nós em subnets/AZs DIFERENTES; exige source/dest check desligado)
#    - HAPM_EIP_ALLOCATION_ID : Elastic IP que acompanha o MASTER
# O --preflight valida permissões IAM (--dry-run), ENI, subnet e tabelas de rotas, sem alterar nada.
set -u

ENV_FILE="${HAPM_FAILOVER_ENV:-/etc/keepalived/hapm-failover.env}"
STATE_FILE="${HAPM_VRRP_STATE_FILE:-/var/lib/hapm/vrrp_state}"
IMDS="${HAPM_IMDS_URL:-http://169.254.169.254}"
AWS="${HAPM_AWS_CLI:-aws}"
SYSFS_NET="${HAPM_SYSFS_NET:-/sys/class/net}"

# shellcheck source=/dev/null
[ -f "$ENV_FILE" ] && . "$ENV_FILE"

log() { logger -t hapm-keepalived "$*" 2>/dev/null || true; echo "$*" >&2; }

imds() {
  local token
  token=$(curl -fsS -m 2 -X PUT "$IMDS/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 60") || return 1
  curl -fsS -m 2 -H "X-aws-ec2-metadata-token: $token" "$IMDS/latest/meta-data/$1"
}

# ENI que deve receber os IPs: a configurada no HAPM (por nó) ou a da interface do VRRP
resolve_eni() {
  if [ -n "${HAPM_ENI_ID:-}" ]; then echo "$HAPM_ENI_ID"; return 0; fi
  local mac
  mac=$(cat "$SYSFS_NET/${HAPM_IFACE:-eth0}/address" 2>/dev/null) || mac=$(imds mac) || return 1
  imds "network/interfaces/macs/${mac}/interface-id"
}

ec2() { "$AWS" ec2 "$@" --region "${HAPM_AWS_REGION:-}"; }

# Role opcional só para o failover (HAPM_AWS_ROLE_ARN [+ HAPM_AWS_EXTERNAL_ID]): credenciais temporárias do STS,
# obtidas com o instance profile do nó. Sem a variável, usa direto o instance profile.
assume_role() {
  [ -n "${HAPM_AWS_ROLE_ARN:-}" ] || return 0
  local out ext=()
  [ -n "${HAPM_AWS_EXTERNAL_ID:-}" ] && ext=(--external-id "$HAPM_AWS_EXTERNAL_ID")
  out=$("$AWS" sts assume-role --role-arn "$HAPM_AWS_ROLE_ARN" --role-session-name "hapm-failover-$(hostname -s 2>/dev/null || echo node)" \
    "${ext[@]}" --duration-seconds 900 --region "${HAPM_AWS_REGION:-}" \
    --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' --output text 2>&1) || { log "ERRO: assume-role $HAPM_AWS_ROLE_ARN falhou: $out"; return 1; }
  read -r AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN <<< "$out"
  export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
}

routes() { echo "${HAPM_ROUTE_TABLES:-}" | tr ',' ' '; }

ip_to_int() { local IFS=.; read -r a b c d <<< "$1"; echo $(((a << 24) + (b << 16) + (c << 8) + d)); }
ip_in_cidr() {
  local ip=$1 net=${2%/*} bits=${2#*/} mask
  mask=$(((0xffffffff << (32 - bits)) & 0xffffffff))
  [ $(($(ip_to_int "$ip") & mask)) -eq $(($(ip_to_int "$net") & mask)) ]
}

# ------------------------------------------------------------------ failover (novo MASTER)
failover() {
  local eni attempt ok rtb
  assume_role || return 1
  eni=$(resolve_eni) || { log "ERRO: não foi possível descobrir a ENI deste nó"; return 1; }
  for attempt in 1 2 3 4 5; do
    ok=1
    if [ -n "${HAPM_ROUTE_VIP:-}" ] && [ "${HAPM_MANAGE_SRC_DST_CHECK:-1}" = 1 ]; then
      ec2 modify-network-interface-attribute --network-interface-id "$eni" --no-source-dest-check >/dev/null || ok=0
    fi
    if [ -n "${HAPM_PRIVATE_VIP:-}" ]; then
      ec2 assign-private-ip-addresses --network-interface-id "$eni" --private-ip-addresses "$HAPM_PRIVATE_VIP" --allow-reassignment >/dev/null || ok=0
    fi
    if [ -n "${HAPM_ROUTE_VIP:-}" ]; then
      for rtb in $(routes); do
        ec2 replace-route --route-table-id "$rtb" --destination-cidr-block "${HAPM_ROUTE_VIP}/32" --network-interface-id "$eni" >/dev/null 2>&1 \
          || ec2 create-route --route-table-id "$rtb" --destination-cidr-block "${HAPM_ROUTE_VIP}/32" --network-interface-id "$eni" >/dev/null \
          || ok=0
      done
    fi
    if [ -n "${HAPM_EIP_ALLOCATION_ID:-}" ]; then
      if [ -n "${HAPM_PRIVATE_VIP:-}" ]; then
        ec2 associate-address --allocation-id "$HAPM_EIP_ALLOCATION_ID" --network-interface-id "$eni" \
          --private-ip-address "$HAPM_PRIVATE_VIP" --allow-reassociation >/dev/null || ok=0
      else
        ec2 associate-address --allocation-id "$HAPM_EIP_ALLOCATION_ID" --network-interface-id "$eni" --allow-reassociation >/dev/null || ok=0
      fi
    fi
    if [ "$ok" = 1 ]; then log "failover AWS concluído: IPs na ENI $eni"; return 0; fi
    sleep $((attempt * 2))
  done
  log "ERRO: failover AWS falhou após 5 tentativas (ENI $eni)"
  return 1
}

# ------------------------------------------------------------------ preflight (somente leitura / --dry-run)
FAILS=0
ok()   { printf 'OK\t%s\n' "$*"; }
warn() { printf 'WARN\t%s\n' "$*"; }
fail() { printf 'FAIL\t%s\n' "$*"; FAILS=$((FAILS + 1)); }

# Executa com --dry-run: "DryRunOperation" = teria funcionado (permissão OK)
dry() {
  local what=$1 out; shift
  out=$(ec2 "$@" --dry-run 2>&1)
  case "$out" in
    *DryRunOperation*) ok "permissão IAM: $what" ;;
    *UnauthorizedOperation*|*AccessDenied*) fail "sem permissão IAM para $what (ec2:$(echo "$1" | sed -E 's/(^|-)([a-z])/\U\2/g'))" ;;
    *) fail "$what: $(echo "$out" | tail -n1 | cut -c1-200)" ;;
  esac
}

preflight() {
  if [ "${HAPM_MODE:-}" != "aws-eip" ]; then ok "modo ${HAPM_MODE:-?}: nada a verificar"; return 0; fi
  command -v "$AWS" >/dev/null 2>&1 || { fail "AWS CLI não encontrado ($AWS)"; return 1; }
  if [ -n "${HAPM_AWS_ROLE_ARN:-}" ]; then
    if assume_role; then ok "role de failover assumida: $HAPM_AWS_ROLE_ARN"; else fail "não foi possível assumir a role $HAPM_AWS_ROLE_ARN (confira a trust policy e sts:AssumeRole no instance profile)"; return 1; fi
  fi
  [ -n "${HAPM_AWS_REGION:-}" ] || fail "região AWS não configurada"
  local eni info subnet srcdst vpc cidr vpccidr rtb rvpc
  if ! eni=$(resolve_eni) || [ -z "$eni" ]; then fail "não foi possível descobrir a ENI (IMDS inacessível? configure a ENI do nó no HAPM)"; return 1; fi
  info=$(ec2 describe-network-interfaces --network-interface-ids "$eni" --query 'NetworkInterfaces[0].[SubnetId,SourceDestCheck,VpcId]' --output text 2>&1) \
    || { fail "não consegui ler a ENI $eni: $(echo "$info" | tail -n1)"; return 1; }
  read -r subnet srcdst vpc <<< "$info"
  ok "ENI $eni (subnet $subnet, VPC $vpc)"

  if [ -n "${HAPM_PRIVATE_VIP:-}" ]; then
    cidr=$(ec2 describe-subnets --subnet-ids "$subnet" --query 'Subnets[0].CidrBlock' --output text 2>/dev/null)
    if [ -n "$cidr" ] && ip_in_cidr "$HAPM_PRIVATE_VIP" "$cidr"; then
      ok "IP secundário $HAPM_PRIVATE_VIP pertence à subnet $subnet ($cidr)"
    else
      fail "IP secundário $HAPM_PRIVATE_VIP está fora da subnet $subnet (${cidr:-?}) deste nó: IP secundário só funciona com os nós na mesma subnet; para subnets diferentes use o IP de overlay (tabela de rotas)"
    fi
    dry "mover IP secundário" assign-private-ip-addresses --network-interface-id "$eni" --private-ip-addresses "$HAPM_PRIVATE_VIP" --allow-reassignment
  fi

  if [ -n "${HAPM_ROUTE_VIP:-}" ]; then
    vpccidr=$(ec2 describe-vpcs --vpc-ids "$vpc" --query 'Vpcs[0].CidrBlockAssociationSet[].CidrBlock' --output text 2>/dev/null)
    for cidr in $vpccidr; do
      ip_in_cidr "$HAPM_ROUTE_VIP" "$cidr" && fail "IP de overlay $HAPM_ROUTE_VIP está dentro do CIDR da VPC ($cidr): escolha um IP fora dos CIDRs da VPC"
    done
    for rtb in $(routes); do
      rvpc=$(ec2 describe-route-tables --route-table-ids "$rtb" --query 'RouteTables[0].VpcId' --output text 2>&1)
      if [ "$rvpc" = "$vpc" ]; then ok "tabela de rotas $rtb encontrada na VPC $vpc"; else fail "tabela de rotas $rtb não encontrada na VPC $vpc ($rvpc)"; fi
      dry "atualizar rota em $rtb" replace-route --route-table-id "$rtb" --destination-cidr-block "${HAPM_ROUTE_VIP}/32" --network-interface-id "$eni"
    done
    if [ "$srcdst" = "False" ]; then
      ok "source/dest check desligado na ENI"
    elif [ "${HAPM_MANAGE_SRC_DST_CHECK:-1}" = 1 ]; then
      warn "source/dest check ligado: será desligado automaticamente no failover"
      dry "desligar source/dest check" modify-network-interface-attribute --network-interface-id "$eni" --no-source-dest-check
    else
      fail "source/dest check ligado na ENI $eni: desligue-o (necessário para o IP de overlay)"
    fi
  fi

  if [ -n "${HAPM_EIP_ALLOCATION_ID:-}" ]; then
    if ec2 describe-addresses --allocation-ids "$HAPM_EIP_ALLOCATION_ID" --query 'Addresses[0].AllocationId' --output text >/dev/null 2>&1; then
      ok "Elastic IP $HAPM_EIP_ALLOCATION_ID encontrado"
    else
      fail "Elastic IP $HAPM_EIP_ALLOCATION_ID não encontrado na região ${HAPM_AWS_REGION:-}"
    fi
    dry "associar Elastic IP" associate-address --allocation-id "$HAPM_EIP_ALLOCATION_ID" --network-interface-id "$eni" --allow-reassociation
  fi
  [ "$FAILS" -eq 0 ]
}

# ------------------------------------------------------------------ main
if [ "${1:-}" = "--preflight" ]; then
  preflight
  exit $?
fi

STATE="${3:-UNKNOWN}"
mkdir -p "$(dirname "$STATE_FILE")"
echo "$STATE" > "${STATE_FILE}.tmp" && mv "${STATE_FILE}.tmp" "$STATE_FILE"
log "transição VRRP: ${2:-?} -> $STATE (prio ${4:-?})"

[ "${HAPM_MODE:-}" = "aws-eip" ] || exit 0
[ "$STATE" = "MASTER" ] || exit 0
failover
