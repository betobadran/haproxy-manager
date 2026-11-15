// Emissão/renovação ACME (Let's Encrypt) via HTTP-01.
// Fluxo: o HAProxy de todos os nós encaminha /.well-known/acme-challenge/* para o
// backend "hapm_acme" (o próprio manager), que responde com o key-authorization.
import { normalizeCertificate } from '../validate.js';

export class CertService {
  constructor({ store, config, deploy, log }) {
    this.store = store;
    this.config = config;
    this.deploy = deploy;
    this.log = log;
    this.running = new Set();
    this._acme = null;
  }

  async acmeLib() {
    if (!this._acme) this._acme = (await import('acme-client')).default;
    return this._acme;
  }

  async client(acme) {
    let key = this.store.getKv('acme.accountKey');
    if (!key) {
      key = (await acme.crypto.createPrivateKey()).toString();
      this.store.setKv('acme.accountKey', key);
    }
    return new acme.Client({ directoryUrl: this.config.acmeDirectory, accountKey: key });
  }

  /** Dispara a emissão em background; o status fica em cert.acme.status. */
  issue(clusterId, certId, user) {
    const key = `${clusterId}/${certId}`;
    if (this.running.has(key)) return false;
    this.running.add(key);
    this.setStatus(clusterId, certId, { status: 'pending', error: null });
    this._issue(clusterId, certId, user)
      .catch((e) => {
        this.log?.error({ err: e.message, certId }, 'falha ACME');
        this.setStatus(clusterId, certId, { status: 'error', error: e.message });
        this.store.audit({ username: user, clusterId, action: 'certificate.acme.error', target: certId, detail: { error: e.message } });
      })
      .finally(() => this.running.delete(key));
    return true;
  }

  setStatus(clusterId, certId, patch) {
    const c = this.store.getObject(clusterId, 'certificate', certId);
    if (!c) return;
    const { id, clusterId: _c, kind, createdAt, updatedAt, ...data } = c;
    data.acme = { ...(data.acme || {}), ...patch, updatedAt: new Date().toISOString() };
    this.store.updateObject(clusterId, 'certificate', certId, data);
  }

  async _issue(clusterId, certId, user) {
    const cluster = this.store.getCluster(clusterId);
    const cert = this.store.getObject(clusterId, 'certificate', certId);
    if (!cluster.settings.acme?.backend) throw new Error('configure "acme.backend" (host:porta do manager visto pelo HAProxy) no cluster');
    const acme = await this.acmeLib();
    const client = await this.client(acme);
    const [key, csr] = await acme.crypto.createCsr({ commonName: cert.acme.domains[0], altNames: cert.acme.domains });
    const email = cert.acme.email || cluster.settings.acme.email;
    const pem = await client.auto({
      csr,
      email: email || undefined,
      termsOfServiceAgreed: true,
      challengePriority: ['http-01'],
      challengeCreateFn: async (_authz, challenge, keyAuthorization) => {
        if (challenge.type !== 'http-01') throw new Error(`desafio ${challenge.type} não suportado`);
        this.store.putChallenge(challenge.token, keyAuthorization);
      },
      challengeRemoveFn: async (_authz, challenge) => this.store.deleteChallenge(challenge.token),
    });
    const fresh = this.store.getObject(clusterId, 'certificate', certId);
    const { id, clusterId: _c, kind, createdAt, updatedAt, ...data } = fresh;
    const normalized = normalizeCertificate({ ...data, pem: `${pem.toString()}\n${key.toString()}` });
    normalized.acme = { ...normalized.acme, status: 'issued', error: null, issuedAt: new Date().toISOString() };
    this.store.updateObject(clusterId, 'certificate', certId, normalized);
    this.store.audit({ username: user, clusterId, action: 'certificate.acme.issued', target: cert.name, detail: { notAfter: normalized.meta.notAfter } });
    this.log?.info({ cert: cert.name, notAfter: normalized.meta.notAfter }, 'certificado ACME emitido');
    if (cluster.settings.autoDeployOnCertRenew) {
      try {
        this.deploy.create(clusterId, { note: `certificado ${cert.name} emitido/renovado`, user: user || 'acme' });
      } catch (e) {
        this.log?.warn({ err: e.message }, 'deploy automático após emissão falhou');
      }
    }
  }

  /** Renova certificados ACME que expiram em menos de N dias. */
  renewDue() {
    const limit = Date.now() + this.config.certRenewBeforeDays * 86400_000;
    for (const cluster of this.store.listClusters()) {
      for (const c of this.store.listObjects(cluster.id, 'certificate')) {
        if (c.source !== 'acme' || c.acme?.status === 'pending') continue;
        const exp = c.meta?.notAfter ? Date.parse(c.meta.notAfter) : 0;
        if (exp < limit) {
          this.log?.info({ cert: c.name }, 'renovando certificado ACME');
          this.issue(cluster.id, c.id, 'acme-renew');
        }
      }
    }
  }
}
