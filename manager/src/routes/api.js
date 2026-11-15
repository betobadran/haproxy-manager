// API administrativa (consumida pela UI). Autenticação: Bearer token de sessão.
import { normalizeCluster, normalizeNodeSettings, normalizers, ValidationError, NAME_RE } from '../validate.js';
import { verifyPassword, hashPassword, joinToken } from '../services/auth.js';
import { buildBundle } from '../render/bundle.js';
import { RenderError } from '../render/haproxy.js';
import { nodePriority, usesKeepalived } from '../render/keepalived.js';
import { checkHaproxyFiles } from '../services/check.js';
import { lineDiff } from '../services/diff.js';

const KINDS = { frontends: 'frontend', backends: 'backend', certificates: 'certificate' };

function publicObject(o) {
  if (!o) return o;
  const { pem, clusterId, kind, ...rest } = o;
  if (kind === 'certificate') return { ...rest, hasKey: !!pem };
  return { ...rest };
}
const stripMeta = ({ id, clusterId, kind, createdAt, updatedAt, ...rest }) => rest;

export default async function apiRoutes(app, { store, deploy, certs, signer, config }) {
  const notFound = (reply, what = 'não encontrado') => reply.code(404).send({ error: what });

  app.addHook('onRequest', async (req, reply) => {
    if (req.routeOptions.config?.public) return;
    const h = req.headers.authorization || '';
    const payload = h.startsWith('Bearer ') ? signer.verify(h.slice(7)) : null;
    if (!payload) return reply.code(401).send({ error: 'não autenticado' });
    req.user = payload.u;
  });

  const clusterOr404 = (req, reply) => {
    const c = store.getCluster(req.params.cid);
    if (!c) notFound(reply, 'cluster não encontrado');
    return c;
  };

  // ------------------------------------------------------------- auth
  // limitador simples contra força bruta: 10 tentativas falhas / 5 min por IP
  const attempts = new Map();
  app.post('/auth/login', { config: { public: true } }, async (req, reply) => {
    const now = Date.now();
    const a = attempts.get(req.ip);
    if (a && now - a.first < 300_000 && a.count >= 10) return reply.code(429).send({ error: 'muitas tentativas; aguarde alguns minutos' });
    const { username, password } = req.body || {};
    const u = store.getUser(String(username || ''));
    if (!u || !verifyPassword(password, u.password_hash)) {
      const cur = a && now - a.first < 300_000 ? a : { first: now, count: 0 };
      cur.count++;
      attempts.set(req.ip, cur);
      await new Promise((r) => setTimeout(r, 500));
      store.audit({ username, action: 'auth.login.failed' });
      return reply.code(401).send({ error: 'usuário ou senha inválidos' });
    }
    attempts.delete(req.ip);
    store.audit({ username: u.username, action: 'auth.login' });
    return { token: signer.sign({ u: u.username }), username: u.username };
  });

  app.get('/me', async (req) => ({ username: req.user }));

  app.post('/me/password', async (req, reply) => {
    const { current, password } = req.body || {};
    const u = store.getUser(req.user);
    if (!u || !verifyPassword(current, u.password_hash)) return reply.code(400).send({ error: 'senha atual incorreta' });
    if (!password || String(password).length < 8) return reply.code(400).send({ error: 'nova senha deve ter ao menos 8 caracteres' });
    store.setUserPassword(req.user, hashPassword(password));
    store.audit({ username: req.user, action: 'auth.password.changed' });
    return { ok: true };
  });

  // ------------------------------------------------------------- clusters
  const clusterSummary = (c) => {
    const nodes = store.listNodes(c.id);
    const online = nodes.filter((n) => deploy.isOnline(n));
    const latest = store.latestDeployment(c.id);
    return {
      id: c.id, name: c.name, mode: c.settings.mode,
      nodes: nodes.length, online: online.length,
      masters: online.filter((n) => n.vrrpState === 'MASTER').map((n) => n.name),
      latestDeployment: latest ? { version: latest.version, status: latest.status, createdAt: latest.createdAt } : null,
    };
  };

  app.get('/clusters', async () => store.listClusters().map(clusterSummary));

  app.post('/clusters', async (req, reply) => {
    const n = normalizeCluster(req.body);
    if (store.listClusters().some((c) => c.name === n.name)) return reply.code(409).send({ error: 'já existe um cluster com esse nome' });
    const c = store.createCluster(n);
    store.audit({ username: req.user, clusterId: c.id, action: 'cluster.create', target: c.name });
    return reply.code(201).send({ ...c, joinToken: joinToken(c) });
  });

  app.get('/clusters/:cid', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    const { joinSecret, ...rest } = c;
    return { ...rest, joinToken: joinToken(c), summary: clusterSummary(c) };
  });

  app.put('/clusters/:cid', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    const n = normalizeCluster(req.body, c);
    const u = store.updateCluster(c.id, n);
    store.audit({ username: req.user, clusterId: c.id, action: 'cluster.update', target: u.name });
    const { joinSecret, ...rest } = u;
    return { ...rest, joinToken: joinToken(u) };
  });

  app.post('/clusters/:cid/rotate-token', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    const u = store.rotateJoinSecret(c.id);
    store.audit({ username: req.user, clusterId: c.id, action: 'cluster.token.rotate' });
    return { joinToken: joinToken(u) };
  });

  app.delete('/clusters/:cid', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    store.deleteCluster(c.id);
    store.audit({ username: req.user, action: 'cluster.delete', target: c.name });
    return { ok: true };
  });

  // ------------------------------------------------------------- nodes
  app.get('/clusters/:cid/nodes', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    const nodes = store.listNodes(c.id);
    return nodes.map((n) => ({
      ...n,
      online: deploy.isOnline(n),
      priority: usesKeepalived(c) ? nodePriority(nodes, n) : null,
      desired: deploy.desiredFor(c.id, n),
    }));
  });

  app.post('/clusters/:cid/nodes', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    const name = String(req.body?.name || '');
    if (!NAME_RE.test(name)) throw new ValidationError('name: nome inválido');
    if (store.getNodeByName(c.id, name)) return reply.code(409).send({ error: 'nó já existe' });
    const n = store.createNode(c.id, { name, address: req.body?.address || null, settings: normalizeNodeSettings(req.body?.settings) });
    store.audit({ username: req.user, clusterId: c.id, action: 'node.create', target: name });
    return reply.code(201).send(n);
  });

  app.put('/clusters/:cid/nodes/:nid', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    const n = store.getNode(req.params.nid);
    if (!n || n.clusterId !== c.id) return notFound(reply);
    const u = store.updateNodeSettings(n.id, normalizeNodeSettings(req.body?.settings ?? req.body));
    store.audit({ username: req.user, clusterId: c.id, action: 'node.update', target: n.name, detail: u.settings });
    return u;
  });

  app.delete('/clusters/:cid/nodes/:nid', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    const n = store.getNode(req.params.nid);
    if (!n || n.clusterId !== c.id) return notFound(reply);
    store.deleteNode(n.id);
    store.audit({ username: req.user, clusterId: c.id, action: 'node.delete', target: n.name });
    return { ok: true };
  });

  // ------------------------------------------------------------- frontends / backends / certificates
  const kindOf = (req, reply) => {
    const k = KINDS[req.params.kind];
    if (!k) notFound(reply, 'tipo desconhecido');
    return k;
  };

  app.get('/clusters/:cid/:kind', async (req, reply) => {
    const c = clusterOr404(req, reply);
    const k = c && kindOf(req, reply);
    if (!k) return;
    return store.listObjects(c.id, k).map(publicObject);
  });

  app.get('/clusters/:cid/:kind/:oid', async (req, reply) => {
    const c = clusterOr404(req, reply);
    const k = c && kindOf(req, reply);
    if (!k) return;
    const o = store.getObject(c.id, k, req.params.oid);
    return o ? publicObject(o) : notFound(reply);
  });

  app.post('/clusters/:cid/:kind', async (req, reply) => {
    const c = clusterOr404(req, reply);
    const k = c && kindOf(req, reply);
    if (!k) return;
    const data = normalizers[req.params.kind](req.body);
    if (store.getObjectByName(c.id, k, data.name)) return reply.code(409).send({ error: `${k} "${data.name}" já existe` });
    const o = store.createObject(c.id, k, data);
    store.audit({ username: req.user, clusterId: c.id, action: `${k}.create`, target: o.name });
    if (k === 'certificate' && o.source === 'acme' && !o.pem) certs.issue(c.id, o.id, req.user);
    return reply.code(201).send(publicObject(o));
  });

  app.put('/clusters/:cid/:kind/:oid', async (req, reply) => {
    const c = clusterOr404(req, reply);
    const k = c && kindOf(req, reply);
    if (!k) return;
    const cur = store.getObject(c.id, k, req.params.oid);
    if (!cur) return notFound(reply);
    const data = k === 'certificate' ? normalizers.certificates(req.body, stripMeta(cur)) : normalizers[req.params.kind](req.body);
    const other = store.getObjectByName(c.id, k, data.name);
    if (other && other.id !== cur.id) return reply.code(409).send({ error: `${k} "${data.name}" já existe` });
    if (k === 'certificate' && cur.acme?.status) data.acme = { ...cur.acme, ...data.acme };
    const o = store.updateObject(c.id, k, cur.id, data);
    store.audit({ username: req.user, clusterId: c.id, action: `${k}.update`, target: o.name });
    return publicObject(o);
  });

  app.delete('/clusters/:cid/:kind/:oid', async (req, reply) => {
    const c = clusterOr404(req, reply);
    const k = c && kindOf(req, reply);
    if (!k) return;
    const cur = store.getObject(c.id, k, req.params.oid);
    if (!cur) return notFound(reply);
    if (k === 'backend') {
      const users = store.listObjects(c.id, 'frontend').filter((f) => f.defaultBackend === cur.name || f.rules?.some((r) => r.backend === cur.name));
      if (users.length) return reply.code(409).send({ error: `backend em uso por: ${users.map((f) => f.name).join(', ')}` });
    }
    store.deleteObject(c.id, k, cur.id);
    store.audit({ username: req.user, clusterId: c.id, action: `${k}.delete`, target: cur.name });
    return { ok: true };
  });

  app.post('/clusters/:cid/certificates/:oid/issue', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    const cert = store.getObject(c.id, 'certificate', req.params.oid);
    if (!cert || cert.source !== 'acme') return reply.code(400).send({ error: 'certificado não é ACME' });
    const started = certs.issue(c.id, cert.id, req.user);
    return { started };
  });

  // ------------------------------------------------------------- preview / deployments
  app.get('/clusters/:cid/preview', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    let p;
    try {
      p = deploy.preview(c.id);
    } catch (e) {
      if (e instanceof RenderError) return { ok: false, errors: e.errors };
      throw e;
    }
    const files = buildBundle({ deployment: { version: p.version, snapshot: p.snapshot, haproxyCfg: p.haproxyCfg }, node: { name: '__preview__' } }).files;
    const check = await checkHaproxyFiles(files, config.haproxyBin);
    const keepalived = {};
    if (usesKeepalived(c)) {
      for (const n of p.snapshot.nodes) {
        keepalived[n.name] = buildBundle({ deployment: { version: p.version, snapshot: p.snapshot, haproxyCfg: p.haproxyCfg }, node: n }).keepalived['keepalived.conf'];
      }
    }
    const strip = (s) => s.replace(/^# cluster: .*$/m, '');
    return {
      ok: !check.available || check.ok,
      version: p.version,
      haproxyCfg: p.haproxyCfg,
      keepalived,
      check,
      currentVersion: p.current?.version ?? null,
      diff: p.current ? lineDiff(strip(p.current.haproxyCfg), strip(p.haproxyCfg)) : null,
      changed: p.current ? strip(p.current.haproxyCfg) !== strip(p.haproxyCfg) : true,
    };
  });

  app.get('/clusters/:cid/deployments', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    return store.listDeployments(c.id, Number(req.query.limit) || 50);
  });

  app.get('/clusters/:cid/deployments/:did', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    const d = store.getDeployment(c.id, Number(req.params.did));
    if (!d) return notFound(reply);
    const nodes = store.listNodes(c.id).map((n) => ({ name: n.name, online: deploy.isOnline(n), appliedVersion: n.appliedVersion, lastApply: n.lastApply, vrrpState: n.vrrpState }));
    const { snapshot, ...rest } = d;
    return { ...rest, nodes };
  });

  app.post('/clusters/:cid/deployments', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    if (req.body?.validate !== false) {
      const p = deploy.preview(c.id);
      const files = buildBundle({ deployment: { version: p.version, snapshot: p.snapshot, haproxyCfg: p.haproxyCfg }, node: { name: '__check__' } }).files;
      const check = await checkHaproxyFiles(files, config.haproxyBin);
      if (check.available && !check.ok) return reply.code(422).send({ error: 'haproxy -c falhou', errors: [check.output] });
    }
    const d = deploy.create(c.id, { note: req.body?.note, user: req.user });
    return reply.code(201).send(d && { ...d, snapshot: undefined });
  });

  app.post('/clusters/:cid/deployments/:did/rollback', async (req, reply) => {
    const c = clusterOr404(req, reply);
    if (!c) return;
    const d = deploy.rollback(c.id, Number(req.params.did), req.user);
    if (!d) return notFound(reply);
    return reply.code(201).send({ ...d, snapshot: undefined });
  });

  app.get('/audit', async (req) => store.listAudit(Number(req.query.limit) || 200, req.query.cluster || null));

  app.setErrorHandler((err, req, reply) => {
    if (err instanceof ValidationError || err instanceof RenderError) return reply.code(err.statusCode).send({ error: err.message, errors: err.errors });
    if (err.validation) return reply.code(400).send({ error: err.message });
    req.log.error(err);
    return reply.code(err.statusCode || 500).send({ error: err.statusCode ? err.message : 'erro interno' });
  });
}
