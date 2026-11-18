#!/usr/bin/env bash
# Funções comuns dos instaladores do HAProxy Manager (sourced por install-*.sh e deploy.sh).
# shellcheck disable=SC2034

set -o errexit -o nounset -o pipefail

HAPM_BUNDLE_DIR="${HAPM_BUNDLE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"

if [[ -t 1 ]]; then C_OK=$'\e[32m' C_WARN=$'\e[33m' C_ERR=$'\e[31m' C_DIM=$'\e[2m' C_RST=$'\e[0m'; else C_OK='' C_WARN='' C_ERR='' C_DIM='' C_RST=''; fi
log()  { printf '%s==>%s %s\n' "$C_OK" "$C_RST" "$*"; }
info() { printf '%s    %s%s\n' "$C_DIM" "$*" "$C_RST"; }
warn() { printf '%s[aviso]%s %s\n' "$C_WARN" "$C_RST" "$*" >&2; }
die()  { printf '%s[erro]%s %s\n' "$C_ERR" "$C_RST" "$*" >&2; exit 1; }

require_root() { [[ $EUID -eq 0 ]] || die "execute como root (sudo $0 ...)"; }

# Família do SO: debian (Debian/Ubuntu) ou rhel (RHEL/Rocky/Alma/Oracle)
os_family() {
  [[ -r /etc/os-release ]] || die "não foi possível identificar o sistema (/etc/os-release ausente)"
  # shellcheck disable=SC1091
  . /etc/os-release
  case " ${ID:-} ${ID_LIKE:-} " in
    *" debian "*|*" ubuntu "*) echo debian ;;
    *" rhel "*|*" fedora "*|*" centos "*|*" rocky "*|*" almalinux "*) echo rhel ;;
    *) die "sistema não suportado: ${PRETTY_NAME:-desconhecido} (suportados: Debian/Ubuntu, RHEL/Rocky/Alma)" ;;
  esac
}

# Instala somente o que falta (funciona offline se tudo já estiver instalado).
# Uso: ensure_packages <nome-debian>:<nome-rhel> ...   (use o mesmo nome se for igual)
ensure_packages() {
  local fam missing=() spec pkg
  fam=$(os_family)
  for spec in "$@"; do
    if [[ $fam == debian ]]; then pkg=${spec%%:*}; else pkg=${spec##*:}; fi
    [[ -z $pkg ]] && continue
    if [[ $fam == debian ]]; then
      dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null | grep -q 'install ok installed' || missing+=("$pkg")
    else
      rpm -q "$pkg" >/dev/null 2>&1 || missing+=("$pkg")
    fi
  done
  ((${#missing[@]} == 0)) && { info "pacotes já instalados"; return 0; }
  log "instalando pacotes: ${missing[*]}"
  if [[ $fam == debian ]]; then
    DEBIAN_FRONTEND=noninteractive apt-get update -q
    DEBIAN_FRONTEND=noninteractive apt-get install -y -q --no-install-recommends "${missing[@]}"
  else
    dnf install -y -q "${missing[@]}"
  fi
}

JAVA_PKG="openjdk-21-jre-headless:java-21-openjdk-headless"

# Grava um arquivo só se o conteúdo mudou. Retorna 0 se mudou, 1 se já estava igual.
# Uso: printf '...' | write_if_changed <destino> <modo> [dono]
write_if_changed() {
  local dest=$1 mode=$2 owner=${3:-root:root} tmp
  tmp=$(mktemp "${dest}.XXXXXX" 2>/dev/null || mktemp)
  cat > "$tmp"
  if [[ -f $dest ]] && cmp -s "$tmp" "$dest"; then rm -f "$tmp"; return 1; fi
  install -D -m "$mode" -o "${owner%%:*}" -g "${owner##*:}" "$tmp" "$dest"
  rm -f "$tmp"
  info "atualizado: $dest"
  return 0
}

# Copia arquivo se diferente. Retorna 0 se mudou.
copy_if_changed() {
  local src=$1 dest=$2 mode=$3
  [[ -f $src ]] || die "arquivo não encontrado: $src"
  if [[ -f $dest ]] && cmp -s "$src" "$dest"; then return 1; fi
  install -D -m "$mode" -o root -g root "$src" "$dest"
  info "atualizado: $dest"
  return 0
}

# Atualiza KEY=valor num arquivo .env preservando as outras linhas. Retorna 0 se mudou.
set_env_var() {
  local file=$1 key=$2 value=$3 cur
  [[ $value == *$'\n'* ]] && die "valor de $key não pode ter quebra de linha"
  if [[ -f $file ]]; then
    cur=$(grep -E "^${key}=" "$file" | tail -n1 | cut -d= -f2- || true)
    [[ $cur == "$value" ]] && grep -qE "^${key}=" "$file" && return 1
    grep -vE "^${key}=" "$file" > "${file}.tmp" || true
  else
    : > "${file}.tmp"
  fi
  printf '%s=%s\n' "$key" "$value" >> "${file}.tmp"
  chmod 600 "${file}.tmp"
  mv "${file}.tmp" "$file"
  return 0
}

has_systemd() { [[ -d /run/systemd/system ]]; }

# Lê um segredo de arquivo (ou da variável de ambiente indicada), sem expor em argumentos de processo.
read_secret() {
  local file=$1 envname=$2
  if [[ -n $file ]]; then [[ -r $file ]] || die "não foi possível ler $file"; tr -d '\r\n' < "$file"; return; fi
  printf '%s' "${!envname:-}"
}

wait_http() {
  local url=$1 timeout=${2:-60} i
  for ((i = 0; i < timeout; i++)); do
    curl -fsS -o /dev/null -m 2 "$url" 2>/dev/null && return 0
    sleep 1
  done
  return 1
}
