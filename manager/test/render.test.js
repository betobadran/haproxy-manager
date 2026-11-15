import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeCluster, normalizeFrontend, normalizeBackend, normalizeCertificate, ValidationError } from '../src/validate.js';
import { renderHaproxy, RenderError } from '../src/render/haproxy.js';
import { renderKeepalived } from '../src/render/keepalived.js';
import { buildBundle } from '../src/render/bundle.js';
import { checkHaproxyFiles, checkKeepalived } from '../src/services/check.js';
import { hasBin, selfSigned, sampleFrontend, sampleBackends, sampleTcpFrontend } from './helpers.js';

const cluster = {
  name: 'prod',
  ...normalizeCluster({
    name: 'prod',
    settings: {
      mode: 'onprem-keepalived',
      haproxy: { user: '', log: 'stdout format raw local0', statsSocket: '/tmp/hapm-test-admin.sock', statsUser: 'admin', statsPassword: 's3cret' },
      keepalived: { vips: '192.168.10.100/24', interface: 'eth0', authPass: 'abc12345' },
      acme: { backend: 'manager.internal:8080', email: 'ops@example.com' },
    },
  }),
};

function snapshot() {
  return {
    cluster,
    frontends: [normalizeFrontend(sampleFrontend), normalizeFrontend(sampleTcpFrontend)],
    backends: sampleBackends.map(normalizeBackend),
    certificates: [normalizeCertificate({ name: 'example', pem: selfSigned('example.com') })],
    nodes: [
      { name: 'lb1', address: '192.168.10.11', settings: {} },
      { name: 'lb2', address: '192.168.10.12', settings: {} },
    ],
  };
}

test('renderiza haproxy.cfg com regras, ACME, stats e TLS', () => {
  const cfg = renderHaproxy({ ...snapshot(), version: 7 });
  assert.match(cfg, /frontend web\n/);
  assert.match(cfg, /bind :443 ssl crt-list @HAPM_RELEASE_DIR@\/certs.list alpn h2,http\/1.1/);
  assert.match(cfg, /acl r1_c1 req.hdr\(host\),field\(1,:\) -i api.example.com/);
  assert.match(cfg, /acl r1_c1 req.hdr\(host\),field\(1,:\) -i -m end .api.example.com/);
  assert.match(cfg, /use_backend api if r1_c1 r1_c2/);
  assert.match(cfg, /http-request deny deny_status 403 if r2_c1 !r2_c2/);
  assert.match(cfg, /use_backend hapm_acme if hapm_acme/);
  assert.match(cfg, /http-request redirect scheme https code 301 if !\{ ssl_fc \} !hapm_acme/);
  assert.match(cfg, /http-request use-service prometheus-exporter if \{ path \/metrics \}/);
  assert.match(cfg, /resolvers sysdns/); // há servidor com hostname
  assert.match(cfg, /cookie HAPM_SRV insert/);
  assert.match(cfg, /stats auth admin:s3cret/);
  assert.match(cfg, /tcp-request content reject if r1_c1/);
});

test('rejeita injeção de configuração', () => {
  assert.throws(() => normalizeFrontend({ ...sampleFrontend, rules: [{ conditions: [{ type: 'path_beg', value: '/x\n  bind :1' }], action: 'deny' }] }), ValidationError);
  assert.throws(() => normalizeFrontend({ ...sampleFrontend, name: 'web\nlisten x' }), ValidationError);
  assert.throws(() => normalizeBackend({ name: 'b', servers: [{ name: 's', address: '1.2.3.4 check\nlisten', port: 80 }] }), ValidationError);
  assert.throws(() => normalizeFrontend({ ...sampleFrontend, extra: 'http-request deny\nbackend evil' }), ValidationError);
  assert.throws(() => normalizeFrontend({ ...sampleFrontend, rules: [{ conditions: [{ type: 'header', name: 'X', value: '1' }], action: 'set_header', header: 'X', value: 'a" \nfoo' }] }), ValidationError);
  assert.throws(() => normalizeFrontend({ ...sampleFrontend, name: 'hapm_stats' }), ValidationError);
});

test('detecta referências quebradas e conflitos de porta', () => {
  const s = snapshot();
  s.frontends[0].defaultBackend = 'nao-existe';
  assert.throws(() => renderHaproxy(s), (e) => e instanceof RenderError && /não existe/.test(e.message));
  const s2 = snapshot();
  s2.frontends.push(normalizeFrontend({ name: 'other', binds: [{ port: 80 }], defaultBackend: 'app' }));
  assert.throws(() => renderHaproxy(s2), /porta 80/);
  const s3 = snapshot();
  s3.certificates = [];
  assert.throws(() => renderHaproxy(s3), /certificados/);
  const s4 = snapshot();
  s4.frontends[1].defaultBackend = 'app'; // tcp -> http
  assert.throws(() => renderHaproxy(s4), /não pode usar backend/);
});

test('certificado: valida par chave/cert e extrai metadados', () => {
  const c = normalizeCertificate({ name: 'x', pem: selfSigned('foo.example.org') });
  assert.deepEqual(c.meta.domains, ['foo.example.org', 'www.foo.example.org']);
  assert.ok(Date.parse(c.meta.notAfter) > Date.now());
  const other = selfSigned('bar.example.org');
  const certOnly = c.pem.match(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/)[0];
  const keyOnly = other.match(/-----BEGIN PRIVATE KEY-----[\s\S]+?-----END PRIVATE KEY-----/)[0];
  assert.throws(() => normalizeCertificate({ name: 'y', pem: `${certOnly}\n${keyOnly}` }), /não corresponde/);
});

test('keepalived: unicast, prioridades e VIPs', () => {
  const s = snapshot();
  const conf = renderKeepalived({ cluster, nodes: s.nodes, node: s.nodes[1], version: 3 });
  assert.match(conf, /priority 140/);
  assert.match(conf, /unicast_src_ip 192.168.10.12/);
  assert.match(conf, /unicast_peer \{\n\s+192.168.10.11\n/);
  assert.match(conf, /192.168.10.100\/24 dev eth0/);
  assert.match(conf, /virtual_router_id 51/);
});

test('aws-eip: exige parâmetros e adiciona VIP privado', () => {
  assert.throws(() => normalizeCluster({ name: 'a', settings: { mode: 'aws-eip' } }), /aws-eip/);
  const c = normalizeCluster({ name: 'a', settings: { mode: 'aws-eip', aws: { region: 'us-east-1', privateVip: '10.0.1.50', eipAllocationId: 'eipalloc-0abc' } } });
  const conf = renderKeepalived({ cluster: c, nodes: [{ name: 'n1', address: '10.0.1.10' }, { name: 'n2', address: '10.0.1.11' }], node: { name: 'n1', address: '10.0.1.10' } });
  assert.match(conf, /10.0.1.50\/32 dev eth0/);
  assert.match(conf, /unicast_peer/);
});

test('haproxy -c aceita a configuração gerada', { skip: !hasBin('haproxy') && 'haproxy não instalado' }, async () => {
  const s = snapshot();
  const cfg = renderHaproxy({ ...s, version: 1 });
  const bundle = buildBundle({ deployment: { version: 1, snapshot: s, haproxyCfg: cfg }, node: { name: 'lb1', address: '192.168.10.11' } });
  const res = await checkHaproxyFiles(bundle.files);
  assert.ok(res.available);
  assert.ok(res.ok, res.output);
});

test('keepalived -t aceita a configuração gerada', { skip: !hasBin('keepalived') && 'keepalived não instalado' }, async () => {
  const s = snapshot();
  // usa os scripts do agente deste repositório no lugar de /usr/local/lib/hapm
  const scripts = new URL('../../agent/scripts', import.meta.url).pathname;
  const conf = renderKeepalived({ cluster, nodes: s.nodes, node: s.nodes[0] }).replaceAll('/usr/local/lib/hapm', scripts);
  const res = await checkKeepalived(conf);
  assert.ok(res.ok, res.output);
});
