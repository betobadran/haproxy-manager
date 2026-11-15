#!/usr/bin/env bash
# Instala/atualiza o HAPM em vários servidores via SSH, a partir da sua máquina.
#
#   deploy/bash/make-bundle.sh
#   deploy/bash/deploy.sh -i hosts.conf manager     # 1) sobe o manager
#   (na UI: crie o cluster e salve o token de join em JOIN_TOKEN_FILE)
#   deploy/bash/deploy.sh -i hosts.conf nodes       # 2) nós, UM POR VEZ (nunca os dois lados do VIP juntos)
#   deploy/bash/deploy.sh -i hosts.conf nodes lb02  # só um nó
set -o errexit -o nounset -o pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib.sh
source "$HERE/lib.sh"

INVENTORY=hosts.conf BUNDLE=$HERE/../../target/hapm-bundle
while getopts 'i:b:h' o; do
  case $o in
    i) INVENTORY=$OPTARG ;;
    b) BUNDLE=$OPTARG ;;
    *) sed -n '2,9p' "$0"; exit 0 ;;
  esac
done
shift $((OPTIND - 1))
TARGET=${1:-}; ONLY=${2:-}
[[ $TARGET == manager || $TARGET == nodes ]] || die "uso: deploy.sh [-i hosts.conf] [-b bundle] manager|nodes [nome]"
[[ -r $INVENTORY ]] || die "inventário não encontrado: $INVENTORY (veja hosts.example.conf)"
[[ -f $BUNDLE/install-node.sh ]] || die "bundle não encontrado em $BUNDLE (rode make-bundle.sh)"
INV_DIR=$(cd "$(dirname "$INVENTORY")" && pwd)

SSH_USER=root SSH_OPTS=() MANAGER_HOST='' MANAGER_ARGS=() ADMIN_PASSWORD_FILE='' MANAGER_ENV_FILE='' NODES=() MANAGER_URL='' JOIN_TOKEN_FILE='' NODE_ARGS=()
# shellcheck source=hosts.example.conf
source "$INVENTORY"
abs() { [[ -z $1 || $1 == /* ]] && printf '%s' "$1" || printf '%s/%s' "$INV_DIR" "$1"; }
ADMIN_PASSWORD_FILE=$(abs "$ADMIN_PASSWORD_FILE"); MANAGER_ENV_FILE=$(abs "$MANAGER_ENV_FILE"); JOIN_TOKEN_FILE=$(abs "$JOIN_TOKEN_FILE")

REMOTE_DIR=/tmp/hapm-bundle
# shellcheck disable=SC2029
ssh_run() { local host=$1; shift; ssh "${SSH_OPTS[@]}" "$SSH_USER@$host" "$@"; }

# Copia o bundle e executa o instalador com sudo. O segredo vai pelo stdin do ssh para um arquivo 0600
# temporário (nunca aparece em argumentos de processo nem no histórico) e é apagado ao final.
remote_install() {
  local host=$1 script=$2 secret_flag=$3 secret_file=$4 extra_file=$5; shift 5
  local args=() a
  log "[$host] enviando bundle"
  # shellcheck disable=SC2029  # REMOTE_DIR é expandido localmente de propósito
  ssh_run "$host" "rm -rf $REMOTE_DIR && mkdir -m 700 -p $REMOTE_DIR"
  tar -C "$BUNDLE" -cf - . | ssh_run "$host" "tar -C $REMOTE_DIR -xf -"
  ssh_run "$host" "cd $REMOTE_DIR && sha256sum --quiet -c SHA256SUMS" || die "[$host] checksum do bundle não confere"
  if [[ -n $secret_file ]]; then
    ssh_run "$host" "umask 077 && cat > $REMOTE_DIR/.secret" < <(tr -d '\r\n' < "$secret_file")
    args+=("$secret_flag" "$REMOTE_DIR/.secret")
  fi
  if [[ -n $extra_file ]]; then
    ssh_run "$host" "umask 077 && cat > $REMOTE_DIR/.extra.env" < "$extra_file"
    args+=(--env-file "$REMOTE_DIR/.extra.env")
  fi
  for a in "$@"; do args+=("$a"); done
  local quoted=() q
  for q in "${args[@]}"; do quoted+=("$(printf '%q' "$q")"); done
  log "[$host] executando $script"
  # shellcheck disable=SC2029
  ssh_run "$host" "sudo -n $REMOTE_DIR/$script ${quoted[*]}; rc=\$?; rm -rf $REMOTE_DIR; exit \$rc"
}

if [[ $TARGET == manager ]]; then
  [[ -n $MANAGER_HOST ]] || die "MANAGER_HOST não definido"
  [[ -z $ADMIN_PASSWORD_FILE || -r $ADMIN_PASSWORD_FILE ]] || die "não li $ADMIN_PASSWORD_FILE"
  remote_install "$MANAGER_HOST" install-manager.sh --admin-password-file "$ADMIN_PASSWORD_FILE" "$MANAGER_ENV_FILE" "${MANAGER_ARGS[@]}"
  log "manager instalado em $MANAGER_HOST"
  exit 0
fi

[[ -n $MANAGER_URL ]] || die "MANAGER_URL não definido"
[[ -r $JOIN_TOKEN_FILE ]] || die "token de join não encontrado em $JOIN_TOKEN_FILE (crie o cluster na UI primeiro)"
peers_of() { local me=$1 n out=(); for n in "${NODES[@]}"; do [[ ${n#* } != "$me" ]] && out+=("${n#* }"); done; local IFS=,; echo "${out[*]}"; }
done_any=0
for entry in "${NODES[@]}"; do
  name=${entry%% *}; ip=${entry#* }
  [[ -n $ONLY && $ONLY != "$name" ]] && continue
  remote_install "$ip" install-node.sh --join-token-file "$JOIN_TOKEN_FILE" '' \
    --manager-url "$MANAGER_URL" --node-name "$name" --address "$ip" --vrrp-peers "$(peers_of "$ip")" "${NODE_ARGS[@]}"
  done_any=1
done
((done_any)) || die "nenhum nó corresponde a '$ONLY'"
log "nós instalados. Publique uma versão no manager para aplicar listeners e keepalived."
