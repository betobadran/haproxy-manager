#!/usr/bin/env bash
# Instala/atualiza o HAPM manager como serviço systemd. Idempotente.
#
# Uso (como root, a partir do bundle):
#   HAPM_ADMIN_PASSWORD='...' ./install-manager.sh
#   ./install-manager.sh --with-postgres                       # instalação completa: PostgreSQL local criado e configurado
#   ./install-manager.sh --db-url jdbc:postgresql://db:5432/hapm --db-user hapm --db-password-file /root/db-pass   # PostgreSQL externo
#   ./install-manager.sh --env-file ./auth.env                  # LDAP/OIDC (ver docs/autenticacao.md)
# Sem opções de banco: SQLite em /var/lib/hapm-manager/hapm.db (instalação local, um único manager).

# shellcheck source=lib.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() {
  cat <<'USAGE'
Opções:
  --port PORTA               porta HTTP (padrão: 8080)
  --agent-port PORTA         porta exclusiva dos agentes (data plane); a interface deixa de atender os agentes
  --admin-password-file ARQ  senha do admin inicial (ou HAPM_ADMIN_PASSWORD); sem ela, uma senha aleatória vai para o log
  --secret-file ARQ          segredo de sessão (ou HAPM_SECRET); obrigatório e igual em todos os managers de um cluster de managers
  --with-postgres            instala o PostgreSQL neste servidor, cria usuário/banco "hapm" (senha aleatória) e usa-o
  --db-url URL               JDBC de um PostgreSQL externo (padrão: SQLite em /var/lib/hapm-manager/hapm.db)
  --db-user USUARIO
  --db-password-file ARQ     (ou HAPM_DB_PASSWORD)
  --env-file ARQ             variáveis extras (HAPM_LDAP_*, HAPM_OIDC_*, SPRING_SECURITY_OAUTH2_*...), mescladas no manager.env
  --no-haproxy               não instala o haproxy local (usado só para validar com haproxy -c antes de publicar)
  --bundle-dir DIR
  --no-start
  -h, --help
USAGE
}

AGENT_PORT=0 PORT=8080 ADMIN_FILE='' SECRET_FILE='' DB_URL='' DB_USER='' DB_PASS_FILE='' EXTRA_ENV='' WITH_HAPROXY=1 START=1 WITH_PG=0
while (($#)); do
  case $1 in
    --port) PORT=$2; shift 2 ;;
    --agent-port) AGENT_PORT=$2; shift 2 ;;
    --admin-password-file) ADMIN_FILE=$2; shift 2 ;;
    --secret-file) SECRET_FILE=$2; shift 2 ;;
    --with-postgres) WITH_PG=1; shift ;;
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
((WITH_PG)) && [[ -n $DB_URL ]] && die "use --with-postgres OU --db-url, não os dois"
if ! [[ $PORT =~ ^[0-9]+$ ]] || ((PORT < 1 || PORT > 65535)); then die "--port inválida"; fi
if ! [[ $AGENT_PORT =~ ^[0-9]+$ ]] || ((AGENT_PORT > 65535)) || ((AGENT_PORT == PORT)); then die "--agent-port inválida (diferente de --port)"; fi
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

# ------------------------------------------------------------------ PostgreSQL local (instalação completa)
setup_postgres() {
  local fam pwfile=/etc/hapm-manager/db-password hba
  fam=$(os_family)
  log "PostgreSQL local"
  ensure_packages "postgresql:postgresql-server"
  if [[ $fam == rhel ]] && [[ ! -s /var/lib/pgsql/data/PG_VERSION ]]; then postgresql-setup --initdb; fi
  if has_systemd; then systemctl enable -q --now postgresql; fi
  local i
  for i in $(seq 1 30); do runuser -u postgres -- psql -tAc 'select 1' >/dev/null 2>&1 && break; sleep 1; done
  runuser -u postgres -- psql -tAc 'select 1' >/dev/null 2>&1 || die "PostgreSQL não respondeu"
  if [[ ! -s $pwfile ]]; then
    umask 077
    head -c 24 /dev/urandom | base64 | tr -d '/+=\n' > "$pwfile"
    umask 022
    info "senha do banco gerada em $pwfile"
  fi
  chmod 600 "$pwfile"
  local pw
  pw=$(cat "$pwfile")
  if [[ $(runuser -u postgres -- psql -tAc "select 1 from pg_roles where rolname='hapm'") != 1 ]]; then
    runuser -u postgres -- psql -q -c "create role hapm login password '$pw'"
    info "role hapm criada"
  else
    runuser -u postgres -- psql -q -c "alter role hapm password '$pw'"
  fi
  if [[ $(runuser -u postgres -- psql -tAc "select 1 from pg_database where datname='hapm'") != 1 ]]; then
    runuser -u postgres -- createdb -O hapm hapm
    info "banco hapm criado"
  fi
  # garante login por senha em 127.0.0.1 (o padrão do RHEL é ident)
  hba=$(runuser -u postgres -- psql -tAc 'show hba_file')
  if ! grep -qE '^host[[:space:]]+hapm[[:space:]]+hapm[[:space:]]+127\.0\.0\.1/32' "$hba"; then
    sed -i '1i host    hapm    hapm    127.0.0.1/32    scram-sha-256' "$hba"
    runuser -u postgres -- psql -q -c 'select pg_reload_conf()' >/dev/null
    info "pg_hba: acesso por senha para hapm em 127.0.0.1"
  fi
  DB_URL=jdbc:postgresql://127.0.0.1:5432/hapm DB_USER=hapm DB_PASS_FILE=$pwfile
}
((WITH_PG)) && setup_postgres

ENV=/etc/hapm-manager/manager.env
[[ -f $ENV ]] || { install -m 0600 -o hapm -g hapm /dev/null "$ENV"; echo '# Gerenciado pelo install-manager.sh do HAPM' > "$ENV"; }
set_env_var "$ENV" HAPM_PORT "$PORT" && changed=1
set_env_var "$ENV" HAPM_AGENT_PORT "$AGENT_PORT" && changed=1
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
After=network-online.target postgresql.service
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
if grep -q '^HAPM_DB_URL=jdbc:postgresql' "$ENV"; then info "banco: PostgreSQL ($(grep '^HAPM_DB_URL=' "$ENV" | cut -d= -f2-))"; else info "banco: SQLite (/var/lib/hapm-manager/hapm.db)"; fi
if ! grep -q '^HAPM_ADMIN_PASSWORD=' "$ENV"; then
  info "senha inicial do admin: journalctl -u hapm-manager | grep 'usuário inicial'"
fi
