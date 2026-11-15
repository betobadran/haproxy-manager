// Integração com o sistema: haproxy, keepalived, processos.
import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import net from 'node:net';

export function run(cmd, args, { timeout = 30000 } = {}) {
  return new Promise((resolve) => {
    execFile(cmd, args, { timeout }, (err, stdout, stderr) => {
      resolve({ ok: !err, code: err?.code ?? 0, missing: err?.code === 'ENOENT', output: `${stdout || ''}${stderr || ''}`.trim() });
    });
  });
}

export const sh = (command, opts) => run('/bin/sh', ['-c', command], opts);

export async function haproxyVersion(bin) {
  const r = await run(bin, ['-v']);
  return r.ok ? (r.output.match(/version (\S+)/) || [])[1] || null : null;
}

export async function haproxyCheck(bin, cfgFile) {
  const r = await run(bin, ['-c', '-f', cfgFile]);
  if (r.missing) throw new Error(`binário ${bin} não encontrado`);
  return r;
}

/** Envia "reload" ao master CLI (HAProxy em modo master-worker). */
export function masterSocketReload(socketPath, timeoutMs = 20000) {
  return new Promise((resolve) => {
    let out = '';
    const s = net.createConnection(socketPath);
    const timer = setTimeout(() => {
      s.destroy();
      resolve({ ok: false, output: 'timeout no master socket' });
    }, timeoutMs);
    s.on('connect', () => s.write('reload\n'));
    s.on('data', (d) => (out += d.toString()));
    s.on('error', (e) => {
      clearTimeout(timer);
      resolve({ ok: false, output: e.message });
    });
    s.on('close', () => {
      clearTimeout(timer);
      // HAProxy >= 2.7 responde "Success=1|0"; versões antigas apenas fecham a conexão
      const m = out.match(/Success=(\d)/);
      resolve({ ok: m ? m[1] === '1' : true, output: out.trim() });
    });
  });
}

export async function reloadHaproxy(cfg) {
  if (cfg.reloadMode === 'master-socket') return masterSocketReload(cfg.masterSocket);
  return sh(cfg.reloadCommand, { timeout: 60000 });
}

export async function waitHealthy(url, timeoutMs = 15000) {
  const end = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < end) {
    try {
      const r = await fetch(url, { signal: AbortSignal.timeout(2000) });
      if (r.ok) return { ok: true };
      last = `HTTP ${r.status}`;
    } catch (e) {
      last = e.message;
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  return { ok: false, output: `health-check ${url} falhou: ${last}` };
}

export async function isHealthy(url) {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(2000) });
    return r.ok;
  } catch {
    return false;
  }
}

/** Procura um processo pelo nome lendo /proc (sem depender de pidof). */
export async function processRunning(name) {
  try {
    for (const pid of await fs.readdir('/proc')) {
      if (!/^\d+$/.test(pid)) continue;
      const comm = await fs.readFile(`/proc/${pid}/comm`, 'utf8').catch(() => '');
      if (comm.trim() === name) return true;
    }
  } catch {
    /* sem /proc */
  }
  return false;
}

export async function keepalivedCheck(bin, file) {
  const r = await run(bin, ['-t', '-f', file, '-l', '-n']);
  if (r.missing) return { ok: true, skipped: true, output: 'keepalived não instalado; validação ignorada' };
  return r;
}
