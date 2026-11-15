import path from 'node:path';

const env = process.env;

export const config = {
  port: Number(env.HAPM_PORT || 8080),
  host: env.HAPM_HOST || '0.0.0.0',
  dataDir: env.HAPM_DATA_DIR || '/var/lib/hapm-manager',
  get dbFile() {
    return env.HAPM_DB_FILE || path.join(this.dataDir, 'hapm.db');
  },
  // segredo para assinar tokens de sessão; se vazio é gerado e salvo no banco
  secret: env.HAPM_SECRET || null,
  adminUser: env.HAPM_ADMIN_USER || 'admin',
  adminPassword: env.HAPM_ADMIN_PASSWORD || null,
  acmeDirectory: env.HAPM_ACME_DIRECTORY || 'https://acme-v02.api.letsencrypt.org/directory',
  // binário opcional do haproxy no manager, usado no "preview" para validar com `haproxy -c`
  haproxyBin: env.HAPM_HAPROXY_BIN || 'haproxy',
  nodeOfflineAfterSec: Number(env.HAPM_NODE_OFFLINE_AFTER || 30),
  deploymentTimeoutSec: Number(env.HAPM_DEPLOYMENT_TIMEOUT || 600),
  certRenewBeforeDays: Number(env.HAPM_CERT_RENEW_DAYS || 30),
  logLevel: env.HAPM_LOG_LEVEL || 'info',
};
