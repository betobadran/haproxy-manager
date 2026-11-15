// Teste ponta-a-ponta: manager + agente + HAProxy real (master-worker) + backend HTTP.
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { buildApp } from '../src/app.js';
import { hasBin } from './helpers.js';

const agentEntry = new URL('../../agent/src/index.js', import.meta.url).pathname;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function freePort() {
  const s = http.createServer();
  await new Promise((r) => s.listen(0, '127.0.0.1', r));
  const p = s.address().port;
  await new Promise((r) => s.close(r));
  return p;
}

async function waitFor(fn, timeoutMs = 30000, label = 'condição') {
  const end = Date.now() + timeoutMs;
  let last;
  while (Date.now() < end) {
    try {
      last = await fn();
      if (last) return last;
    } catch (e) {
      last = e;
    }
    await sleep(300);
  }
  throw new Error(`timeout esperando ${typeof label === 'function' ? await label() : label}: ${last instanceof Error ? last.message : JSON.stringify(last)}`);
}

test('fluxo completo: publicar, falhar com rollback automático, rollback manual', { skip: !hasBin('haproxy') && 'haproxy não instalado', timeout: 120000 }, async (t) => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'hapm-e2e-'));
  const procs = [];
  t.after(() => {
    for (const p of procs) p.kill('SIGKILL');
    fs.rmSync(tmp, { recursive: true, force: true });
  });

  // backend de exemplo
  const backend = http.createServer((req, res) => res.end(`ola de ${req.headers.host} ${req.url}`));
  await new Promise((r) => backend.listen(0, '127.0.0.1', r));
  t.after(() => backend.close());
  const bePort = backend.address().port;

  // manager
  const app = await buildApp({ port: 0, host: '127.0.0.1', dbFile: ':memory:', adminUser: 'admin', adminPassword: 'admin-pass', haproxyBin: 'haproxy',
    nodeOfflineAfterSec: 30, deploymentTimeoutSec: 60, certRenewBeforeDays: 30, logLevel: 'warn', acmeDirectory: 'http://invalid' }, { logger: false });
  await app.listen({ port: 0, host: '127.0.0.1' });
  t.after(() => app.close());
  const base = `http://127.0.0.1:${app.server.address().port}`;
  let token;
  const api = async (method, p, body) => {
    const r = await fetch(`${base}/api${p}`, { method, headers: { ...(body ? { 'content-type': 'application/json' } : {}), authorization: `Bearer ${token}` }, body: body && JSON.stringify(body) });
    const j = await r.json();
    return { status: r.status, body: j };
  };
  token = (await api('POST', '/auth/login', { username: 'admin', password: 'admin-pass' })).body.token;
  assert.ok(token);

  const statsPort = await freePort();
  const fePort = await freePort();
  const c = await api('POST', '/clusters', { name: 'e2e', settings: { mode: 'aws-nlb', haproxy: { maxconn: 2000, user: '', log: 'stdout format raw local0', statsPort, statsSocket: path.join(tmp, 'admin.sock') } } });
  assert.equal(c.status, 201, JSON.stringify(c.body));
  const cid = c.body.id;

  // nó: bootstrap + haproxy master-worker + agente
  const hdir = path.join(tmp, 'haproxy');
  const masterSock = path.join(tmp, 'master.sock');
  const env = {
    ...process.env,
    HAPM_MANAGER_URL: base, HAPM_JOIN_TOKEN: c.body.joinToken, HAPM_NODE_NAME: 'lb1', HAPM_NODE_ADDRESS: '127.0.0.1',
    HAPM_HAPROXY_DIR: hdir, HAPM_STATE_DIR: path.join(tmp, 'state'), HAPM_RELOAD_MODE: 'master-socket', HAPM_MASTER_SOCKET: masterSock,
    HAPM_STATS_PORT: String(statsPort), HAPM_METRICS_PORT: String(await freePort()), HAPM_METRICS_HOST: '127.0.0.1', HAPM_POLL_INTERVAL: '1',
  };
  assert.equal(spawnSync('node', [agentEntry, '--bootstrap'], { env }).status, 0);
  const hap = spawn('haproxy', ['-W', '-S', masterSock, '-f', path.join(hdir, 'current', 'haproxy.cfg')], { stdio: 'ignore' });
  procs.push(hap);
  await waitFor(async () => (await fetch(`http://127.0.0.1:${statsPort}/healthz`)).ok, 10000, 'haproxy bootstrap');
  const agent = spawn('node', [agentEntry], { env, stdio: ['ignore', 'pipe', 'pipe'] });
  let agentLog = '';
  agent.stdout.on('data', (d) => (agentLog += d));
  agent.stderr.on('data', (d) => (agentLog += d));
  procs.push(agent);
  await waitFor(async () => (await api('GET', `/clusters/${cid}/nodes`)).body.some((n) => n.name === 'lb1' && n.online), 15000, 'registro do nó');

  // objetos
  assert.equal((await api('POST', `/clusters/${cid}/backends`, { name: 'app', servers: [{ name: 's1', address: '127.0.0.1', port: bePort }], healthCheck: { type: 'http', path: '/health' } })).status, 201);
  const fe = await api('POST', `/clusters/${cid}/frontends`, {
    name: 'web', binds: [{ address: '127.0.0.1', port: fePort }], defaultBackend: 'app',
    rules: [{ conditions: [{ type: 'path_beg', value: '/bloqueado' }], action: 'deny', status: 403 }],
  });
  assert.equal(fe.status, 201, JSON.stringify(fe.body));

  const preview = await api('GET', `/clusters/${cid}/preview`);
  assert.ok(preview.body.ok, JSON.stringify(preview.body));
  assert.equal(preview.body.check.ok, true);

  // v1
  const d1 = await api('POST', `/clusters/${cid}/deployments`, { note: 'primeira versão' });
  assert.equal(d1.status, 201, JSON.stringify(d1.body));
  await waitFor(async () => (await api('GET', `/clusters/${cid}/deployments/${d1.body.id}`)).body.status === 'succeeded', 30000, async () => `v1 aplicada\n${agentLog}\n${JSON.stringify((await api('GET', `/clusters/${cid}/nodes`)).body)}`);
  const r1 = await fetch(`http://127.0.0.1:${fePort}/ok`);
  assert.equal(r1.status, 200);
  assert.match(await r1.text(), /ola de/);
  assert.equal((await fetch(`http://127.0.0.1:${fePort}/bloqueado`)).status, 403);

  // config inválida: o manager recusa com haproxy -c
  const feId = fe.body.id;
  const bad = { ...fe.body, extra: 'diretiva-que-nao-existe 123' };
  assert.equal((await api('PUT', `/clusters/${cid}/frontends/${feId}`, bad)).status, 200);
  const rejected = await api('POST', `/clusters/${cid}/deployments`, {});
  assert.equal(rejected.status, 422);

  // forçando (validate:false) o agente recusa, o deployment falha e o tráfego continua na v1
  const d2 = await api('POST', `/clusters/${cid}/deployments`, { validate: false, note: 'quebrado' });
  assert.equal(d2.status, 201);
  const failed = await waitFor(async () => {
    const d = (await api('GET', `/clusters/${cid}/deployments/${d2.body.id}`)).body;
    return d.status === 'failed' && d;
  }, 30000, 'falha da v2');
  assert.match(failed.error, /haproxy -c falhou/);
  assert.equal((await fetch(`http://127.0.0.1:${fePort}/ok`)).status, 200);
  const nodes = (await api('GET', `/clusters/${cid}/nodes`)).body;
  assert.equal(nodes[0].appliedVersion, 1);
  assert.equal(nodes[0].desired.version, 1);

  // rollback manual para a v1 gera v3 com a config da v1
  await api('PUT', `/clusters/${cid}/frontends/${feId}`, { ...fe.body, extra: '' });
  const d3 = await api('POST', `/clusters/${cid}/deployments/${d1.body.id}/rollback`);
  assert.equal(d3.status, 201);
  assert.equal(d3.body.version, 3);
  await waitFor(async () => (await api('GET', `/clusters/${cid}/deployments/${d3.body.id}`)).body.status === 'succeeded', 30000, 'v3 aplicada');

  // métricas do agente e do HAProxy
  const am = await (await fetch(`http://127.0.0.1:${env.HAPM_METRICS_PORT}/metrics`)).text();
  assert.match(am, /hapm_config_applied_version 3/);
  assert.match(am, /hapm_haproxy_up 1/);
  const hm = await (await fetch(`http://127.0.0.1:${statsPort}/metrics`)).text();
  assert.match(hm, /haproxy_frontend_http_requests_total\{proxy="web"\}/);
  const mm = await (await fetch(`${base}/metrics`)).text();
  assert.match(mm, /hapm_node_applied_version\{hapm_cluster="e2e",hapm_node="lb1"\} 3/);

  // auditoria registrou tudo
  const audit = (await api('GET', '/audit')).body.map((a) => a.action);
  for (const a of ['deployment.create', 'deployment.failed', 'deployment.succeeded', 'frontend.update']) assert.ok(audit.includes(a), a);
});
