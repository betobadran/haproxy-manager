# Alertas: certificados, failover, nós, servidores e publicações

Os alertas aparecem na **tela** e são enviados por **e-mail**, **Microsoft Teams** e/ou **webhook**. Há dois grupos:
certificados (verificados a cada hora) e **alertas operacionais** (detectados a partir dos heartbeats dos agentes, em
segundos). Todos também ficam na **linha do tempo** do cluster (ver [operacao.md](operacao.md)).

## Quando um alerta é gerado

| Evento | Nível | Tipo (`kind`) |
|---|---|---|
| Certificado vence em menos de *N* dias (limites padrão: 30, 14, 7 e 1) | atenção; **crítico** abaixo de 7 dias | `cert-expiring` |
| Certificado já expirado | crítico | `cert-expired` |
| Falha ao emitir/renovar via ACME | crítico | `acme-failed` |

Cada limite gera **um único alerta** por certificado: ao cruzar 30 dias você recebe um aviso, ao cruzar 14 outro, e assim
por diante. Se o certificado for renovado (nova data de validade), a contagem recomeça. Isso evita spam a cada hora.

Os limites são configurados em **Canais de notificação › Certificados: quando alertar** (1 a 365 dias, separados por vírgula).

### Alertas operacionais

| Evento | Nível | Tipo (`kind`) | Como é detectado |
|---|---|---|---|
| **Failover**: o VIP mudou de nó | atenção | `failover` | um nó passa a MASTER e o MASTER anterior era outro |
| **Nó offline** | atenção; **crítico** se não sobrou nenhum nó online | `node-offline` | sem heartbeat há mais de *N* segundos (padrão 60) |
| Nó voltou | info | `node-online` | heartbeat depois de um alerta de offline |
| Nó removido (scale-in na AWS ou remoção automática) | info | `node-left` | ver [operacao.md](operacao.md#scale-in-na-aws) |
| **Split-brain**: dois ou mais nós MASTER | crítico | `split-brain` | mesma condição em 2 verificações seguidas (~10 s) |
| **Sem MASTER**: há nós online, nenhum com o VIP | crítico | `no-master` | idem |
| VRRP normalizado | info | `vrrp-ok` | volta a haver exatamente um MASTER |
| **Publicação falhou** (os nós voltaram para a última versão boa) | crítico | `deploy-failed` | erro em um nó ou timeout |
| **Servidor de backend DOWN** (health-check do HAProxy) | atenção; **crítico** se o backend ficou sem servidores | `server-down` | estado vindo do exporter do HAProxy de cada nó |
| Servidor voltou | info | `server-up` | |

Servidores que você **drenou** ou pôs **em manutenção** não geram alerta (só DOWN do health-check conta). Cada tipo
pode ser desligado em **Canais de notificação › Alertas operacionais**, onde também se define o tempo para considerar um
nó offline (15 a 3600 s). O "estado atual" de cada nó/servidor é o último evento gravado, então reiniciar o manager
não repete alertas já enviados.

## Tela

- **Alertas** na barra lateral, com o número de alertas não reconhecidos (atualizado a cada 30 s).
- A lista mostra nível, horário, cluster, mensagem e o **status da entrega em cada canal** (✓/✗; passe o mouse no ✗
  para ver o erro).
- **Reconhecer** (qualquer papel) tira o alerta da contagem; o reconhecimento fica registrado com usuário e horário,
  e na auditoria.
- A **Visão geral** de cada cluster mostra um banner com os alertas pendentes daquele cluster.
- **Verificar certificados agora** (ADMIN) roda a verificação sem esperar a próxima hora.

## Canais (ADMIN: Canais de notificação)

Cada canal tem um botão **Enviar teste**. Senhas, URL do Teams e segredo do webhook nunca voltam para o navegador
(aparecem como `********`); deixe o campo como está para manter o valor salvo.

### E-mail (SMTP)

Servidor, porta (587 com STARTTLS, 465 com SSL/TLS direto, 25 sem TLS), usuário/senha (opcional), remetente e
destinatários. O assunto vem como `[HAProxy Manager CRÍTICO] Certificado X expira em N dia(s) (cluster Y)`.

### Microsoft Teams

Use um webhook de **Workflows** (o conector "Incoming Webhook" do Office 365 foi descontinuado):

1. No canal do Teams: **⋯ › Workflows › "Postar em um canal quando uma solicitação de webhook for recebida"**.
2. Escolha a equipe/canal e copie a URL gerada (HTTPS).
3. Cole em **URL do webhook (Workflows)**, marque *ativo*, salve e clique em **Enviar teste**.

O HAProxy Manager envia um *Adaptive Card* com nível (cor), título, mensagem e horário.

### Webhook genérico

`POST` com `Content-Type: application/json` para a URL configurada:

```json
{
  "id": 42,
  "ts": "2026-11-16T01:38:43Z",
  "level": "critical",
  "kind": "cert-expiring",
  "title": "Certificado loja expira em 4 dia(s) (cluster loja-prod)",
  "message": "Expira em 2026-11-21T01:38:29Z. Domínios: loja.example.com. Origem: upload manual (...)",
  "data": { "cluster": "loja-prod", "certificate": "loja", "domains": ["loja.example.com"],
            "notAfter": "2026-11-21T01:38:29Z", "daysLeft": 4, "source": "upload" }
}
```

Cabeçalhos:
- `X-HAPM-Event`: o `kind` (`cert-expiring`, `cert-expired`, `acme-failed`, `failover`, `node-offline`, `split-brain`, `deploy-failed`, `server-down`... e `test`).
- `X-HAPM-Signature: sha256=<hex>`: HMAC-SHA256 do corpo **exato** com o segredo configurado (só se houver segredo).

Validação no receptor (exemplo em Python):

```python
import hmac, hashlib
esperado = "sha256=" + hmac.new(SEGREDO.encode(), corpo_bruto, hashlib.sha256).hexdigest()
ok = hmac.compare_digest(esperado, request.headers["X-HAPM-Signature"])
```

Qualquer resposta 2xx é sucesso. Serve para PagerDuty/Opsgenie (via integração de webhook), Slack (por um relay),
ServiceNow, n8n etc.

## Entregas que falham

Uma falha de entrega não impede os outros canais nem a tela: o alerta fica registrado com o erro de cada canal
(visível na lista e no log do manager). Não há reenvio automático; o próximo limite gera um novo alerta.
Use **Enviar teste** após corrigir a configuração.

## API

Payload do webhook para alertas operacionais: o mesmo formato acima, com `kind` correspondente e `data` contendo
`cluster` e, conforme o caso, `node`, `from`/`to` (failover), `server`, `masters`, `version`/`error` (publicação).

| Método | Caminho | Papel |
|---|---|---|
| GET | `/api/notifications?unacked=true&cluster=<id>&limit=200` | VIEWER |
| GET | `/api/notifications/count` | VIEWER |
| POST | `/api/notifications/{id}/ack` | VIEWER |
| GET/PUT | `/api/notifications/settings` | ADMIN |
| POST | `/api/notifications/test/{email\|teams\|webhook}` | ADMIN |
| POST | `/api/notifications/check` | ADMIN |

Com Prometheus, as regras `HAPMCertificadoExpirando` e `HAPMCertificadoExpirado` em `observability/prometheus/alerts.yml` continuam disponíveis
para quem prefere centralizar alertas no Alertmanager; os dois mecanismos são independentes.
