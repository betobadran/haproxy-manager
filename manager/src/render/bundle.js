// Monta o "bundle" que o agente baixa: haproxy.cfg + certificados + keepalived.conf.
import crypto from 'node:crypto';
import { renderHaproxy, renderCrtList, orderCertificates } from './haproxy.js';
import { renderKeepalived, renderFailoverEnv, usesKeepalived } from './keepalived.js';

/** Snapshot imutável do estado do cluster, guardado em cada deployment (permite rollback). */
export function buildSnapshot(store, clusterId) {
  const cluster = store.getCluster(clusterId);
  const strip = ({ id, clusterId: _c, kind, createdAt, updatedAt, ...rest }) => rest;
  return {
    cluster: { id: cluster.id, name: cluster.name, settings: cluster.settings },
    frontends: store.listObjects(clusterId, 'frontend').map(strip),
    backends: store.listObjects(clusterId, 'backend').map(strip),
    certificates: store.listObjects(clusterId, 'certificate').map(strip).filter((c) => c.pem),
    nodes: store.listNodes(clusterId).map((n) => ({ name: n.name, address: n.address, settings: n.settings })),
  };
}

export function renderSnapshotHaproxy(snapshot, version) {
  return renderHaproxy({ ...snapshot, version });
}

const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');

/**
 * Bundle de um nó. Os nós usados no keepalived são os do snapshot + o próprio nó
 * (caso tenha entrado depois do deploy, p.ex. scale-out de um ASG).
 */
export function buildBundle({ deployment, node }) {
  const snap = deployment.snapshot;
  const files = {
    'haproxy.cfg': deployment.haproxyCfg,
    'certs.list': renderCrtList(snap.cluster, snap.certificates),
  };
  for (const c of orderCertificates(snap.cluster, snap.certificates)) files[`certs/${c.name}.pem`] = c.pem;

  let keepalived = null;
  if (usesKeepalived(snap.cluster)) {
    const nodes = snap.nodes.some((n) => n.name === node.name) ? snap.nodes : [...snap.nodes, { name: node.name, address: node.address, settings: node.settings }];
    const self = { ...(nodes.find((n) => n.name === node.name) || {}), address: node.address || nodes.find((n) => n.name === node.name)?.address };
    keepalived = {
      'keepalived.conf': renderKeepalived({ cluster: snap.cluster, nodes, node: self, version: deployment.version }),
      'hapm-failover.env': renderFailoverEnv(snap.cluster),
    };
  }
  const body = { version: deployment.version, cluster: snap.cluster.name, files, keepalived };
  return { ...body, sha256: sha(JSON.stringify(body)) };
}
