// Aplicação de uma versão: grava release, valida, troca o symlink "current"
// atomicamente, recarrega e faz rollback local se algo falhar.
//
//   /etc/haproxy/hapm/
//     current -> releases/v12          (o systemd/haproxy usa current/haproxy.cfg)
//     releases/v11/{haproxy.cfg,certs.list,certs/*.pem}
//     releases/v12/...
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import * as sys from './system.js';

export const RELEASE_DIR_PLACEHOLDER = '@HAPM_RELEASE_DIR@';

const exists = (p) => fs.access(p).then(() => true, () => false);

export function bootstrapConfig(statsPort) {
  return [
    '# Configuração inicial do HAPM (nenhuma versão publicada ainda)',
    'global',
    '    maxconn 4096',
    'defaults',
    '    mode http',
    '    timeout connect 5s',
    '    timeout client 30s',
    '    timeout server 30s',
    'frontend hapm_stats',
    `    bind :${statsPort}`,
    '    http-request use-service prometheus-exporter if { path /metrics }',
    '    monitor-uri /healthz',
    '',
  ].join('\n');
}

export class Applier {
  constructor(cfg, log = console) {
    this.cfg = cfg;
    this.log = log;
    this.releases = path.join(cfg.haproxyDir, 'releases');
    this.current = path.join(cfg.haproxyDir, 'current');
  }

  async currentRelease() {
    try {
      return await fs.readlink(this.current);
    } catch {
      return null;
    }
  }

  async appliedVersion() {
    const r = await this.currentRelease();
    const m = r && path.basename(r).match(/^v(\d+)$/);
    return m ? Number(m[1]) : 0;
  }

  /** Garante uma config mínima para o haproxy subir antes do primeiro deploy. */
  async ensureBootstrap() {
    if (await this.currentRelease()) return false;
    const dir = path.join(this.releases, 'v0');
    await fs.mkdir(dir, { recursive: true });
    await fs.writeFile(path.join(dir, 'haproxy.cfg'), bootstrapConfig(this.cfg.statsPort));
    await this.swap(dir);
    this.log.info('release bootstrap v0 criada');
    return true;
  }

  async swap(target) {
    const tmp = `${this.current}.tmp-${process.pid}`;
    await fs.rm(tmp, { force: true });
    await fs.symlink(path.relative(this.cfg.haproxyDir, target), tmp);
    await fs.rename(tmp, this.current); // rename é atômico
  }

  async writeRelease(bundle) {
    const dir = path.join(this.releases, `v${bundle.version}`);
    const staging = `${dir}.staging`;
    await fs.rm(staging, { recursive: true, force: true });
    await fs.mkdir(staging, { recursive: true, mode: 0o750 });
    for (const [rel, content] of Object.entries(bundle.files)) {
      const target = path.resolve(staging, rel);
      if (!target.startsWith(staging + path.sep)) throw new Error(`caminho inválido no bundle: ${rel}`);
      await fs.mkdir(path.dirname(target), { recursive: true, mode: 0o750 });
      // durante a validação os caminhos apontam para o staging; depois para o diretório final
      await fs.writeFile(target, content.split(RELEASE_DIR_PLACEHOLDER).join(staging), { mode: rel.endsWith('.pem') ? 0o600 : 0o640 });
    }
    return { dir, staging };
  }

  async finalizeRelease(bundle, { dir, staging }) {
    for (const rel of Object.keys(bundle.files)) {
      if (rel.endsWith('.pem')) continue;
      const f = path.join(staging, rel);
      await fs.writeFile(f, (await fs.readFile(f, 'utf8')).split(staging).join(dir));
    }
    await fs.rm(dir, { recursive: true, force: true });
    await fs.rename(staging, dir);
  }

  async applyKeepalived(files) {
    const kdir = this.cfg.keepalivedDir;
    const conf = path.join(kdir, 'keepalived.conf');
    const prev = await fs.readFile(conf, 'utf8').catch(() => null);
    const changedEnv = await fs.readFile(path.join(kdir, 'hapm-failover.env'), 'utf8').catch(() => null) !== files['hapm-failover.env'];
    if (prev === files['keepalived.conf'] && !changedEnv) return { changed: false };
    await fs.mkdir(kdir, { recursive: true });
    const tmp = path.join(kdir, `.keepalived.conf.hapm-${process.pid}`);
    await fs.writeFile(tmp, files['keepalived.conf'], { mode: 0o640 });
    const chk = await sys.keepalivedCheck(this.cfg.keepalivedBin, tmp);
    if (!chk.ok) {
      await fs.rm(tmp, { force: true });
      throw new Error(`keepalived -t falhou: ${chk.output}`);
    }
    if (files['hapm-failover.env'] != null) await fs.writeFile(path.join(kdir, 'hapm-failover.env'), files['hapm-failover.env'], { mode: 0o640 });
    await fs.rename(tmp, conf);
    const r = await sys.sh(this.cfg.keepalivedReloadCommand);
    if (!r.ok) {
      if (prev != null) {
        await fs.writeFile(conf, prev);
        await sys.sh(this.cfg.keepalivedReloadCommand);
      }
      throw new Error(`reload do keepalived falhou: ${r.output}`);
    }
    return { changed: true };
  }

  async apply(bundle) {
    const started = Date.now();
    const { version, sha256, ...body } = bundle;
    const digest = crypto.createHash('sha256').update(JSON.stringify({ version, ...body })).digest('hex');
    if (sha256 && digest !== sha256) throw new Error('checksum do bundle não confere');

    const paths = await this.writeRelease(bundle);
    // 1) valida no staging
    const chk = await sys.haproxyCheck(this.cfg.haproxyBin, path.join(paths.staging, 'haproxy.cfg'));
    if (!chk.ok) {
      await fs.rm(paths.staging, { recursive: true, force: true });
      throw new Error(`haproxy -c falhou: ${chk.output.split(paths.staging).join('<release>')}`);
    }
    await this.finalizeRelease(bundle, paths);

    // 2) keepalived primeiro: se falhar, o haproxy segue intacto
    let keepalived = { changed: false };
    if (bundle.keepalived) keepalived = await this.applyKeepalived(bundle.keepalived);

    // 3) troca atômica + reload; rollback se o reload/health falhar
    const previous = await this.currentRelease();
    await this.swap(paths.dir);
    let r = await sys.reloadHaproxy(this.cfg);
    if (r.ok) r = await sys.waitHealthy(this.cfg.healthUrl);
    if (!r.ok) {
      this.log.error(`reload falhou, voltando para ${previous}: ${r.output}`);
      if (previous) {
        await this.swap(path.resolve(this.cfg.haproxyDir, previous));
        await sys.reloadHaproxy(this.cfg);
      }
      throw new Error(`reload do haproxy falhou (rollback para ${previous}): ${r.output}`);
    }
    await this.prune(paths.dir, previous);
    return { version, durationMs: Date.now() - started, keepalivedChanged: keepalived.changed };
  }

  async prune(currentDir, previous) {
    const keep = new Set([path.basename(currentDir), previous && path.basename(previous)]);
    const list = (await fs.readdir(this.releases).catch(() => []))
      .filter((d) => /^v\d+$/.test(d))
      .sort((a, b) => Number(b.slice(1)) - Number(a.slice(1)));
    for (const d of list.slice(this.cfg.keepReleases)) {
      if (!keep.has(d)) await fs.rm(path.join(this.releases, d), { recursive: true, force: true });
    }
    for (const d of await fs.readdir(this.releases).catch(() => [])) {
      if (d.endsWith('.staging')) await fs.rm(path.join(this.releases, d), { recursive: true, force: true });
    }
  }

  /** Datas de expiração dos certificados da release atual (para métricas). */
  async certificates() {
    const dir = path.join(this.current, 'certs');
    if (!(await exists(dir))) return [];
    const out = [];
    for (const f of await fs.readdir(dir)) {
      if (!f.endsWith('.pem')) continue;
      try {
        const pem = await fs.readFile(path.join(dir, f), 'utf8');
        const first = pem.match(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/)?.[0];
        const x = new crypto.X509Certificate(first);
        out.push({ name: f.replace(/\.pem$/, ''), notAfter: Date.parse(x.validTo), cn: (x.subject.match(/CN=([^\n]+)/) || [])[1] || '' });
      } catch {
        /* ignora arquivo inválido */
      }
    }
    return out;
  }
}
