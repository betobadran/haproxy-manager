// API usada pelos agentes (modelo pull: o agente só precisa de saída HTTP até o manager).
import net from 'node:net';
import { parseJoinToken, safeEqual } from '../services/auth.js';
import { buildBundle } from '../render/bundle.js';
import { NAME_RE } from '../validate.js';

const VRRP_STATES = new Set(['MASTER', 'BACKUP', 'FAULT', 'STOP', 'UNKNOWN']);

export default async function agentRoutes(app, { store, deploy }) {
  app.addHook('onRequest', async (req, reply) => {
    const h = req.headers.authorization || '';
    const t = parseJoinToken(h.startsWith('Bearer ') ? h.slice(7) : '');
    const cluster = t && store.getCluster(t.clusterId);
    if (!cluster || !safeEqual(cluster.joinSecret, t.secret)) return reply.code(401).send({ error: 'token de join inválido' });
    req.cluster = cluster;
  });

  app.post('/heartbeat', async (req, reply) => {
    const b = req.body || {};
    if (!NAME_RE.test(String(b.name || ''))) return reply.code(400).send({ error: 'nome de nó inválido' });
    if (b.address && !net.isIP(String(b.address))) return reply.code(400).send({ error: 'endereço inválido' });
    const hb = {
      name: String(b.name),
      address: b.address ? String(b.address) : null,
      agentVersion: String(b.agentVersion || '').slice(0, 32) || null,
      haproxyVersion: String(b.haproxyVersion || '').slice(0, 64) || null,
      vrrpState: VRRP_STATES.has(b.vrrpState) ? b.vrrpState : null,
      appliedVersion: Number.isInteger(b.appliedVersion) ? b.appliedVersion : 0,
      lastApply: b.lastApply && typeof b.lastApply === 'object'
        ? { version: Number(b.lastApply.version) || 0, ok: !!b.lastApply.ok, error: b.lastApply.error ? String(b.lastApply.error).slice(0, 4000) : null, at: String(b.lastApply.at || '').slice(0, 40) }
        : null,
    };
    const node = store.upsertNodeHeartbeat(req.cluster.id, hb);
    deploy.evaluate(req.cluster.id);
    const desired = deploy.desiredFor(req.cluster.id, store.getNode(node.id));
    return { desired, pollIntervalSec: 5 };
  });

  app.get('/bundle/:version', async (req, reply) => {
    const name = String(req.headers['x-hapm-node'] || '');
    const node = store.getNodeByName(req.cluster.id, name);
    if (!node) return reply.code(404).send({ error: 'nó desconhecido; envie heartbeat primeiro' });
    const dep = store.getDeploymentByVersion(req.cluster.id, Number(req.params.version));
    if (!dep) return reply.code(404).send({ error: 'versão não encontrada' });
    return buildBundle({ deployment: dep, node });
  });
}
