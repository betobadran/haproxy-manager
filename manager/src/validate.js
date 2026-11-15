// Validação e normalização de todos os objetos que viram configuração.
// Regra de ouro: nenhum valor livre pode conter quebra de linha, aspas ou '#',
// senão seria possível injetar diretivas arbitrárias no haproxy.cfg / keepalived.conf.
import net from 'node:net';
import crypto from 'node:crypto';

export class ValidationError extends Error {
  constructor(errors) {
    super(Array.isArray(errors) ? errors.join('; ') : errors);
    this.errors = Array.isArray(errors) ? errors : [errors];
    this.statusCode = 400;
  }
}

export const NAME_RE = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$/;
const HOSTNAME_RE = /^(?=.{1,253}$)([A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9])?\.)*[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$/;
const DOMAIN_RE = /^(\*\.)?([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$|^(\*\.)?[A-Za-z0-9-]+$/;
const TOKEN_RE = /^[^\s"'\\#]+$/;
const DURATION_RE = /^\d+(us|ms|s|m|h|d)?$/;
const HEADER_NAME_RE = /^[A-Za-z0-9!#$%&*+.^_`|~-]+$/; // RFC 7230 token (sem '#'? ver abaixo)
const SAFE_LINE_RE = /^[^\r\n"\\]*$/;
const RESERVED_PREFIX = 'hapm_';

class Ctx {
  constructor() {
    this.errors = [];
  }
  err(path, msg) {
    this.errors.push(`${path}: ${msg}`);
  }
  done() {
    if (this.errors.length) throw new ValidationError(this.errors);
  }
}

const isObj = (v) => v && typeof v === 'object' && !Array.isArray(v);
const arr = (v) => (Array.isArray(v) ? v : v == null || v === '' ? [] : [v]);
const bool = (v, d) => (v === undefined || v === null || v === '' ? d : v === true || v === 'true' || v === 1 || v === 'on');
const str = (v, d = '') => (v === undefined || v === null ? d : String(v).trim());

function int(ctx, path, v, { min = -Infinity, max = Infinity, def = null } = {}) {
  if (v === undefined || v === null || v === '') return def;
  const n = Number(v);
  if (!Number.isInteger(n) || n < min || n > max) {
    ctx.err(path, `deve ser inteiro entre ${min} e ${max}`);
    return def;
  }
  return n;
}
function name(ctx, path, v, { reserved = true } = {}) {
  const s = str(v);
  if (!NAME_RE.test(s)) ctx.err(path, 'nome inválido (use letras, números, "_", "-", "."; máx. 63)');
  else if (reserved && s.startsWith(RESERVED_PREFIX)) ctx.err(path, `prefixo "${RESERVED_PREFIX}" é reservado`);
  return s;
}
function oneOf(ctx, path, v, options, def) {
  const s = v === undefined || v === null || v === '' ? def : String(v);
  if (!options.includes(s)) ctx.err(path, `deve ser um de: ${options.join(', ')}`);
  return s;
}
function duration(ctx, path, v, def = null) {
  const s = str(v);
  if (!s) return def;
  if (!DURATION_RE.test(s)) ctx.err(path, 'duração inválida (ex.: 500ms, 5s, 1m)');
  return s;
}
function tokens(ctx, path, v) {
  const list = Array.isArray(v) ? v.map(String) : str(v).split(/[\s,]+/);
  const out = list.map((t) => t.trim()).filter(Boolean);
  for (const t of out) if (!TOKEN_RE.test(t)) ctx.err(path, `valor "${t}" contém caracteres não permitidos (espaço, aspas, \\ ou #)`);
  return out;
}
function safeLine(ctx, path, v, def = '') {
  const s = str(v, def);
  if (!SAFE_LINE_RE.test(s)) ctx.err(path, 'não pode conter quebras de linha, aspas duplas ou \\');
  return s;
}
export function isCidr(v) {
  const [ip, bits, ...rest] = String(v).split('/');
  if (rest.length) return false;
  const fam = net.isIP(ip);
  if (!fam) return false;
  if (bits === undefined) return true;
  const n = Number(bits);
  return Number.isInteger(n) && n >= 0 && n <= (fam === 4 ? 32 : 128);
}
function cidrs(ctx, path, v) {
  const list = tokens(ctx, path, v);
  for (const c of list) if (!isCidr(c)) ctx.err(path, `"${c}" não é IP/CIDR válido`);
  return list;
}
function address(ctx, path, v, { allowWildcard = false } = {}) {
  const s = str(v);
  if (allowWildcard && (s === '*' || s === '')) return '*';
  if (net.isIP(s) || HOSTNAME_RE.test(s)) return s;
  ctx.err(path, `endereço inválido "${s}"`);
  return s;
}
function port(ctx, path, v) {
  return int(ctx, path, v, { min: 1, max: 65535 });
}
function rawBlock(ctx, path, v) {
  // bloco "avançado" com linhas cruas — permitido para admins, mas sem seções novas
  const s = str(v);
  if (!s) return '';
  for (const [i, line] of s.split(/\r?\n/).entries()) {
    const t = line.trim();
    if (/^(global|defaults|frontend|backend|listen|resolvers|peers|userlist|program|cache|ring|http-errors|mailers|vrrp_|virtual_server|global_defs)\b/.test(t)) {
      ctx.err(`${path}[linha ${i + 1}]`, 'não é permitido abrir novas seções no bloco avançado');
    }
  }
  return s.replace(/\r/g, '');
}

// ---------------------------------------------------------------- cluster
export const CLUSTER_MODES = ['onprem-keepalived', 'aws-nlb', 'aws-eip'];

export function normalizeCluster(input, existing = null) {
  const ctx = new Ctx();
  const i = isObj(input) ? input : {};
  const prev = existing?.settings || {};
  const s = isObj(i.settings) ? i.settings : {};
  const h = isObj(s.haproxy) ? s.haproxy : {};
  const k = isObj(s.keepalived) ? s.keepalived : {};
  const a = isObj(s.aws) ? s.aws : {};
  const acme = isObj(s.acme) ? s.acme : {};
  const t = isObj(h.timeouts) ? h.timeouts : {};

  const out = {
    name: name(ctx, 'name', i.name ?? existing?.name, { reserved: false }),
    settings: {
      mode: oneOf(ctx, 'settings.mode', s.mode, CLUSTER_MODES, 'onprem-keepalived'),
      rollout: oneOf(ctx, 'settings.rollout', s.rollout, ['sequential', 'parallel'], 'sequential'),
      autoDeployOnCertRenew: bool(s.autoDeployOnCertRenew, true),
      haproxy: {
        maxconn: int(ctx, 'haproxy.maxconn', h.maxconn, { min: 100, max: 10_000_000, def: 50000 }),
        nbthread: int(ctx, 'haproxy.nbthread', h.nbthread, { min: 1, max: 256, def: null }),
        log: safeLine(ctx, 'haproxy.log', h.log, '/dev/log local0'),
        user: h.user === '' ? '' : name(ctx, 'haproxy.user', h.user ?? 'haproxy', { reserved: false }),
        statsSocket: safeLine(ctx, 'haproxy.statsSocket', h.statsSocket, '/run/haproxy/admin.sock'),
        statsPort: port(ctx, 'haproxy.statsPort', h.statsPort ?? 8405),
        statsUser: str(h.statsUser),
        statsPassword: str(h.statsPassword),
        sslMinVer: oneOf(ctx, 'haproxy.sslMinVer', h.sslMinVer, ['TLSv1.2', 'TLSv1.3'], 'TLSv1.2'),
        defaultCertificate: str(h.defaultCertificate),
        timeouts: {
          connect: duration(ctx, 'timeouts.connect', t.connect, '5s'),
          client: duration(ctx, 'timeouts.client', t.client, '50s'),
          server: duration(ctx, 'timeouts.server', t.server, '50s'),
          httpRequest: duration(ctx, 'timeouts.httpRequest', t.httpRequest, '10s'),
          httpKeepAlive: duration(ctx, 'timeouts.httpKeepAlive', t.httpKeepAlive, '10s'),
          queue: duration(ctx, 'timeouts.queue', t.queue, '30s'),
          tunnel: duration(ctx, 'timeouts.tunnel', t.tunnel, '1h'),
        },
        globalExtra: rawBlock(ctx, 'haproxy.globalExtra', h.globalExtra),
        defaultsExtra: rawBlock(ctx, 'haproxy.defaultsExtra', h.defaultsExtra),
      },
      keepalived: {
        interface: str(k.interface, 'eth0'),
        routerId: int(ctx, 'keepalived.routerId', k.routerId, { min: 1, max: 255, def: 51 }),
        vips: cidrs(ctx, 'keepalived.vips', k.vips),
        authPass: str(k.authPass, prev.keepalived?.authPass || crypto.randomBytes(6).toString('base64url').slice(0, 8)),
        advertInt: int(ctx, 'keepalived.advertInt', k.advertInt, { min: 1, max: 255, def: 1 }),
        unicast: bool(k.unicast, true),
        preempt: bool(k.preempt, true),
        checkInterval: int(ctx, 'keepalived.checkInterval', k.checkInterval, { min: 1, max: 60, def: 2 }),
        checkFall: int(ctx, 'keepalived.checkFall', k.checkFall, { min: 1, max: 20, def: 2 }),
        checkRise: int(ctx, 'keepalived.checkRise', k.checkRise, { min: 1, max: 20, def: 2 }),
      },
      aws: {
        region: str(a.region),
        eipAllocationId: str(a.eipAllocationId),
        privateVip: str(a.privateVip),
      },
      acme: {
        email: str(acme.email),
        // host:porta onde o HAProxy alcança o manager para responder /.well-known/acme-challenge
        backend: str(acme.backend),
      },
    },
  };
  const so = out.settings;
  if (!/^[A-Za-z0-9_.:-]{1,15}$/.test(so.keepalived.interface)) ctx.err('keepalived.interface', 'interface inválida');
  if (!/^[A-Za-z0-9_.@%+=-]{1,8}$/.test(so.keepalived.authPass)) ctx.err('keepalived.authPass', 'até 8 caracteres alfanuméricos');
  if (so.haproxy.statsUser && !/^[A-Za-z0-9_.-]+$/.test(so.haproxy.statsUser)) ctx.err('haproxy.statsUser', 'inválido');
  if (so.haproxy.statsPassword && !TOKEN_RE.test(so.haproxy.statsPassword)) ctx.err('haproxy.statsPassword', 'sem espaços, aspas, \\ ou #');
  if (so.haproxy.defaultCertificate && !NAME_RE.test(so.haproxy.defaultCertificate)) ctx.err('haproxy.defaultCertificate', 'nome inválido');
  if (so.aws.region && !/^[a-z]{2}(-[a-z]+)+-\d$/.test(so.aws.region)) ctx.err('aws.region', 'região inválida (ex.: us-east-1)');
  if (so.aws.eipAllocationId && !/^eipalloc-[0-9a-f]+$/.test(so.aws.eipAllocationId)) ctx.err('aws.eipAllocationId', 'formato eipalloc-xxxx');
  if (so.aws.privateVip && net.isIP(so.aws.privateVip) !== 4) ctx.err('aws.privateVip', 'IPv4 inválido');
  if (so.acme.backend) {
    const m = so.acme.backend.match(/^(.+):(\d+)$/);
    if (!m || !(net.isIP(m[1]) || HOSTNAME_RE.test(m[1]))) ctx.err('acme.backend', 'formato host:porta');
  }
  if (so.acme.email && !/^[^\s@"'<>]+@[^\s@"'<>]+$/.test(so.acme.email)) ctx.err('acme.email', 'e-mail inválido');
  if (so.mode === 'aws-eip' && !so.aws.eipAllocationId && !so.aws.privateVip) {
    ctx.err('aws', 'modo aws-eip exige eipAllocationId e/ou privateVip');
  }
  if (so.mode === 'aws-eip' && !so.aws.region) ctx.err('aws.region', 'obrigatório no modo aws-eip');
  // no modo aws-eip o IP privado secundário também vira VIP do keepalived
  ctx.done();
  return out;
}

// ---------------------------------------------------------------- nodes
export function normalizeNodeSettings(input) {
  const ctx = new Ctx();
  const i = isObj(input) ? input : {};
  const out = {
    priority: int(ctx, 'priority', i.priority, { min: 1, max: 254, def: null }),
    interface: str(i.interface),
    unicastAddress: str(i.unicastAddress),
    disabled: bool(i.disabled, false),
  };
  if (out.interface && !/^[A-Za-z0-9_.:-]{1,15}$/.test(out.interface)) ctx.err('interface', 'interface inválida');
  if (out.unicastAddress && !net.isIP(out.unicastAddress)) ctx.err('unicastAddress', 'IP inválido');
  ctx.done();
  return out;
}

// ---------------------------------------------------------------- frontend
const HTTP_COND = ['host', 'path', 'path_beg', 'path_end', 'path_reg', 'method', 'src', 'header'];
const TCP_COND = ['sni', 'src'];
const HTTP_ACTIONS = ['use_backend', 'redirect', 'deny', 'set_header', 'del_header'];
const TCP_ACTIONS = ['use_backend', 'deny'];

function normalizeCondition(ctx, path, c, mode) {
  const type = oneOf(ctx, `${path}.type`, c?.type, mode === 'http' ? HTTP_COND : TCP_COND, mode === 'http' ? 'host' : 'sni');
  const out = { type, negate: bool(c?.negate, false), value: [] };
  if (type === 'path_reg') {
    const v = str(c?.value);
    if (!v || /[\r\n']/.test(v)) ctx.err(`${path}.value`, 'regex obrigatória, sem aspas simples ou quebra de linha');
    out.value = [v];
    return out;
  }
  out.value = type === 'src' ? cidrs(ctx, `${path}.value`, c?.value) : tokens(ctx, `${path}.value`, c?.value);
  if (!out.value.length) ctx.err(`${path}.value`, 'obrigatório');
  if (type === 'host' || type === 'sni') {
    out.value = out.value.map((v) => v.toLowerCase());
    for (const v of out.value) if (!DOMAIN_RE.test(v)) ctx.err(`${path}.value`, `domínio inválido "${v}"`);
  }
  if (type === 'path' || type === 'path_beg') for (const v of out.value) if (!v.startsWith('/')) ctx.err(`${path}.value`, `caminho deve começar com "/" ("${v}")`);
  if (type === 'method') out.value = out.value.map((v) => v.toUpperCase());
  if (type === 'header') {
    out.name = str(c?.name);
    if (!HEADER_NAME_RE.test(out.name) || out.name.includes('#')) ctx.err(`${path}.name`, 'nome de header inválido');
  }
  return out;
}

function normalizeRule(ctx, path, r, mode) {
  const out = {
    description: safeLine(ctx, `${path}.description`, r?.description),
    conditions: arr(r?.conditions).map((c, j) => normalizeCondition(ctx, `${path}.conditions[${j}]`, c, mode)),
    action: oneOf(ctx, `${path}.action`, r?.action, mode === 'http' ? HTTP_ACTIONS : TCP_ACTIONS, 'use_backend'),
  };
  if (!out.conditions.length && out.action === 'use_backend') ctx.err(`${path}.conditions`, 'use_backend sem condição: use o backend padrão');
  switch (out.action) {
    case 'use_backend':
      out.backend = name(ctx, `${path}.backend`, r?.backend);
      break;
    case 'redirect': {
      out.redirectType = oneOf(ctx, `${path}.redirectType`, r?.redirectType, ['location', 'prefix', 'scheme'], 'location');
      out.url = str(r?.url);
      if (out.redirectType === 'scheme') {
        if (!['http', 'https'].includes(out.url)) ctx.err(`${path}.url`, 'para scheme use http ou https');
      } else if (!TOKEN_RE.test(out.url)) ctx.err(`${path}.url`, 'URL obrigatória, sem espaços/aspas');
      out.code = oneOf(ctx, `${path}.code`, r?.code != null ? String(r.code) : undefined, ['301', '302', '303', '307', '308'], '302');
      break;
    }
    case 'deny':
      out.status = int(ctx, `${path}.status`, r?.status, { min: 200, max: 599, def: 403 });
      break;
    case 'set_header':
    case 'del_header':
      out.header = str(r?.header);
      if (!HEADER_NAME_RE.test(out.header) || out.header.includes('#')) ctx.err(`${path}.header`, 'nome de header inválido');
      if (out.action === 'set_header') out.value = safeLine(ctx, `${path}.value`, r?.value);
      out.direction = oneOf(ctx, `${path}.direction`, r?.direction, ['request', 'response'], 'request');
      break;
  }
  return out;
}

export function normalizeFrontend(input) {
  const ctx = new Ctx();
  const i = isObj(input) ? input : {};
  const mode = oneOf(ctx, 'mode', i.mode, ['http', 'tcp'], 'http');
  const rl = isObj(i.rateLimit) ? i.rateLimit : {};
  const out = {
    name: name(ctx, 'name', i.name),
    enabled: bool(i.enabled, true),
    description: safeLine(ctx, 'description', i.description),
    mode,
    binds: arr(i.binds).map((b, j) => ({
      address: address(ctx, `binds[${j}].address`, b?.address, { allowWildcard: true }),
      port: port(ctx, `binds[${j}].port`, b?.port),
      ssl: bool(b?.ssl, false),
    })),
    defaultBackend: str(i.defaultBackend),
    redirectToHttps: mode === 'http' && bool(i.redirectToHttps, false),
    hsts: mode === 'http' && bool(i.hsts, false),
    http2: bool(i.http2, true),
    forwardFor: mode === 'http' && bool(i.forwardFor, true),
    acme: mode === 'http' && bool(i.acme, true),
    allowCidrs: cidrs(ctx, 'allowCidrs', i.allowCidrs),
    rateLimit: {
      enabled: bool(rl.enabled, false),
      requests: int(ctx, 'rateLimit.requests', rl.requests, { min: 1, max: 1_000_000, def: 100 }),
      period: duration(ctx, 'rateLimit.period', rl.period, '10s'),
    },
    maxconn: int(ctx, 'maxconn', i.maxconn, { min: 1, max: 10_000_000, def: null }),
    timeoutClient: duration(ctx, 'timeoutClient', i.timeoutClient, null),
    rules: arr(i.rules).map((r, j) => normalizeRule(ctx, `rules[${j}]`, r, mode)),
    extra: rawBlock(ctx, 'extra', i.extra),
  };
  if (!out.binds.length) ctx.err('binds', 'pelo menos um bind é obrigatório');
  if (out.defaultBackend && !NAME_RE.test(out.defaultBackend)) ctx.err('defaultBackend', 'nome inválido');
  ctx.done();
  return out;
}

// ---------------------------------------------------------------- backend
export function normalizeBackend(input) {
  const ctx = new Ctx();
  const i = isObj(input) ? input : {};
  const mode = oneOf(ctx, 'mode', i.mode, ['http', 'tcp'], 'http');
  const hc = isObj(i.healthCheck) ? i.healthCheck : {};
  const st = isObj(i.sticky) ? i.sticky : {};
  const ssl = isObj(i.serverSsl) ? i.serverSsl : {};
  const out = {
    name: name(ctx, 'name', i.name),
    description: safeLine(ctx, 'description', i.description),
    mode,
    balance: oneOf(ctx, 'balance', i.balance, ['roundrobin', 'leastconn', 'source', 'first', 'uri', 'random'], 'roundrobin'),
    servers: arr(i.servers).map((s, j) => ({
      name: name(ctx, `servers[${j}].name`, s?.name ?? `srv${j + 1}`),
      address: address(ctx, `servers[${j}].address`, s?.address),
      port: port(ctx, `servers[${j}].port`, s?.port),
      weight: int(ctx, `servers[${j}].weight`, s?.weight, { min: 0, max: 256, def: 100 }),
      backup: bool(s?.backup, false),
      maxconn: int(ctx, `servers[${j}].maxconn`, s?.maxconn, { min: 1, max: 10_000_000, def: null }),
      disabled: bool(s?.disabled, false),
    })),
    healthCheck: {
      type: oneOf(ctx, 'healthCheck.type', hc.type, ['none', 'tcp', 'http'], mode === 'http' ? 'http' : 'tcp'),
      path: str(hc.path, '/'),
      method: oneOf(ctx, 'healthCheck.method', hc.method, ['GET', 'HEAD', 'OPTIONS'], 'GET'),
      host: str(hc.host),
      expectStatus: str(hc.expectStatus, '200-399'),
      interval: duration(ctx, 'healthCheck.interval', hc.interval, '2s'),
      fall: int(ctx, 'healthCheck.fall', hc.fall, { min: 1, max: 100, def: 3 }),
      rise: int(ctx, 'healthCheck.rise', hc.rise, { min: 1, max: 100, def: 2 }),
    },
    sticky: { enabled: mode === 'http' && bool(st.enabled, false), cookie: str(st.cookie, 'HAPM_SRV') },
    serverSsl: { enabled: bool(ssl.enabled, false), verify: oneOf(ctx, 'serverSsl.verify', ssl.verify, ['none', 'required'], 'none'), sni: bool(ssl.sni, true) },
    compression: mode === 'http' && bool(i.compression, false),
    timeoutServer: duration(ctx, 'timeoutServer', i.timeoutServer, null),
    timeoutConnect: duration(ctx, 'timeoutConnect', i.timeoutConnect, null),
    extra: rawBlock(ctx, 'extra', i.extra),
  };
  const seen = new Set();
  for (const s of out.servers) {
    if (seen.has(s.name)) ctx.err('servers', `nome de servidor duplicado "${s.name}"`);
    seen.add(s.name);
  }
  if (out.healthCheck.type === 'http') {
    if (!/^\/[^\s"'\\#]*$/.test(out.healthCheck.path)) ctx.err('healthCheck.path', 'caminho inválido');
    if (out.healthCheck.host && !HOSTNAME_RE.test(out.healthCheck.host)) ctx.err('healthCheck.host', 'host inválido');
    if (!/^\d{3}(-\d{3})?$/.test(out.healthCheck.expectStatus)) ctx.err('healthCheck.expectStatus', 'use "200" ou faixa "200-399"');
  }
  if (!/^[A-Za-z0-9_-]{1,32}$/.test(out.sticky.cookie)) ctx.err('sticky.cookie', 'nome de cookie inválido');
  ctx.done();
  return out;
}

// ---------------------------------------------------------------- certificate
export function parsePem(pem) {
  const text = String(pem || '').replace(/\r/g, '');
  const certs = text.match(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/g) || [];
  const keyMatch = text.match(/-----BEGIN (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----[\s\S]+?-----END (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----/);
  if (!certs.length) throw new ValidationError('pem: nenhum certificado encontrado');
  if (!keyMatch) throw new ValidationError('pem: chave privada não encontrada (cole certificado + cadeia + chave)');
  if (keyMatch[0].includes('ENCRYPTED')) throw new ValidationError('pem: chave privada criptografada não é suportada');
  let x509, key;
  try {
    x509 = new crypto.X509Certificate(certs[0]);
    key = crypto.createPrivateKey(keyMatch[0]);
  } catch (e) {
    throw new ValidationError(`pem: inválido (${e.message})`);
  }
  if (!x509.checkPrivateKey(key)) throw new ValidationError('pem: a chave privada não corresponde ao certificado');
  const sans = (x509.subjectAltName || '').split(',').map((s) => s.trim()).filter((s) => s.startsWith('DNS:')).map((s) => s.slice(4));
  const cn = (x509.subject.match(/CN=([^\n]+)/) || [])[1] || null;
  return {
    pem: `${certs.join('\n')}\n${keyMatch[0]}\n`,
    meta: {
      commonName: cn,
      domains: sans.length ? sans : cn ? [cn] : [],
      issuer: x509.issuer.replace(/\n/g, ', '),
      notBefore: new Date(x509.validFrom).toISOString(),
      notAfter: new Date(x509.validTo).toISOString(),
      fingerprint256: x509.fingerprint256,
      chainLength: certs.length,
    },
  };
}

export function normalizeCertificate(input, existing = null) {
  const ctx = new Ctx();
  const i = isObj(input) ? input : {};
  const out = {
    name: name(ctx, 'name', i.name ?? existing?.name),
    source: oneOf(ctx, 'source', i.source ?? existing?.source, ['upload', 'acme'], 'upload'),
  };
  if (out.source === 'acme') {
    const a = isObj(i.acme) ? i.acme : existing?.acme || {};
    out.acme = { domains: tokens(ctx, 'acme.domains', a.domains).map((d) => d.toLowerCase()), email: str(a.email) };
    if (!out.acme.domains.length) ctx.err('acme.domains', 'informe pelo menos um domínio');
    for (const d of out.acme.domains) if (!DOMAIN_RE.test(d) || d.startsWith('*.')) ctx.err('acme.domains', `domínio inválido para HTTP-01: "${d}"`);
  }
  ctx.done();
  const pem = i.pem ?? existing?.pem;
  if (pem) {
    const parsed = parsePem(pem);
    out.pem = parsed.pem;
    out.meta = parsed.meta;
  } else if (out.source === 'upload') {
    throw new ValidationError('pem: obrigatório');
  } else {
    out.pem = null;
    out.meta = existing?.meta || null;
  }
  return out;
}

export const normalizers = {
  frontends: normalizeFrontend,
  backends: normalizeBackend,
  certificates: normalizeCertificate,
};
