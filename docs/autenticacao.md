# Autenticação, papéis e aprovação de publicação

## Papéis

| Papel | Pode |
|---|---|
| **VIEWER** | ver tudo (clusters, nós, configs, histórico, auditoria) — exceto o token de join |
| **OPERATOR** | VIEWER + criar/editar/remover listeners, backends e certificados, ajustar prioridade de nós, publicar (ou **solicitar** publicação), rollback |
| **APPROVER** | VIEWER + aprovar/rejeitar pedidos de publicação |
| **ADMIN** | tudo: clusters, token de join, remoção de nós, usuários, **publicação emergencial** |

OPERATOR e APPROVER são independentes (separação de funções). Quem precisa dos dois recebe os dois grupos.

## Fontes de login

Podem ser usadas juntas. Quem existe como **usuário local** sempre autentica localmente (precedência), o que
evita que um homônimo no diretório herde os papéis da conta local.

### Local
Usuários criados em **Sistema → Usuários** (somente ADMIN). Recomendação: manter apenas um ADMIN local de emergência
(break-glass) com senha no cofre, e desligar logins locais comuns (`HAPM_AUTH_LOCAL_ENABLED=false` desliga todos).

### LDAP (OpenLDAP, 389-DS, FreeIPA)

```bash
HAPM_LDAP_ENABLED=true
HAPM_LDAP_URL=ldaps://ldap.exemplo.com:636
HAPM_LDAP_BASE_DN=dc=exemplo,dc=com
HAPM_LDAP_USER_SEARCH_BASE=ou=people
HAPM_LDAP_USER_SEARCH_FILTER=(uid={0})
HAPM_LDAP_MANAGER_DN=cn=hapm-bind,ou=services,dc=exemplo,dc=com   # conta de serviço para a busca
HAPM_LDAP_MANAGER_PASSWORD=...
HAPM_LDAP_GROUP_SEARCH_BASE=ou=groups
HAPM_LDAP_GROUP_SEARCH_FILTER=(member={0})      # {0} = DN do usuário, {1} = login
HAPM_LDAP_GROUP_ATTRIBUTE=cn
```
Alternativa sem busca: `HAPM_LDAP_USER_DN_PATTERN=uid={0},ou=people`.

### Active Directory

```bash
HAPM_LDAP_ENABLED=true
HAPM_LDAP_AD_DOMAIN=corp.exemplo.com
HAPM_LDAP_URL=ldaps://dc01.corp.exemplo.com:636
HAPM_LDAP_BASE_DN=dc=corp,dc=exemplo,dc=com
```
O login aceita `usuario` (vira `usuario@corp.exemplo.com`) e os grupos vêm do `memberOf`.
Mapeie pelo nome do grupo (CN) ou pelo DN completo.
> O modo AD usa o provedor do Spring Security para Active Directory, mas não foi testado contra um AD real neste projeto.
> O LDAP genérico tem teste automatizado com servidor em memória.

### OIDC / SSO (Keycloak, Microsoft Entra ID, Okta, Google Workspace…)

Fluxo *authorization code*. Ao voltar do IdP, o navegador recebe um **código de uso único (60 s)**, que a UI troca
pelo token de sessão. O token nunca aparece na URL.

```bash
HAPM_OIDC_ENABLED=true
HAPM_OIDC_LABEL="Entrar com SSO"
HAPM_OIDC_GROUPS_CLAIM=groups                      # Keycloak (roles de realm): realm_access.roles
HAPM_OIDC_USERNAME_CLAIM=preferred_username        # Entra ID: preferred_username ou upn
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_HAPM_CLIENTID=hapm
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_HAPM_CLIENTSECRET=...
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_HAPM_SCOPE=openid,profile,email
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_HAPM_ISSUERURI=https://sso.exemplo.com/realms/empresa
```

No IdP, cadastre a *redirect URI* `https://<manager>/login/oauth2/code/hapm`.

- **Keycloak:** inclua os grupos no token com o mapper "Group Membership" (claim `groups`, sem "full path"),
  ou use `realm_access.roles`.
- **Entra ID:** issuer `https://login.microsoftonline.com/<tenant>/v2.0`. Em "Token configuration", adicione a claim
  `groups`. O Entra envia **IDs (GUID)** dos grupos; use os GUIDs no mapeamento, ou configure o envio de nomes
  (`sAMAccountName`) para grupos sincronizados do AD.

O nome de usuário vem da claim configurada (`preferred_username` por padrão). Sem ela, o manager usa o `email` só
se o IdP marcar `email_verified=true`; senão, usa o `sub`. Um login por SSO ou LDAP **nunca assume uma conta local**
de mesmo nome (por exemplo, `admin`): o login é recusado e a conta local continua só com a senha local.

Com vários managers atrás de um balanceador, o fluxo OAuth2 usa a sessão HTTP por alguns segundos.
Use afinidade (sticky) só para `/oauth2/*` e `/login/oauth2/*`, ou uma única instância para o login.

## Mapeamento de grupos para papéis

```yaml
hapm:
  auth:
    role-mapping:
      ADMIN: [hapm-admins]
      APPROVER: [hapm-approvers, gmud-aprovadores]
      OPERATOR: [hapm-operators, sre]
      VIEWER: [hapm-viewers, ti]
    default-roles: []        # ex.: [VIEWER] para qualquer autenticado ler
    session-hours: 12
```
Ou por variáveis de ambiente: `HAPM_ROLES_ADMIN=hapm-admins`, `HAPM_ROLES_APPROVER=grp1,grp2`, `HAPM_DEFAULT_ROLES=VIEWER`.
A comparação não diferencia maiúsculas e aceita o DN completo (`CN=sre,OU=...`), o CN ou o caminho do Keycloak (`/sre`).
Usuário autenticado sem nenhum papel tem o acesso **negado**. Os papéis são recalculados a cada login
(mudança de grupo vale no próximo login ou ao expirar a sessão).

## Aprovação de publicação (quatro olhos)

Em **Configurações do cluster → Governança de publicação**, marque *Exigir aprovação*:

1. O OPERATOR clica em **Solicitar aprovação**. O pedido guarda o snapshot e o `haproxy.cfg` exatos daquele momento.
2. Um APPROVER vê o motivo e o diff em relação à versão base, e **aprova** (publica exatamente o que revisou) ou
   **rejeita** (motivo obrigatório).
3. Quem pediu não aprova, a menos que *Permitir autoaprovação* esteja marcado.
4. Se outra versão for publicada depois do pedido, ele fica **obsoleto**: aprovar publicaria um estado velho.
   Só existe um pedido pendente por cluster; um novo substitui o anterior.
5. **Emergência:** ADMIN pode publicar direto, com justificativa obrigatória. Fica marcado como `[EMERGÊNCIA]`
   e é auditado.
6. **Rollback** também passa por aprovação.
7. **Renovação ACME** republica só os certificados sobre a última versão aprovada, sem aprovação, porque não
   carrega edições pendentes.

Tudo fica na auditoria (`request.create`, `request.approve`, `request.reject`, `deployment.emergency`…) e o
histórico mostra quem publicou e quem aprovou.
