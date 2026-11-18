# Banco de dados do manager

| Instalação | Banco | Quando usar |
|---|---|---|
| **Local** (padrão) | **SQLite**, arquivo `HAPM_DATA_DIR/hapm.db` (`/var/lib/hapm-manager/hapm.db` no `install-manager.sh`, `/data/hapm.db` na imagem Docker) | um único manager, lab, ambientes pequenos; zero configuração |
| **Completa** | **PostgreSQL** | produção, vários managers atrás de um balanceador (HA do control plane), backups com ferramentas corporativas |

Os nós HAProxy **não dependem do banco**: se o manager ou o banco caírem, o tráfego continua; só não há novas publicações.

## SQLite (local)

Nada a configurar. O manager abre o arquivo com WAL (leituras não bloqueiam a escrita), transações `IMMEDIATE` e
`busy_timeout`, o que evita erros de concorrência entre heartbeats dos agentes e a UI.

Backup a quente (consistente, sem parar o manager):
```bash
sqlite3 /var/lib/hapm-manager/hapm.db ".backup '/backup/hapm-$(date +%F).db'"
```

## PostgreSQL (instalação completa)

PostgreSQL local, criado e configurado pelo instalador:
```bash
sudo ./install-manager.sh --with-postgres --admin-password-file /root/admin-pass
```
O instalador:
- instala o servidor (`postgresql` no Debian/Ubuntu, `postgresql-server` + `initdb` no RHEL);
- cria a role e o banco `hapm`, com senha aleatória em `/etc/hapm-manager/db-password` (0600);
- libera login por senha em `127.0.0.1` no `pg_hba.conf`;
- grava `HAPM_DB_URL`/`HAPM_DB_USER`/`HAPM_DB_PASSWORD` em `/etc/hapm-manager/manager.env` (0600).

Reexecutar é seguro: o banco e a senha existentes são mantidos.

PostgreSQL externo (RDS, Aurora, cluster corporativo):
```bash
sudo ./install-manager.sh --db-url jdbc:postgresql://db.interno:5432/hapm --db-user hapm --db-password-file /root/db-pass
```
ou, sem o instalador: `HAPM_DB_URL`, `HAPM_DB_USER` e `HAPM_DB_PASSWORD`.

Vários managers: aponte todos para o mesmo PostgreSQL e use o mesmo `HAPM_SECRET`.

## Migrações

Flyway, com scripts separados por banco em `haproxy-manager-server/src/main/resources/db/migration/{sqlite,postgresql}`
(mesmas versões nos dois). Elas rodam sozinhas na subida do manager.

Não há migração automática de dados de SQLite para PostgreSQL. Para trocar de banco, suba o manager novo no
PostgreSQL e recrie os clusters (ou exporte pela API).

## Testes

A suíte do manager roda por padrão em SQLite (um arquivo temporário por classe de teste). Contra PostgreSQL:
```bash
mvn -pl haproxy-manager-server test -Dhapm.test.pg=jdbc:postgresql://127.0.0.1:5432/postgres \
    -Dhapm.test.pg.user=postgres -Dhapm.test.pg.password=...
```
(cria um banco `hapm_t_<classe>` novo por classe de teste). As duas variantes passam.
