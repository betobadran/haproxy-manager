import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const SCHEMA = `
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS kv (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS clusters (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  settings TEXT NOT NULL,
  join_secret TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS nodes (
  id TEXT PRIMARY KEY,
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  address TEXT,
  settings TEXT NOT NULL DEFAULT '{}',
  agent_version TEXT,
  haproxy_version TEXT,
  last_seen INTEGER,
  vrrp_state TEXT,
  applied_version INTEGER NOT NULL DEFAULT 0,
  last_apply TEXT,
  created_at TEXT NOT NULL,
  UNIQUE(cluster_id, name)
);
CREATE TABLE IF NOT EXISTS objects (
  id TEXT PRIMARY KEY,
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  name TEXT NOT NULL,
  data TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(cluster_id, kind, name)
);
CREATE TABLE IF NOT EXISTS deployments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cluster_id TEXT NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  status TEXT NOT NULL,
  error TEXT,
  note TEXT,
  created_by TEXT,
  created_at TEXT NOT NULL,
  finished_at TEXT,
  snapshot TEXT NOT NULL,
  haproxy_cfg TEXT NOT NULL,
  UNIQUE(cluster_id, version)
);
CREATE TABLE IF NOT EXISTS acme_challenges (
  token TEXT PRIMARY KEY,
  key_authorization TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS audit (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts TEXT NOT NULL,
  username TEXT,
  cluster_id TEXT,
  action TEXT NOT NULL,
  target TEXT,
  detail TEXT
);
`;

const now = () => new Date().toISOString();
const json = (v) => JSON.stringify(v ?? null);
const parse = (v, d = null) => (v == null ? d : JSON.parse(v));

function mapCluster(r) {
  if (!r) return null;
  return { id: r.id, name: r.name, settings: parse(r.settings, {}), joinSecret: r.join_secret, createdAt: r.created_at, updatedAt: r.updated_at };
}
function mapNode(r) {
  if (!r) return null;
  return {
    id: r.id, clusterId: r.cluster_id, name: r.name, address: r.address, settings: parse(r.settings, {}),
    agentVersion: r.agent_version, haproxyVersion: r.haproxy_version, lastSeen: r.last_seen,
    vrrpState: r.vrrp_state, appliedVersion: r.applied_version, lastApply: parse(r.last_apply), createdAt: r.created_at,
  };
}
function mapObject(r) {
  if (!r) return null;
  return { id: r.id, clusterId: r.cluster_id, kind: r.kind, name: r.name, ...parse(r.data, {}), createdAt: r.created_at, updatedAt: r.updated_at };
}
function mapDeployment(r, full = false) {
  if (!r) return null;
  const d = {
    id: r.id, clusterId: r.cluster_id, version: r.version, status: r.status, error: r.error, note: r.note,
    createdBy: r.created_by, createdAt: r.created_at, finishedAt: r.finished_at,
  };
  if (full) {
    d.snapshot = parse(r.snapshot);
    d.haproxyCfg = r.haproxy_cfg;
  }
  return d;
}

export class Store {
  constructor(db) {
    this.db = db;
  }

  tx(fn) {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const r = fn();
      this.db.exec('COMMIT');
      return r;
    } catch (e) {
      this.db.exec('ROLLBACK');
      throw e;
    }
  }

  // ---- kv
  getKv(key) {
    const r = this.db.prepare('SELECT value FROM kv WHERE key=?').get(key);
    return r ? parse(r.value) : null;
  }
  setKv(key, value) {
    this.db.prepare('INSERT INTO kv(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value').run(key, json(value));
  }

  // ---- users
  countUsers() {
    return this.db.prepare('SELECT COUNT(*) AS n FROM users').get().n;
  }
  getUser(username) {
    return this.db.prepare('SELECT * FROM users WHERE username=?').get(username) || null;
  }
  createUser(username, passwordHash) {
    this.db.prepare('INSERT INTO users(username,password_hash,created_at) VALUES(?,?,?)').run(username, passwordHash, now());
  }
  setUserPassword(username, passwordHash) {
    this.db.prepare('UPDATE users SET password_hash=? WHERE username=?').run(passwordHash, username);
  }

  // ---- clusters
  listClusters() {
    return this.db.prepare('SELECT * FROM clusters ORDER BY name').all().map(mapCluster);
  }
  getCluster(id) {
    return mapCluster(this.db.prepare('SELECT * FROM clusters WHERE id=?').get(id));
  }
  createCluster({ name, settings }) {
    const id = crypto.randomUUID();
    const secret = crypto.randomBytes(24).toString('base64url');
    const t = now();
    this.db.prepare('INSERT INTO clusters(id,name,settings,join_secret,created_at,updated_at) VALUES(?,?,?,?,?,?)').run(id, name, json(settings), secret, t, t);
    return this.getCluster(id);
  }
  updateCluster(id, { name, settings }) {
    this.db.prepare('UPDATE clusters SET name=?, settings=?, updated_at=? WHERE id=?').run(name, json(settings), now(), id);
    return this.getCluster(id);
  }
  rotateJoinSecret(id) {
    this.db.prepare('UPDATE clusters SET join_secret=?, updated_at=? WHERE id=?').run(crypto.randomBytes(24).toString('base64url'), now(), id);
    return this.getCluster(id);
  }
  deleteCluster(id) {
    this.db.prepare('DELETE FROM clusters WHERE id=?').run(id);
  }

  // ---- nodes
  listNodes(clusterId) {
    return this.db.prepare('SELECT * FROM nodes WHERE cluster_id=? ORDER BY name').all(clusterId).map(mapNode);
  }
  getNode(id) {
    return mapNode(this.db.prepare('SELECT * FROM nodes WHERE id=?').get(id));
  }
  getNodeByName(clusterId, name) {
    return mapNode(this.db.prepare('SELECT * FROM nodes WHERE cluster_id=? AND name=?').get(clusterId, name));
  }
  upsertNodeHeartbeat(clusterId, hb) {
    const existing = this.getNodeByName(clusterId, hb.name);
    const ts = Date.now();
    if (!existing) {
      const id = crypto.randomUUID();
      this.db.prepare(`INSERT INTO nodes(id,cluster_id,name,address,settings,agent_version,haproxy_version,last_seen,vrrp_state,applied_version,last_apply,created_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`).run(id, clusterId, hb.name, hb.address ?? null, json(hb.settings || {}), hb.agentVersion ?? null,
        hb.haproxyVersion ?? null, ts, hb.vrrpState ?? null, hb.appliedVersion ?? 0, json(hb.lastApply), now());
      return this.getNode(id);
    }
    this.db.prepare(`UPDATE nodes SET address=?, agent_version=?, haproxy_version=?, last_seen=?, vrrp_state=?, applied_version=?, last_apply=? WHERE id=?`)
      .run(hb.address ?? existing.address, hb.agentVersion ?? null, hb.haproxyVersion ?? null, ts, hb.vrrpState ?? null,
        hb.appliedVersion ?? 0, json(hb.lastApply), existing.id);
    return this.getNode(existing.id);
  }
  updateNodeSettings(id, settings) {
    this.db.prepare('UPDATE nodes SET settings=? WHERE id=?').run(json(settings), id);
    return this.getNode(id);
  }
  createNode(clusterId, { name, address, settings }) {
    const id = crypto.randomUUID();
    this.db.prepare('INSERT INTO nodes(id,cluster_id,name,address,settings,created_at) VALUES(?,?,?,?,?,?)').run(id, clusterId, name, address ?? null, json(settings || {}), now());
    return this.getNode(id);
  }
  deleteNode(id) {
    this.db.prepare('DELETE FROM nodes WHERE id=?').run(id);
  }

  // ---- objects (frontend | backend | certificate)
  listObjects(clusterId, kind) {
    return this.db.prepare('SELECT * FROM objects WHERE cluster_id=? AND kind=? ORDER BY name').all(clusterId, kind).map(mapObject);
  }
  getObject(clusterId, kind, id) {
    return mapObject(this.db.prepare('SELECT * FROM objects WHERE cluster_id=? AND kind=? AND id=?').get(clusterId, kind, id));
  }
  getObjectByName(clusterId, kind, name) {
    return mapObject(this.db.prepare('SELECT * FROM objects WHERE cluster_id=? AND kind=? AND name=?').get(clusterId, kind, name));
  }
  createObject(clusterId, kind, data) {
    const id = crypto.randomUUID();
    const t = now();
    const { name, ...rest } = data;
    this.db.prepare('INSERT INTO objects(id,cluster_id,kind,name,data,created_at,updated_at) VALUES(?,?,?,?,?,?,?)').run(id, clusterId, kind, name, json(rest), t, t);
    return this.getObject(clusterId, kind, id);
  }
  updateObject(clusterId, kind, id, data) {
    const { name, ...rest } = data;
    this.db.prepare('UPDATE objects SET name=?, data=?, updated_at=? WHERE id=? AND cluster_id=? AND kind=?').run(name, json(rest), now(), id, clusterId, kind);
    return this.getObject(clusterId, kind, id);
  }
  deleteObject(clusterId, kind, id) {
    this.db.prepare('DELETE FROM objects WHERE id=? AND cluster_id=? AND kind=?').run(id, clusterId, kind);
  }

  // ---- deployments
  listDeployments(clusterId, limit = 50) {
    return this.db.prepare('SELECT * FROM deployments WHERE cluster_id=? ORDER BY version DESC LIMIT ?').all(clusterId, limit).map((r) => mapDeployment(r));
  }
  getDeployment(clusterId, id) {
    return mapDeployment(this.db.prepare('SELECT * FROM deployments WHERE cluster_id=? AND id=?').get(clusterId, id), true);
  }
  getDeploymentByVersion(clusterId, version) {
    return mapDeployment(this.db.prepare('SELECT * FROM deployments WHERE cluster_id=? AND version=?').get(clusterId, version), true);
  }
  latestDeployment(clusterId) {
    return mapDeployment(this.db.prepare('SELECT * FROM deployments WHERE cluster_id=? ORDER BY version DESC LIMIT 1').get(clusterId), true);
  }
  latestSucceededDeployment(clusterId) {
    return mapDeployment(this.db.prepare("SELECT * FROM deployments WHERE cluster_id=? AND status='succeeded' ORDER BY version DESC LIMIT 1").get(clusterId), true);
  }
  createDeployment(clusterId, { snapshot, haproxyCfg, note, createdBy }) {
    return this.tx(() => {
      const max = this.db.prepare('SELECT COALESCE(MAX(version),0) AS v FROM deployments WHERE cluster_id=?').get(clusterId).v;
      // um novo deployment substitui qualquer um que ainda esteja em andamento
      this.db.prepare("UPDATE deployments SET status='superseded', finished_at=? WHERE cluster_id=? AND status='in_progress'").run(now(), clusterId);
      const r = this.db.prepare(`INSERT INTO deployments(cluster_id,version,status,note,created_by,created_at,snapshot,haproxy_cfg)
        VALUES(?,?,?,?,?,?,?,?)`).run(clusterId, max + 1, 'in_progress', note ?? null, createdBy ?? null, now(), json(snapshot), haproxyCfg);
      return this.getDeployment(clusterId, Number(r.lastInsertRowid));
    });
  }
  setDeploymentStatus(id, status, error = null) {
    this.db.prepare('UPDATE deployments SET status=?, error=?, finished_at=? WHERE id=?').run(status, error, status === 'in_progress' ? null : now(), id);
  }

  // ---- acme
  putChallenge(token, keyAuthorization) {
    this.db.prepare('INSERT OR REPLACE INTO acme_challenges(token,key_authorization,created_at) VALUES(?,?,?)').run(token, keyAuthorization, now());
  }
  getChallenge(token) {
    return this.db.prepare('SELECT key_authorization FROM acme_challenges WHERE token=?').get(token)?.key_authorization ?? null;
  }
  deleteChallenge(token) {
    this.db.prepare('DELETE FROM acme_challenges WHERE token=?').run(token);
  }

  // ---- audit
  audit({ username, clusterId, action, target, detail }) {
    this.db.prepare('INSERT INTO audit(ts,username,cluster_id,action,target,detail) VALUES(?,?,?,?,?,?)').run(now(), username ?? null, clusterId ?? null, action, target ?? null, detail ? json(detail) : null);
  }
  listAudit(limit = 200, clusterId = null) {
    const rows = clusterId
      ? this.db.prepare('SELECT * FROM audit WHERE cluster_id=? ORDER BY id DESC LIMIT ?').all(clusterId, limit)
      : this.db.prepare('SELECT * FROM audit ORDER BY id DESC LIMIT ?').all(limit);
    return rows.map((r) => ({ ...r, detail: parse(r.detail) }));
  }
}

export function openStore(file) {
  if (file !== ':memory:') fs.mkdirSync(path.dirname(file), { recursive: true });
  const db = new DatabaseSync(file);
  db.exec('PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;');
  db.exec(SCHEMA);
  return new Store(db);
}
