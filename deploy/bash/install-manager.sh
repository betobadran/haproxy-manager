#!/usr/bin/env bash
# Instala/atualiza o HAPM manager como serviço systemd. Idempotente.
#
# Uso (como root, a partir do bundle):
#   HAPM_ADMIN_PASSWORD='...' ./install-manager.sh
#   ./install-manager.sh --port 8080 --db-url jdbc:postgresql://db:5432/hapm --db-user hapm --db-password-file /root/db-pass \
#                        --env-file ./auth.env      # LDAP/OIDC (ver docs/autenticacao.md)

# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() {
  cat <<'USAGE'
Opções:
  --port PORTA               porta HTTP (padrão: 8080)
  --admin-password-file ARQ  senha do admin inicial (ou HAPM_ADMIN_PASSWORD); sem ela, uma senha aleatória vai para o log
  --secret-file ARQ          segredo de sessão (ou HAPM_SECRET); obrigatório e igual em todos os managers de um cluster de managers
  --db-url URL               JDBC do PostgreSQL (padrão: H2 local em /var/lib/hapm-manager)
  --db-user USUARIO
  --db-password-file ARQ     (ou HAPM_DB_PASSWORD)
  --env-file ARQ             variáveis extras (HAPM_LDAP_*, HAPM_OIDC_*, SPRING_SECURITY_OAUTH2_*...), mescladas no manager.env
  --no-haproxy               não instala o haproxy local (usado só para validar com haproxy -c antes de publicar)
  --bundle-dir DIR
  --no-start
  -h, --help
USAGE
}

PORT=8080 ADMIN_FILE='' SECRET_FILE='' DB_URL='' DB_USER='' DB_PASS_FILE='' EXTRA_ENV='' WITH_HAPROXY=1 START=1
while (($#)); do
  case $1 in
    --port) PORT=$2; shift 2 ;;
    --admin-password-file) ADMIN_FILE=$2; shift 2 ;;
    --secret-file) SECRET_FILE=$2; shift 2 ;;
    --db-url) DB_URL=$2; shift 2 ;;
    --db-user) DB_USER=$2; shift 2 ;;
    --db-password-file) DB_PASS_FILE=$2; shift 2 ;;
    --env-file) EXTRA_ENV=$2; shift 2 ;;
    --no-haproxy) WITH_HAPROXY=0; shift ;;
    --bundle-dir) HAPM_BUNDLE_DIR=$2; shift 2 ;;
    --no-start) START=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; die "opção desconhecida: $1" ;;
  esac
done

require_root
if ! [[ $PORT =~ ^[0-9]+$ ]] || ((PORT < 1 || PORT > 65535)); then die "--port inválida"; fi
B=$HAPM_BUNDLE_DIR
[[ -f $B/hapm-manager.jar ]] || die "bundle sem hapm-manager.jar (gere com make-bundle.sh)"

log "HAPM manager na porta $PORT"
pkgs=("$JAVA_PKG" curl)
((WITH_HAPROXY)) && pkgs+=(haproxy)
ensure_packages "${pkgs[@]}"

if ! id hapm >/dev/null 2>&1; then
  useradd --system --home-dir /var/lib/hapm-manager --shell /usr/sbin/nologin hapm
  info "usuário hapm criado"
fi
install -d -m 0750 -o hapm -g hapm /var/lib/hapm-manager /etc/hapm-manager
install -d -m 0755 /opt/hapm-manager

changed=0
copy_if_changed "$B/hapm-manager.jar" /opt/hapm-manager/hapm-manager.jar 0644 && changed=1

ENV=/etc/hapm-manager/manager.env
[[ -f $ENV ]] || { install -m 0600 -o hapm -g hapm /dev/null "$ENV"; echo '# Gerenciado pelo install-manager.sh do HAPM' > "$ENV"; }
set_env_var "$ENV" HAPM_PORT "$PORT" && changed=1
set_env_var "$ENV" HAPM_DATA_DIR /var/lib/hapm-manager && changed=1
v=$(read_secret "$ADMIN_FILE" HAPM_ADMIN_PASSWORD); [[ -n $v ]] && set_env_var "$ENV" HAPM_ADMIN_PASSWORD "$v" && changed=1
v=$(read_secret "$SECRET_FILE" HAPM_SECRET); [[ -n $v ]] && set_env_var "$ENV" HAPM_SECRET "$v" && changed=1
if [[ -n $DB_URL ]]; then
  set_env_var "$ENV" HAPM_DB_URL "$DB_URL" && changed=1
  set_env_var "$ENV" HAPM_DB_USER "$DB_USER" && changed=1
  v=$(read_secret "$DB_PASS_FILE" HAPM_DB_PASSWORD); set_env_var "$ENV" HAPM_DB_PASSWORD "$v" && changed=1
fi
if [[ -n $EXTRA_ENV ]]; then
  [[ -r $EXTRA_ENV ]] || die "não foi possível ler $EXTRA_ENV"
  while IFS= read -r line || [[ -n $line ]]; do
    [[ $line =~ ^[[:space:]]*(#|$) ]] && continue
    [[ $line =~ ^([A-Z_][A-Z0-9_]*)=(.*)$ ]] || die "linha inválida em $EXTRA_ENV: $line"
    set_env_var "$ENV" "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" && changed=1
  done < "$EXTRA_ENV"
fi
chown hapm:hapm "$ENV"; chmod 0600 "$ENV"

cat <<'UNIT' | write_if_changed /etc/systemd/system/hapm-manager.service 0644 && changed=1
[Unit]
Description=HAPM manager (control plane HAProxy/Keepalived)
After=network-online.target
Wants=network-online.target

[Service]
User=hapm
EnvironmentFile=/etc/hapm-manager/manager.env
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError -jar /opt/hapm-manager/hapm-manager.jar
Restart=always
RestartSec=5
NoNewPrivileges=true
ProtectSystem=full
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT

if ! has_systemd; then warn "systemd não encontrado: arquivos instalados, serviço não iniciado"; exit 0; fi
systemctl daemon-reload
systemctl enable -q hapm-manager
((START)) || { log "instalado (--no-start)"; exit 0; }
if ((changed)) || ! systemctl is-active -q hapm-manager; then systemctl restart hapm-manager; fi
wait_http "http://127.0.0.1:$PORT/actuator/health" 90 || die "manager não respondeu (veja: journalctl -u hapm-manager)"
log "manager pronto em http://$(hostname -f 2>/dev/null || hostname):$PORT"
if ! grep -q '^HAPM_ADMIN_PASSWORD=' "$ENV"; then
  info "senha inicial do admin: journalctl -u hapm-manager | grep 'usuário inicial'"
fi
