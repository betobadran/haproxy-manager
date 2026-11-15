import os from 'node:os';

const env = process.env;

function detectAddress(iface) {
  const ifs = os.networkInterfaces();
  const pick = (list) => (list || []).find((a) => a.family === 'IPv4' && !a.internal)?.address;
  if (iface && ifs[iface]) return pick(ifs[iface]) || null;
  for (const list of Object.values(ifs)) {
    const a = pick(list);
    if (a) return a;
  }
  return null;
}

export function loadConfig() {
  const iface = env.HAPM_NODE_INTERFACE || '';
  return {
    version: '0.1.0',
    managerUrl: (env.HAPM_MANAGER_URL || 'http://127.0.0.1:8080').replace(/\/$/, ''),
    joinToken: env.HAPM_JOIN_TOKEN || '',
    nodeName: env.HAPM_NODE_NAME || os.hostname().split('.')[0],
    nodeAddress: env.HAPM_NODE_ADDRESS || detectAddress(iface),
    pollIntervalSec: Number(env.HAPM_POLL_INTERVAL || 5),

    haproxyBin: env.HAPM_HAPROXY_BIN || 'haproxy',
    haproxyDir: env.HAPM_HAPROXY_DIR || '/etc/haproxy/hapm',
    // systemd | master-socket | command
    reloadMode: env.HAPM_RELOAD_MODE || 'systemd',
    reloadCommand: env.HAPM_RELOAD_COMMAND || 'systemctl reload-or-restart haproxy',
    masterSocket: env.HAPM_MASTER_SOCKET || '/run/haproxy-master.sock',
    statsPort: Number(env.HAPM_STATS_PORT || 8405),
    healthUrl: env.HAPM_HEALTH_URL || `http://127.0.0.1:${env.HAPM_STATS_PORT || 8405}/healthz`,
    keepReleases: Number(env.HAPM_KEEP_RELEASES || 10),

    keepalivedBin: env.HAPM_KEEPALIVED_BIN || 'keepalived',
    keepalivedDir: env.HAPM_KEEPALIVED_DIR || '/etc/keepalived',
    keepalivedReloadCommand: env.HAPM_KEEPALIVED_RELOAD_COMMAND || 'systemctl reload-or-restart keepalived',
    vrrpStateFile: env.HAPM_VRRP_STATE_FILE || '/var/lib/hapm/vrrp_state',

    stateDir: env.HAPM_STATE_DIR || '/var/lib/hapm',
    metricsPort: Number(env.HAPM_METRICS_PORT || 9101),
    metricsHost: env.HAPM_METRICS_HOST || '0.0.0.0',
  };
}
