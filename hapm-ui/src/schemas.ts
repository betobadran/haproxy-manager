import type { Opt, Row } from './form';

const backendOptions = (ctx: any): Opt[] => [['', '— nenhum —'], ...(ctx.backends ?? []).map((b: any): Opt => [b.name, `${b.name} (${b.mode})`])];

const http = (v: any) => v.mode !== 'tcp';

export const frontendSchema: Row[] = [
  [{ key: 'name', label: 'Nome' }, { key: 'mode', label: 'Modo', type: 'select', options: ['http', 'tcp'], default: 'http' }, { key: 'enabled', label: 'Ativo', type: 'checkbox', default: true }],
  { key: 'description', label: 'Descrição' },
  { key: 'binds', label: 'Binds (endereço/porta)', type: 'list', addLabel: 'bind', default: () => [{ address: '*', port: 80 }], newItem: () => ({ address: '*' }),
    fields: [[{ key: 'address', label: 'Endereço (* = todos)', default: '*' }, { key: 'port', label: 'Porta', type: 'number' }, { key: 'ssl', label: 'TLS/SSL', type: 'checkbox' }]] },
  { key: 'defaultBackend', label: 'Backend padrão', type: 'select', options: backendOptions },
  [{ key: 'redirectToHttps', label: 'Redirecionar HTTP→HTTPS', type: 'checkbox', showIf: http }, { key: 'hsts', label: 'HSTS', type: 'checkbox', showIf: http },
    { key: 'http2', label: 'HTTP/2', type: 'checkbox', default: true, showIf: http }, { key: 'forwardFor', label: 'X-Forwarded-For', type: 'checkbox', default: true, showIf: http },
    { key: 'acme', label: "Responder ACME (Let's Encrypt)", type: 'checkbox', default: true, showIf: http }],
  { key: 'allowCidrs', label: 'Permitir somente estas origens (IPs/CIDRs; vazio = todas)', type: 'tokens', placeholder: '10.0.0.0/8 192.168.0.0/16' },
  { key: 'rateLimit', label: 'Rate limit por IP', type: 'group', fields: [[
    { key: 'enabled', label: 'Ativar', type: 'checkbox' }, { key: 'requests', label: 'Máx. requisições', type: 'number', default: 100 }, { key: 'period', label: 'Janela', default: '10s' }]] },
  [{ key: 'maxconn', label: 'maxconn (opcional)', type: 'number' }, { key: 'timeoutClient', label: 'timeout client (opcional)', placeholder: '50s' }],
  { key: 'rules', label: 'Regras (avaliadas em ordem)', type: 'list', addLabel: 'regra',
    newItem: () => ({ conditions: [{ type: 'host' }], action: 'use_backend' }),
    fields: [
      { key: 'description', label: 'Descrição' },
      { key: 'conditions', label: 'Condições (todas devem casar)', type: 'list', addLabel: 'condição', newItem: () => ({ type: 'host' }), fields: [[
        { key: 'type', label: 'Tipo', type: 'select', default: 'host', options: [['host', 'Host'], ['path_beg', 'Caminho começa com'], ['path', 'Caminho exato'], ['path_end', 'Caminho termina com'],
          ['path_reg', 'Caminho (regex)'], ['method', 'Método'], ['src', 'IP de origem'], ['header', 'Header'], ['sni', 'SNI (tcp)']] },
        { key: 'name', label: 'Nome do header', showIf: (v) => v.type === 'header' },
        { key: 'value', label: 'Valores (espaço separa)', type: 'tokens', placeholder: 'app.exemplo.com *.exemplo.com' },
        { key: 'negate', label: 'NÃO', type: 'checkbox' }]] },
      [{ key: 'action', label: 'Ação', type: 'select', default: 'use_backend', options: [['use_backend', 'Encaminhar para backend'], ['redirect', 'Redirecionar'], ['deny', 'Negar'], ['set_header', 'Definir header'], ['del_header', 'Remover header']] },
        { key: 'backend', label: 'Backend', type: 'select', options: backendOptions, showIf: (v) => v.action === 'use_backend' }],
      [{ key: 'redirectType', label: 'Tipo de redirect', type: 'select', options: ['location', 'prefix', 'scheme'], default: 'location', showIf: (v) => v.action === 'redirect' },
        { key: 'url', label: 'URL / prefixo / scheme', showIf: (v) => v.action === 'redirect' },
        { key: 'code', label: 'Código', type: 'select', options: ['301', '302', '303', '307', '308'], default: '302', showIf: (v) => v.action === 'redirect' }],
      [{ key: 'status', label: 'Status HTTP', type: 'number', default: 403, showIf: (v) => v.action === 'deny' },
        { key: 'header', label: 'Header', showIf: (v) => /header/.test(v.action) },
        { key: 'value', label: 'Valor do header', showIf: (v) => v.action === 'set_header' },
        { key: 'direction', label: 'Direção', type: 'select', default: 'request', options: [['request', 'requisição'], ['response', 'resposta']], showIf: (v) => /header/.test(v.action) }],
    ] },
  { key: 'extra', label: 'Avançado: linhas cruas do HAProxy (opcional)', type: 'textarea', mono: true, rows: 3 },
];

export const backendSchema: Row[] = [
  [{ key: 'name', label: 'Nome' }, { key: 'mode', label: 'Modo', type: 'select', options: ['http', 'tcp'], default: 'http' },
    { key: 'balance', label: 'Balanceamento', type: 'select', default: 'roundrobin', options: ['roundrobin', 'leastconn', 'source', 'random', 'first', 'uri'] }],
  { key: 'description', label: 'Descrição' },
  { key: 'servers', label: 'Servidores', type: 'list', addLabel: 'servidor', default: () => [{}], fields: [[
    { key: 'name', label: 'Nome' }, { key: 'address', label: 'IP/host' }, { key: 'port', label: 'Porta', type: 'number' },
    { key: 'weight', label: 'Peso', type: 'number', default: 100 }, { key: 'maxconn', label: 'maxconn', type: 'number' },
    { key: 'backup', label: 'Backup', type: 'checkbox' }, { key: 'disabled', label: 'Desativado', type: 'checkbox' }]] },
  { key: 'healthCheck', label: 'Health-check', type: 'group', fields: [
    [{ key: 'type', label: 'Tipo', type: 'select', default: 'http', options: [['http', 'HTTP'], ['tcp', 'TCP'], ['none', 'nenhum']] },
      { key: 'method', label: 'Método', type: 'select', default: 'GET', options: ['GET', 'HEAD', 'OPTIONS'], showIf: (v) => v.type === 'http' },
      { key: 'path', label: 'Caminho', default: '/', showIf: (v) => v.type === 'http' }, { key: 'host', label: 'Host header', showIf: (v) => v.type === 'http' },
      { key: 'expectStatus', label: 'Status esperado', default: '200-399', showIf: (v) => v.type === 'http' }],
    [{ key: 'interval', label: 'Intervalo', default: '2s' }, { key: 'fall', label: 'Falhas p/ DOWN', type: 'number', default: 3 }, { key: 'rise', label: 'Sucessos p/ UP', type: 'number', default: 2 }]] },
  [{ key: 'sticky', label: 'Sessão persistente (cookie)', type: 'group', fields: [[{ key: 'enabled', label: 'Ativar', type: 'checkbox' }, { key: 'cookie', label: 'Cookie', default: 'HAPM_SRV' }]] },
    { key: 'serverSsl', label: 'TLS até os servidores', type: 'group', fields: [[{ key: 'enabled', label: 'Ativar', type: 'checkbox' },
      { key: 'verify', label: 'Verificar cert.', type: 'select', default: 'none', options: ['none', 'required'] }, { key: 'sni', label: 'Enviar SNI', type: 'checkbox', default: true }]] }],
  [{ key: 'compression', label: 'Compressão gzip', type: 'checkbox' }, { key: 'timeoutServer', label: 'timeout server', placeholder: '50s' }, { key: 'timeoutConnect', label: 'timeout connect', placeholder: '5s' }],
  { key: 'extra', label: 'Avançado: linhas cruas do HAProxy (opcional)', type: 'textarea', mono: true, rows: 3 },
];

export const clusterSchema: Row[] = [
  { key: 'name', label: 'Nome do cluster' },
  { key: 'settings', type: 'group', label: 'Configurações', fields: [
    [{ key: 'mode', label: 'Topologia', type: 'select', default: 'onprem-keepalived', options: [['onprem-keepalived', 'On-premise: HAProxy + Keepalived (VIP/VRRP)'], ['aws-nlb', 'AWS: NLB + HAProxy (Auto Scaling)'], ['aws-eip', 'AWS: HAProxy + Keepalived com failover de IP entre ENIs']] },
      { key: 'rollout', label: 'Rollout', type: 'select', default: 'sequential', options: [['sequential', 'sequencial (um nó por vez, MASTER por último)'], ['parallel', 'paralelo (MASTER por último)']] }],
    { key: 'autoDeployOnCertRenew', label: 'Publicar automaticamente quando um certificado for renovado (republica só os certificados)', type: 'checkbox', default: true },
    { key: 'approval', label: 'Governança de publicação', type: 'group', fields: [[
      { key: 'required', label: 'Exigir aprovação (APPROVER) antes de publicar', type: 'checkbox' },
      { key: 'allowSelfApproval', label: 'Permitir que o próprio solicitante aprove (desliga os quatro olhos)', type: 'checkbox' }]] },
    { key: 'keepalived', label: 'Keepalived / VRRP', type: 'group', showIf: (v) => v.mode !== 'aws-nlb', fields: [
      [{ key: 'vips', label: 'VIPs (CIDR)', type: 'tokens', placeholder: '192.168.10.100/24' }, { key: 'interface', label: 'Interface', default: 'eth0' }, { key: 'routerId', label: 'virtual_router_id', type: 'number', default: 51 }],
      [{ key: 'authPass', label: 'Senha VRRP (≤8, vazio = gerar)' }, { key: 'advertInt', label: 'advert_int (s)', type: 'number', default: 1 }, { key: 'checkInterval', label: 'Intervalo do check (s)', type: 'number', default: 2 }],
      [{ key: 'checkFall', label: 'Falhas do check para perder o VIP', type: 'number', default: 2 }, { key: 'checkRise', label: 'Sucessos do check para voltar a disputar', type: 'number', default: 2 }],
      [{ key: 'unicast', label: 'Unicast (recomendado)', type: 'checkbox', default: true }, { key: 'preempt', label: 'Preempção (volta ao nó de maior prioridade)', type: 'checkbox', default: true }]] },
    { key: 'aws', label: 'AWS: failover de IP entre ENIs', type: 'group', showIf: (v) => v.mode === 'aws-eip', fields: [
      { key: 'region', label: 'Região', placeholder: 'sa-east-1' },
      { key: 'privateVip', label: 'IP secundário (nós na MESMA subnet)', placeholder: '10.0.1.50',
        help: 'O novo MASTER reatribui este IP privado à própria ENI. Só funciona se todos os nós estiverem na mesma subnet.' },
      [{ key: 'routeVip', label: 'IP de overlay (nós em subnets/AZs DIFERENTES)', placeholder: '192.168.250.10' },
        { key: 'routeTableIds', label: 'Tabelas de rotas a atualizar', type: 'tokens', placeholder: 'rtb-0aaa... rtb-0bbb...' }],
      { key: 'manageSourceDestCheck', label: 'Desligar o source/dest check das ENIs automaticamente', type: 'checkbox', default: true,
        help: 'IP de overlay: um IP fora do CIDR da VPC; o novo MASTER aponta a rota /32 nessas tabelas para a própria ENI. Os clientes precisam usar essas tabelas (mesma VPC, ou via Transit Gateway/VPN com rota estática).' },
      { key: 'eipAllocationId', label: 'Elastic IP (opcional, acompanha o MASTER)', placeholder: 'eipalloc-...' },
    ] },
    { key: 'logs', label: 'Logs de acesso', type: 'group', fields: [
      [{ key: 'enabled', label: 'Enviar logs de acesso (logfmt, syslog RFC 5424)', type: 'checkbox' },
        { key: 'userAgent', label: 'Incluir User-Agent', type: 'checkbox', default: true }],
      [{ key: 'collector', label: 'OTel Collector do nó (UDP host:porta)', placeholder: '127.0.0.1:5514', default: '127.0.0.1:5514' },
        { key: 'siem', label: 'Destinos syslog adicionais (SIEM, UDP host:porta)', type: 'tokens', placeholder: 'siem.exemplo.interno:514' }]] },
    { key: 'nodes', label: 'Nós', type: 'group', fields: [[
      { key: 'autoRemoveOfflineMinutes', label: 'Remover automaticamente nós offline há mais de (min; vazio = nunca)', type: 'number',
        help: 'Útil com Auto Scaling na AWS (o lifecycle hook já remove na hora; isto cobre instâncias que sumiram sem avisar). Mínimo 5.' }]] },
    { key: 'acme', label: "ACME / Let's Encrypt", type: 'group', fields: [[{ key: 'email', label: 'E-mail' },
      { key: 'backend', label: 'Manager visto pelo HAProxy (host:porta)', placeholder: '10.0.0.5:8080' }]] },
    { key: 'haproxy', label: 'HAProxy global', type: 'group', fields: [
      [{ key: 'maxconn', label: 'maxconn', type: 'number', default: 50000 }, { key: 'nbthread', label: 'nbthread (vazio = auto)', type: 'number' }, { key: 'sslMinVer', label: 'TLS mínimo', type: 'select', default: 'TLSv1.2', options: ['TLSv1.2', 'TLSv1.3'] }],
      [{ key: 'log', label: 'log', default: '/dev/log local0' }, { key: 'user', label: 'Usuário (vazio = não trocar)', default: 'haproxy' }, { key: 'statsSocket', label: 'Stats socket', default: '/run/haproxy/admin.sock' }],
      [{ key: 'statsPort', label: 'Porta métricas/stats', type: 'number', default: 8405 }, { key: 'statsUser', label: 'Stats usuário' }, { key: 'statsPassword', label: 'Stats senha' },
        { key: 'defaultCertificate', label: 'Certificado padrão (sem SNI)' }],
      { key: 'timeouts', label: 'Timeouts', type: 'group', fields: [[{ key: 'connect', label: 'connect', default: '5s' }, { key: 'client', label: 'client', default: '50s' }, { key: 'server', label: 'server', default: '50s' },
        { key: 'httpRequest', label: 'http-request', default: '10s' }, { key: 'httpKeepAlive', label: 'keep-alive', default: '10s' }, { key: 'queue', label: 'queue', default: '30s' }, { key: 'tunnel', label: 'tunnel', default: '1h' }]] },
      { key: 'globalExtra', label: 'Linhas extras em global', type: 'textarea', mono: true, rows: 2 },
      { key: 'defaultsExtra', label: 'Linhas extras em defaults', type: 'textarea', mono: true, rows: 2 }] },
  ] },
];
