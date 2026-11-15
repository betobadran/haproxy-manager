// Gera o haproxy.cfg a partir do estado do cluster.
// O agente substitui RELEASE_DIR pelo diretório absoluto da release antes de validar.
import net from 'node:net';

export const RELEASE_DIR = '@HAPM_RELEASE_DIR@';
export const CRT_LIST = `${RELEASE_DIR}/certs.list`;
export const STATS_FRONTEND = 'hapm_stats';
export const ACME_BACKEND = 'hapm_acme';

const ind = (s) => `    ${s}`;

export class RenderError extends Error {
  constructor(errors) {
    super(errors.join('; '));
    this.errors = errors;
    this.statusCode = 422;
  }
}

/** Checa referências cruzadas que só fazem sentido com o cluster inteiro. */
export function crossValidate({ cluster, frontends, backends, certificates }) {
  const errors = [];
  const fe = frontends.filter((f) => f.enabled !== false);
  const beNames = new Map(backends.map((b) => [b.name, b]));
  const feNames = new Set(frontends.map((f) => f.name));
  for (const b of backends) if (feNames.has(b.name)) errors.push(`nome "${b.name}" usado em frontend e backend`);
  const binds = new Map();
  const usableCerts = certificates.filter((c) => c.pem);
  for (const f of fe) {
    const refs = [f.defaultBackend, ...f.rules.filter((r) => r.action === 'use_backend').map((r) => r.backend)].filter(Boolean);
    for (const r of refs) {
      const b = beNames.get(r);
      if (!b) errors.push(`frontend ${f.name}: backend "${r}" não existe`);
      else if (b.mode !== f.mode) errors.push(`frontend ${f.name} (${f.mode}) não pode usar backend ${b.name} (${b.mode})`);
    }
    for (const b of f.binds) {
      const key = `${b.address}:${b.port}`;
      const wkey = `*:${b.port}`;
      const clash = binds.get(key) || (b.address === '*' ? [...binds.entries()].find(([k]) => k.endsWith(`:${b.port}`))?.[1] : binds.get(wkey));
      if (clash && clash !== f.name) errors.push(`porta ${b.port} usada por ${clash} e ${f.name}`);
      binds.set(key, f.name);
      if (b.port === cluster.settings.haproxy.statsPort) errors.push(`frontend ${f.name}: porta ${b.port} reservada para stats/métricas`);
      if (b.ssl && !usableCerts.length) errors.push(`frontend ${f.name}: bind SSL na porta ${b.port} mas não há certificados emitidos`);
    }
  }
  const dc = cluster.settings.haproxy.defaultCertificate;
  if (dc && !usableCerts.find((c) => c.name === dc)) errors.push(`certificado padrão "${dc}" não existe ou não foi emitido`);
  return errors;
}

function bindLine(b, f) {
  const addr = b.address === '*' ? '' : b.address;
  let line = `bind ${addr}:${b.port}`;
  if (addr === '::') line += ' v4v6';
  if (b.ssl) {
    line += ` ssl crt-list ${CRT_LIST}`;
    if (f.mode === 'http') line += f.http2 ? ' alpn h2,http/1.1' : ' alpn http/1.1';
  }
  return line;
}

function hostAcls(aclName, fetch, values) {
  const exact = values.filter((v) => !v.startsWith('*.'));
  const wild = values.filter((v) => v.startsWith('*.')).map((v) => v.slice(1));
  const out = [];
  if (exact.length) out.push(`acl ${aclName} ${fetch} -i ${exact.join(' ')}`);
  if (wild.length) out.push(`acl ${aclName} ${fetch} -i -m end ${wild.join(' ')}`);
  return out;
}

function conditionAcls(aclName, c, f) {
  const v = c.value;
  switch (c.type) {
    case 'host':
      return hostAcls(aclName, 'req.hdr(host),field(1,:)', v);
    case 'sni':
      return hostAcls(aclName, f.binds.some((b) => b.ssl) ? 'ssl_fc_sni' : 'req.ssl_sni', v);
    case 'path':
      return [`acl ${aclName} path ${v.join(' ')}`];
    case 'path_beg':
      return [`acl ${aclName} path_beg ${v.join(' ')}`];
    case 'path_end':
      return [`acl ${aclName} path_end ${v.join(' ')}`];
    case 'path_reg':
      return [`acl ${aclName} path_reg '${v[0]}'`];
    case 'method':
      return [`acl ${aclName} method ${v.join(' ')}`];
    case 'src':
      return [`acl ${aclName} src ${v.join(' ')}`];
    case 'header':
      return [`acl ${aclName} req.hdr(${c.name}) -m str ${v.join(' ')}`];
    default:
      throw new Error(`condição desconhecida ${c.type}`);
  }
}

function renderFrontend(f, ctx) {
  const L = [];
  const isHttp = f.mode === 'http';
  const hasSsl = f.binds.some((b) => b.ssl);
  L.push(`frontend ${f.name}`);
  if (f.description) L.push(ind(`description ${f.description}`));
  L.push(ind(`mode ${f.mode}`));
  for (const b of f.binds) L.push(ind(bindLine(b, f)));
  if (f.maxconn) L.push(ind(`maxconn ${f.maxconn}`));
  if (f.timeoutClient) L.push(ind(`timeout client ${f.timeoutClient}`));
  L.push(ind(isHttp ? 'option httplog' : 'option tcplog'));

  const useAcme = isHttp && f.acme && ctx.acmeBackend;
  if (useAcme) L.push(ind('acl hapm_acme path_beg /.well-known/acme-challenge/'));

  // allowlist de origem (o desafio ACME continua liberado para o Let's Encrypt validar)
  if (f.allowCidrs.length) {
    L.push(ind(`acl hapm_allowed src ${f.allowCidrs.join(' ')}`));
    L.push(ind(isHttp ? `http-request deny deny_status 403 if !hapm_allowed${useAcme ? ' !hapm_acme' : ''}` : 'tcp-request connection reject if !hapm_allowed'));
  }

  // rate limit por IP de origem
  if (f.rateLimit.enabled) {
    if (isHttp) {
      L.push(ind(`stick-table type ipv6 size 1m expire 2m store http_req_rate(${f.rateLimit.period})`));
      L.push(ind('http-request track-sc0 src'));
      L.push(ind(`http-request deny deny_status 429 if { sc_http_req_rate(0) gt ${f.rateLimit.requests} }`));
    } else {
      L.push(ind(`stick-table type ipv6 size 1m expire 2m store conn_rate(${f.rateLimit.period})`));
      L.push(ind('tcp-request connection track-sc0 src'));
      L.push(ind(`tcp-request connection reject if { sc_conn_rate(0) gt ${f.rateLimit.requests} }`));
    }
  }


  if (isHttp) {
    if (f.forwardFor) L.push(ind('option forwardfor'));
    L.push(ind('http-request set-header X-Forwarded-Proto https if { ssl_fc }'));
    L.push(ind('http-request set-header X-Forwarded-Proto http if !{ ssl_fc }'));
    if (f.redirectToHttps) L.push(ind(`http-request redirect scheme https code 301 if !{ ssl_fc }${useAcme ? ' !hapm_acme' : ''}`));
    if (f.hsts) L.push(ind('http-response set-header Strict-Transport-Security "max-age=63072000; includeSubDomains" if { ssl_fc }'));
  }

  // regras do usuário
  const needsInspect = !isHttp && !hasSsl && f.rules.some((r) => r.conditions.some((c) => c.type === 'sni'));
  if (needsInspect) {
    L.push(ind('tcp-request inspect-delay 5s'));
    L.push(ind('tcp-request content accept if { req_ssl_hello_type 1 }'));
  }
  const actions = [];
  const routes = [];
  f.rules.forEach((r, i) => {
    const refs = [];
    if (r.description) L.push(ind(`# regra ${i + 1}: ${r.description}`));
    r.conditions.forEach((c, j) => {
      const acl = `r${i + 1}_c${j + 1}`;
      for (const a of conditionAcls(acl, c, f)) L.push(ind(a));
      refs.push(c.negate ? `!${acl}` : acl);
    });
    const cond = refs.length ? ` if ${refs.join(' ')}` : '';
    switch (r.action) {
      case 'use_backend':
        routes.push(`use_backend ${r.backend}${cond}`);
        break;
      case 'deny':
        actions.push(isHttp ? `http-request deny deny_status ${r.status}${cond}` : `tcp-request content reject${cond}`);
        break;
      case 'redirect':
        actions.push(`http-request redirect ${r.redirectType} ${r.url} code ${r.code}${cond}`);
        break;
      case 'set_header':
        actions.push(r.direction === 'response'
          ? `http-response set-header ${r.header} "${r.value}"${cond}`
          : `http-request set-header ${r.header} "${r.value}"${cond}`);
        break;
      case 'del_header':
        actions.push(`${r.direction === 'response' ? 'http-response' : 'http-request'} del-header ${r.header}${cond}`);
        break;
    }
  });
  for (const a of actions) L.push(ind(a));
  if (useAcme) L.push(ind(`use_backend ${ACME_BACKEND} if hapm_acme`));
  for (const r of routes) L.push(ind(r));
  if (f.defaultBackend) L.push(ind(`default_backend ${f.defaultBackend}`));
  if (f.extra) {
    L.push(ind('# --- bloco avançado'));
    for (const line of f.extra.split('\n')) if (line.trim()) L.push(ind(line.trim()));
  }
  return L;
}

function serverAddr(s) {
  return `${s.address}:${s.port}`;
}

function renderBackend(b) {
  const L = [];
  const hc = b.healthCheck;
  L.push(`backend ${b.name}`);
  if (b.description) L.push(ind(`description ${b.description}`));
  L.push(ind(`mode ${b.mode}`));
  L.push(ind(`balance ${b.balance}`));
  if (b.timeoutServer) L.push(ind(`timeout server ${b.timeoutServer}`));
  if (b.timeoutConnect) L.push(ind(`timeout connect ${b.timeoutConnect}`));
  if (hc.type === 'http') {
    L.push(ind('option httpchk'));
    L.push(ind(`http-check send meth ${hc.method} uri ${hc.path} ver HTTP/1.1 hdr Host ${hc.host || 'localhost'}`));
    L.push(ind(`http-check expect status ${hc.expectStatus}`));
  }
  if (b.compression) {
    L.push(ind('compression algo gzip'));
    L.push(ind('compression type text/html text/plain text/css text/javascript application/javascript application/json image/svg+xml'));
  }
  if (b.sticky.enabled) L.push(ind(`cookie ${b.sticky.cookie} insert indirect nocache httponly`));
  const ds = ['default-server'];
  if (hc.type !== 'none') ds.push(`check inter ${hc.interval} fall ${hc.fall} rise ${hc.rise}`);
  if (b.serverSsl.enabled) {
    ds.push(b.serverSsl.verify === 'required' ? 'ssl verify required ca-file @system-ca' : 'ssl verify none');
    if (b.serverSsl.sni && b.mode === 'http') ds.push('sni req.hdr(host),field(1,:)');
  }
  if (ds.length > 1) L.push(ind(ds.join(' ')));
  for (const s of b.servers) {
    let line = `server ${s.name} ${serverAddr(s)} weight ${s.weight}`;
    if (s.backup) line += ' backup';
    if (s.maxconn) line += ` maxconn ${s.maxconn}`;
    if (b.sticky.enabled) line += ` cookie ${s.name}`;
    if (s.disabled) line += ' disabled';
    L.push(ind(line));
  }
  if (b.extra) {
    L.push(ind('# --- bloco avançado'));
    for (const line of b.extra.split('\n')) if (line.trim()) L.push(ind(line.trim()));
  }
  return L;
}

/** Ordem dos certificados no crt-list: o padrão primeiro (é o usado quando não há SNI). */
export function orderCertificates(cluster, certificates) {
  const usable = certificates.filter((c) => c.pem).sort((a, b) => a.name.localeCompare(b.name));
  const dc = cluster.settings.haproxy.defaultCertificate;
  const i = usable.findIndex((c) => c.name === dc);
  if (i > 0) usable.unshift(...usable.splice(i, 1));
  return usable;
}

export function renderCrtList(cluster, certificates) {
  return orderCertificates(cluster, certificates).map((c) => `${RELEASE_DIR}/certs/${c.name}.pem`).join('\n') + '\n';
}

export function renderHaproxy({ cluster, frontends, backends, certificates, version = 0, generatedAt = new Date().toISOString() }) {
  const errors = crossValidate({ cluster, frontends, backends, certificates });
  if (errors.length) throw new RenderError(errors);
  const h = cluster.settings.haproxy;
  const t = h.timeouts;
  const acmeBackend = cluster.settings.acme?.backend || null;
  const fe = frontends.filter((f) => f.enabled !== false).sort((a, b) => a.name.localeCompare(b.name));
  const be = [...backends].sort((a, b) => a.name.localeCompare(b.name));
  const needsResolvers = [...be.flatMap((b) => b.servers.map((s) => s.address)), acmeBackend?.replace(/:\d+$/, '')]
    .filter(Boolean).some((a) => !net.isIP(a));

  const L = [];
  L.push('# ==========================================================================');
  L.push('# Gerado pelo HAPM (HAProxy HA Manager) - NÃO EDITE MANUALMENTE');
  L.push(`# cluster: ${cluster.name}   versão: ${version}   gerado em: ${generatedAt}`);
  L.push('# ==========================================================================');
  L.push('global');
  L.push(ind(`log ${h.log}`));
  L.push(ind(`maxconn ${h.maxconn}`));
  if (h.nbthread) L.push(ind(`nbthread ${h.nbthread}`));
  if (h.user) {
    L.push(ind(`user ${h.user}`));
    L.push(ind(`group ${h.user}`));
  }
  L.push(ind(`stats socket ${h.statsSocket} mode 660 level admin expose-fd listeners`));
  L.push(ind('stats timeout 30s'));
  L.push(ind('ssl-default-bind-ciphersuites TLS_AES_128_GCM_SHA256:TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256'));
  L.push(ind('ssl-default-bind-ciphers ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305'));
  L.push(ind(`ssl-default-bind-options ssl-min-ver ${h.sslMinVer} no-tls-tickets`));
  L.push(ind('ssl-default-server-options ssl-min-ver TLSv1.2'));
  if (h.globalExtra) for (const line of h.globalExtra.split('\n')) if (line.trim()) L.push(ind(line.trim()));
  L.push('');

  if (needsResolvers) {
    L.push('resolvers sysdns');
    L.push(ind('parse-resolv-conf'));
    L.push(ind('hold valid 10s'));
    L.push(ind('accepted_payload_size 8192'));
    L.push('');
  }

  L.push('defaults');
  L.push(ind('mode http'));
  L.push(ind('log global'));
  L.push(ind('option dontlognull'));
  L.push(ind('option redispatch'));
  L.push(ind('retries 3'));
  L.push(ind(`timeout connect ${t.connect}`));
  L.push(ind(`timeout client ${t.client}`));
  L.push(ind(`timeout server ${t.server}`));
  L.push(ind(`timeout http-request ${t.httpRequest}`));
  L.push(ind(`timeout http-keep-alive ${t.httpKeepAlive}`));
  L.push(ind(`timeout queue ${t.queue}`));
  L.push(ind(`timeout tunnel ${t.tunnel}`));
  L.push(ind(`default-server init-addr last,libc,none${needsResolvers ? ' resolvers sysdns' : ''}`));
  if (h.defaultsExtra) for (const line of h.defaultsExtra.split('\n')) if (line.trim()) L.push(ind(line.trim()));
  L.push('');

  // frontend interno: métricas Prometheus, health-check (keepalived/NLB) e página de stats
  L.push(`frontend ${STATS_FRONTEND}`);
  L.push(ind('mode http'));
  L.push(ind(`bind :${h.statsPort}`));
  L.push(ind('no log'));
  L.push(ind('http-request use-service prometheus-exporter if { path /metrics }'));
  L.push(ind('monitor-uri /healthz'));
  L.push(ind('stats enable'));
  L.push(ind('stats uri /stats'));
  L.push(ind('stats refresh 10s'));
  if (h.statsUser && h.statsPassword) L.push(ind(`stats auth ${h.statsUser}:${h.statsPassword}`));
  L.push('');

  for (const f of fe) {
    L.push(...renderFrontend(f, { acmeBackend }));
    L.push('');
  }
  if (acmeBackend) {
    L.push(`backend ${ACME_BACKEND}`);
    L.push(ind('mode http'));
    L.push(ind(`server manager ${acmeBackend}`));
    L.push('');
  }
  for (const b of be) {
    L.push(...renderBackend(b));
    L.push('');
  }
  return L.join('\n');
}
