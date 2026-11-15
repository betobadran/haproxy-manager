// Orquestração de deploy: versões, rollout seguro (BACKUP antes do MASTER),
// detecção de falha e rollback automático para a última versão boa.
import { buildSnapshot, renderSnapshotHaproxy } from '../render/bundle.js';

export class DeployService {
  constructor({ store, config, log }) {
    this.store = store;
    this.config = config;
    this.log = log;
  }

  isOnline(node, now = Date.now()) {
    return !!node.lastSeen && now - node.lastSeen < this.config.nodeOfflineAfterSec * 1000 && !node.settings?.disabled;
  }

  /** Renderiza sem persistir (usado no preview). Lança RenderError se houver problemas. */
  preview(clusterId) {
    const snapshot = buildSnapshot(this.store, clusterId);
    const latest = this.store.latestDeployment(clusterId);
    const nextVersion = (latest?.version || 0) + 1;
    return { snapshot, version: nextVersion, haproxyCfg: renderSnapshotHaproxy(snapshot, nextVersion), current: latest };
  }

  create(clusterId, { note, user, snapshot } = {}) {
    const snap = snapshot || buildSnapshot(this.store, clusterId);
    const latest = this.store.latestDeployment(clusterId);
    const version = (latest?.version || 0) + 1;
    const haproxyCfg = renderSnapshotHaproxy(snap, version); // valida antes de gravar
    const dep = this.store.createDeployment(clusterId, { snapshot: snap, haproxyCfg, note, createdBy: user });
    this.store.audit({ username: user, clusterId, action: 'deployment.create', target: `v${dep.version}`, detail: { note } });
    this.evaluate(clusterId);
    return this.store.getDeployment(clusterId, dep.id);
  }

  rollback(clusterId, deploymentId, user) {
    const old = this.store.getDeployment(clusterId, deploymentId);
    if (!old) return null;
    // volta a configuração de tráfego, mas mantém a topologia de nós e os certificados
    // atuais (evita reintroduzir um certificado já expirado/renovado)
    const cur = buildSnapshot(this.store, clusterId);
    const snapshot = { ...old.snapshot, nodes: cur.nodes, certificates: cur.certificates };
    return this.create(clusterId, { note: `rollback para v${old.version}`, user, snapshot });
  }

  /** Ordem do rollout: nós não-MASTER primeiro (canário), MASTER por último. */
  rolloutOrder(nodes) {
    const isMaster = (n) => (n.vrrpState === 'MASTER' ? 1 : 0);
    return nodes.filter((n) => this.isOnline(n)).sort((a, b) => isMaster(a) - isMaster(b) || a.name.localeCompare(b.name));
  }

  /** Qual versão o nó deve ter agora e se deve esperar. */
  desiredFor(clusterId, node) {
    const dep = this.store.latestDeployment(clusterId);
    if (!dep) return { version: null };
    if (dep.status === 'failed') {
      const good = this.store.latestSucceededDeployment(clusterId);
      return { version: good?.version ?? null, reason: `v${dep.version} falhou; mantendo última versão boa` };
    }
    if (node.appliedVersion === dep.version || dep.status === 'succeeded') return { version: dep.version };

    const cluster = this.store.getCluster(clusterId);
    const order = this.rolloutOrder(this.store.listNodes(clusterId));
    const idx = order.findIndex((n) => n.id === node.id);
    const pending = (n) => n.id !== node.id && n.appliedVersion !== dep.version;
    let blocker = null;
    if (cluster.settings.rollout === 'parallel') {
      if (node.vrrpState === 'MASTER') blocker = order.find((n) => n.vrrpState !== 'MASTER' && pending(n));
    } else {
      blocker = order.slice(0, Math.max(idx, 0)).find(pending);
    }
    if (blocker) return { version: dep.version, hold: true, reason: `aguardando ${blocker.name} aplicar v${dep.version}` };
    return { version: dep.version };
  }

  /** Recalcula o status do deployment em andamento. */
  evaluate(clusterId) {
    const dep = this.store.latestDeployment(clusterId);
    if (!dep || dep.status !== 'in_progress') return dep;
    const online = this.store.listNodes(clusterId).filter((n) => this.isOnline(n));
    const failed = online.find((n) => n.lastApply?.version === dep.version && n.lastApply.ok === false);
    if (failed) {
      const error = `${failed.name}: ${failed.lastApply.error || 'erro desconhecido'}`;
      this.store.setDeploymentStatus(dep.id, 'failed', error);
      this.store.audit({ clusterId, action: 'deployment.failed', target: `v${dep.version}`, detail: { error } });
      this.log?.warn({ clusterId, version: dep.version, error }, 'deployment falhou; nós voltarão para a última versão boa');
    } else if (online.every((n) => n.appliedVersion === dep.version)) {
      this.store.setDeploymentStatus(dep.id, 'succeeded', online.length ? null : 'nenhum nó online; será aplicado quando os nós conectarem');
      this.store.audit({ clusterId, action: 'deployment.succeeded', target: `v${dep.version}` });
    } else if (Date.now() - Date.parse(dep.createdAt) > this.config.deploymentTimeoutSec * 1000) {
      const stuck = online.filter((n) => n.appliedVersion !== dep.version).map((n) => n.name);
      this.store.setDeploymentStatus(dep.id, 'failed', `timeout aguardando: ${stuck.join(', ')}`);
      this.store.audit({ clusterId, action: 'deployment.failed', target: `v${dep.version}`, detail: { error: 'timeout', stuck } });
    }
    return this.store.getDeployment(clusterId, dep.id);
  }

  evaluateAll() {
    for (const c of this.store.listClusters()) {
      try {
        this.evaluate(c.id);
      } catch (e) {
        this.log?.error(e, 'erro avaliando deployments');
      }
    }
  }
}
