import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import Fastify from 'fastify';
import fastifyStatic from '@fastify/static';
import { openStore } from './db.js';
import { hashPassword, TokenSigner } from './services/auth.js';
import { DeployService } from './services/deploy.js';
import { CertService } from './services/certs.js';
import apiRoutes from './routes/api.js';
import agentRoutes from './routes/agent.js';

const here = path.dirname(fileURLToPath(import.meta.url));
export const VERSION = '0.1.0';

function promEscape(v) {
  return String(v).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
}

export async function buildApp(config, { logger = true } = {}) {
  const app = Fastify({ logger: logger && { level: config.logLevel }, bodyLimit: 2 * 1024 * 1024, trustProxy: true });
  const store = openStore(config.dbFile);

  // segredo de assinatura e usuário inicial
  let secret = config.secret || store.getKv('session.secret');
  if (!secret) {
    secret = crypto.randomBytes(32).toString('base64url');
    store.setKv('session.secret', secret);
  }
  if (store.countUsers() === 0) {
    const pwd = config.adminPassword || crypto.randomBytes(9).toString('base64url');
    store.createUser(config.adminUser, hashPassword(pwd));
    if (!config.adminPassword) app.log.warn(`usuário inicial criado: ${config.adminUser} / ${pwd}  (troque a senha!)`);
  }

  const signer = new TokenSigner(secret);
  const deploy = new DeployService({ store, config, log: app.log });
  const certs = new CertService({ store, config, deploy, log: app.log });
  app.decorate('hapm', { store, deploy, certs, signer, config });

  app.get('/healthz', async () => ({ ok: true, version: VERSION }));

  // desafio ACME HTTP-01 (o HAProxy encaminha para cá)
  app.get('/.well-known/acme-challenge/:token', async (req, reply) => {
    const v = store.getChallenge(String(req.params.token));
    if (!v) return reply.code(404).send('not found');
    return reply.type('text/plain').send(v);
  });

  // métricas do control plane (scrape pelo OTel Collector gateway)
  app.get('/metrics', async (req, reply) => {
    const L = [];
    const now = Date.now();
    L.push('# HELP hapm_manager_info Informações do manager', '# TYPE hapm_manager_info gauge', `hapm_manager_info{version="${VERSION}"} 1`);
    L.push('# HELP hapm_node_online Nó enviou heartbeat recentemente', '# TYPE hapm_node_online gauge');
    const nodeLines = { applied: [], master: [], seen: [] };
    const depLines = [];
    const certLines = [];
    for (const c of store.listClusters()) {
      const cl = promEscape(c.name);
      const latest = store.latestDeployment(c.id);
      if (latest) {
        depLines.push(`hapm_cluster_desired_version{hapm_cluster="${cl}"} ${latest.version}`);
        depLines.push(`hapm_cluster_deployment_failed{hapm_cluster="${cl}"} ${latest.status === 'failed' ? 1 : 0}`);
        depLines.push(`hapm_cluster_deployment_in_progress{hapm_cluster="${cl}"} ${latest.status === 'in_progress' ? 1 : 0}`);
      }
      for (const n of store.listNodes(c.id)) {
        const lbl = `hapm_cluster="${cl}",hapm_node="${promEscape(n.name)}"`;
        L.push(`hapm_node_online{${lbl}} ${deploy.isOnline(n, now) ? 1 : 0}`);
        nodeLines.applied.push(`hapm_node_applied_version{${lbl}} ${n.appliedVersion}`);
        nodeLines.master.push(`hapm_node_vrrp_master{${lbl}} ${n.vrrpState === 'MASTER' ? 1 : 0}`);
        if (n.lastSeen) nodeLines.seen.push(`hapm_node_last_seen_timestamp_seconds{${lbl}} ${Math.floor(n.lastSeen / 1000)}`);
      }
      for (const cert of store.listObjects(c.id, 'certificate')) {
        if (cert.meta?.notAfter) certLines.push(`hapm_certificate_not_after_timestamp_seconds{hapm_cluster="${cl}",certificate="${promEscape(cert.name)}",source="${cert.source}"} ${Math.floor(Date.parse(cert.meta.notAfter) / 1000)}`);
      }
    }
    L.push('# TYPE hapm_node_applied_version gauge', ...nodeLines.applied);
    L.push('# TYPE hapm_node_vrrp_master gauge', ...nodeLines.master);
    L.push('# TYPE hapm_node_last_seen_timestamp_seconds gauge', ...nodeLines.seen);
    L.push('# TYPE hapm_cluster_desired_version gauge', ...depLines.filter((l) => l.startsWith('hapm_cluster_desired')));
    L.push('# TYPE hapm_cluster_deployment_failed gauge', ...depLines.filter((l) => l.startsWith('hapm_cluster_deployment_failed')));
    L.push('# TYPE hapm_cluster_deployment_in_progress gauge', ...depLines.filter((l) => l.startsWith('hapm_cluster_deployment_in_progress')));
    L.push('# TYPE hapm_certificate_not_after_timestamp_seconds gauge', ...certLines);
    return reply.type('text/plain; version=0.0.4').send(L.join('\n') + '\n');
  });

  await app.register(apiRoutes, { prefix: '/api', store, deploy, certs, signer, config });
  await app.register(agentRoutes, { prefix: '/agent/v1', store, deploy });
  await app.register(fastifyStatic, { root: path.join(here, '..', 'public'), prefix: '/' });

  const timers = [
    setInterval(() => deploy.evaluateAll(), 10_000),
    setInterval(() => certs.renewDue(), 12 * 3600_000),
  ];
  timers.forEach((t) => t.unref());
  app.addHook('onClose', async () => timers.forEach(clearInterval));
  return app;
}
