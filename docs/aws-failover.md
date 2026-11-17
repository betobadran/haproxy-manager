# Failover de IP na AWS entre ENIs (topologia `aws-eip`)

Alternativa ao NLB: dois ou mais nós HAProxy + Keepalived. O VRRP roda em **unicast** (a AWS não tem multicast), e o
nó que vira MASTER usa a API da AWS para trazer o IP do serviço para a **própria ENI**. Tudo é configurado em
**Configurações do cluster → AWS: failover de IP entre ENIs**.

| Cenário dos nós | Configure | Como funciona | Tempo típico |
|---|---|---|---|
| **Mesma subnet** (mesma AZ) | *IP secundário* | `assign-private-ip-addresses --allow-reassignment` move o IP privado para a ENI do MASTER | 3–10 s |
| **Subnets / AZs diferentes** | *IP de overlay* + *tabelas de rotas* | o IP de overlay fica **fora do CIDR da VPC**; `replace-route` aponta a rota `/32` de cada tabela para a ENI do MASTER | 3–10 s |
| Qualquer um, IP público | *Elastic IP* (opcional) | `associate-address --allow-reassociation` leva o EIP para a ENI do MASTER (funciona entre AZs) | 3–10 s |

Dá para combinar: por exemplo, IP de overlay para os clientes internos e Elastic IP para os externos.

## IP de overlay (nós em subnets diferentes)

```
             VPC 10.0.0.0/16 — rota 192.168.250.10/32 -> ENI do MASTER (em todas as tabelas listadas)
   ┌──────── subnet-a (AZ a) ────────┐      ┌──────── subnet-b (AZ b) ────────┐
   │ lb1  eni-aaa  10.0.1.10  MASTER │<VRRP>│ lb2  eni-bbb  10.0.2.10  BACKUP │
   │ 192.168.250.10/32 na eth0       │      │                                 │
   └─────────────────────────────────┘      └─────────────────────────────────┘
```

- Escolha um IP **fora** de todos os CIDRs da VPC (ex.: `192.168.250.10`); o preflight acusa se estiver dentro.
- Liste **todas as tabelas de rotas usadas pelos clientes** (as das subnets dos clientes). Clientes de outra VPC ou do
  datacenter chegam via Transit Gateway/VPN com rota estática para o IP de overlay apontando para a VPC.
- O **source/dest check** precisa estar desligado nas ENIs dos nós (pacotes para um IP que não é da ENI). Com a opção
  "desligar automaticamente" marcada, o próprio failover faz isso.
- O keepalived configura o IP de overlay na interface do MASTER, e o HAProxy faz bind nele (ou em `*`).

## IP secundário (nós na mesma subnet)

- O IP precisa pertencer ao CIDR da subnet dos nós; o preflight confere em cada nó.
- O keepalived configura o IP na interface do MASTER; a AWS passa a entregar o tráfego à nova ENI após o
  `assign-private-ip-addresses`.

## ENI por nó

Por padrão, a ENI é a da interface do VRRP (descoberta pelo MAC via IMDSv2). Em instâncias com várias ENIs, defina a
ENI de cada nó na **Visão geral → Failover AWS → ENI → editar** e publique.

## Preflight

Cada nó roda `keepalived-notify.sh --preflight` após aplicar uma versão e a cada 5 minutos, e o resultado aparece na
coluna **Failover AWS** da visão geral:

- ENI encontrada, subnet e VPC;
- IP secundário dentro da subnet do nó;
- IP de overlay fora do CIDR da VPC;
- tabelas de rotas existentes na mesma VPC;
- estado do source/dest check;
- Elastic IP existente;
- permissões IAM de cada ação, testadas com `--dry-run` (nada é alterado).

## Requisitos

- Instance profile com a política `deploy/aws/eip-failover-iam-policy.json` (restrinja `Resource` às suas ENIs e
  tabelas de rotas, se a política da empresa exigir).
- AWS CLI v2 nos nós (o `install-node.sh` não instala; em Ubuntu: `snap install aws-cli --classic`).
- Security group entre os nós liberando o **protocolo IP 112 (VRRP)**.
- IMDSv2 acessível (hop limit 1 basta, a chamada é feita no host).
