import test from 'node:test';
import assert from 'node:assert/strict';
import { openStore } from '../src/db.js';
import { DeployService } from '../src/services/deploy.js';
import { normalizeCluster } from '../src/validate.js';

function setup(rollout = 'sequential') {
  const store = openStore(':memory:');
  const deploy = new DeployService({ store, config: { nodeOfflineAfterSec: 30, deploymentTimeoutSec: 600 } });
  const c = store.createCluster(normalizeCluster({ name: 'c1', settings: { rollout, keepalived: { vips: '10.0.0.100/24' } } }));
  const hb = (name, vrrpState, appliedVersion = 0, lastApply = null) => store.upsertNodeHeartbeat(c.id, { name, address: '10.0.0.1', vrrpState, appliedVersion, lastApply });
  return { store, deploy, c, hb };
}

test('sequencial: BACKUPs primeiro, MASTER por último', () => {
  const { deploy, c, hb, store } = setup();
  const m = hb('lb-a', 'MASTER');
  const b1 = hb('lb-b', 'BACKUP');
  const b2 = hb('lb-c', 'BACKUP');
  const d = deploy.create(c.id, { user: 't' });
  assert.equal(d.status, 'in_progress');
  assert.deepEqual(deploy.desiredFor(c.id, b1), { version: 1 });
  assert.equal(deploy.desiredFor(c.id, b2).hold, true);
  assert.equal(deploy.desiredFor(c.id, m).hold, true);
  hb('lb-b', 'BACKUP', 1);
  assert.deepEqual(deploy.desiredFor(c.id, store.getNode(b2.id)), { version: 1 });
  assert.equal(deploy.desiredFor(c.id, m).hold, true);
  hb('lb-c', 'BACKUP', 1);
  assert.deepEqual(deploy.desiredFor(c.id, m), { version: 1 });
  hb('lb-a', 'MASTER', 1);
  assert.equal(deploy.evaluate(c.id).status, 'succeeded');
});

test('paralelo: só o MASTER espera', () => {
  const { deploy, c, hb } = setup('parallel');
  const m = hb('lb-a', 'MASTER');
  const b1 = hb('lb-b', 'BACKUP');
  const b2 = hb('lb-c', 'BACKUP');
  deploy.create(c.id, {});
  assert.equal(deploy.desiredFor(c.id, b1).hold, undefined);
  assert.equal(deploy.desiredFor(c.id, b2).hold, undefined);
  assert.equal(deploy.desiredFor(c.id, m).hold, true);
});

test('falha num BACKUP (canário) protege o MASTER e volta todos para a última versão boa', () => {
  const { deploy, c, hb } = setup();
  hb('lb-a', 'MASTER');
  hb('lb-b', 'BACKUP');
  deploy.create(c.id, {});
  hb('lb-b', 'BACKUP', 1);
  const m = hb('lb-a', 'MASTER', 1);
  assert.equal(deploy.evaluate(c.id).status, 'succeeded');
  deploy.create(c.id, {});
  hb('lb-b', 'BACKUP', 1, { version: 2, ok: false, error: 'haproxy -c falhou' });
  const d2 = deploy.evaluate(c.id);
  assert.equal(d2.status, 'failed');
  assert.match(d2.error, /lb-b/);
  assert.equal(deploy.desiredFor(c.id, m).version, 1);
});

test('cluster sem nós online: deployment conclui e é aplicado quando os nós entrarem', () => {
  const { deploy, c, hb } = setup();
  assert.equal(deploy.create(c.id, {}).status, 'succeeded');
  const n = hb('novo', 'BACKUP');
  assert.deepEqual(deploy.desiredFor(c.id, n), { version: 1 });
});
