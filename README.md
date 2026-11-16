# HAPM — HAProxy HA Manager

Gerência de **HAProxy em alta disponibilidade** — algo como o *nginx-proxy-manager*, mas para HAProxy e com
foco em HA: setup dos nós, gestão de listeners, regras, backends e certificados (upload ou Let's Encrypt),
publicação versionada com rollout seguro e rollback, e monitoramento via **OpenTelemetry Collector → Prometheus → Grafana**.

| Ambiente | Topologia | Quem garante a HA da entrada |
|---|---|---|
| **On-premise** | HAProxy + **Keepalived** (VRRP unicast, VIP flutuante) | Keepalived move o VIP em ~3 s |
| **AWS (recomendado)** | **NLB** + Auto Scaling Group de HAProxy | NLB (multi-AZ, gerenciado pela AWS) |
| **AWS (alternativa)** | HAProxy + Keepalived movendo **Elastic IP / IP privado secundário** via API | Keepalived + script de failover |

Stack: **Java 21 + Maven**, Spring Boot 4.1 no manager, agente em Java puro (jar único, sem Spring),
UI em **React** compilada durante o build Maven e servida pelo próprio jar. Node/npm são usados **somente no build**
da UI (baixados pelo `frontend-maven-plugin`); nada de Node roda em produção.

**Documentação:** [primeiros passos (criar e configurar um cluster pela tela, failover automático)](docs/primeiros-passos.md) ·
[alertas de certificado](docs/alertas.md) · [banco de dados (SQLite / PostgreSQL)](docs/banco-de-dados.md) ·
[login corporativo e aprovações](docs/autenticacao.md) · [failover de IP na AWS](docs/aws-failover.md)

---

## Arquitetura

```mermaid
flowchart LR
  subgraph cp[Control plane]
    UI[UI React] --> M[hapm-manager<br/>Spring Boot]
    M --- DB[(SQLite / PostgreSQL)]
  end
  subgraph n1[Nó 1]
    A1[hapm-agent] -->|valida, troca release,<br/>reload sem queda| H1[HAProxy]
    A1 --> K1[Keepalived]
    O1[OTel Collector edge]
  end
  subgraph n2[Nó 2]
    A2[hapm-agent] --> H2[HAProxy]
    A2 --> K2[Keepalived]
    O2[OTel Collector edge]
  end
  A1 -- "heartbeat / pull (HTTPS)" --> M
  A2 -- "heartbeat / pull (HTTPS)" --> M
  K1 <-. VRRP unicast .-> K2
  O1 & O2 -- OTLP --> GW[OTel Collector gateway] -- remote write --> P[(Prometheus)] --> G[Grafana]
  GW -- scrape /metrics --> M
```

**Modelo pull:** o agente só precisa de saída HTTP(S) até o manager (nada abre portas de entrada nos nós).
A cada poucos segundos ele envia um heartbeat (versão aplicada, estado VRRP, resultado da última aplicação) e
recebe a versão desejada.

### Como uma publicação acontece
1. Você edita listeners/backends/certificados na UI. Nada vai para os nós até clicar em **Publicar**.
2. O manager gera o `haproxy.cfg` (e um `keepalived.conf` por nó), mostra o **diff** e valida com `haproxy -c`.
3. A publicação cria uma **versão imutável** (snapshot completo → rollback exato).
4. **Rollout seguro:** nós **BACKUP primeiro (canário)**, o **MASTER por último**; em modo sequencial, um nó por vez.
5. Em cada nó o agente grava a release em `/etc/haproxy/hapm/releases/vN`, valida de novo com `haproxy -c`,
   troca o symlink `current` **atomicamente** e recarrega o HAProxy sem derrubar conexões
   (master-worker / `systemctl reload`). Se o reload ou o health-check falharem, **volta sozinho** para a release anterior.
6. Se qualquer nó falhar, a publicação é marcada como falha e **todos os nós voltam para a última versão boa**
   — o MASTER nunca chega a receber uma config que falhou no canário.

### O que dá para gerenciar
- **Listeners (frontends):** binds múltiplos (HTTP/TCP, TLS com SNI via crt-list, HTTP/2), redirect HTTP→HTTPS, HSTS,
  X-Forwarded-For/Proto, allowlist de IPs, rate limit por IP, timeouts, bloco avançado.
- **Regras** (avaliadas em ordem, condições com AND e negação): host (inclusive `*.dominio`), caminho
  (exato/prefixo/sufixo/regex), método, IP de origem, header, SNI (TCP) → encaminhar para backend, redirecionar,
  negar, definir/remover header.
- **Backends:** servidores (peso, backup, maxconn, desativar), balanceamento, health-check HTTP/TCP, sessão
  persistente por cookie, TLS até os servidores, compressão, resolução DNS em runtime.
- **Certificados:** upload de PEM (valida par chave/certificado, extrai domínios e validade) ou **ACME/Let's Encrypt
  HTTP-01** com renovação automática (30 dias antes) e publicação automática após renovar.
- **Alertas de expiração de certificado** (30/14/7/1 dia, configurável) e de falha ACME: na **tela** (contador na barra
  lateral e banner no cluster, com reconhecimento), por **e-mail** (SMTP), **Microsoft Teams** (Workflows) e **webhook**
  assinado com HMAC. Detalhes em [docs/alertas.md](docs/alertas.md).
- **Nós:** auto-registro, prioridade VRRP (automática ou manual), estado MASTER/BACKUP, versão aplicada, erros.
- **Auditoria** de todas as ações; histórico de versões com diff e rollback.
- **Vários clusters num só manager:** cada cluster tem topologia própria (on-prem/keepalived, AWS NLB, AWS EIP),
  nós, VIPs, listeners, certificados, histórico de versões, política de aprovação e token de join independentes.
- **Tela de métricas ao vivo** por cluster ou por nó: req/s e conexões/s por listener, respostas por classe (2xx–5xx),
  taxa de 5xx, sessões, tráfego, estado de backends e servidores. Os agentes leem o exporter nativo do HAProxy e
  enviam no heartbeat; o manager guarda 1 h em memória, sem depender de Prometheus (que continua para histórico e alertas).
- **Interface em pt-BR e en-US** (detectado pelo navegador, alternável) e **tema claro / automático / escuro**;
  **formato de data, de hora e fuso horário** escolhidos por usuário e salvos na conta.
  O build da UI falha se algum texto novo não tiver tradução (`hapm-ui/scripts/i18n-keys.mjs`).
- **Login corporativo e governança:** LDAP/Active Directory e OIDC/SSO (Keycloak, Entra ID, Okta), papéis
  VIEWER / OPERATOR / APPROVER / ADMIN a partir dos grupos, e **aprovação de publicação com quatro olhos**
  (quem pede não aprova), publicação emergencial auditada. Detalhes em [docs/autenticacao.md](docs/autenticacao.md).

Segurança da geração: todo valor que vira configuração é validado contra injeção (sem quebras de linha, aspas ou
`#`); o bloco "avançado" não pode abrir novas seções; nomes `hapm_*` são reservados.

---

## Estrutura do repositório

```
pom.xml                    POM pai (Maven multi-módulo)
hapm-core/                 modelo, validação, render de haproxy.cfg/keepalived.conf (Java puro)
hapm-ui/                   React + Vite + TypeScript (frontend-maven-plugin -> META-INF/resources)
hapm-manager/              Spring Boot: API REST, UI, publicação, ACME, /metrics, SQLite ou PostgreSQL  (+ Dockerfile)
hapm-agent/                agente Java (jar único) + scripts do keepalived + unit systemd
observability/             OTel Collector (edge e gateway), Prometheus (+ alertas), Grafana (dashboard)
deploy/bash/               instaladores Bash (manager e nós) + deploy via SSH + bundle
deploy/terraform/aws-nlb/  AWS: NLB + Auto Scaling Group
deploy/aws/                política IAM para o modo aws-eip
lab/                       ambiente completo em Docker (2 nós com VIP real, backends, observabilidade)
```

## Build

Requisitos: **JDK 21** e **Maven 3.9+** (o Node para a UI é baixado pelo próprio build).

```bash
mvn clean verify          # compila tudo e roda os testes
# artefatos:
#   hapm-manager/target/hapm-manager.jar   (inclui a UI)
#   hapm-agent/target/hapm-agent.jar
```

Rede corporativa sem acesso à internet: aponte `-Dnode.download.root=` e `-Dnpm.registry=` para o
Nexus/Artifactory (há proxies prontos para `nodejs.org/dist` e para o registry npm), além do mirror Maven.

Os testes usam `haproxy` e `keepalived` reais quando instalados (são pulados se não existirem), incluindo um
teste ponta a ponta com manager + agente + HAProxy em master-worker.

Desenvolvimento da UI com hot reload: `cd hapm-ui && npx vite` (faz proxy de `/api` para o manager em `:8080`).
Traduções: os textos pt-BR são a chave e `hapm-ui/src/i18n.en.ts` traz o en-US. Mensagens de validação vindas da
API ainda são exibidas em pt-BR.

## Lab completo em Docker

Sobe manager, 2 nós HAProxy+Keepalived compartilhando o VIP `172.30.0.100`, 3 backends, OTel Collector
(edge por nó + gateway), Prometheus e Grafana — e já publica a primeira versão.

```bash
./lab/setup.sh
curl http://172.30.0.100/            # round-robin entre app1 e app2
docker compose -f lab/docker-compose.yml stop lb1   # failover: o VIP vai para o lb2 em ~3 s
```

- Manager: http://localhost:8080 (admin / admin12345)
- Grafana: http://localhost:3000 (dashboard *HAPM · HAProxy alta disponibilidade*)
- Prometheus: http://localhost:9090

## On-premise (scripts Bash)

Tudo em Bash, sem dependências além de `ssh`/`tar` na sua máquina. Os instaladores são **idempotentes**: rodar de
novo só altera e reinicia o que mudou. Rodar com o mesmo bundle não reinicia o HAProxy nem derruba conexões.
Funcionam em **Debian/Ubuntu e RHEL/Rocky/Alma**. Se os pacotes (`haproxy`, `keepalived`, Java 21) já estiverem
instalados ou vierem de um repositório interno, não precisam de internet.

```bash
mvn -q -DskipTests package
deploy/bash/make-bundle.sh                    # -> target/hapm-bundle/ e target/hapm-bundle-<versão>.tar.gz

cp deploy/bash/hosts.example.conf hosts.conf  # servidores, usuário ssh (com sudo) e caminhos dos segredos
deploy/bash/deploy.sh -i hosts.conf manager   # 1) instala o manager
# 2) na UI: crie o cluster (VIPs, interface, router id) e salve o token de join no JOIN_TOKEN_FILE
deploy/bash/deploy.sh -i hosts.conf nodes     # 3) nós, um por vez (nunca os dois lados do VIP juntos)
deploy/bash/deploy.sh -i hosts.conf nodes lb02   # atualizar só um nó
```

Sem SSH central: copie o bundle para o servidor e rode o instalador localmente:

```bash
sudo ./install-manager.sh --admin-password-file /root/admin-pass [--with-postgres | --db-url jdbc:postgresql://... --db-user hapm --db-password-file ...] [--env-file auth.env]
sudo ./install-node.sh --manager-url http://hapm:8080 --join-token-file /root/token --interface eth0 \
     [--otel-gateway otel-gw:4317 --cluster-label dc1] [--vrrp-peers 10.10.0.12] [--no-keepalived]
```

O que o `install-node.sh` faz:
- instala os pacotes `haproxy`, `keepalived`, Java 21, `curl` e `iproute2`;
- configura os sysctls `ip_nonlocal_bind` (bind no VIP mesmo em BACKUP), `somaxconn` e afins;
- cria um drop-in systemd que aponta o HAProxy para `/etc/haproxy/hapm/current/haproxy.cfg`, com master CLI e
  `LimitNOFILE`; o mesmo drop-in vale para Debian e RHEL;
- instala os scripts do keepalived, o agente com sua unit e um `agent.env` 0600, e a release bootstrap;
- opcionalmente instala o OTel Collector (pacote baixado ou `--otelcol-package` local);
- opcionalmente libera VRRP no firewalld;
- verifica `:8405/healthz` e o agente.

Os segredos (senha do admin, token de join) nunca vão em argumento de linha de comando: o `deploy.sh` envia pelo
stdin do SSH para um arquivo 0600 temporário, apagado ao final.

Validado em "VMs" Ubuntu 24.04 com systemd e SSH: instalação de manager + 2 nós via `deploy.sh`, VIP ativo,
failover ao parar o HAProxy do MASTER e reexecução sem interrupção de tráfego.

Firewall entre os nós: liberar **VRRP (protocolo IP 112)**.

Recomendações: 2 ou 3 nós em racks/hosts físicos diferentes; `virtual_router_id` único por segmento L2;
manager atrás de TLS (reverse proxy ou o próprio HAProxy).

## AWS

### Opção 1 (recomendada): NLB + Auto Scaling — `deploy/terraform/aws-nlb`
O NLB é a entrada multi-AZ; os nós HAProxy ficam num ASG e se registram sozinhos no manager (nome = instance-id).
Health-check do NLB em `:8405/healthz` (frontend interno `hapm_stats`), IP do cliente preservado, IMDSv2,
acesso por SSM Session Manager, `instance_refresh` rolling. O token de join fica no SSM Parameter Store
(SecureString). O user-data baixa do S3 o **mesmo bundle** do on-premise (`make-bundle.sh`) e roda o
`install-node.sh --no-keepalived`. Crie o cluster no manager com topologia **aws-nlb**.

```bash
cd deploy/terraform/aws-nlb
cp terraform.tfvars.example terraform.tfvars   # preencha
terraform init && terraform apply
```

> ⚠️ Este módulo **ainda não foi validado** com `terraform validate/plan` (não foi possível baixar o Terraform no
> ambiente em que foi escrito). Revise e rode `terraform validate` antes do primeiro uso.

### Opção 2: Keepalived + failover de IP entre ENIs (topologia **aws-eip**) — ver [docs/aws-failover.md](docs/aws-failover.md)
Para quando não se quer NLB (custo, IP fixo, protocolos específicos). Keepalived em **unicast**; ao virar MASTER, o
nó traz o IP do serviço para a **própria ENI**, tudo configurado pela tela:
- **mesma subnet:** IP privado secundário reatribuído entre as ENIs;
- **subnets/AZs diferentes:** IP de *overlay* (fora do CIDR da VPC) com a rota `/32` das tabelas indicadas apontando
  para a ENI do MASTER (source/dest check desligado automaticamente);
- **Elastic IP** opcional acompanhando o MASTER.

Cada nó roda um **preflight** (permissões IAM com `--dry-run`, ENI, subnet, tabelas de rotas) e o resultado aparece
na visão geral do cluster. Requer a política `deploy/aws/eip-failover-iam-policy.json`, AWS CLI nos nós e o
protocolo 112 liberado entre eles no security group.

## Observabilidade

```
HAProxy (exporter nativo :8405/metrics) ┐
hapm-agent (:9101/metrics)              ├─> OTel Collector edge (por nó) ─OTLP─> OTel gateway ─remote write─> Prometheus ─> Grafana
métricas do host (hostmetrics)          ┘                                         ↑ raspa o manager (/metrics)
```

- `observability/otel-collector/node.yaml` e `gateway.yaml`; labels `hapm_cluster` / `hapm_node` em tudo.
- `observability/prometheus/alerts.yml`: sem MASTER, **split-brain**, keepalived parado/FAULT, HAProxy fora,
  nó sem heartbeat, publicação falhou, config divergente, backend sem servidores, servidor DOWN, 5xx > 5%,
  certificado expirando/expirado.
- `observability/grafana/dashboards/hapm-overview.json`: MASTER atual, linha do tempo VRRP (failovers),
  versão por nó, req/s, respostas por classe, 5xx, sessões, negadas (allowlist/rate limit/deny), backends,
  servidores fora, expiração de certificados, CPU/memória dos nós. Gerado por `build-dashboard.mjs`
  (ferramenta de desenvolvimento; o JSON versionado é o que vai para o Grafana).

Métricas próprias: `hapm_vrrp_is_master`, `hapm_vrrp_state{state}`, `hapm_keepalived_running`, `hapm_haproxy_up`,
`hapm_config_applied_version`, `hapm_config_desired_version`, `hapm_config_apply_total{result}`,
`hapm_certificate_not_after_timestamp_seconds`, `hapm_node_online`, `hapm_cluster_deployment_failed`…

## Configuração

**Manager** (variáveis de ambiente): `HAPM_PORT` (8080), `HAPM_DATA_DIR`, `HAPM_ADMIN_USER`/`HAPM_ADMIN_PASSWORD`
(senha aleatória no log se vazio), `HAPM_SECRET` (assinatura das sessões; defina o mesmo em todos os managers),
`HAPM_DB_URL`/`HAPM_DB_USER`/`HAPM_DB_PASSWORD` (PostgreSQL; sem eles, SQLite em `HAPM_DATA_DIR` — ver [docs/banco-de-dados.md](docs/banco-de-dados.md)), `HAPM_ACME_DIRECTORY`
(`acme://letsencrypt.org`; staging: `acme://letsencrypt.org/staging`), `HAPM_HAPROXY_BIN`, `HAPM_NODE_OFFLINE_AFTER` (15 s).

**Agente**: ver `hapm-agent/src/main/dist/systemd/agent.env.example` — `HAPM_MANAGER_URL`, `HAPM_JOIN_TOKEN`,
`HAPM_NODE_NAME`, `HAPM_NODE_INTERFACE`/`HAPM_NODE_ADDRESS`, `HAPM_RELOAD_MODE` (`systemd` | `master-socket` | `command`).
CA interna para o manager: importe no truststore do JVM ou use `-Djavax.net.ssl.trustStore=`.

**Login corporativo:** `HAPM_LDAP_*` (LDAP/AD), `HAPM_OIDC_*` + `SPRING_SECURITY_OAUTH2_CLIENT_*` (SSO),
`HAPM_ROLES_ADMIN|APPROVER|OPERATOR|VIEWER` (grupos → papéis) — ver [docs/autenticacao.md](docs/autenticacao.md).

**Banco:** **SQLite** na instalação local (padrão, zero configuração) e **PostgreSQL** na instalação completa
(`install-manager.sh --with-postgres` instala e configura um PostgreSQL local; `--db-url` usa um externo).

**HA do próprio manager:** os nós continuam servindo tráfego se o manager cair (só não recebem mudanças). Para
redundância do control plane: 2+ instâncias atrás de um balanceador, mesmo `HAPM_SECRET`, banco PostgreSQL.

## API (resumo)

Autenticação: `POST /api/auth/login` (local/LDAP) ou SSO em `/oauth2/authorization/hapm` → `Authorization: Bearer <token>`.

| Método | Caminho | |
|---|---|---|
| GET/POST | `/api/clusters` | listar / criar cluster |
| GET/PUT/DELETE | `/api/clusters/{id}` | detalhar (inclui token de join) / alterar / remover |
| GET/POST/PUT/DELETE | `/api/clusters/{id}/{frontends\|backends\|certificates}[/{oid}]` | CRUD |
| POST | `/api/clusters/{id}/certificates/{oid}/issue` | emitir/renovar ACME |
| GET | `/api/clusters/{id}/nodes` | nós, estado VRRP, versão desejada |
| GET | `/api/clusters/{id}/preview` | config gerada, diff, `haproxy -c` |
| POST | `/api/clusters/{id}/deployments` | publicar (`{"note": "..."}`); com aprovação obrigatória cria um pedido (202). ADMIN: `{"emergency": true}` |
| GET/POST | `/api/clusters/{id}/requests[/{rid}/approve\|reject\|cancel]` | pedidos de publicação |
| GET/POST/PUT/DELETE | `/api/users[/{username}]` | usuários locais (ADMIN) |
| POST | `/api/clusters/{id}/deployments/{did}/rollback` | nova versão com a config antiga |
| GET | `/api/audit` | auditoria |
| GET/POST/PUT | `/api/notifications[/count\|/{id}/ack\|/settings\|/test/{canal}\|/check]` | alertas e canais ([docs/alertas.md](docs/alertas.md)) |
| — | `/agent/v1/heartbeat`, `/agent/v1/bundle/{v}` | uso dos agentes (token de join) |
| GET | `/metrics`, `/actuator/health` | Prometheus / health |

## Limitações conhecidas e próximos passos
- Papéis são globais (valem para todos os clusters); papéis **por cluster/equipe** são o próximo passo natural.
- Chaves privadas dos certificados ficam no banco do manager (proteja o volume/banco; criptografia em repouso com KMS/Vault está no roadmap).
- ACME apenas HTTP-01 (sem wildcard); DNS-01 (Route 53 etc.) no roadmap.
- Logs de acesso do HAProxy → OTel (receiver syslog) → Loki ainda não incluídos.
- Scale-in na AWS deixa o nó como "offline" no manager até ser removido (lifecycle hook para remoção automática no roadmap).
- Templates de erro customizados, `userlist`/basic auth, cache e peers (stick-table sincronizada entre nós) ainda não expostos na UI (use o bloco avançado).
