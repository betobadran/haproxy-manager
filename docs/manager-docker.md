# Manager em Docker (opcional)

O manager pode rodar em contêiner em produção. É **opcional**: a instalação padrão continua sendo o
`deploy/bash/install-manager.sh` (systemd). Escolha Docker quando você já opera contêineres e quer o manager no mesmo
esquema de deploy, monitoramento e backup.

Só o **manager** vai para o contêiner. Os nós (HAProxy + Keepalived + agente) continuam nos servidores, instalados com
`deploy/bash/install-node.sh`, porque precisam de VIP, VRRP e portas baixas no host.

## O que sobe

`deploy/docker/docker-compose.yml`:

| Serviço | Papel | Portas no host |
|---------|-------|----------------|
| `db` | PostgreSQL 17 (estado do manager: configuração, usuários, segredo de sessão, chave de assinatura) | nenhuma |
| `manager` | interface/API na 8080 e porta exclusiva dos agentes na 8081 (`HAPM_AGENT_PORT`) | nenhuma |
| `proxy` | Caddy com TLS na frente do manager | 80, 443 (interface), 8443 (agentes), 8088 (desafio ACME) |

## Passo a passo

```bash
cd deploy/docker
cp .env.example .env               # domínio, TLS e segredos
openssl rand -base64 48            # gere HAPM_DB_PASSWORD e HAPM_SECRET
(cd ../.. && mvn -q -DskipTests package)
docker compose up -d --build
docker compose logs manager | grep 'usuário inicial'   # se HAPM_ADMIN_PASSWORD ficou vazio
```

Acesse `https://<MANAGER_DOMAIN>/`.

### TLS (`MANAGER_TLS` no `.env`)

- **vazio**: certificado automático do Let's Encrypt. O domínio precisa ser público e a porta 80 acessível.
- **`tls internal`**: CA própria do Caddy, para rede interna. A CA fica no volume `caddy-data`
  (`/data/caddy/pki/authorities/local/root.crt`) e precisa ser confiável nos nós (veja abaixo).
- **`tls /certs/fullchain.pem /certs/privkey.pem`**: seu certificado (por exemplo, da CA corporativa), em
  `deploy/docker/certs/`.

## Nós e firewall

- **Porta 443**: só para a rede de administração.
- **Porta 8443**: só para a rede dos nós. Ela atende apenas `/agent/**`; o resto responde 404.
- **Porta 8088**: os nós repassam `/.well-known/acme-challenge/` para o manager (Let's Encrypt dos listeners). Em cada
  cluster, configure **Manager visto pelo HAProxy** = `<host do manager>:8088`.

Instale os nós apontando para a porta dos agentes:

```bash
sudo -E ./install-node.sh --manager-url https://<MANAGER_DOMAIN>:8443 ...
```

O agente usa o truststore padrão do Java. Com `tls internal` ou CA corporativa, importe a CA no truststore dos nós
(`keytool -importcert -cacerts ...`) ou passe `-Djavax.net.ssl.trustStore=...` em `JAVA_OPTS` no `agent.env`.

## Variáveis extras

LDAP/AD, OIDC, SMTP, região AWS e Loki vão em `deploy/docker/manager.env` (copie de `manager.env.example`); o arquivo
é opcional e não é versionado. Para a AWS, prefira a role da instância ou da task (ECS) ou IRSA (EKS) a chaves fixas.

## Backup e atualização

- **O banco é o estado inteiro do manager**, inclusive a chave que assina as configurações. Se ele se perder, a chave
  muda e os agentes param de aplicar versões novas. Faça backup do volume `pgdata` ou use `pg_dump`:
  `docker compose exec db pg_dump -U hapm hapm > hapm-$(date +%F).sql`.
- **Atualizar**: gere o jar novo e rode `docker compose up -d --build manager`. As migrações do banco rodam sozinhas.

## Mais de um manager

Com PostgreSQL, vários managers podem compartilhar o mesmo banco. Use o mesmo `HAPM_SECRET` em todos, um banco
PostgreSQL gerenciado (fora deste compose) e um balanceador na frente; veja [banco-de-dados.md](banco-de-dados.md).
