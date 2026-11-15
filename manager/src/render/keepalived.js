// Gera o keepalived.conf de um nó específico (VRRP unicast por padrão,
// que funciona em qualquer rede, inclusive onde multicast é bloqueado).

export const LIB_DIR = '/usr/local/lib/hapm';
export const FAILOVER_ENV = '/etc/keepalived/hapm-failover.env';

export function usesKeepalived(cluster) {
  return cluster.settings.mode === 'onprem-keepalived' || cluster.settings.mode === 'aws-eip';
}

/** Prioridade explícita do nó ou automática (ordem alfabética: 150, 140, 130...). */
export function nodePriority(nodes, node) {
  if (node.settings?.priority) return node.settings.priority;
  const sorted = [...nodes].filter((n) => !n.settings?.disabled).sort((a, b) => a.name.localeCompare(b.name));
  const i = Math.max(0, sorted.findIndex((n) => n.name === node.name));
  return Math.max(1, 150 - i * 10);
}

const nodeIp = (n) => n.settings?.unicastAddress || n.address;

export function renderKeepalived({ cluster, nodes, node, version = 0 }) {
  const k = cluster.settings.keepalived;
  const s = cluster.settings;
  const iface = node.settings?.interface || k.interface;
  const vips = [...k.vips];
  if (s.mode === 'aws-eip' && s.aws.privateVip) vips.push(`${s.aws.privateVip}/32`);
  const self = nodeIp(node);
  const peers = nodes.filter((n) => n.name !== node.name && !n.settings?.disabled).map(nodeIp).filter(Boolean);
  const unicast = s.mode === 'aws-eip' ? true : k.unicast; // AWS não suporta multicast
  const statsPort = s.haproxy.statsPort;

  const L = [];
  L.push('# Gerado pelo HAPM (HAProxy HA Manager) - NÃO EDITE MANUALMENTE');
  L.push(`# cluster: ${cluster.name}   nó: ${node.name}   versão: ${version}`);
  L.push('global_defs {');
  L.push(`    router_id ${node.name.replace(/[^A-Za-z0-9_.-]/g, '_')}`);
  L.push('    enable_script_security');
  L.push('    script_user root');
  L.push('    vrrp_garp_master_refresh 60');
  L.push('}');
  L.push('');
  L.push('vrrp_script chk_haproxy {');
  L.push(`    script "${LIB_DIR}/check-haproxy.sh ${statsPort}"`);
  L.push(`    interval ${k.checkInterval}`);
  L.push(`    timeout ${k.checkInterval + 1}`);
  L.push(`    fall ${k.checkFall}`);
  L.push(`    rise ${k.checkRise}`);
  L.push('}');
  L.push('');
  L.push(`vrrp_instance VI_${k.routerId} {`);
  L.push('    state BACKUP');
  L.push(`    interface ${iface}`);
  L.push(`    virtual_router_id ${k.routerId}`);
  L.push(`    priority ${nodePriority(nodes, node)}`);
  L.push(`    advert_int ${k.advertInt}`);
  if (!k.preempt) L.push('    nopreempt');
  if (unicast && self) {
    L.push(`    unicast_src_ip ${self}`);
    L.push('    unicast_peer {');
    for (const p of peers) L.push(`        ${p}`);
    L.push('    }');
  }
  L.push('    authentication {');
  L.push('        auth_type PASS');
  L.push(`        auth_pass ${k.authPass}`);
  L.push('    }');
  if (vips.length) {
    L.push('    virtual_ipaddress {');
    for (const v of vips) L.push(`        ${v.includes('/') ? v : `${v}/32`} dev ${iface}`);
    L.push('    }');
  }
  L.push('    track_script {');
  L.push('        chk_haproxy');
  L.push('    }');
  L.push(`    notify "${LIB_DIR}/keepalived-notify.sh"`);
  L.push('}');
  L.push('');
  return L.join('\n');
}

export function renderFailoverEnv(cluster) {
  const a = cluster.settings.aws;
  const lines = ['# Gerado pelo HAPM - usado por keepalived-notify.sh no modo aws-eip'];
  lines.push(`HAPM_MODE=${cluster.settings.mode}`);
  if (cluster.settings.mode === 'aws-eip') {
    lines.push(`HAPM_AWS_REGION=${a.region}`);
    if (a.eipAllocationId) lines.push(`HAPM_EIP_ALLOCATION_ID=${a.eipAllocationId}`);
    if (a.privateVip) lines.push(`HAPM_PRIVATE_VIP=${a.privateVip}`);
  }
  return lines.join('\n') + '\n';
}
