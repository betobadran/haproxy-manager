import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export function hasBin(bin) {
  try {
    execFileSync('sh', ['-c', `command -v ${bin}`], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

/** Gera um certificado autoassinado (cert + chave em um PEM) com openssl. */
export function selfSigned(cn = 'app.example.com') {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'hapm-cert-'));
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '30', '-subj', `/CN=${cn}`,
    '-addext', `subjectAltName=DNS:${cn},DNS:www.${cn}`, '-keyout', `${dir}/k.pem`, '-out', `${dir}/c.pem`], { stdio: 'ignore' });
  const pem = fs.readFileSync(`${dir}/c.pem`, 'utf8') + fs.readFileSync(`${dir}/k.pem`, 'utf8');
  fs.rmSync(dir, { recursive: true, force: true });
  return pem;
}

export const sampleFrontend = {
  name: 'web',
  mode: 'http',
  binds: [{ address: '*', port: 80 }, { address: '*', port: 443, ssl: true }],
  defaultBackend: 'app',
  redirectToHttps: true,
  hsts: true,
  allowCidrs: '0.0.0.0/0 ::/0',
  rateLimit: { enabled: true, requests: 200, period: '10s' },
  rules: [
    { description: 'API', conditions: [{ type: 'host', value: 'api.example.com *.api.example.com' }, { type: 'path_beg', value: '/v1 /v2' }], action: 'use_backend', backend: 'api' },
    { conditions: [{ type: 'path_reg', value: '^/admin(/.*)?$' }, { type: 'src', value: '10.0.0.0/8', negate: true }], action: 'deny', status: 403 },
    { conditions: [{ type: 'host', value: 'old.example.com' }], action: 'redirect', redirectType: 'prefix', url: 'https://new.example.com', code: 301 },
    { conditions: [{ type: 'header', name: 'X-Debug', value: '1' }], action: 'set_header', header: 'X-Trace', value: '%[uuid]' },
    { conditions: [], action: 'del_header', header: 'Server', direction: 'response' },
  ],
};

export const sampleBackends = [
  {
    name: 'app', mode: 'http', balance: 'roundrobin',
    servers: [{ name: 'a1', address: '10.0.1.10', port: 8080 }, { name: 'a2', address: '10.0.1.11', port: 8080, backup: true }],
    healthCheck: { type: 'http', path: '/health', expectStatus: '200-399' },
    sticky: { enabled: true }, compression: true,
  },
  {
    name: 'api', mode: 'http', balance: 'leastconn',
    servers: [{ name: 'api1', address: 'api-1.internal.local', port: 443 }],
    healthCheck: { type: 'http', path: '/ready', expectStatus: '200' },
    serverSsl: { enabled: true, verify: 'none' },
  },
  { name: 'pg', mode: 'tcp', servers: [{ name: 'pg1', address: '10.0.2.10', port: 5432 }], healthCheck: { type: 'tcp' } },
];

export const sampleTcpFrontend = {
  name: 'postgres', mode: 'tcp', binds: [{ address: '*', port: 5432 }], defaultBackend: 'pg',
  rules: [{ conditions: [{ type: 'src', value: '192.168.0.0/16' }], action: 'deny' }],
};
