# Instaladores Bash do HAPM

| Script | Onde roda | Faz |
|---|---|---|
| `make-bundle.sh` | sua máquina / CI | junta jars, instaladores, scripts do keepalived e config do OTel em `target/hapm-bundle` (+ `.tar.gz` e `SHA256SUMS`) |
| `deploy.sh` | sua máquina | envia o bundle por SSH e roda os instaladores com `sudo`; nós um por vez |
| `install-manager.sh` | servidor do manager | Java, usuário `hapm`, jar, `manager.env` (0600), unit systemd |
| `install-node.sh` | cada nó HAProxy | HAProxy, Keepalived, sysctls, drop-in systemd, agente, OTel Collector, firewall VRRP |
| `lib.sh` | — | funções comuns (detecção Debian/RHEL, pacotes, escrita idempotente) |

Use `--help` em cada instalador para ver as opções. Inventário de exemplo: `hosts.example.conf`.

Requisitos nos servidores: systemd, usuário SSH com `sudo` sem senha (para o `deploy.sh`), Debian/Ubuntu ou
RHEL/Rocky/Alma. Sem internet, deixe `haproxy`, `keepalived` e `openjdk-21-jre-headless`
(`java-21-openjdk-headless` no RHEL) disponíveis num repositório interno, e passe `--otelcol-package`.
