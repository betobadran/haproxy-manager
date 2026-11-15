// HAPM UI — SPA sem build. Todo texto vindo da API é inserido via textContent (sem innerHTML).

// ------------------------------------------------------------------ utilidades
const $app = document.getElementById('app');
let token = localStorage.getItem('hapm.token');
let refreshTimer = null;

function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k === 'value') el.value = v;
    else if (k === 'checked') el.checked = !!v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat(Infinity)) if (c != null && c !== false) el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return el;
}

function put(el, ...children) {
  el.replaceChildren(...children.flat(Infinity).filter((c) => c != null && c !== false));
}

function toast(msg, isErr = false) {
  const t = h('div', { class: `toast${isErr ? ' err' : ''}` }, msg);
  document.body.append(t);
  setTimeout(() => t.remove(), isErr ? 8000 : 3500);
}

async function api(method, path, body) {
  const r = await fetch(`/api${path}`, {
    method,
    headers: { ...(body ? { 'content-type': 'application/json' } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (r.status === 401 && path !== '/auth/login') {
    logout();
    throw new Error('sessão expirada');
  }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) {
    const e = new Error(j.errors?.join('\n') || j.error || `HTTP ${r.status}`);
    e.status = r.status;
    throw e;
  }
  return j;
}
const guard = (fn) => async (...a) => {
  try {
    await fn(...a);
  } catch (e) {
    toast(e.message, true);
  }
};

function logout() {
  token = null;
  localStorage.removeItem('hapm.token');
  location.hash = '#/login';
}

const ago = (ms) => {
  if (!ms) return 'nunca';
  const s = Math.round((Date.now() - ms) / 1000);
  return s < 60 ? `${s}s atrás` : s < 3600 ? `${Math.round(s / 60)}min atrás` : `${Math.round(s / 3600)}h atrás`;
};
const fmtDate = (s) => (s ? new Date(s).toLocaleString('pt-BR') : '—');
const daysLeft = (s) => (s ? Math.floor((Date.parse(s) - Date.now()) / 86400000) : null);

const STATUS = {
  succeeded: ['sucesso', 'b-ok'], failed: ['falhou', 'b-err'], in_progress: ['em andamento', 'b-acc'], superseded: ['substituído', 'b-muted'],
  MASTER: ['MASTER', 'b-ok'], BACKUP: ['BACKUP', 'b-acc'], FAULT: ['FAULT', 'b-err'], STOP: ['STOP', 'b-err'], UNKNOWN: ['?', 'b-muted'],
};
const badge = (s) => {
  const [t, c] = STATUS[s] || [s ?? '—', 'b-muted'];
  return h('span', { class: `badge ${c}` }, t);
};

// ------------------------------------------------------------------ formulários dirigidos por schema
// tipos: text | number | select | checkbox | textarea | tokens | group | list
function buildField(def, value, ctx) {
  const v = value === undefined ? (typeof def.default === 'function' ? def.default() : def.default) : value;
  const show = def.showIf ? def.showIf : null;
  let node;
  let get;
  switch (def.type || 'text') {
    case 'checkbox': {
      const i = h('input', { type: 'checkbox', checked: !!v });
      node = h('label', { class: 'cb' }, i, def.label);
      get = () => i.checked;
      break;
    }
    case 'select': {
      const opts = typeof def.options === 'function' ? def.options(ctx) : def.options;
      const s = h('select', {}, opts.map((o) => {
        const [val, lab] = Array.isArray(o) ? o : [o, o];
        return h('option', { value: val, selected: String(v ?? '') === String(val) ? 'selected' : null }, lab);
      }));
      node = h('label', { class: 'f' }, h('span', {}, def.label), s);
      get = () => s.value;
      if (def.onchange) s.addEventListener('change', () => def.onchange(s.value));
      break;
    }
    case 'textarea': {
      const t = h('textarea', { class: def.mono ? 'mono' : '', rows: def.rows || 4, placeholder: def.placeholder || '' });
      t.value = v ?? '';
      node = h('label', { class: 'f' }, h('span', {}, def.label), t);
      get = () => t.value;
      break;
    }
    case 'tokens': {
      const i = h('input', { value: Array.isArray(v) ? v.join(' ') : v ?? '', placeholder: def.placeholder || '' });
      node = h('label', { class: 'f' }, h('span', {}, def.label), i);
      get = () => i.value.split(/[\s,]+/).filter(Boolean);
      break;
    }
    case 'group': {
      const f = buildFields(def.fields, v || {}, ctx);
      node = h('fieldset', {}, h('legend', {}, def.label), f.node);
      get = f.get;
      break;
    }
    case 'list': {
      const box = h('div');
      const items = [];
      const add = (val) => {
        const f = buildFields(def.fields, val || {}, ctx);
        const wrap = h('div', { class: 'item' });
        const entry = { get: f.get };
        wrap.append(h('button', { type: 'button', class: 'small danger rm', onclick: () => { items.splice(items.indexOf(entry), 1); wrap.remove(); } }, 'remover'), f.node);
        items.push(entry);
        box.append(wrap);
      };
      (v || []).forEach(add);
      node = h('fieldset', {}, h('legend', {}, def.label), box,
        h('button', { type: 'button', class: 'small', onclick: () => add(def.newItem ? def.newItem() : {}) }, `+ ${def.addLabel || 'adicionar'}`));
      get = () => items.map((i) => i.get());
      break;
    }
    default: {
      const i = h('input', { type: def.type === 'number' ? 'number' : 'text', value: v ?? '', placeholder: def.placeholder || '' });
      node = h('label', { class: 'f' }, h('span', {}, def.label), i);
      get = () => (def.type === 'number' ? (i.value === '' ? null : Number(i.value)) : i.value);
    }
  }
  if (def.help) node.append(h('div', { class: 'muted', style: 'font-size:12px;margin-top:-6px;margin-bottom:8px' }, def.help));
  return { node, get, show };
}

function buildFields(defs, value, ctx) {
  const parts = defs.map((d) => (Array.isArray(d) ? d.map((x) => [x, buildField(x, value[x.key], ctx)]) : [[d, buildField(d, value[d.key], ctx)]]));
  const node = h('div', {}, parts.map((row) => (row.length > 1 ? h('div', { class: 'row' }, row.map(([, f]) => f.node)) : row[0][1].node)));
  const get = () => {
    const out = { ...value };
    for (const row of parts) for (const [d, f] of row) out[d.key] = f.get();
    return out;
  };
  // campos com showIf só aparecem quando fazem sentido (ex.: "Backend" só para use_backend)
  const conditional = parts.flat().filter(([d]) => d.showIf);
  if (conditional.length) {
    const refresh = () => {
      const v = get();
      for (const [d, f] of conditional) f.node.style.display = d.showIf(v) ? '' : 'none';
    };
    node.addEventListener('change', refresh);
    refresh();
  }
  return { node, get };
}

// ------------------------------------------------------------------ schemas
const backendOptions = (ctx) => [['', '— nenhum —'], ...(ctx.backends || []).map((b) => [b.name, `${b.name} (${b.mode})`])];

const frontendSchema = [
  [{ key: 'name', label: 'Nome' }, { key: 'mode', label: 'Modo', type: 'select', options: ['http', 'tcp'], default: 'http' }, { key: 'enabled', label: 'Ativo', type: 'checkbox', default: true }],
  { key: 'description', label: 'Descrição' },
  { key: 'binds', label: 'Binds (endereço/porta)', type: 'list', addLabel: 'bind', default: () => [{ address: '*', port: 80 }],
    fields: [[{ key: 'address', label: 'Endereço (* = todos)', default: '*' }, { key: 'port', label: 'Porta', type: 'number' }, { key: 'ssl', label: 'TLS/SSL', type: 'checkbox' }]] },
  { key: 'defaultBackend', label: 'Backend padrão', type: 'select', options: backendOptions },
  [{ key: 'redirectToHttps', label: 'Redirecionar HTTP→HTTPS', type: 'checkbox' }, { key: 'hsts', label: 'HSTS', type: 'checkbox' },
    { key: 'http2', label: 'HTTP/2', type: 'checkbox', default: true }, { key: 'forwardFor', label: 'X-Forwarded-For', type: 'checkbox', default: true },
    { key: 'acme', label: "Responder ACME (Let's Encrypt)", type: 'checkbox', default: true }],
  { key: 'allowCidrs', label: 'Permitir somente estas origens (IPs/CIDRs; vazio = todas)', type: 'tokens', placeholder: '10.0.0.0/8 192.168.0.0/16' },
  { key: 'rateLimit', label: 'Rate limit por IP', type: 'group', fields: [[
    { key: 'enabled', label: 'Ativar', type: 'checkbox' }, { key: 'requests', label: 'Máx. requisições', type: 'number', default: 100 }, { key: 'period', label: 'Janela', default: '10s' }]] },
  [{ key: 'maxconn', label: 'maxconn (opcional)', type: 'number' }, { key: 'timeoutClient', label: 'timeout client (opcional)', placeholder: '50s' }],
  { key: 'rules', label: 'Regras (avaliadas em ordem)', type: 'list', addLabel: 'regra',
    newItem: () => ({ conditions: [{ type: 'host' }], action: 'use_backend' }),
    fields: [
      { key: 'description', label: 'Descrição' },
      { key: 'conditions', label: 'Condições (todas devem casar)', type: 'list', addLabel: 'condição', fields: [[
        { key: 'type', label: 'Tipo', type: 'select', options: [['host', 'Host'], ['path_beg', 'Caminho começa com'], ['path', 'Caminho exato'], ['path_end', 'Caminho termina com'],
          ['path_reg', 'Caminho (regex)'], ['method', 'Método'], ['src', 'IP de origem'], ['header', 'Header'], ['sni', 'SNI (tcp)']] },
        { key: 'name', label: 'Nome do header', showIf: (v) => v.type === 'header' },
        { key: 'value', label: 'Valores (espaço separa)', type: 'tokens', placeholder: 'app.exemplo.com *.exemplo.com' },
        { key: 'negate', label: 'NÃO', type: 'checkbox' }]] },
      [{ key: 'action', label: 'Ação', type: 'select', options: [['use_backend', 'Encaminhar para backend'], ['redirect', 'Redirecionar'], ['deny', 'Negar'], ['set_header', 'Definir header'], ['del_header', 'Remover header']] },
        { key: 'backend', label: 'Backend', type: 'select', options: backendOptions, showIf: (v) => v.action === 'use_backend' }],
      [{ key: 'redirectType', label: 'Tipo de redirect', type: 'select', options: ['location', 'prefix', 'scheme'], showIf: (v) => v.action === 'redirect' },
        { key: 'url', label: 'URL / prefixo / scheme', showIf: (v) => v.action === 'redirect' },
        { key: 'code', label: 'Código', type: 'select', options: ['301', '302', '303', '307', '308'], default: '302', showIf: (v) => v.action === 'redirect' }],
      [{ key: 'status', label: 'Status HTTP', type: 'number', default: 403, showIf: (v) => v.action === 'deny' },
        { key: 'header', label: 'Header', showIf: (v) => /header/.test(v.action) },
        { key: 'value', label: 'Valor do header', showIf: (v) => v.action === 'set_header' },
        { key: 'direction', label: 'Direção', type: 'select', options: [['request', 'requisição'], ['response', 'resposta']], showIf: (v) => /header/.test(v.action) }],
    ] },
  { key: 'extra', label: 'Avançado: linhas cruas do HAProxy (opcional)', type: 'textarea', mono: true, rows: 3 },
];

const backendSchema = [
  [{ key: 'name', label: 'Nome' }, { key: 'mode', label: 'Modo', type: 'select', options: ['http', 'tcp'], default: 'http' },
    { key: 'balance', label: 'Balanceamento', type: 'select', options: ['roundrobin', 'leastconn', 'source', 'random', 'first', 'uri'] }],
  { key: 'description', label: 'Descrição' },
  { key: 'servers', label: 'Servidores', type: 'list', addLabel: 'servidor', default: () => [{}], fields: [[
    { key: 'name', label: 'Nome' }, { key: 'address', label: 'IP/host' }, { key: 'port', label: 'Porta', type: 'number' },
    { key: 'weight', label: 'Peso', type: 'number', default: 100 }, { key: 'maxconn', label: 'maxconn', type: 'number' },
    { key: 'backup', label: 'Backup', type: 'checkbox' }, { key: 'disabled', label: 'Desativado', type: 'checkbox' }]] },
  { key: 'healthCheck', label: 'Health-check', type: 'group', fields: [
    [{ key: 'type', label: 'Tipo', type: 'select', options: [['http', 'HTTP'], ['tcp', 'TCP'], ['none', 'nenhum']] }, { key: 'method', label: 'Método', type: 'select', options: ['GET', 'HEAD', 'OPTIONS'] },
      { key: 'path', label: 'Caminho', default: '/' }, { key: 'host', label: 'Host header' }, { key: 'expectStatus', label: 'Status esperado', default: '200-399' }],
    [{ key: 'interval', label: 'Intervalo', default: '2s' }, { key: 'fall', label: 'Falhas p/ DOWN', type: 'number', default: 3 }, { key: 'rise', label: 'Sucessos p/ UP', type: 'number', default: 2 }]] },
  [{ key: 'sticky', label: 'Sessão persistente (cookie)', type: 'group', fields: [[{ key: 'enabled', label: 'Ativar', type: 'checkbox' }, { key: 'cookie', label: 'Cookie', default: 'HAPM_SRV' }]] },
    { key: 'serverSsl', label: 'TLS até os servidores', type: 'group', fields: [[{ key: 'enabled', label: 'Ativar', type: 'checkbox' },
      { key: 'verify', label: 'Verificar cert.', type: 'select', options: ['none', 'required'] }, { key: 'sni', label: 'Enviar SNI', type: 'checkbox', default: true }]] }],
  [{ key: 'compression', label: 'Compressão gzip', type: 'checkbox' }, { key: 'timeoutServer', label: 'timeout server', placeholder: '50s' }, { key: 'timeoutConnect', label: 'timeout connect', placeholder: '5s' }],
  { key: 'extra', label: 'Avançado: linhas cruas do HAProxy (opcional)', type: 'textarea', mono: true, rows: 3 },
];

const clusterSchema = [
  { key: 'name', label: 'Nome do cluster' },
  { key: 'settings', type: 'group', label: 'Configurações', fields: [
    [{ key: 'mode', label: 'Topologia', type: 'select', options: [['onprem-keepalived', 'On-premise: HAProxy + Keepalived (VIP/VRRP)'], ['aws-nlb', 'AWS: NLB + HAProxy (Auto Scaling)'], ['aws-eip', 'AWS: HAProxy + Keepalived movendo EIP/IP privado']] },
      { key: 'rollout', label: 'Rollout', type: 'select', options: [['sequential', 'sequencial (um nó por vez, MASTER por último)'], ['parallel', 'paralelo (MASTER por último)']] }],
    { key: 'autoDeployOnCertRenew', label: 'Publicar automaticamente quando um certificado for renovado', type: 'checkbox', default: true },
    { key: 'keepalived', label: 'Keepalived / VRRP', type: 'group', fields: [
      [{ key: 'vips', label: 'VIPs (CIDR)', type: 'tokens', placeholder: '192.168.10.100/24' }, { key: 'interface', label: 'Interface', default: 'eth0' }, { key: 'routerId', label: 'virtual_router_id', type: 'number', default: 51 }],
      [{ key: 'authPass', label: 'Senha VRRP (≤8)' }, { key: 'advertInt', label: 'advert_int (s)', type: 'number', default: 1 }, { key: 'checkInterval', label: 'Intervalo do check (s)', type: 'number', default: 2 }],
      [{ key: 'unicast', label: 'Unicast (recomendado)', type: 'checkbox', default: true }, { key: 'preempt', label: 'Preempção (volta ao nó de maior prioridade)', type: 'checkbox', default: true }]] },
    { key: 'aws', label: 'AWS (modo aws-eip)', type: 'group', fields: [[{ key: 'region', label: 'Região' }, { key: 'eipAllocationId', label: 'EIP allocation id' }, { key: 'privateVip', label: 'IP privado secundário (VIP)' }]] },
    { key: 'acme', label: "ACME / Let's Encrypt", type: 'group', fields: [[{ key: 'email', label: 'E-mail' },
      { key: 'backend', label: 'Manager visto pelo HAProxy (host:porta)', placeholder: '10.0.0.5:8080' }]] },
    { key: 'haproxy', label: 'HAProxy global', type: 'group', fields: [
      [{ key: 'maxconn', label: 'maxconn', type: 'number', default: 50000 }, { key: 'nbthread', label: 'nbthread (vazio = auto)', type: 'number' }, { key: 'sslMinVer', label: 'TLS mínimo', type: 'select', options: ['TLSv1.2', 'TLSv1.3'] }],
      [{ key: 'log', label: 'log', default: '/dev/log local0' }, { key: 'user', label: 'Usuário (vazio = não trocar)', default: 'haproxy' }, { key: 'statsSocket', label: 'Stats socket', default: '/run/haproxy/admin.sock' }],
      [{ key: 'statsPort', label: 'Porta métricas/stats', type: 'number', default: 8405 }, { key: 'statsUser', label: 'Stats usuário' }, { key: 'statsPassword', label: 'Stats senha' },
        { key: 'defaultCertificate', label: 'Certificado padrão (sem SNI)' }],
      { key: 'timeouts', label: 'Timeouts', type: 'group', fields: [[{ key: 'connect', label: 'connect', default: '5s' }, { key: 'client', label: 'client', default: '50s' }, { key: 'server', label: 'server', default: '50s' },
        { key: 'httpRequest', label: 'http-request', default: '10s' }, { key: 'httpKeepAlive', label: 'keep-alive', default: '10s' }, { key: 'queue', label: 'queue', default: '30s' }, { key: 'tunnel', label: 'tunnel', default: '1h' }]] },
      { key: 'globalExtra', label: 'Linhas extras em global', type: 'textarea', mono: true, rows: 2 },
      { key: 'defaultsExtra', label: 'Linhas extras em defaults', type: 'textarea', mono: true, rows: 2 }] },
  ] },
];

// ------------------------------------------------------------------ layout
let clustersCache = [];

async function shell(active, content) {
  clearInterval(refreshTimer);
  try {
    clustersCache = await api('GET', '/clusters');
  } catch {
    return;
  }
  put($app, h('div', { class: 'layout' },
    h('nav', { class: 'side' },
      h('h1', {}, 'HAPM'), h('div', { class: 'sub' }, 'HAProxy HA Manager'),
      h('a', { href: '#/', class: active === 'home' ? 'active' : '' }, 'Clusters'),
      h('div', { class: 'section' }, 'Clusters'),
      clustersCache.map((c) => h('a', { href: `#/c/${c.id}`, class: active === c.id ? 'active' : '' }, c.name)),
      h('div', { class: 'section' }, 'Sistema'),
      h('a', { href: '#/audit', class: active === 'audit' ? 'active' : '' }, 'Auditoria'),
      h('a', { href: '#/account', class: active === 'account' ? 'active' : '' }, 'Conta'),
      h('a', { href: '#/login', onclick: logout }, 'Sair')),
    h('main', { class: 'main' }, content)));
}

// ------------------------------------------------------------------ páginas
function loginPage() {
  const u = h('input', { value: 'admin', autocomplete: 'username' });
  const p = h('input', { type: 'password', autocomplete: 'current-password' });
  const submit = guard(async (e) => {
    e.preventDefault();
    const r = await api('POST', '/auth/login', { username: u.value, password: p.value });
    token = r.token;
    localStorage.setItem('hapm.token', token);
    location.hash = '#/';
  });
  put($app, h('form', { class: 'panel login', onsubmit: submit },
    h('h3', {}, 'HAPM · entrar'), h('label', { class: 'f' }, h('span', {}, 'Usuário'), u), h('label', { class: 'f' }, h('span', {}, 'Senha'), p),
    h('button', { class: 'primary', type: 'submit' }, 'Entrar')));
  p.focus();
}

async function homePage() {
  const form = buildFields(clusterSchema, { settings: {} }, {});
  const create = guard(async () => {
    const c = await api('POST', '/clusters', form.get());
    toast('cluster criado');
    location.hash = `#/c/${c.id}`;
  });
  await shell('home', [
    h('div', { class: 'top' }, h('h2', {}, 'Clusters')),
    h('div', { class: 'panel' }, clustersCache.length ? h('table', {},
      h('tr', {}, ['Nome', 'Topologia', 'Nós online', 'MASTER', 'Última publicação'].map((t) => h('th', {}, t))),
      clustersCache.map((c) => h('tr', {},
        h('td', {}, h('a', { href: `#/c/${c.id}` }, c.name)), h('td', {}, c.mode), h('td', {}, `${c.online}/${c.nodes}`),
        h('td', {}, c.masters.join(', ') || '—'),
        h('td', {}, c.latestDeployment ? [`v${c.latestDeployment.version} `, badge(c.latestDeployment.status)] : '—'))))
      : h('p', { class: 'muted' }, 'Nenhum cluster ainda. Crie o primeiro abaixo.')),
    h('details', { class: 'panel', open: clustersCache.length ? null : true }, h('summary', {}, h('b', {}, 'Novo cluster')), form.node,
      h('button', { class: 'primary', onclick: create }, 'Criar cluster')),
  ]);
}

const TABS = [['', 'Visão geral'], ['frontends', 'Listeners'], ['backends', 'Backends'], ['certificates', 'Certificados'], ['publish', 'Publicar'], ['history', 'Histórico'], ['settings', 'Configurações']];

async function clusterPage(cid, tab = '', sub) {
  const cluster = await api('GET', `/clusters/${cid}`);
  const body = h('div');
  await shell(cid, [
    h('div', { class: 'top' }, h('h2', {}, cluster.name), h('span', { class: 'badge b-muted' }, cluster.settings.mode)),
    h('div', { class: 'tabs' }, TABS.map(([k, l]) => h('a', { href: `#/c/${cid}${k ? `/${k}` : ''}`, class: tab === k ? 'active' : '' }, l))),
    body,
  ]);
  const pages = { '': overviewTab, frontends: objectsTab, backends: objectsTab, certificates: certsTab, publish: publishTab, history: historyTab, settings: settingsTab };
  await (pages[tab] || overviewTab)(cluster, body, tab, sub);
}

async function overviewTab(cluster, body) {
  const render = async () => {
    const [nodes, deps] = await Promise.all([api('GET', `/clusters/${cluster.id}/nodes`), api('GET', `/clusters/${cluster.id}/deployments?limit=1`)]);
    const online = nodes.filter((n) => n.online);
    const masters = online.filter((n) => n.vrrpState === 'MASTER');
    const keep = cluster.settings.mode !== 'aws-nlb';
    const latest = deps[0];
    const editPrio = (n) => guard(async () => {
      const v = prompt(`Prioridade VRRP de ${n.name} (1-254, vazio = automática)`, n.settings.priority ?? '');
      if (v === null) return;
      await api('PUT', `/clusters/${cluster.id}/nodes/${n.id}`, { settings: { ...n.settings, priority: v === '' ? null : Number(v) } });
      toast('prioridade salva — publique para aplicar no keepalived');
      render();
    });
    const del = (n) => guard(async () => {
      if (!confirm(`Remover o nó ${n.name}? (o agente o registrará de novo se continuar rodando)`)) return;
      await api('DELETE', `/clusters/${cluster.id}/nodes/${n.id}`);
      render();
    });
    put(body, 
      h('div', { class: 'grid', style: 'margin-bottom:16px' },
        h('div', { class: 'stat' }, h('div', { class: 'k' }, 'Nós online'), h('div', { class: 'v' }, `${online.length}/${nodes.length}`)),
        keep && h('div', { class: 'stat' }, h('div', { class: 'k' }, 'MASTER (detém o VIP)'),
          h('div', { class: `v ${masters.length === 1 ? '' : 'b-err'}` }, masters.length === 1 ? masters[0].name : masters.length ? `SPLIT-BRAIN: ${masters.map((m) => m.name).join(', ')}` : 'nenhum')),
        keep && h('div', { class: 'stat' }, h('div', { class: 'k' }, 'VIPs'), h('div', { class: 'v', style: 'font-size:15px' }, [...cluster.settings.keepalived.vips, cluster.settings.aws.privateVip].filter(Boolean).join(', ') || '—')),
        h('div', { class: 'stat' }, h('div', { class: 'k' }, 'Versão publicada'), h('div', { class: 'v' }, latest ? [`v${latest.version} `, badge(latest.status)] : '—')),
      ),
      latest?.error ? h('div', { class: 'panel err' }, `Última publicação falhou: ${latest.error}`) : null,
      h('div', { class: 'panel' }, h('h3', {}, 'Nós'), nodes.length ? h('table', {},
        h('tr', {}, ['Nó', 'Endereço', 'Estado', keep && 'VRRP', keep && 'Prioridade', 'Versão', 'Última aplicação', 'HAProxy', ''].filter((x) => x !== false).map((t) => h('th', {}, t))),
        nodes.map((n) => h('tr', {},
          h('td', {}, h('b', {}, n.name)), h('td', {}, n.address || '—'),
          h('td', {}, n.online ? h('span', { class: 'badge b-ok' }, 'online') : h('span', { class: 'badge b-err' }, `offline (${ago(n.lastSeen)})`)),
          keep && h('td', {}, badge(n.vrrpState || 'UNKNOWN')),
          keep && h('td', {}, String(n.priority ?? '—'), ' ', h('button', { class: 'small', onclick: editPrio(n) }, 'editar')),
          h('td', {}, `v${n.appliedVersion}`, n.desired?.hold ? h('div', { class: 'muted' }, n.desired.reason) : null),
          h('td', {}, n.lastApply ? [badge(n.lastApply.ok ? 'succeeded' : 'failed'), ` v${n.lastApply.version}`, n.lastApply.error ? h('div', { class: 'err', style: 'font-size:12px;max-width:420px' }, n.lastApply.error.slice(0, 400)) : null] : '—'),
          h('td', { class: 'muted' }, n.haproxyVersion || '—'),
          h('td', {}, h('button', { class: 'small danger', onclick: del(n) }, 'remover')))))
        : h('p', { class: 'muted' }, 'Nenhum nó registrado. Instale o agente nos servidores HAProxy (instruções abaixo).')),
      h('details', { class: 'panel', open: nodes.length ? null : true }, h('summary', {}, h('b', {}, 'Adicionar nós (instalar o agente)')),
        h('p', {}, 'Em cada servidor, configure /etc/hapm/agent.env (ou use o playbook Ansible / user-data do Terraform):'),
        h('pre', {}, `HAPM_MANAGER_URL=${location.origin}\nHAPM_JOIN_TOKEN=${cluster.joinToken}\nHAPM_NODE_NAME=$(hostname -s)\nHAPM_NODE_INTERFACE=${cluster.settings.keepalived.interface}`),
        h('p', { class: 'muted' }, 'O agente se registra sozinho no primeiro heartbeat. Trate o token como segredo (pode ser rotacionado em Configurações).')),
    );
  };
  await render();
  refreshTimer = setInterval(() => render().catch(() => {}), 5000);
}

async function objectsTab(cluster, body, kind, sub) {
  const isFe = kind === 'frontends';
  const label = isFe ? 'listener' : 'backend';
  const [items, backends] = await Promise.all([api('GET', `/clusters/${cluster.id}/${kind}`), api('GET', `/clusters/${cluster.id}/backends`)]);
  const ctx = { backends };
  if (sub) {
    const cur = sub === 'new' ? { mode: 'http' } : items.find((i) => i.id === sub);
    if (!cur) return put(body, h('p', {}, 'não encontrado'));
    const form = buildFields(isFe ? frontendSchema : backendSchema, cur, ctx);
    const save = guard(async () => {
      const data = form.get();
      if (sub === 'new') await api('POST', `/clusters/${cluster.id}/${kind}`, data);
      else await api('PUT', `/clusters/${cluster.id}/${kind}/${sub}`, data);
      toast('salvo — vá em "Publicar" para aplicar nos nós');
      location.hash = `#/c/${cluster.id}/${kind}`;
    });
    const del = guard(async () => {
      if (!confirm(`Excluir ${label} ${cur.name}?`)) return;
      await api('DELETE', `/clusters/${cluster.id}/${kind}/${sub}`);
      location.hash = `#/c/${cluster.id}/${kind}`;
    });
    return put(body, h('div', { class: 'panel' }, h('h3', {}, sub === 'new' ? `Novo ${label}` : `${label}: ${cur.name}`), form.node,
      h('div', { class: 'actions' }, h('button', { class: 'primary', onclick: save }, 'Salvar'), h('a', { class: 'btn', href: `#/c/${cluster.id}/${kind}` }, 'Cancelar'),
        sub !== 'new' && h('button', { class: 'danger', onclick: del }, 'Excluir'))));
  }
  const summary = isFe
    ? (f) => [f.binds.map((b) => `${b.address}:${b.port}${b.ssl ? ' (TLS)' : ''}`).join(', '), `${f.rules.length} regra(s)`, f.defaultBackend || '—']
    : (b) => [b.balance, b.servers.map((s) => `${s.address}:${s.port}${s.backup ? ' (backup)' : ''}`).join(', '), b.healthCheck.type];
  const cols = isFe ? ['Nome', 'Modo', 'Binds', 'Regras', 'Backend padrão'] : ['Nome', 'Modo', 'Balance', 'Servidores', 'Check'];
  put(body, h('div', { class: 'panel' },
    h('div', { class: 'top' }, h('h3', { style: 'margin:0' }, isFe ? 'Listeners (frontends)' : 'Backends'), h('a', { class: 'btn', href: `#/c/${cluster.id}/${kind}/new` }, `+ Novo ${label}`)),
    items.length ? h('table', {}, h('tr', {}, cols.map((c) => h('th', {}, c))),
      items.map((i) => h('tr', {}, h('td', {}, h('a', { href: `#/c/${cluster.id}/${kind}/${i.id}` }, i.name), i.enabled === false ? h('span', { class: 'muted' }, ' (inativo)') : null),
        h('td', {}, i.mode), summary(i).map((s) => h('td', {}, s)))))
      : h('p', { class: 'muted' }, isFe ? 'Nenhum listener. Crie backends primeiro e depois um listener apontando para eles.' : 'Nenhum backend.')));
}

async function certsTab(cluster, body) {
  const render = async () => {
    const certs = await api('GET', `/clusters/${cluster.id}/certificates`);
    const upload = buildFields([{ key: 'name', label: 'Nome' }, { key: 'pem', label: 'PEM (certificado + cadeia + chave privada)', type: 'textarea', mono: true, rows: 8 }], { source: 'upload' }, {});
    const acme = buildFields([[{ key: 'name', label: 'Nome' }, { key: 'email', label: 'E-mail (opcional)' }],
      { key: 'domains', label: 'Domínios (o DNS deve apontar para o VIP/NLB)', type: 'tokens', placeholder: 'app.exemplo.com www.exemplo.com' }], {}, {});
    const doUpload = guard(async () => {
      await api('POST', `/clusters/${cluster.id}/certificates`, upload.get());
      toast('certificado salvo — publique para aplicar');
      render();
    });
    const doAcme = guard(async () => {
      const v = acme.get();
      await api('POST', `/clusters/${cluster.id}/certificates`, { name: v.name, source: 'acme', acme: { domains: v.domains, email: v.email } });
      toast('emissão iniciada — acompanhe o status');
      render();
    });
    const issue = (c) => guard(async () => {
      await api('POST', `/clusters/${cluster.id}/certificates/${c.id}/issue`);
      toast('emissão iniciada');
      render();
    });
    const del = (c) => guard(async () => {
      if (!confirm(`Excluir certificado ${c.name}?`)) return;
      await api('DELETE', `/clusters/${cluster.id}/certificates/${c.id}`);
      render();
    });
    put(body, 
      h('div', { class: 'panel' }, h('h3', {}, 'Certificados'), certs.length ? h('table', {},
        h('tr', {}, ['Nome', 'Domínios', 'Expira', 'Origem', 'Status', ''].map((t) => h('th', {}, t))),
        certs.map((c) => {
          const d = daysLeft(c.meta?.notAfter);
          return h('tr', {}, h('td', {}, h('b', {}, c.name)), h('td', {}, (c.meta?.domains || c.acme?.domains || []).join(', ')),
            h('td', {}, d == null ? '—' : h('span', { class: d < 14 ? 'b-err' : d < 30 ? 'b-warn' : '' }, `${fmtDate(c.meta.notAfter)} (${d} dias)`)),
            h('td', {}, c.source === 'acme' ? 'ACME' : 'upload'),
            h('td', {}, c.source === 'acme' ? [badge(c.acme?.status === 'issued' ? 'succeeded' : c.acme?.status === 'error' ? 'failed' : 'in_progress'),
              c.acme?.error ? h('div', { class: 'err', style: 'font-size:12px' }, c.acme.error) : null] : c.hasKey ? badge('succeeded') : '—'),
            h('td', {}, h('div', { class: 'actions' }, c.source === 'acme' && h('button', { class: 'small', onclick: issue(c) }, 'emitir/renovar'), h('button', { class: 'small danger', onclick: del(c) }, 'excluir'))));
        })) : h('p', { class: 'muted' }, 'Nenhum certificado.'),
      h('p', { class: 'muted' }, 'Todos os certificados vão para um crt-list: o HAProxy escolhe pelo SNI. O "certificado padrão" (sem SNI) é definido em Configurações.')),
      h('details', { class: 'panel' }, h('summary', {}, h('b', {}, "Emitir com Let's Encrypt (HTTP-01)")),
        h('p', { class: 'muted' }, 'Requer "ACME → manager visto pelo HAProxy" em Configurações, um listener HTTP na porta 80 com ACME ativo e a configuração já publicada.'),
        acme.node, h('button', { class: 'primary', onclick: doAcme }, 'Emitir')),
      h('details', { class: 'panel' }, h('summary', {}, h('b', {}, 'Enviar certificado (PEM)')), upload.node, h('button', { class: 'primary', onclick: doUpload }, 'Salvar')),
    );
    if (certs.some((c) => c.acme?.status === 'pending')) refreshTimer = setTimeout(() => render().catch(() => {}), 4000);
  };
  await render();
}

function diffView(diff) {
  return h('div', { class: 'diff panel', style: 'padding:8px;max-height:520px;overflow:auto' },
    diff.map((d) => h('div', { class: d.op === '+' ? 'add' : d.op === '-' ? 'del' : d.op === '@' ? 'gap' : '' }, `${d.op === '@' ? '' : d.op} ${d.line}`)));
}

async function publishTab(cluster, body) {
  put(body, h('p', { class: 'muted' }, 'Gerando configuração...'));
  const p = await api('GET', `/clusters/${cluster.id}/preview`);
  if (p.errors) {
    return put(body, h('div', { class: 'panel' }, h('h3', { class: 'b-err' }, 'A configuração tem problemas'), h('ul', {}, p.errors.map((e) => h('li', { class: 'err' }, e)))));
  }
  const note = h('input', { placeholder: 'o que mudou? (opcional)' });
  const publish = guard(async (e) => {
    e.target.disabled = true;
    try {
      const d = await api('POST', `/clusters/${cluster.id}/deployments`, { note: note.value });
      toast(`v${d.version} publicada — acompanhando rollout`);
      location.hash = `#/c/${cluster.id}`;
    } finally {
      e.target.disabled = false;
    }
  });
  put(body, 
    h('div', { class: 'panel' },
      h('h3', {}, `Publicar v${p.version}`),
      h('p', {}, p.check.available ? (p.check.ok ? [badge('succeeded'), ' haproxy -c: configuração válida'] : [badge('failed'), ' haproxy -c falhou']) : h('span', { class: 'muted' }, 'haproxy não instalado no manager: a validação acontece em cada nó antes do reload.')),
      p.check.available && !p.check.ok ? h('pre', { class: 'err' }, p.check.output) : null,
      !p.changed ? h('p', { class: 'muted' }, `Nada mudou em relação à v${p.currentVersion} (publicar mesmo assim reaplica certificados/keepalived).`) : null,
      h('div', { class: 'row' }, note, h('button', { class: 'primary', style: 'flex:0 0 auto', onclick: publish, disabled: p.check.available && !p.check.ok ? true : null }, 'Publicar')),
      h('p', { class: 'muted' }, cluster.settings.mode === 'aws-nlb' ? 'Rollout: um nó por vez; cada nó valida, recarrega sem derrubar conexões e faz rollback sozinho se falhar.'
        : 'Rollout: nós BACKUP primeiro (canário) e o MASTER por último. Se algum nó falhar, todos voltam para a última versão boa.')),
    p.diff && p.changed ? h('div', {}, h('h3', {}, `Diferenças em relação à v${p.currentVersion}`), diffView(p.diff)) : null,
    h('details', { class: 'panel' }, h('summary', {}, 'haproxy.cfg completo'), h('pre', {}, p.haproxyCfg)),
    Object.keys(p.keepalived).length ? h('details', { class: 'panel' }, h('summary', {}, 'keepalived.conf por nó'),
      Object.entries(p.keepalived).map(([n, conf]) => [h('h4', {}, n), h('pre', {}, conf)])) : null,
  );
}

async function historyTab(cluster, body) {
  const deps = await api('GET', `/clusters/${cluster.id}/deployments`);
  const rollback = (d) => guard(async () => {
    if (!confirm(`Criar uma nova versão com a configuração da v${d.version}?`)) return;
    const n = await api('POST', `/clusters/${cluster.id}/deployments/${d.id}/rollback`);
    toast(`rollback publicado como v${n.version}`);
    location.hash = `#/c/${cluster.id}`;
  });
  const show = (d) => guard(async () => {
    const full = await api('GET', `/clusters/${cluster.id}/deployments/${d.id}`);
    const w = window.open('', '_blank');
    w.document.title = `v${d.version}`;
    w.document.body.append(Object.assign(w.document.createElement('pre'), { textContent: full.haproxyCfg }));
  });
  put(body, h('div', { class: 'panel' }, h('h3', {}, 'Histórico de publicações'), deps.length ? h('table', {},
    h('tr', {}, ['Versão', 'Status', 'Nota', 'Por', 'Quando', ''].map((t) => h('th', {}, t))),
    deps.map((d) => h('tr', {}, h('td', {}, h('b', {}, `v${d.version}`)), h('td', {}, badge(d.status), d.error ? h('div', { class: 'err', style: 'font-size:12px' }, d.error.slice(0, 300)) : null),
      h('td', {}, d.note || ''), h('td', {}, d.createdBy || ''), h('td', {}, fmtDate(d.createdAt)),
      h('td', {}, h('div', { class: 'actions' }, h('button', { class: 'small', onclick: show(d) }, 'ver config'), h('button', { class: 'small', onclick: rollback(d) }, 'rollback'))))))
    : h('p', { class: 'muted' }, 'Nada publicado ainda.')));
}

async function settingsTab(cluster, body) {
  const form = buildFields(clusterSchema, cluster, {});
  const save = guard(async () => {
    await api('PUT', `/clusters/${cluster.id}`, form.get());
    toast('salvo — publique para aplicar');
  });
  const rotate = guard(async () => {
    if (!confirm('Rotacionar o token? Todos os agentes precisarão do novo token.')) return;
    const r = await api('POST', `/clusters/${cluster.id}/rotate-token`);
    prompt('Novo token de join:', r.joinToken);
  });
  const del = guard(async () => {
    if (prompt(`Digite o nome do cluster (${cluster.name}) para excluir:`) !== cluster.name) return;
    await api('DELETE', `/clusters/${cluster.id}`);
    location.hash = '#/';
  });
  put(body, h('div', { class: 'panel' }, form.node, h('div', { class: 'actions' }, h('button', { class: 'primary', onclick: save }, 'Salvar'))),
    h('div', { class: 'panel' }, h('h3', {}, 'Zona de perigo'), h('div', { class: 'actions' }, h('button', { onclick: rotate }, 'Rotacionar token de join'), h('button', { class: 'danger', onclick: del }, 'Excluir cluster'))));
}

async function auditPage() {
  const rows = await api('GET', '/audit');
  await shell('audit', [h('div', { class: 'top' }, h('h2', {}, 'Auditoria')), h('div', { class: 'panel' }, h('table', {},
    h('tr', {}, ['Quando', 'Usuário', 'Ação', 'Alvo', 'Detalhe'].map((t) => h('th', {}, t))),
    rows.map((r) => h('tr', {}, h('td', {}, fmtDate(r.ts)), h('td', {}, r.username || '—'), h('td', {}, h('code', {}, r.action)), h('td', {}, r.target || ''),
      h('td', { class: 'muted', style: 'font-size:12px' }, r.detail ? JSON.stringify(r.detail).slice(0, 200) : '')))))]);
}

async function accountPage() {
  const cur = h('input', { type: 'password' });
  const nw = h('input', { type: 'password' });
  const save = guard(async () => {
    await api('POST', '/me/password', { current: cur.value, password: nw.value });
    toast('senha alterada');
  });
  await shell('account', [h('div', { class: 'top' }, h('h2', {}, 'Conta')), h('div', { class: 'panel', style: 'max-width:420px' }, h('h3', {}, 'Trocar senha'),
    h('label', { class: 'f' }, h('span', {}, 'Senha atual'), cur), h('label', { class: 'f' }, h('span', {}, 'Nova senha (mín. 8)'), nw), h('button', { class: 'primary', onclick: save }, 'Salvar'))]);
}

// ------------------------------------------------------------------ roteador
async function route() {
  clearInterval(refreshTimer);
  clearTimeout(refreshTimer);
  const parts = location.hash.replace(/^#\/?/, '').split('/').filter(Boolean);
  if (!token && parts[0] !== 'login') return (location.hash = '#/login');
  try {
    if (parts[0] === 'login') return loginPage();
    if (parts[0] === 'c') return await clusterPage(parts[1], parts[2] || '', parts[3]);
    if (parts[0] === 'audit') return await auditPage();
    if (parts[0] === 'account') return await accountPage();
    return await homePage();
  } catch (e) {
    toast(e.message, true);
  }
}
window.addEventListener('hashchange', route);
route();
