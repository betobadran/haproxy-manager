#!/usr/bin/env node
// HAPM agent: heartbeat -> manager, baixa e aplica a versão desejada, expõe /metrics.
import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import { loadConfig } from './config.js';
import { Applier } from './apply.js';
import * as sys from './system.js';

const cfg = loadConfig();
const log = {
  info: (...a) => console.log(new Date().toISOString(), 'INFO', ...a),
  warn: (...a) => console.warn(new Date().toISOString(), 'WARN', ...a),
  error: (...a) => console.error(new Date().toISOString(), 'ERROR', ...a),
};
const applier = new Applier(cfg, log);

const state = {
  lastApply: null, // { version, ok, error, at }
  desiredVersion: null,
  hold: null,
  heartbeatOk: false,
  applyTotal: { success: 0, failure: 0 },
  usesKeepalived: false,
  haproxyVersion: null,
};
const stateFile = path.join(cfg.stateDir, 'agent-state.json');

async function loadState() {
  try {
    const s = JSON.parse(await fs.readFile(stateFile, 'utf8'));
    Object.assign(state, { lastApply: s.lastApply ?? null, usesKeepalived: !!s.usesKeepalived, applyTotal: s.applyTotal || state.applyTotal });
  } catch {
    /* primeiro start */
  }
}
async function saveState() {
  await fs.mkdir(cfg.stateDir, { recursive: true });
  await fs.writeFile(stateFile, JSON.stringify({ lastApply: state.lastApply, usesKeepalived: state.usesKeepalived, applyTotal: state.applyTotal }));
}

async function vrrpState() {
  if (!state.usesKeepalived) return null;
  if (!(await sys.processRunning('keepalived'))) return 'STOP';
  try {
    const s = (await fs.readFile(cfg.vrrpStateFile, 'utf8')).trim().toUpperCase();
    return ['MASTER', 'BACKUP', 'FAULT', 'STOP'].includes(s) ? s : 'UNKNOWN';
  } catch {
    return 'UNKNOWN';
  }
}

async function api(method, p, body) {
  const r = await fetch(`${cfg.managerUrl}${p}`, {
    method,
    headers: { authorization: `Bearer ${cfg.joinToken}`, 'content-type': 'application/json', 'x-hapm-node': cfg.nodeName },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000),
  });
  const text = await r.text();
  if (!r.ok) throw new Error(`${method} ${p}: HTTP ${r.status} ${text.slice(0, 300)}`);
  return JSON.parse(text);
}

async function heartbeat() {
  return api('POST', '/agent/v1/heartbeat', {
    name: cfg.nodeName,
    address: cfg.nodeAddress,
    agentVersion: cfg.version,
    haproxyVersion: state.haproxyVersion,
    vrrpState: await vrrpState(),
    appliedVersion: await applier.appliedVersion(),
    lastApply: state.lastApply,
  });
}

async function tick() {
  let res;
  try {
    res = await heartbeat();
    state.heartbeatOk = true;
  } catch (e) {
    state.heartbeatOk = false;
    log.warn(`heartbeat falhou: ${e.message}`);
    return cfg.pollIntervalSec;
  }
  const d = res.desired || {};
  state.desiredVersion = d.version ?? null;
  state.hold = d.hold ? d.reason : null;
  const applied = await applier.appliedVersion();
  if (d.version == null || d.version === applied) return res.pollIntervalSec || cfg.pollIntervalSec;
  if (d.hold) {
    log.info(`v${d.version} pendente: ${d.reason}`);
    return 2;
  }
  // não tenta de novo uma versão que já falhou localmente
  if (state.lastApply && state.lastApply.version === d.version && !state.lastApply.ok) return res.pollIntervalSec || cfg.pollIntervalSec;

  log.info(`aplicando v${d.version} (atual v${applied})`);
  try {
    const bundle = await api('GET', `/agent/v1/bundle/${d.version}`);
    state.usesKeepalived = !!bundle.keepalived;
    const r = await applier.apply(bundle);
    state.lastApply = { version: d.version, ok: true, error: null, at: new Date().toISOString() };
    state.applyTotal.success++;
    log.info(`v${d.version} aplicada em ${r.durationMs}ms${r.keepalivedChanged ? ' (keepalived recarregado)' : ''}`);
  } catch (e) {
    state.lastApply = { version: d.version, ok: false, error: e.message, at: new Date().toISOString() };
    state.applyTotal.failure++;
    log.error(`falha ao aplicar v${d.version}: ${e.message}`);
  }
  await saveState();
  return 0.2; // reporta o resultado logo em seguida
}

// ------------------------------------------------------------------ métricas
async function metrics() {
  const L = [];
  const g = (name, help, lines) => {
    L.push(`# HELP ${name} ${help}`, `# TYPE ${name} gauge`, ...lines);
  };
  const vs = await vrrpState();
  const healthy = await sys.isHealthy(cfg.healthUrl);
  g('hapm_agent_info', 'Informações do agente', [`hapm_agent_info{version="${cfg.version}",haproxy_version="${state.haproxyVersion || ''}"} 1`]);
  g('hapm_agent_heartbeat_ok', 'Último heartbeat ao manager teve sucesso', [`hapm_agent_heartbeat_ok ${state.heartbeatOk ? 1 : 0}`]);
  g('hapm_haproxy_up', 'HAProxy responde ao health-check local', [`hapm_haproxy_up ${healthy ? 1 : 0}`]);
  g('hapm_config_applied_version', 'Versão de configuração ativa', [`hapm_config_applied_version ${await applier.appliedVersion()}`]);
  g('hapm_config_desired_version', 'Versão desejada informada pelo manager', [`hapm_config_desired_version ${state.desiredVersion ?? 0}`]);
  g('hapm_config_last_apply_success', 'Última aplicação teve sucesso', [`hapm_config_last_apply_success ${state.lastApply ? (state.lastApply.ok ? 1 : 0) : 1}`]);
  if (state.lastApply?.at) g('hapm_config_last_apply_timestamp_seconds', 'Momento da última aplicação', [`hapm_config_last_apply_timestamp_seconds ${Math.floor(Date.parse(state.lastApply.at) / 1000)}`]);
  L.push('# HELP hapm_config_apply_total Aplicações por resultado', '# TYPE hapm_config_apply_total counter',
    `hapm_config_apply_total{result="success"} ${state.applyTotal.success}`, `hapm_config_apply_total{result="failure"} ${state.applyTotal.failure}`);
  if (state.usesKeepalived) {
    g('hapm_vrrp_state', 'Estado VRRP do keepalived', ['MASTER', 'BACKUP', 'FAULT', 'STOP', 'UNKNOWN'].map((s) => `hapm_vrrp_state{state="${s}"} ${vs === s ? 1 : 0}`));
    g('hapm_vrrp_is_master', 'Este nó é o MASTER (detém o VIP)', [`hapm_vrrp_is_master ${vs === 'MASTER' ? 1 : 0}`]);
    g('hapm_keepalived_running', 'Processo keepalived em execução', [`hapm_keepalived_running ${vs === 'STOP' ? 0 : 1}`]);
  }
  const certs = await applier.certificates();
  g('hapm_certificate_not_after_timestamp_seconds', 'Expiração dos certificados em uso', certs.map((c) =>
    `hapm_certificate_not_after_timestamp_seconds{certificate="${c.name}",cn="${c.cn.replace(/["\\\n]/g, '')}"} ${Math.floor(c.notAfter / 1000)}`));
  return L.join('\n') + '\n';
}

function startMetricsServer() {
  const srv = http.createServer(async (req, res) => {
    if (req.url === '/metrics') {
      try {
        res.writeHead(200, { 'content-type': 'text/plain; version=0.0.4' }).end(await metrics());
      } catch (e) {
        res.writeHead(500).end(e.message);
      }
    } else if (req.url === '/healthz') {
      res.writeHead(200).end('ok');
    } else res.writeHead(404).end();
  });
  srv.listen(cfg.metricsPort, cfg.metricsHost, () => log.info(`métricas em http://${cfg.metricsHost}:${cfg.metricsPort}/metrics`));
  return srv;
}

// ------------------------------------------------------------------ main
async function main() {
  await fs.mkdir(cfg.haproxyDir, { recursive: true });
  const created = await applier.ensureBootstrap();
  if (process.argv.includes('--bootstrap')) {
    log.info(created ? 'bootstrap criado' : 'bootstrap já existia');
    return;
  }
  if (!cfg.joinToken) {
    log.error('HAPM_JOIN_TOKEN não definido');
    process.exit(2);
  }
  await loadState();
  state.haproxyVersion = await sys.haproxyVersion(cfg.haproxyBin);
  log.info(`agente ${cfg.version} nó=${cfg.nodeName} endereço=${cfg.nodeAddress} manager=${cfg.managerUrl} haproxy=${state.haproxyVersion}`);
  const srv = startMetricsServer();
  let stop = false;
  for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => { stop = true; srv.close(); process.exit(0); });
  while (!stop) {
    const wait = await tick().catch((e) => {
      log.error(e);
      return cfg.pollIntervalSec;
    });
    await new Promise((r) => setTimeout(r, wait * 1000));
  }
}

main();
