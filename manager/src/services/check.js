// Validação opcional com o binário real do haproxy (`haproxy -c`) no próprio manager.
import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { RELEASE_DIR } from '../render/haproxy.js';

function run(bin, args, timeout = 15000) {
  return new Promise((resolve) => {
    execFile(bin, args, { timeout }, (err, stdout, stderr) => {
      resolve({ ok: !err, code: err?.code ?? 0, output: `${stdout}${stderr}`.trim(), missing: err?.code === 'ENOENT' });
    });
  });
}

/** Grava os arquivos do bundle num diretório temporário e roda `haproxy -c`. */
export async function checkHaproxyFiles(files, haproxyBin = 'haproxy') {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'hapm-check-'));
  try {
    for (const [rel, content] of Object.entries(files)) {
      const p = path.join(dir, rel);
      await fs.mkdir(path.dirname(p), { recursive: true });
      await fs.writeFile(p, content.split(RELEASE_DIR).join(dir), { mode: 0o600 });
    }
    const res = await run(haproxyBin, ['-c', '-f', path.join(dir, 'haproxy.cfg')]);
    if (res.missing) return { available: false };
    return { available: true, ok: res.ok, output: res.output.split(dir).join(RELEASE_DIR) };
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
}

export async function checkKeepalived(conf, bin = 'keepalived') {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'hapm-kc-'));
  try {
    const f = path.join(dir, 'keepalived.conf');
    await fs.writeFile(f, conf);
    const res = await run(bin, ['-t', '-f', f, '-l', '-n']);
    if (res.missing) return { available: false };
    return { available: true, ok: res.ok, output: res.output };
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
}
